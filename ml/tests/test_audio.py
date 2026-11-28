"""Synthetic signals test DSP math only, not vehicle classification quality."""

from pathlib import Path

import numpy as np
import pytest
import soundfile as sf
from scipy import signal

from workshop_audio.audio import augment_training, load_audio, preprocess, windows


@pytest.fixture
def cfg() -> dict:
    return {
        "sample_rate": 16000,
        "max_audio_seconds": 60,
        "max_channels": 8,
        "trim_frame_samples": 320,
        "trim_hop_samples": 160,
        "trim_threshold_dbfs": -60,
        "trim_margin_samples": 160,
        "normalize_peak": 0.9,
        "window_samples": 15600,
        "hop_samples": 7800,
    }


@pytest.fixture
def aug_cfg() -> dict:
    return {
        "sample_rate": 16000,
        "copies": 1,
        "pitch_semitones": [-0.5, 0.5],
        "stretch_rate": [0.95, 1.05],
        "gain_db": [-3, 3],
        "snr_db": [10, 25],
        "phone_low_hz": [60, 180],
        "phone_high_hz": [4500, 7500],
        "phone_tilt_db": [-3, 3],
    }


def tone(frequency: float = 440, size: int = 15600, amplitude: float = 0.2) -> np.ndarray:
    return (amplitude * np.sin(2 * np.pi * frequency * np.arange(size) / 16000)).astype(
        np.float32
    )


def fixed_augmentation(aug_cfg: dict, **changes: float) -> dict:
    result = {
        **aug_cfg,
        "pitch_semitones": [0, 0],
        "stretch_rate": [1, 1],
        "gain_db": [0, 0],
        "snr_db": [20, 20],
        "phone_low_hz": [100, 100],
        "phone_high_hz": [6000, 6000],
        "phone_tilt_db": [0, 0],
    }
    result.update({key: [value, value] for key, value in changes.items()})
    return result


def test_load_downmixes_before_rational_resampling(tmp_path: Path, cfg: dict) -> None:
    source_rate = 44100
    t = np.arange(source_rate) / source_rate
    stereo = np.column_stack((0.4 * np.sin(2 * np.pi * 1000 * t), np.zeros(t.size)))
    path = tmp_path / "stereo.wav"
    sf.write(path, stereo, source_rate, subtype="FLOAT")

    loaded = load_audio(path, cfg)

    assert loaded.shape == (16000,)
    assert loaded.dtype == np.float32
    expected = 0.2 * np.sin(2 * np.pi * 1000 * np.arange(16000) / 16000)
    np.testing.assert_allclose(loaded[100:-100], expected[100:-100], atol=0.001)


def test_load_same_rate_preserves_samples(tmp_path: Path, cfg: dict) -> None:
    original = tone()
    path = tmp_path / "mono.wav"
    sf.write(path, original, 16000, subtype="FLOAT")
    np.testing.assert_array_equal(load_audio(path, cfg), original)


@pytest.mark.parametrize("value", [np.nan, np.inf, 1.01, -1.01])
def test_load_rejects_bad_channels_before_cancellation(
    tmp_path: Path, cfg: dict, value: float
) -> None:
    path = tmp_path / "bad.wav"
    samples = np.full((100, 2), value, dtype=np.float32)
    samples[:, 1] = -value
    sf.write(path, samples, 16000, subtype="FLOAT")
    with pytest.raises(ValueError, match="finite|range"):
        load_audio(path, cfg)


@pytest.mark.parametrize(
    "frames,channels,overrides,error",
    [(16001, 1, {"max_audio_seconds": 1}, "duration"),
     (100, 3, {"max_channels": 2}, "channels")],
)
def test_load_rejects_header_bounds(
    tmp_path: Path, cfg: dict, frames: int, channels: int, overrides: dict, error: str
) -> None:
    path = tmp_path / "bounded.wav"
    sf.write(path, np.full((frames, channels), 0.1), 16000, subtype="FLOAT")
    with pytest.raises(ValueError, match=error):
        load_audio(path, {**cfg, **overrides})


def test_trim_uses_absolute_frame_rms_and_preserves_interior_gap(cfg: dict) -> None:
    config = {
        **cfg,
        "trim_frame_samples": 4,
        "trim_hop_samples": 2,
        "trim_margin_samples": 1,
        "window_samples": 8,
        "trim_threshold_dbfs": -20,
        "normalize_peak": 0.8,
    }
    original = np.array([0] * 8 + [0.2] * 4 + [0] * 8 + [0.4] * 4 + [0] * 8,
                        dtype=np.float32)
    untouched = original.copy()
    result = preprocess(original, config)
    # First active frame [6,10), last [22,26), then one-sample margins.
    np.testing.assert_array_equal(result, original[5:27] * np.float32(2))
    np.testing.assert_array_equal(original, untouched)
    assert result.dtype == np.float32
    assert np.max(result) == pytest.approx(0.8)
    assert np.count_nonzero(result[7:15]) == 0
    np.testing.assert_array_equal(preprocess(original, config), result)


def test_trim_threshold_is_not_relative_to_loudest_sample(cfg: dict) -> None:
    original = tone(amplitude=0.0001)
    with pytest.raises(ValueError, match="threshold|silent"):
        preprocess(original, cfg)
    result = preprocess(original, {**cfg, "trim_threshold_dbfs": -100})
    assert result.size == original.size
    assert np.max(np.abs(result)) == pytest.approx(cfg["normalize_peak"])


def test_trim_detects_final_partial_frame(cfg: dict) -> None:
    config = {**cfg, "trim_frame_samples": 4, "trim_hop_samples": 4,
              "trim_margin_samples": 0, "window_samples": 2}
    result = preprocess(np.array([0, 0, 0, 0, 0.1, 0.1], dtype=np.float32), config)
    np.testing.assert_allclose(result, [0.9, 0.9])


@pytest.mark.parametrize("operation", [preprocess, windows])
@pytest.mark.parametrize(
    "waveform,error",
    [(np.zeros(15600, dtype=np.float32), "silent"),
     (np.full(15600, np.nan, dtype=np.float32), "finite"),
     (np.full(15600, 1.1, dtype=np.float32), "range"),
     (np.ones(15599, dtype=np.float32) * 0.1, "short"),
     (np.ones((2, 15600), dtype=np.float32) * 0.1, "mono"),
     (np.empty(0, dtype=np.float32), "empty")],
)
def test_preprocessing_rejects_invalid_inputs(operation, waveform, error, cfg) -> None:
    with pytest.raises(ValueError, match=error):
        operation(waveform, cfg)


def test_trim_does_not_pad_short_active_region(cfg: dict) -> None:
    original = np.concatenate((np.zeros(16000), tone(size=1000), np.zeros(16000))).astype(
        np.float32
    )
    with pytest.raises(ValueError, match="short"):
        preprocess(original, cfg)


def test_windows_are_exact_overlapping_complete_segments(cfg: dict) -> None:
    original = np.linspace(-0.8, 0.8, 15600 + 2 * 7800 + 9, dtype=np.float32)
    result = windows(original, cfg)
    assert result.shape == (3, 15600)
    assert result.dtype == np.float32
    for index, row in enumerate(result):
        np.testing.assert_array_equal(row, original[index * 7800:index * 7800 + 15600])
    np.testing.assert_array_equal(windows(original, cfg), result)
    assert windows(original[:15600], cfg).shape == (1, 15600)


@pytest.mark.parametrize("split", ["validation", "val", "test", "unknown", "TRAIN"])
def test_augmentation_rejects_nontraining_splits_before_other_work(split: str) -> None:
    with pytest.raises(ValueError, match="train"):
        augment_training(None, None, None, {}, split=split)


def test_augmentation_is_seeded_exact_length_and_does_not_mutate(aug_cfg: dict) -> None:
    clean = tone() + tone(930, amplitude=0.1)
    noise = tone(310, size=713, amplitude=0.1)
    before_clean, before_noise = clean.copy(), noise.copy()
    first = augment_training(clean, noise, np.random.default_rng(82), aug_cfg, split="train")
    second = augment_training(clean, noise, np.random.default_rng(82), aug_cfg, split="train")
    third = augment_training(clean, noise, np.random.default_rng(83), aug_cfg, split="train")
    np.testing.assert_array_equal(first, second)
    assert not np.allclose(first, third)
    assert first.shape == clean.shape
    assert first.dtype == np.float32
    assert np.isfinite(first).all()
    assert np.max(np.abs(first)) <= 1
    assert not np.allclose(first, clean)
    np.testing.assert_array_equal(clean, before_clean)
    np.testing.assert_array_equal(noise, before_noise)


@pytest.mark.parametrize("noise", [np.zeros(7, dtype=np.float32),
                                    np.full(7, np.nan, dtype=np.float32)])
def test_augmentation_rejects_invalid_real_noise(noise: np.ndarray, aug_cfg: dict) -> None:
    with pytest.raises(ValueError, match="noise.*(silent|finite)"):
        augment_training(tone(), noise, np.random.default_rng(0), aug_cfg, split="train")


def test_noise_snr_and_gain_match_math_without_peak_renormalization(aug_cfg: dict) -> None:
    clean = tone()
    noise = tone(997, amplitude=0.1)
    config = fixed_augmentation(aug_cfg, gain_db=-3, snr_db=10)
    actual = augment_training(clean, noise, np.random.default_rng(7), config, split="train")
    scale = np.sqrt(np.mean(clean.astype(np.float64) ** 2)
                    / (10 * np.mean(noise.astype(np.float64) ** 2)))
    mixed = (clean.astype(np.float64) + scale * noise) * 10 ** (-3 / 20)
    sos = signal.butter(2, [100, 6000], btype="bandpass", fs=16000, output="sos")
    expected = signal.sosfilt(sos, mixed)
    np.testing.assert_allclose(actual, expected, atol=2e-7)
    louder = augment_training(clean, noise, np.random.default_rng(7),
                             fixed_augmentation(aug_cfg, gain_db=0, snr_db=10), split="train")
    np.testing.assert_allclose(actual, louder * 10 ** (-3 / 20), atol=2e-7)


def test_phone_tilt_changes_low_high_balance(aug_cfg: dict) -> None:
    clean = tone(300, amplitude=0.1) + tone(3500, amplitude=0.1)
    noise = tone(1100, amplitude=0.1)
    base = fixed_augmentation(aug_cfg, snr_db=80)
    flat = augment_training(clean, noise, np.random.default_rng(0), base, split="train")
    tilted = augment_training(clean, noise, np.random.default_rng(0),
                              fixed_augmentation(aug_cfg, snr_db=80, phone_tilt_db=3),
                              split="train")
    frequencies = np.fft.rfftfreq(clean.size, 1 / 16000)
    low, high = (np.argmin(np.abs(frequencies - value)) for value in (300, 3500))
    flat_spectrum, tilt_spectrum = np.abs(np.fft.rfft(flat)), np.abs(np.fft.rfft(tilted))
    assert tilt_spectrum[high] / tilt_spectrum[low] > flat_spectrum[high] / flat_spectrum[low]


def test_phone_bandpass_attenuates_out_of_band_tones(aug_cfg: dict) -> None:
    clean = tone(30, amplitude=0.05) + tone(1000, amplitude=0.05) + tone(7000, amplitude=0.05)
    actual = augment_training(clean, tone(2200), np.random.default_rng(0),
                              fixed_augmentation(aug_cfg, snr_db=80,
                                                 phone_low_hz=180, phone_high_hz=4500),
                              split="train")
    spectrum = np.abs(np.fft.rfft(actual))
    bins = np.fft.rfftfreq(actual.size, 1 / 16000)
    amplitude = lambda hz: spectrum[np.argmin(np.abs(bins - hz))]
    assert amplitude(30) < 0.1 * amplitude(1000)
    assert amplitude(7000) < 0.1 * amplitude(1000)


def test_pitch_shifts_tone_frequency(aug_cfg: dict) -> None:
    clean = tone(1000)
    result = augment_training(clean, tone(2200), np.random.default_rng(0),
                              fixed_augmentation(aug_cfg, pitch_semitones=0.5, snr_db=80),
                              split="train")
    spectrum = np.abs(np.fft.rfft(result[2048:-2048]))
    frequency = np.fft.rfftfreq(result.size - 4096, 1 / 16000)[np.argmax(spectrum)]
    assert frequency == pytest.approx(1000 * 2 ** (0.5 / 12), abs=3)


def test_stretch_changes_envelope_but_restores_exact_length(aug_cfg: dict) -> None:
    clean = tone() * np.linspace(0.2, 1, 15600, dtype=np.float32)
    noise = tone(997)
    base = augment_training(clean, noise, np.random.default_rng(0),
                            fixed_augmentation(aug_cfg, snr_db=80), split="train")
    stretched = augment_training(clean, noise, np.random.default_rng(0),
                                 fixed_augmentation(aug_cfg, snr_db=80, stretch_rate=1.05),
                                 split="train")
    assert stretched.shape == clean.shape
    assert not np.allclose(stretched, base, atol=0.001)
    assert np.linalg.norm(stretched[-200:]) < np.linalg.norm(base[-200:]) * 0.01


def test_final_clamp_is_finite_and_bounded(aug_cfg: dict) -> None:
    result = augment_training(tone(amplitude=0.9), tone(997), np.random.default_rng(0),
                              fixed_augmentation(aug_cfg, gain_db=20, snr_db=10), split="train")
    assert np.max(np.abs(result)) == 1
    assert np.isfinite(result).all()


@pytest.mark.parametrize(
    "key,value,error",
    [("stretch_rate", [0, 1], "stretch_rate"),
     ("gain_db", [3, -3], "gain_db"),
     ("snr_db", [10, np.nan], "snr_db"),
     ("phone_high_hz", [8000, 9000], "phone_high_hz")],
)
def test_invalid_augmentation_bounds_fail_cleanly(
    aug_cfg: dict, key: str, value: list, error: str
) -> None:
    with pytest.raises(ValueError, match=error):
        augment_training(tone(), tone(997), np.random.default_rng(0),
                         {**aug_cfg, key: value}, split="train")
