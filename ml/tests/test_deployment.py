"""Technical export gates and original-YAMNet parity, never vehicle accuracy."""
import copy
import hashlib
import json
from pathlib import Path
import wave

import numpy as np
import pytest

from workshop_audio.audio import load_audio, preprocess, windows
from workshop_audio.deployment import (
    METADATA_NAME,
    embed_metadata,
    export_model,
    load_phase4_head,
    read_embedded_metadata,
    validate_output_location,
)
from workshop_audio.encoder import SavedModelEncoder
from workshop_audio.learning import LinearHead, probabilities

PROJECT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize("location", ["androbd/src/main/assets/diagnosis", "androbd/src/release/assets/diagnosis",
                                      "androbd/src/production/assets/diagnosis"])
def test_synthetic_output_cannot_enter_production_source_sets(tmp_path, location):
    with pytest.raises(ValueError, match="debug|test"):
        validate_output_location(tmp_path / location, synthetic=True)


def test_synthetic_location_follows_symlinks(tmp_path):
    production = tmp_path / "src/main/assets"
    production.mkdir(parents=True)
    link = tmp_path / "debug-output"
    link.symlink_to(production, target_is_directory=True)
    with pytest.raises(ValueError, match="debug|test"):
        validate_output_location(link, synthetic=True)
    validate_output_location(tmp_path / "src/debug/assets/diagnosis", synthetic=True)
    validate_output_location(tmp_path / "fixtures", synthetic=True)


def test_missing_phase4_artifact_fails_closed_before_loading_encoder(tmp_path):
    cfg = json.loads((PROJECT / "config.json").read_text())
    with pytest.raises(ValueError, match="Phase 4 report"):
        export_model(PROJECT / "config.json", tmp_path / "out", phase4_report=tmp_path / "absent.json")
    report = tmp_path / "report.json"
    report.write_text(json.dumps({"status": "blocked", "model_artifact": None}))
    with pytest.raises(ValueError, match="trained Phase 4"):
        load_phase4_head(report, cfg)
    assert not (tmp_path / "out").exists()


def test_real_head_checks_hash_classes_and_calibration(tmp_path):
    cfg = json.loads((PROJECT / "config.json").read_text())
    classes = cfg["classes"][:-1]
    artifact = tmp_path / "head.npz"
    np.savez(artifact, weights=np.zeros((3, len(classes))), bias=np.zeros(len(classes)),
             mean=np.zeros(3), scale=np.ones(3), classes=np.asarray(classes),
             temperature=np.asarray(1.5), rejection_threshold=np.asarray(0.4))
    report = {"status": "research_trained", "config": cfg, "classes": cfg["classes"],
              "datasets": [], "model_artifact": {"path": "head.npz", "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
                                                   "embedding_dimension": 3},
              "calibration": {"temperature": 1.5, "rejection_threshold": 0.4,
                              "fit_split": "val", "unit": "recording_mean_window_logits"}}
    report_path = tmp_path / "report.json"
    report_path.write_text(json.dumps(report))
    head, calibration, _ = load_phase4_head(report_path, cfg)
    assert head.weights.shape == (3, len(classes))
    assert calibration["temperature"] == 1.5
    for change in ["hash", "classes", "unit", "synthetic"]:
        bad = copy.deepcopy(report)
        if change == "hash":
            bad["model_artifact"]["sha256"] = "0" * 64
        elif change == "classes":
            bad["classes"] = list(reversed(cfg["classes"]))
        elif change == "unit":
            bad["calibration"]["unit"] = "window_probabilities"
        else:
            bad["datasets"] = [{"id": "synthetic_engineering_fixture", "source_url": "generated://tones"}]
        report_path.write_text(json.dumps(bad))
        with pytest.raises(ValueError):
            load_phase4_head(report_path, cfg)


def test_flatbuffer_metadata_survives_renaming_without_support_dependency(tmp_path):
    import tensorflow as tf

    @tf.function(input_signature=[tf.TensorSpec([15600], tf.float32)])
    def graph(audio):
        return tf.reshape(tf.reduce_mean(audio), [1, 1])

    if tf.config.threading.get_intra_op_parallelism_threads() != 1:
        tf.config.threading.set_intra_op_parallelism_threads(1)
    if tf.config.threading.get_inter_op_parallelism_threads() != 1:
        tf.config.threading.set_inter_op_parallelism_threads(1)
    converter = tf.lite.TFLiteConverter.from_concrete_functions([graph.get_concrete_function()])
    model = converter.convert()
    metadata = {"schema_version": 1, "purpose": "synthetic_test_only", "release_eligible": False}
    marked = embed_metadata(model, metadata)
    renamed = tmp_path / "innocent-name.bin"
    renamed.write_bytes(marked)
    assert METADATA_NAME.encode() in marked
    assert b"synthetic_test_only" in marked
    assert read_embedded_metadata(renamed.read_bytes()) == metadata
    assert b"model_sha256" not in marked
    with pytest.raises(ValueError, match="already"):
        embed_metadata(marked, metadata)


def test_real_pinned_yamnet_export_matches_original_python_on_twenty_wavs(tmp_path):
    cfg = json.loads((PROJECT / "config.json").read_text())
    cfg["encoder"]["path"] = str(PROJECT / "artifacts/encoders/yamnet-1")
    config = tmp_path / "config.json"
    config.write_text(json.dumps(cfg))
    output = tmp_path / "src/debug/assets/diagnosis"
    fixtures = tmp_path / "src/androidTest/assets/audio-parity"
    report = export_model(config, output, synthetic_test=True, fixtures_output=fixtures)
    model = output / "synthetic_test_model.tflite"
    sidecar = json.loads((output / "synthetic_test_model.json").read_text())
    embedded = read_embedded_metadata(model.read_bytes())
    assert embedded == {key: value for key, value in sidecar.items() if key != "model_sha256"}
    assert sidecar["model_sha256"] == hashlib.sha256(model.read_bytes()).hexdigest()
    assert sidecar["purpose"] == "synthetic_test_only"
    assert sidecar["release_eligible"] is False
    assert sidecar["classes"] == cfg["classes"][:-1]
    assert sidecar["preprocessing"] == cfg["preprocessing"]
    assert sidecar["output"] == "logits"
    assert sidecar["calibration"]["unit"] == "recording_mean_window_logits"
    assert (output / "synthetic_test_model.wav").read_bytes() == (fixtures / "latency_20s.wav").read_bytes()
    with wave.open(str(fixtures / "latency_20s.wav")) as source:
        assert (source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getnframes()) == (1, 2, 16000, 320000)
    expected = json.loads((fixtures / "expected.json").read_text())
    assert len(expected["files"]) == 20
    assert expected["model_sha256"] == sidecar["model_sha256"]
    assert expected["probability_tolerance"] == 0.0001
    assert report["quantization"] == "none"
    assert report["automotive_accuracy"] is None
    assert report["size_target_met"] == (model.stat().st_size < 10000000)
    assert report["python_vs_tflite_max_probability_error"] <= 0.0001
    assert set(report["synthetic_training_sha256"]).isdisjoint(report["synthetic_validation_sha256"])
    assert set(report["synthetic_training_sha256"] + report["synthetic_validation_sha256"]).isdisjoint(
        row["sha256"] for row in expected["files"])
    encoder = SavedModelEncoder(cfg["encoder"], tmp_path, cfg["seed"])
    with np.load(output / "synthetic_test_model.head.npz", allow_pickle=False) as stored:
        assert str(stored["purpose"]) == "synthetic_test_only"
        assert not bool(stored["release_eligible"])
        head = LinearHead(stored["weights"], stored["bias"], stored["mean"], stored["scale"], True, 0)
    for row in expected["files"]:
        path = fixtures / row["file"]
        assert row["sha256"] == hashlib.sha256(path.read_bytes()).hexdigest()
        original = load_audio(path, cfg["preprocessing"])
        prepared = preprocess(original, cfg["preprocessing"])
        batches = windows(prepared, cfg["preprocessing"])
        logits = head.logits(np.asarray([encoder.embed(item) for item in batches])).mean(axis=0)
        calibrated = probabilities(logits[None], sidecar["calibration"]["temperature"])[0]
        assert row["trim_end"] - row["trim_start"] == prepared.size
        assert row["window_count"] == len(batches)
        np.testing.assert_allclose(row["mean_logits"], logits, atol=1e-9, rtol=1e-9)
        np.testing.assert_allclose(row["probabilities"], calibrated, atol=1e-9, rtol=1e-9)
        assert row["rejected"] == (float(calibrated.max()) < sidecar["calibration"]["rejection_threshold"])


def test_synthetic_head_cannot_be_relabelled_as_real_phase4(tmp_path):
    cfg = json.loads((PROJECT / "config.json").read_text())
    artifact = tmp_path / "head.npz"
    np.savez(artifact, purpose=np.asarray("synthetic_test_only"))
    report = {"status": "research_trained", "config": cfg, "classes": cfg["classes"],
              "model_artifact": {"path": artifact.name, "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()},
              "calibration": {"temperature": 1.0, "rejection_threshold": 0.5,
                              "fit_split": "val", "unit": "recording_mean_window_logits"}}
    report_path = tmp_path / "report.json"
    report_path.write_text(json.dumps(report))
    with pytest.raises(ValueError, match="Synthetic head"):
        load_phase4_head(report_path, cfg)
