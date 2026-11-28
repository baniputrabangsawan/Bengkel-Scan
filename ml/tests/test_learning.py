"""Small synthetic feature checks, not claims about vehicle-audio accuracy."""

import numpy as np
import pytest

from workshop_audio.learning import (
    LinearHead,
    choose_rejection_threshold,
    fit_head,
    fit_temperature,
    probabilities,
)


TRAINING = {"class_count": 2, "l2": 0.01, "max_iterations": 200, "tolerance": 1e-9}
CALIBRATION = {
    "temperature_bounds": [0.05, 20.0],
    "temperature_tolerance": 1e-7,
    "known_coverage": 0.75,
}


@pytest.mark.parametrize("dimensions", [1, 5, 17])
def test_real_optimizer_learns_separable_features_and_reuses_training_normalizer(dimensions):
    features = np.full((6, dimensions), 7.0)
    features[:, 0] = [-3, -2, -1, 1, 2, 3]
    labels = np.array([0, 0, 0, 1, 1, 1])
    head = fit_head(features, labels, np.ones(6), TRAINING, seed=19)
    repeat = fit_head(features, labels, np.ones(6), TRAINING, seed=19)
    assert head.converged
    assert head.iterations > 0
    assert head.weights.shape == (dimensions, 2)
    assert np.array_equal(head.weights, repeat.weights)
    assert np.array_equal(np.argmax(head.logits(features), axis=1), labels)
    assert np.mean(-np.log(probabilities(head.logits(features))[np.arange(6), labels])) < 0.1
    assert np.all(head.scale[1:] == 1.0)
    held_out = np.full((2, dimensions), 7.0)
    held_out[:, 0] = [-0.5, 0.5]
    assert np.argmax(head.logits(held_out), axis=1).tolist() == [0, 1]
    assert np.allclose(head.logits(held_out), ((held_out - head.mean) / head.scale) @ head.weights + head.bias)
    assert head.logits(np.empty((0, dimensions))).shape == (0, 2)


def test_class_and_clip_weights_change_decisions_without_changing_features():
    features = np.zeros((4, 3))
    labels = np.array([0, 0, 0, 1])
    ordinary = fit_head(features, labels, np.ones(4), TRAINING, seed=7)
    weighted = fit_head(features, labels, np.array([1.0, 1.0, 1.0, 12.0]), TRAINING, seed=7)
    probe = np.zeros((1, 3))
    assert np.argmax(ordinary.logits(probe), axis=1).item() == 0
    assert np.argmax(weighted.logits(probe), axis=1).item() == 1
    assert probabilities(weighted.logits(probe))[0, 1] == pytest.approx(0.8, abs=1e-4)


def test_weighted_duplicate_windows_preserve_clip_weighted_fit():
    features = np.array([[-2.0], [-1.0], [1.0], [2.0]])
    labels = np.array([0, 0, 1, 1])
    first = fit_head(features, labels, np.ones(4), TRAINING, seed=3)
    duplicated = fit_head(
        np.repeat(features, [3, 1, 1, 1], axis=0),
        np.repeat(labels, [3, 1, 1, 1]),
        np.array([1 / 3, 1 / 3, 1 / 3, 1, 1, 1]),
        TRAINING,
        seed=3,
    )
    assert np.allclose(first.logits(features), duplicated.logits(features), atol=1e-6)


def test_iteration_exhaustion_is_not_reported_as_convergence():
    features = np.array([[-3.0], [-1.0], [1.0], [3.0]])
    head = fit_head(features, np.array([0, 0, 1, 1]), np.ones(4), {**TRAINING, "max_iterations": 1}, seed=1)
    assert head.iterations == 1
    assert not head.converged
    assert np.all(np.isfinite(head.logits(features)))


def test_stable_softmax_and_temperature_consumer_behavior():
    logits = np.array([[10000.0, 10001.0], [-10000.0, -10001.0]])
    result = probabilities(logits)
    assert result[0, 1] == pytest.approx(1 / (1 + np.exp(-1)))
    assert np.allclose(result.sum(axis=1), 1)
    warmer = probabilities(logits, temperature=2)
    assert np.all(warmer.max(axis=1) < result.max(axis=1))
    assert np.argmax(warmer, axis=1).tolist() == [1, 0]
    assert probabilities(np.empty((0, 2))).shape == (0, 2)


def test_temperature_reduces_validation_nll_for_overconfident_errors():
    logits = np.tile([8.0, 0.0], (10, 1))
    labels = np.array([0] * 8 + [1] * 2)
    result = fit_temperature(logits, labels, CALIBRATION)
    assert result["converged"]
    assert 1 < result["temperature"] <= 20
    assert result["temperature"] == pytest.approx(8 / np.log(4), rel=1e-4)
    assert result["nll_after"] < result["nll_before"] - 0.5
    calibrated = probabilities(logits, result["temperature"])
    assert calibrated[0, 0] == pytest.approx(0.8, abs=1e-5)
    assert result["nll_after"] == pytest.approx(-np.log(calibrated[np.arange(10), labels]).mean())


def test_temperature_safeguard_preserves_optimal_unit_temperature():
    logits = np.tile([np.log(4), 0.0], (5, 1))
    result = fit_temperature(logits, np.array([0, 0, 0, 0, 1]), CALIBRATION)
    assert result["nll_after"] <= result["nll_before"]
    assert result["temperature"] == pytest.approx(1.0, abs=1e-5)


def test_threshold_is_observed_lower_tail_with_inclusive_acceptance():
    validation = np.array([[0.55, 0.45], [0.65, 0.35], [0.8, 0.2], [0.95, 0.05]])
    threshold = choose_rejection_threshold(validation, CALIBRATION)
    assert threshold == 0.65
    assert np.mean(validation.max(axis=1) >= threshold) == 0.75
    assert choose_rejection_threshold(validation, {**CALIBRATION, "known_coverage": 1.0}) == 0.55
    tied = np.array([[0.7, 0.3], [0.7, 0.3], [0.8, 0.2]])
    assert choose_rejection_threshold(tied, CALIBRATION) == 0.7


@pytest.mark.parametrize(
    "features,labels,weights,cfg",
    [
        (np.zeros((0, 2)), np.array([], dtype=int), np.ones(0), TRAINING),
        (np.zeros(4), np.array([0, 1]), np.ones(2), TRAINING),
        (np.zeros((2, 0)), np.array([0, 1]), np.ones(2), TRAINING),
        (np.array([[np.nan], [1.0]]), np.array([0, 1]), np.ones(2), TRAINING),
        (np.array([[np.inf], [1.0]]), np.array([0, 1]), np.ones(2), TRAINING),
        (np.zeros((2, 2)), np.array([0.5, 1.0]), np.ones(2), TRAINING),
        (np.zeros((2, 2)), np.array([0, 2]), np.ones(2), TRAINING),
        (np.zeros((2, 2)), np.array([0, 0]), np.ones(2), TRAINING),
        (np.zeros((2, 2)), np.array([[0], [1]]), np.ones(2), TRAINING),
        (np.zeros((2, 2)), np.array([0, 1]), np.ones(3), TRAINING),
        (np.zeros((2, 2)), np.array([0, 1]), np.array([1.0, np.nan]), TRAINING),
        (np.zeros((2, 2)), np.array([0, 1]), np.array([1.0, -1.0]), TRAINING),
        (np.zeros((2, 2)), np.array([0, 1]), np.array([1.0, 0.0]), TRAINING),
        (np.zeros((2, 2)), np.array([0, 1]), np.ones(2), {**TRAINING, "l2": -1}),
        (np.zeros((2, 2)), np.array([0, 1]), np.ones(2), {**TRAINING, "class_count": 2.5}),
    ],
)
def test_fit_rejects_invalid_inputs(features, labels, weights, cfg):
    with pytest.raises(ValueError):
        fit_head(features, labels, weights, cfg, seed=0)


@pytest.mark.parametrize("logits", [np.array([1, 2]), np.empty((2, 0)), np.array([[np.nan, 0]]), np.array([[np.inf, 0]])])
def test_probability_shape_and_finiteness_validation(logits):
    with pytest.raises(ValueError):
        probabilities(logits)


@pytest.mark.parametrize("temperature", [0, -1, np.nan, np.inf])
def test_invalid_temperature_is_rejected(temperature):
    with pytest.raises(ValueError):
        probabilities(np.zeros((1, 2)), temperature)


@pytest.mark.parametrize(
    "logits,labels,cfg",
    [
        (np.empty((0, 2)), np.array([], dtype=int), CALIBRATION),
        (np.zeros((2, 2)), np.array([0, 2]), CALIBRATION),
        (np.zeros((2, 2)), np.array([0.0, 1.0]), CALIBRATION),
        (np.zeros((2, 2)), np.array([0, 1]), {**CALIBRATION, "temperature_bounds": [2, 3]}),
        (np.zeros((2, 2)), np.array([0, 1]), {**CALIBRATION, "temperature_bounds": [0, 20]}),
        (np.zeros((2, 2)), np.array([0, 1]), {**CALIBRATION, "temperature_tolerance": np.nan}),
    ],
)
def test_calibration_rejects_bad_inputs(logits, labels, cfg):
    with pytest.raises(ValueError):
        fit_temperature(logits, labels, cfg)


@pytest.mark.parametrize("probs", [np.empty((0, 2)), np.array([[0.2, 0.3]]), np.array([[1.1, -0.1]]), np.array([[np.nan, 0.5]])])
def test_threshold_rejects_invalid_probability_inputs(probs):
    with pytest.raises(ValueError):
        choose_rejection_threshold(probs, CALIBRATION)


@pytest.mark.parametrize("coverage", [0, 1.1, np.nan])
def test_threshold_rejects_invalid_coverage(coverage):
    with pytest.raises(ValueError):
        choose_rejection_threshold(np.array([[0.6, 0.4]]), {**CALIBRATION, "known_coverage": coverage})


def test_head_validates_saved_parameters_and_inference_input():
    with pytest.raises(ValueError):
        LinearHead(np.zeros((2, 2)), np.zeros(3), np.zeros(2), np.ones(2), True, 1)
    with pytest.raises(ValueError):
        LinearHead(np.zeros((2, 2)), np.zeros(2), np.zeros(2), np.array([0.0, 1.0]), True, 1)
    head = LinearHead(np.zeros((2, 2)), np.zeros(2), np.zeros(2), np.ones(2), True, 1)
    for features in [np.zeros((1, 3)), np.array([[np.nan, 1]]), np.zeros(2)]:
        with pytest.raises(ValueError):
            head.logits(features)
