"""Deterministic mono preprocessing and explicitly training-only augmentation.

Augmentation is a label-preserving approximation, not a simulation validated on
vehicle faults. Real noise provenance and its training split membership must be
checked by the caller; an array alone cannot establish either property.
"""

from math import gcd
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal


def _integer(cfg: dict, key: str, *, allow_zero: bool = False) -> int:
    value = cfg[key]
    minimum = 0 if allow_zero else 1
    if isinstance(value, (bool, np.bool_)) or not isinstance(value, (int, np.integer)):
        raise ValueError(f"{key} must be an integer")
    if value < minimum:
        raise ValueError(f"{key} must be >= {minimum}")
    return int(value)


def _number(cfg: dict, key: str) -> float:
    value = cfg[key]
    if isinstance(value, (bool, np.bool_)) or not isinstance(value, (int, float, np.number)):
        raise ValueError(f"{key} must be a finite number")
    result = float(value)
    if not np.isfinite(result):
        raise ValueError(f"{key} must be a finite number")
    return result


def _mono(waveform: np.ndarray, name: str = "waveform") -> np.ndarray:
    if not isinstance(waveform, np.ndarray) or waveform.ndim != 1:
        raise ValueError(f"{name} must be a one-dimensional mono NumPy array")
    if not np.issubdtype(waveform.dtype, np.floating):
        raise ValueError(f"{name} must contain floating-point samples")
    if waveform.size == 0:
        raise ValueError(f"{name} must not be empty")
    if not np.isfinite(waveform).all():
        raise ValueError(f"{name} must contain only finite samples")
    if np.any(waveform < -1) or np.any(waveform > 1):
        raise ValueError(f"{name} samples are outside the range [-1, 1]")
    result = waveform.astype(np.float32, copy=False)
    if not np.any(result):
        raise ValueError(f"{name} is silent")
    return result


def load_audio(path: Path, cfg: dict) -> np.ndarray:
    """Decode a bounded file, downmix in float64, and rationally resample.

    Header bounds are checked before decoding. Neither invalid channels nor
    resampling overshoot is silently clipped. Minimum usable length and the
    absolute silence threshold are enforced by :func:`preprocess`.
    """
    sample_rate = _integer(cfg, "sample_rate")
    max_channels = _integer(cfg, "max_channels")
    max_seconds = _number(cfg, "max_audio_seconds")
    if max_seconds <= 0:
        raise ValueError("max_audio_seconds must be positive")
    with sf.SoundFile(path) as source:
        if source.channels > max_channels:
            raise ValueError(f"Audio channels exceed max_channels={max_channels}: {path}")
        if source.frames / source.samplerate > max_seconds:
            raise ValueError(f"Audio duration exceeds max_audio_seconds={max_seconds}: {path}")
        # Also bound the read itself rather than trusting metadata alone.
        max_frames = int(max_seconds * source.samplerate)
        samples = source.read(frames=max_frames + 1, dtype="float32", always_2d=True)
        if samples.shape[0] > max_frames:
            raise ValueError(f"Audio duration exceeds max_audio_seconds={max_seconds}: {path}")
        source_rate = source.samplerate
    if samples.shape[0] == 0:
        raise ValueError(f"Audio file is empty: {path}")
    if not np.isfinite(samples).all():
        raise ValueError(f"Audio channels must contain only finite samples: {path}")
    if np.any(samples < -1) or np.any(samples > 1):
        raise ValueError(f"Audio channel samples are outside the range [-1, 1]: {path}")
    mono = samples.mean(axis=1, dtype=np.float64).astype(np.float32)
    if source_rate != sample_rate:
        divisor = gcd(source_rate, sample_rate)
        mono = signal.resample_poly(mono, sample_rate // divisor, source_rate // divisor)
    return _mono(mono, "decoded waveform")


def preprocess(waveform: np.ndarray, cfg: dict) -> np.ndarray:
    """Trim outer absolute-RMS silence with margins, then peak normalize.

    Frames start on the configured hop grid; the final partial frame uses its
    actual sample count. Interior gaps remain untouched. No short-clip padding
    or relative-to-peak silence detection is performed.
    """
    waveform = _mono(waveform)
    frame = _integer(cfg, "trim_frame_samples")
    hop = _integer(cfg, "trim_hop_samples")
    margin = _integer(cfg, "trim_margin_samples", allow_zero=True)
    window = _integer(cfg, "window_samples")
    threshold_dbfs = _number(cfg, "trim_threshold_dbfs")
    peak = _number(cfg, "normalize_peak")
    if hop > frame:
        raise ValueError("trim_hop_samples must not exceed trim_frame_samples")
    if not 0 < peak <= 1:
        raise ValueError("normalize_peak must be in (0, 1]")
    if threshold_dbfs > 0:
        raise ValueError("trim_threshold_dbfs must be <= 0 dBFS")
    starts = np.arange(0, waveform.size, hop)
    ends = np.minimum(starts + frame, waveform.size)
    energy = np.empty(waveform.size + 1, dtype=np.float64)
    energy[0] = 0
    np.cumsum(np.square(waveform, dtype=np.float64), out=energy[1:])
    rms = np.sqrt(np.maximum(energy[ends] - energy[starts], 0) / (ends - starts))
    active = np.flatnonzero(rms > 10 ** (threshold_dbfs / 20))
    if active.size == 0:
        raise ValueError("waveform is silent at the configured absolute RMS threshold")
    start = max(0, int(starts[active[0]]) - margin)
    end = min(waveform.size, int(ends[active[-1]]) + margin)
    trimmed = waveform[start:end]
    if trimmed.size < window:
        raise ValueError(f"Trimmed waveform is too short: {trimmed.size} < {window} samples")
    normalized = trimmed.astype(np.float64) * (peak / float(np.max(np.abs(trimmed))))
    return normalized.astype(np.float32)


def windows(waveform: np.ndarray, cfg: dict) -> np.ndarray:
    """Return complete overlapping windows from one clip, dropping its tail.

    The result is a read-only strided view when the input already is float32.
    It is not flattened or combined with windows from another recording.
    """
    waveform = _mono(waveform)
    size = _integer(cfg, "window_samples")
    hop = _integer(cfg, "hop_samples")
    if waveform.size < size:
        raise ValueError(f"waveform is too short: {waveform.size} < {size} samples")
    return np.lib.stride_tricks.sliding_window_view(waveform, size)[::hop]


def _bounds(cfg: dict, key: str) -> np.ndarray:
    try:
        bounds = np.asarray(cfg[key], dtype=np.float64)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{key} must be two finite ordered bounds") from error
    if bounds.shape != (2,) or not np.isfinite(bounds).all() or bounds[0] > bounds[1]:
        raise ValueError(f"{key} must be two finite ordered bounds")
    return bounds


def _center_length(waveform: np.ndarray, size: int) -> np.ndarray:
    difference = waveform.size - size
    if difference >= 0:
        start = difference // 2
        return waveform[start:start + size]
    missing = -difference
    return np.pad(waveform, (missing // 2, missing - missing // 2))


def augment_training(
    waveform: np.ndarray,
    noise: np.ndarray,
    rng: np.random.Generator,
    cfg: dict,
    *,
    split: str,
) -> np.ndarray:
    """Apply independent bounded draws; never use on validation or test audio.

    Pitch shift and time stretch use librosa; the stretched signal is centered
    and cropped or zero-padded back to the exact input length. A random segment
    of real training noise (cyclically repeated if short) is scaled by measured
    RMS to the drawn SNR. Gain follows mixing, then a second-order Butterworth
    phone bandpass and first-order low/high tilt. Tilt spans opposite half-gains
    around the geometric band center. Causal filters retain their transients.

    No peak renormalization cancels gain variation. Only the final result is
    clamped to [-1, 1], so unusually strong configured gain can cause clipping.
    These transforms approximate label preservation; they do not prove it.
    """
    if split != "train":
        raise ValueError("Audio augmentation is allowed only for split='train'")
    waveform = _mono(waveform)
    noise = _mono(noise, "noise")
    if not isinstance(rng, np.random.Generator):
        raise ValueError("rng must be an explicit NumPy Generator")
    sample_rate = _integer(cfg, "sample_rate")
    keys = ("pitch_semitones", "stretch_rate", "gain_db", "snr_db",
            "phone_low_hz", "phone_high_hz", "phone_tilt_db")
    bounds = {key: _bounds(cfg, key) for key in keys}
    if bounds["stretch_rate"][0] <= 0:
        raise ValueError("stretch_rate bounds must be positive")
    for key in ("phone_low_hz", "phone_high_hz"):
        if bounds[key][0] <= 0 or bounds[key][1] >= sample_rate / 2:
            raise ValueError(f"{key} bounds must be strictly between zero and Nyquist")
    if bounds["phone_low_hz"][1] >= bounds["phone_high_hz"][0]:
        raise ValueError("phone_low_hz bounds must be below phone_high_hz bounds")
    draws = {key: float(rng.uniform(*bounds[key])) for key in keys}

    # A local import keeps deterministic preprocessing independent of librosa's
    # heavier runtime and imports no TensorFlow in any path.
    import librosa

    transformed = waveform
    if draws["pitch_semitones"] != 0:
        transformed = librosa.effects.pitch_shift(
            transformed, sr=sample_rate, n_steps=draws["pitch_semitones"]
        )
    if draws["stretch_rate"] != 1:
        transformed = librosa.effects.time_stretch(transformed, rate=draws["stretch_rate"])
    transformed = _center_length(transformed, waveform.size).astype(np.float64)
    if not np.isfinite(transformed).all() or not np.any(transformed):
        raise ValueError("Pitch/time transformation produced nonfinite or silent audio")

    size = waveform.size
    if noise.size >= size:
        start = int(rng.integers(0, noise.size - size + 1))
        selected_noise = noise[start:start + size].astype(np.float64)
    else:
        start = int(rng.integers(0, noise.size))
        selected_noise = noise[(start + np.arange(size)) % noise.size].astype(np.float64)
    noise_power = float(np.mean(selected_noise ** 2))
    if noise_power == 0:
        raise ValueError("Selected real noise segment is silent")
    clean_power = float(np.mean(transformed ** 2))
    with np.errstate(over="ignore", under="ignore", invalid="ignore"):
        noise_scale = np.sqrt(clean_power / noise_power) * np.power(10.0, -draws["snr_db"] / 20)
        mixed = (transformed + noise_scale * selected_noise) * np.power(
            10.0, draws["gain_db"] / 20
        )
    band = signal.butter(
        2, [draws["phone_low_hz"], draws["phone_high_hz"]],
        btype="bandpass", fs=sample_rate, output="sos",
    )
    filtered = signal.sosfilt(band, mixed)
    tilt = draws["phone_tilt_db"]
    if tilt != 0:
        pivot = np.sqrt(draws["phone_low_hz"] * draws["phone_high_hz"])
        lowpass = signal.butter(1, pivot, btype="lowpass", fs=sample_rate, output="sos")
        low = signal.sosfilt(lowpass, filtered)
        with np.errstate(over="ignore", under="ignore", invalid="ignore"):
            filtered = low * np.power(10.0, -tilt / 40) + (filtered - low) * np.power(
                10.0, tilt / 40
            )
    if not np.isfinite(filtered).all() or not np.any(filtered):
        raise ValueError("Augmentation produced nonfinite or silent audio; check bounds")
    return np.clip(filtered, -1, 1).astype(np.float32)
