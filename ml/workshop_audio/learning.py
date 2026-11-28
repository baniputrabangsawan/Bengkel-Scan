"""Train-only linear classification and validation-only calibration.

The head predicts known classes only. Confidence rejection produces an unknown
label, never an additional calibrated probability or a guarantee of OOD safety.
Callers must supply training features/weights to ``fit_head`` and untouched,
known-label, clip-aggregated validation logits to calibration functions.
"""

from dataclasses import dataclass
from numbers import Integral, Real

import numpy as np
from scipy.optimize import minimize, minimize_scalar


def _matrix(values: np.ndarray, name: str, *, allow_empty: bool = False) -> np.ndarray:
    array = np.asarray(values)
    if array.ndim != 2 or array.shape[1] == 0:
        raise ValueError(f"{name} must have shape [N, D] with D > 0")
    if not allow_empty and array.shape[0] == 0:
        raise ValueError(f"{name} must not be empty")
    if array.dtype.kind not in "fiu" or not np.all(np.isfinite(array)):
        raise ValueError(f"{name} must contain finite real numbers")
    return np.asarray(array, dtype=np.float64)


def _vector(values: np.ndarray, length: int, name: str) -> np.ndarray:
    array = np.asarray(values)
    if array.shape != (length,) or array.dtype.kind not in "fiu":
        raise ValueError(f"{name} must have shape [{length}] and real values")
    if not np.all(np.isfinite(array)):
        raise ValueError(f"{name} must contain finite values")
    return np.asarray(array, dtype=np.float64)


def _labels(values: np.ndarray, length: int, class_count: int) -> np.ndarray:
    array = np.asarray(values)
    if array.shape != (length,) or array.dtype.kind not in "iu":
        raise ValueError("labels must be a one-dimensional integer array matching rows")
    if np.any(array < 0) or np.any(array >= class_count):
        raise ValueError(f"labels must be between 0 and {class_count - 1}")
    return array.astype(np.intp, copy=False)


def _integer(value: int, name: str, minimum: int) -> int:
    if isinstance(value, (bool, np.bool_)) or not isinstance(value, Integral) or value < minimum:
        raise ValueError(f"{name} must be an integer >= {minimum}")
    return int(value)


def _real(value: float, name: str, *, positive: bool = False) -> float:
    if isinstance(value, (bool, np.bool_)) or not isinstance(value, Real) or not np.isfinite(value):
        raise ValueError(f"{name} must be finite and real")
    if positive and value <= 0:
        raise ValueError(f"{name} must be positive")
    return float(value)


def _probability_matrix(values: np.ndarray, *, allow_empty: bool = False) -> np.ndarray:
    array = _matrix(values, "probabilities", allow_empty=allow_empty)
    if np.any(array < 0) or np.any(array > 1):
        raise ValueError("probabilities must lie in [0, 1]")
    if not np.allclose(array.sum(axis=1), 1.0, rtol=1e-6, atol=1e-8):
        raise ValueError("probability rows must sum to 1")
    return array


def _log_probabilities(logits: np.ndarray, temperature: float) -> np.ndarray:
    # Subtract before dividing, avoiding overflow of a large shared logit offset.
    # Extremely separated finite logits may produce -inf (zero probability).
    with np.errstate(over="ignore", under="ignore"):
        shifted = (logits - logits.max(axis=1, keepdims=True)) / temperature
        return shifted - np.log(np.exp(shifted).sum(axis=1, keepdims=True))


@dataclass(frozen=True)
class LinearHead:
    """A known-class softmax head with its training-only feature normalization."""

    weights: np.ndarray
    bias: np.ndarray
    mean: np.ndarray
    scale: np.ndarray
    converged: bool
    iterations: int

    def __post_init__(self) -> None:
        weights = _matrix(self.weights, "weights")
        dimensions, classes = weights.shape
        arrays = {
            "weights": weights,
            "bias": _vector(self.bias, classes, "bias"),
            "mean": _vector(self.mean, dimensions, "mean"),
            "scale": _vector(self.scale, dimensions, "scale"),
        }
        if np.any(arrays["scale"] <= 0):
            raise ValueError("scale must be strictly positive")
        if not isinstance(self.converged, (bool, np.bool_)):
            raise ValueError("converged must be boolean")
        _integer(self.iterations, "iterations", 0)
        # Frozen dataclasses alone do not freeze their ndarray contents. Own these
        # small parameter arrays so caller mutation cannot invalidate the head.
        for name, array in arrays.items():
            owned = array.copy()
            owned.setflags(write=False)
            object.__setattr__(self, name, owned)
        object.__setattr__(self, "converged", bool(self.converged))
        object.__setattr__(self, "iterations", int(self.iterations))

    def logits(self, features: np.ndarray) -> np.ndarray:
        """Transform arbitrary-width embedding batches with the saved normalizer."""
        array = _matrix(features, "features", allow_empty=True)
        if array.shape[1] != self.weights.shape[0]:
            raise ValueError("feature dimension does not match the trained head")
        with np.errstate(over="ignore", invalid="ignore"):
            result = ((array - self.mean) / self.scale) @ self.weights + self.bias
        if not np.all(np.isfinite(result)):
            raise ValueError("features produce nonfinite logits with this head")
        return result


def fit_head(
    features: np.ndarray,
    labels: np.ndarray,
    sample_weights: np.ndarray,
    cfg: dict,
    seed: int,
) -> LinearHead:
    """Minimize weighted mean cross-entropy plus ``l2 * ||W||² / 2``.

    The caller supplies the combined class/clip weights; this function does not
    double-apply class balancing. Positive weights also define train-only feature
    means and variances, preserving the influence of clips split into more windows.
    Biases are not regularized. An exhausted optimizer returns converged=False.
    """
    array = _matrix(features, "features")
    rows, dimensions = array.shape
    classes = _integer(cfg["class_count"], "class_count", 1)
    targets = _labels(labels, rows, classes)
    if np.unique(targets).size != classes:
        raise ValueError("all known classes must be present in training")
    weights = _vector(sample_weights, rows, "sample_weights")
    if np.any(weights <= 0):
        raise ValueError("sample_weights must be strictly positive")
    weights = weights / weights.max()
    weights = weights / weights.sum()
    l2 = _real(cfg["l2"], "l2")
    if l2 < 0:
        raise ValueError("l2 must be nonnegative")
    max_iterations = _integer(cfg["max_iterations"], "max_iterations", 1)
    tolerance = _real(cfg["tolerance"], "tolerance", positive=True)
    seed = _integer(seed, "seed", 0)

    with np.errstate(over="ignore", invalid="ignore", divide="ignore"):
        mean = weights @ array
        constant = np.all(array == array[0], axis=0)
        mean[constant] = array[0, constant]
        centered = array - mean
        variance = weights @ np.square(centered)
        scale = np.sqrt(variance)
        scale[scale == 0] = 1.0
        standardized = centered / scale
    if not all(np.all(np.isfinite(value)) for value in (mean, scale, standardized)):
        raise ValueError("training features exceed numerical standardization limits")

    parameter_count = dimensions * classes
    row_indices = np.arange(rows)

    def objective(parameters: np.ndarray) -> tuple[float, np.ndarray]:
        coefficients = parameters[:parameter_count].reshape(dimensions, classes)
        intercept = parameters[parameter_count:]
        logits = standardized @ coefficients + intercept
        log_probs = _log_probabilities(logits, 1.0)
        loss = -float(weights @ log_probs[row_indices, targets])
        loss += 0.5 * l2 * float(np.sum(coefficients * coefficients))
        residual = np.exp(log_probs)
        residual[row_indices, targets] -= 1.0
        residual *= weights[:, None]
        gradient = np.concatenate(
            ((standardized.T @ residual + l2 * coefficients).ravel(), residual.sum(axis=0))
        )
        if not np.isfinite(loss) or not np.all(np.isfinite(gradient)):
            raise ValueError("nonfinite softmax optimization objective")
        return loss, gradient

    initial = np.concatenate(
        (np.random.default_rng(seed).normal(0.0, 1e-3, parameter_count), np.zeros(classes))
    )
    result = minimize(
        objective,
        initial,
        method="L-BFGS-B",
        jac=True,
        options={"maxiter": max_iterations, "ftol": tolerance, "gtol": tolerance},
    )
    return LinearHead(
        weights=result.x[:parameter_count].reshape(dimensions, classes),
        bias=result.x[parameter_count:],
        mean=mean,
        scale=scale,
        converged=bool(result.success),
        iterations=int(result.nit),
    )


def probabilities(logits: np.ndarray, temperature: float = 1.0) -> np.ndarray:
    """Return stable closed-set probabilities, including for empty inference batches."""
    array = _matrix(logits, "logits", allow_empty=True)
    temperature = _real(temperature, "temperature", positive=True)
    return np.exp(_log_probabilities(array, temperature))


def fit_temperature(logits: np.ndarray, labels: np.ndarray, cfg: dict) -> dict:
    """Fit one bounded temperature on known validation clip logits, never test data.

    Bounds must contain 1, allowing the uncalibrated model to remain a feasible
    safeguard. Missing validation classes are allowed; empty validation is not.
    """
    array = _matrix(logits, "logits")
    targets = _labels(labels, array.shape[0], array.shape[1])
    bounds = _vector(cfg["temperature_bounds"], 2, "temperature_bounds")
    lower, upper = bounds
    if not 0 < lower < upper or not lower <= 1 <= upper:
        raise ValueError("temperature_bounds must be increasing, positive and contain 1")
    tolerance = _real(cfg["temperature_tolerance"], "temperature_tolerance", positive=True)
    indices = np.arange(array.shape[0])

    def nll(temperature: float) -> float:
        value = -float(np.mean(_log_probabilities(array, temperature)[indices, targets]))
        if not np.isfinite(value):
            raise ValueError("validation logits produce nonfinite negative log-likelihood")
        return value

    before = nll(1.0)
    result = minimize_scalar(
        lambda log_temperature: nll(float(np.exp(log_temperature))),
        bounds=(float(np.log(lower)), float(np.log(upper))),
        method="bounded",
        options={"xatol": tolerance},
    )
    temperature, after = 1.0, before
    # Check endpoints as well: bounded scalar minimization samples the interior.
    for candidate in (float(lower), float(upper), float(np.exp(result.x))):
        value = nll(candidate)
        if value < after:
            temperature, after = candidate, value
    return {
        "temperature": temperature,
        "nll_before": before,
        "nll_after": after,
        "converged": bool(result.success),
    }


def choose_rejection_threshold(calibrated_val_probabilities: np.ndarray, cfg: dict) -> float:
    """Choose a known-validation confidence order statistic for target coverage.

    Accept ``max_probability >= threshold``. The largest observed threshold that
    retains at least ceil(known_coverage * N) validation examples is selected;
    ties can retain more. This is empirical known coverage, not OOD validation.
    """
    array = _probability_matrix(calibrated_val_probabilities)
    coverage = _real(cfg["known_coverage"], "known_coverage", positive=True)
    if coverage > 1:
        raise ValueError("known_coverage must lie in (0, 1]")
    confidence = np.sort(array.max(axis=1))
    index = len(confidence) - int(np.ceil(coverage * len(confidence)))
    return float(confidence[index])
