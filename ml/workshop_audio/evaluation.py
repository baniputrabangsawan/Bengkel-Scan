"""Recording-level ranking, confidence calibration, and open-set decisions.

Known-class probabilities remain closed-set quantities. Unknown is only a
confidence-rejection label; no ``1 - max_probability`` unknown probability is
invented. Confusion matrices use truth rows and predicted-label columns.
"""

import numpy as np

from .learning import _integer, _labels, _probability_matrix, _real


def evaluate(
    labels: np.ndarray,
    probs: np.ndarray,
    classes: list[str],
    threshold: float,
    bins: int,
) -> dict:
    """Evaluate clip-level probabilities without fitting any evaluation parameter.

    Top-1/top-3 and ECE use known truth only, before rejection. Coverage is the
    fraction of all examples assigned a known label. Open-set accuracy and
    per-class metrics include unknown examples and false abstentions. Undefined
    metrics are None (JSON null), never fabricated zeros. Unsupported classes
    have undefined recall/F1, and classes with no predictions have undefined
    precision; a supported class with no predictions has recall/F1 zero.
    """
    if (
        not isinstance(classes, list)
        or len(classes) < 2
        or any(not isinstance(name, str) or not name.strip() for name in classes)
        or len(set(classes)) != len(classes)
        or classes[-1] != "unknown"
    ):
        raise ValueError("classes must be unique nonempty names with 'unknown' last")
    class_count = len(classes)
    known_count = class_count - 1
    array = _probability_matrix(probs, allow_empty=True)
    if array.shape[1] != known_count:
        raise ValueError("probability columns must match known classes, excluding unknown")
    rows = array.shape[0]
    truth = _labels(labels, rows, class_count)
    threshold = _real(threshold, "threshold")
    if not 0 <= threshold <= 1:
        raise ValueError("threshold must lie in [0, 1]")
    bins = _integer(bins, "bins", 1)

    raw_prediction = array.argmax(axis=1)
    confidence = array.max(axis=1)
    rejected = confidence < threshold
    prediction = np.where(rejected, known_count, raw_prediction)
    known = truth < known_count
    number_known = int(np.count_nonzero(known))
    number_unknown = rows - number_known
    number_rejected = int(np.count_nonzero(rejected))
    confusion = np.zeros((class_count, class_count), dtype=np.int64)
    np.add.at(confusion, (truth, prediction), 1)

    top1 = top3 = ece = None
    if number_known:
        known_truth = truth[known]
        known_prediction = raw_prediction[known]
        known_confidence = confidence[known]
        correct = known_prediction == known_truth
        top1 = float(np.mean(correct))
        # Stable sort resolves equal scores in the same class order as argmax.
        ranking = np.argsort(-array[known], axis=1, kind="stable")[:, : min(3, known_count)]
        top3 = float(np.mean(np.any(ranking == known_truth[:, None], axis=1)))
        # The final bin includes confidence == 1; all other bins are [left,right).
        bin_index = np.minimum((known_confidence * bins).astype(np.int64), bins - 1)
        ece = 0.0
        for index in np.unique(bin_index):
            members = bin_index == index
            fraction = float(np.count_nonzero(members) / number_known)
            ece += fraction * abs(float(correct[members].mean() - known_confidence[members].mean()))

    per_class = []
    warnings = []
    for index, name in enumerate(classes):
        support = int(confusion[index].sum())
        predicted = int(confusion[:, index].sum())
        true_positive = int(confusion[index, index])
        per_class.append(
            {
                "class": name,
                "support": support,
                "precision": float(true_positive / predicted) if predicted else None,
                "recall": float(true_positive / support) if support else None,
                "f1": float(2 * true_positive / (support + predicted)) if support else None,
            }
        )
        if not support:
            warnings.append(f"Class '{name}' has no truth support; recall and F1 are undefined.")
    if not number_unknown:
        warnings.append("No unknown/OOD examples: unknown rejection performance is not measured.")
    if not rows:
        warnings.append("No examples in this evaluation subset; aggregate metrics are undefined.")
    weakest = sorted(
        per_class,
        key=lambda item: (item["support"] != 0, item["f1"] if item["f1"] is not None else -1.0),
    )
    return {
        "counts": {
            "total": rows,
            "known": number_known,
            "unknown": number_unknown,
            "accepted": rows - number_rejected,
            "rejected": number_rejected,
        },
        "top1_accuracy": top1,
        "top3_accuracy": top3,
        "open_set_accuracy": float(np.mean(prediction == truth)) if rows else None,
        "coverage": float((rows - number_rejected) / rows) if rows else None,
        "per_class": per_class,
        "confusion_matrix": confusion.tolist(),
        "ece": ece,
        "weakest_classes": weakest,
        "warnings": warnings,
    }
