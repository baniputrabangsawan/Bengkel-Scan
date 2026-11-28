"""Hand-computed closed-set ranking and open-set rejection metrics."""

import json

import numpy as np
import pytest

from workshop_audio.evaluation import evaluate
from workshop_audio.learning import choose_rejection_threshold


def test_hand_computed_known_ranking_open_set_confusion_and_calibration():
    labels = np.array([0, 0, 1, 2, 2])
    probs = np.array([[0.8, 0.2], [0.4, 0.6], [0.55, 0.45], [0.5, 0.5], [0.1, 0.9]])
    result = evaluate(labels, probs, ["bearing", "belt", "unknown"], threshold=0.6, bins=2)
    assert result["counts"] == {"total": 5, "known": 3, "unknown": 2, "accepted": 3, "rejected": 2}
    assert result["top1_accuracy"] == pytest.approx(1 / 3)
    assert result["top3_accuracy"] == 1.0
    assert result["open_set_accuracy"] == 0.4
    assert result["coverage"] == 0.6
    assert result["confusion_matrix"] == [[1, 1, 0], [0, 0, 1], [0, 1, 1]]
    assert result["ece"] == pytest.approx(19 / 60)
    bearing, belt, unknown = result["per_class"]
    assert bearing == {"class": "bearing", "support": 2, "precision": 1.0, "recall": 0.5, "f1": pytest.approx(2 / 3)}
    assert belt == {"class": "belt", "support": 1, "precision": 0.0, "recall": 0.0, "f1": 0.0}
    assert unknown == {"class": "unknown", "support": 2, "precision": 0.5, "recall": 0.5, "f1": 0.5}
    assert [item["class"] for item in result["weakest_classes"]] == ["belt", "unknown", "bearing"]
    assert result["warnings"] == []
    json.dumps(result, allow_nan=False)


def test_threshold_calibrated_on_validation_abstains_on_unknown_and_false_abstentions_count():
    validation = np.array([[0.7, 0.3], [0.8, 0.2], [0.9, 0.1], [0.95, 0.05]])
    threshold = choose_rejection_threshold(validation, {"known_coverage": 0.75})
    test_probs = np.array([[0.5, 0.5], [0.6, 0.4], [0.2, 0.8]])
    result = evaluate(np.array([2, 0, 1]), test_probs, ["a", "b", "unknown"], threshold, bins=5)
    assert result["confusion_matrix"] == [[0, 0, 1], [0, 1, 0], [0, 0, 1]]
    assert result["open_set_accuracy"] == pytest.approx(2 / 3)
    assert result["top1_accuracy"] == 1.0
    assert result["coverage"] == pytest.approx(1 / 3)
    assert test_probs.shape == (3, 2)  # Rejection is a label, not a third probability.


def test_ece_includes_confidence_one_and_uses_known_examples_only():
    result = evaluate(
        np.array([0, 1, 2]),
        np.array([[1.0, 0.0], [1.0, 0.0], [0.5, 0.5]]),
        ["a", "b", "unknown"],
        threshold=0.9,
        bins=10,
    )
    assert result["ece"] == 0.5
    assert result["top1_accuracy"] == 0.5
    assert result["open_set_accuracy"] == pytest.approx(2 / 3)


def test_ece_is_weighted_by_bin_population_not_number_of_nonempty_bins():
    result = evaluate(
        np.array([0, 1, 1, 0]),
        np.array([[0.6, 0.4], [0.45, 0.55], [0.45, 0.55], [0.9, 0.1]]),
        ["a", "b", "unknown"],
        threshold=0,
        bins=5,
    )
    assert result["ece"] == pytest.approx((0.4 + 0.45 + 0.45 + 0.1) / 4)


def test_missing_class_metrics_and_warnings_are_explicit():
    result = evaluate(np.array([0]), np.array([[0.9, 0.1]]), ["a", "b", "unknown"], threshold=0.5, bins=5)
    assert result["per_class"][1] == {"class": "b", "support": 0, "precision": None, "recall": None, "f1": None}
    assert result["per_class"][2]["recall"] is None
    assert [item["class"] for item in result["weakest_classes"]] == ["b", "unknown", "a"]
    assert any("b" in warning and "support" in warning for warning in result["warnings"])
    assert any("unknown" in warning and "OOD" in warning for warning in result["warnings"])
    json.dumps(result, allow_nan=False)


def test_supported_class_with_no_predictions_has_null_precision_and_zero_recall_f1():
    result = evaluate(np.array([1]), np.array([[0.9, 0.1]]), ["a", "b", "unknown"], threshold=0.5, bins=5)
    assert result["per_class"][1] == {"class": "b", "support": 1, "precision": None, "recall": 0.0, "f1": 0.0}
    assert result["per_class"][0]["precision"] == 0.0
    assert result["per_class"][0]["recall"] is None
    assert result["per_class"][0]["f1"] is None


def test_empty_subset_returns_null_not_fabricated_accuracies():
    result = evaluate(np.array([], dtype=int), np.empty((0, 2)), ["a", "b", "unknown"], threshold=0.6, bins=10)
    for name in ["top1_accuracy", "top3_accuracy", "open_set_accuracy", "coverage", "ece"]:
        assert result[name] is None
    assert result["counts"]["total"] == 0
    assert result["confusion_matrix"] == [[0, 0, 0], [0, 0, 0], [0, 0, 0]]
    assert all(item["support"] == 0 and item["f1"] is None for item in result["per_class"])
    json.dumps(result, allow_nan=False)


def test_unknown_only_subset_has_no_known_ranking_or_calibration_metrics():
    result = evaluate(np.array([2, 2]), np.array([[0.5, 0.5], [0.9, 0.1]]), ["a", "b", "unknown"], threshold=0.7, bins=2)
    assert result["top1_accuracy"] is None
    assert result["top3_accuracy"] is None
    assert result["ece"] is None
    assert result["open_set_accuracy"] == 0.5
    assert result["per_class"][-1]["recall"] == 0.5


def test_top_three_is_raw_known_ranking_and_ties_follow_class_order():
    probs = np.full((2, 4), 0.25)
    result = evaluate(np.array([2, 3]), probs, ["a", "b", "c", "d", "unknown"], threshold=0.5, bins=4)
    assert result["top1_accuracy"] == 0.0
    assert result["top3_accuracy"] == 0.5
    assert result["coverage"] == 0.0
    assert result["confusion_matrix"][2][4] == 1
    assert result["confusion_matrix"][3][4] == 1
    tied = evaluate(np.array([0]), probs[:1], ["a", "b", "c", "d", "unknown"], threshold=0.25, bins=4)
    assert tied["confusion_matrix"][0][0] == 1


@pytest.mark.parametrize(
    "labels,probs,classes,threshold,bins",
    [
        (np.array([0]), np.array([[0.5, 0.4]]), ["a", "b", "unknown"], 0.5, 10),
        (np.array([0]), np.array([[np.nan, 0.5]]), ["a", "b", "unknown"], 0.5, 10),
        (np.array([0]), np.array([[np.inf, 0.5]]), ["a", "b", "unknown"], 0.5, 10),
        (np.array([0]), np.array([[-0.1, 1.1]]), ["a", "b", "unknown"], 0.5, 10),
        (np.array([0]), np.array([0.5, 0.5]), ["a", "b", "unknown"], 0.5, 10),
        (np.array([0, 1]), np.array([[0.5, 0.5]]), ["a", "b", "unknown"], 0.5, 10),
        (np.array([0.0]), np.array([[0.5, 0.5]]), ["a", "b", "unknown"], 0.5, 10),
        (np.array([3]), np.array([[0.5, 0.5]]), ["a", "b", "unknown"], 0.5, 10),
        (np.array([-1]), np.array([[0.5, 0.5]]), ["a", "b", "unknown"], 0.5, 10),
        (np.array([0]), np.array([[0.5, 0.5]]), ["a", "a", "unknown"], 0.5, 10),
        (np.array([0]), np.array([[0.5, 0.5]]), ["a", "b", "other"], 0.5, 10),
        (np.array([0]), np.array([[0.5, 0.5]]), ["a", "b", "unknown"], np.nan, 10),
        (np.array([0]), np.array([[0.5, 0.5]]), ["a", "b", "unknown"], 1.1, 10),
        (np.array([0]), np.array([[0.5, 0.5]]), ["a", "b", "unknown"], 0.5, 0),
        (np.array([0]), np.array([[0.5, 0.5]]), ["a", "b", "unknown"], 0.5, 1.5),
    ],
)
def test_evaluation_rejects_invalid_contract_inputs(labels, probs, classes, threshold, bins):
    with pytest.raises(ValueError):
        evaluate(labels, probs, classes, threshold, bins)
