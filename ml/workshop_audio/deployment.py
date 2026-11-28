"""Float32 research export with explicit synthetic isolation and original-TF parity.

BENGKEL_AUDIO_METADATA is custom JSON, not TensorFlow Task AudioClassifier
metadata. Temperature/rejection apply once after averaging recording logits.
"""
from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
import wave

import numpy as np

from .audio import load_audio, preprocess, windows
from .data import digest
from .encoder import SavedModelEncoder
from .learning import LinearHead, choose_rejection_threshold, fit_head, fit_temperature, probabilities
from .pipeline import read_config

METADATA_NAME = "BENGKEL_AUDIO_METADATA"
YAMNET_ARCHIVE_SHA256 = "b80da2a1a56926fb0767205051a200dd7b3beaf3ea1ea126c42a53943996e5e0"
AGGREGATION = "recording_mean_window_logits"
PROBABILITY_TOLERANCE = 0.0001
SIZE_TARGET_BYTES = 10000000


def _json_bytes(value: dict) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def validate_output_location(path: Path, *, synthetic: bool) -> None:
    """Synthetic artifacts may only enter Android debug/test source sets."""
    if not synthetic:
        return
    parts = path.resolve().parts
    for index, part in enumerate(parts[:-1]):
        if part == "src" and parts[index + 1] not in {"debug", "test", "androidTest", "testDebug", "androidTestDebug"}:
            raise ValueError("Synthetic model output must use debug/test source sets, never production")


def embed_metadata(model_bytes: bytes, metadata: dict) -> bytes:
    """Use TensorFlow's own generated FlatBuffer schema; no tflite_support."""
    import flatbuffers
    from tensorflow.lite.python import schema_py_generated as schema

    root = schema.Model.GetRootAsModel(model_bytes, 0)
    model = schema.ModelT.InitFromObj(root)
    entries = list(model.metadata or [])
    if any(entry.name in (METADATA_NAME, METADATA_NAME.encode()) for entry in entries):
        raise ValueError("Model already contains Bengkel metadata")
    buffer = schema.BufferT()
    buffer.data = np.frombuffer(_json_bytes(metadata), dtype=np.uint8).copy()
    model.buffers = list(model.buffers or [])
    entry = schema.MetadataT()
    entry.name = METADATA_NAME
    entry.buffer = len(model.buffers)
    model.buffers.append(buffer)
    entries.append(entry)
    model.metadata = entries
    builder = flatbuffers.Builder(len(model_bytes) + len(buffer.data) + 1024)
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    return bytes(builder.Output())


def read_embedded_metadata(model_bytes: bytes) -> dict:
    from tensorflow.lite.python import schema_py_generated as schema

    model = schema.Model.GetRootAsModel(model_bytes, 0)
    for index in range(model.MetadataLength()):
        entry = model.Metadata(index)
        if entry.Name() == METADATA_NAME.encode():
            return json.loads(model.Buffers(entry.Buffer()).DataAsNumpy().tobytes())
    raise ValueError("Missing Bengkel model metadata")


def _calibration(value: dict) -> dict:
    temperature, threshold = value.get("temperature"), value.get("rejection_threshold")
    if (isinstance(temperature, bool) or not isinstance(temperature, (int, float))
            or not np.isfinite(temperature) or temperature <= 0):
        raise ValueError("Calibration temperature must be finite and positive")
    if (isinstance(threshold, bool) or not isinstance(threshold, (int, float))
            or not np.isfinite(threshold) or not 0 <= threshold <= 1):
        raise ValueError("Calibration rejection threshold must be in [0, 1]")
    if value.get("unit") != AGGREGATION:
        raise ValueError("Calibration must use recording_mean_window_logits")
    return {"temperature": float(temperature), "rejection_threshold": float(threshold), "unit": AGGREGATION}


def load_phase4_head(report_path: Path, cfg: dict) -> tuple[LinearHead, dict, dict]:
    """Require an actual hash-bound Phase 4 artifact; never generate a fallback."""
    if not report_path.is_file():
        raise ValueError(f"Missing Phase 4 report: {report_path}; supply --phase4-report or explicitly use --synthetic-test")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    artifact = report.get("model_artifact")
    if report.get("status") != "research_trained" or not isinstance(artifact, dict):
        raise ValueError("A trained Phase 4 report and head artifact are required")
    if report.get("classes") != cfg["classes"]:
        raise ValueError("Phase 4 report class order differs from deployment")
    source_config = report.get("config", {})
    if any(source_config.get(key) != cfg[key] for key in ("classes", "encoder", "preprocessing")):
        raise ValueError("Phase 4 encoder/preprocessing/class configuration differs")
    datasets = report.get("datasets", [])
    if any("synthetic" in str(row.get("id", "")).lower()
           or str(row.get("source_url", "")).startswith("generated:") for row in datasets):
        raise ValueError("Synthetic Phase 4 fixtures cannot be exported as a vehicle research model")
    if any("did not converge" in warning for warning in report.get("warnings", [])):
        raise ValueError("Phase 4 optimization did not converge")
    calibration_source = report.get("calibration") or {}
    if calibration_source.get("fit_split") != "val":
        raise ValueError("Phase 4 calibration must be fitted on validation recordings")
    calibration = _calibration(calibration_source)
    root = report_path.resolve().parent
    path = (root / artifact["path"]).resolve()
    if not path.is_relative_to(root) or path.suffix != ".npz" or not path.is_file():
        raise ValueError("Missing or unconfined Phase 4 head artifact")
    if digest(path) != artifact.get("sha256"):
        raise ValueError("Phase 4 head SHA-256 mismatch")
    with np.load(path, allow_pickle=False) as stored:
        if "purpose" in stored and str(stored["purpose"]) == "synthetic_test_only":
            raise ValueError("Synthetic head artifacts cannot become vehicle research models")
        if stored["classes"].tolist() != cfg["classes"][:-1]:
            raise ValueError("Phase 4 head class order differs")
        head = LinearHead(stored["weights"], stored["bias"], stored["mean"], stored["scale"], True, 0)
        if head.weights.shape != (artifact["embedding_dimension"], len(cfg["classes"]) - 1):
            raise ValueError("Phase 4 head dimensions differ from report")
        if (float(stored["temperature"]) != calibration["temperature"]
                or float(stored["rejection_threshold"]) != calibration["rejection_threshold"]):
            raise ValueError("Phase 4 head calibration differs from report")
    return head, calibration, {"phase4_report_sha256": digest(report_path), "head_sha256": digest(path),
                               "calibration_evidence": "Phase 4 validation; mechanic calibration approval still required"}


def synthetic_waveform(class_index: int, variation: int, seed: int, *, size: int = 15600) -> np.ndarray:
    """Deterministic tones/noise/transients, explicitly not simulated engines."""
    rng = np.random.default_rng(np.random.SeedSequence([seed, class_index, variation]))
    time = np.arange(size, dtype=np.float64) / 16000
    frequency = [110, 430, 950, 1700, 2700, 3900, 6100][class_index % 7]
    frequency *= 1 + 0.006 * (variation % 5 - 2)
    phase = rng.uniform(0, 2 * np.pi)
    signal = np.sin(2 * np.pi * frequency * time + phase)
    signal += 0.24 * np.sin(2 * np.pi * frequency * 1.53 * time)
    signal *= 0.55 + 0.35 * np.sin(2 * np.pi * (3 + class_index) * time) ** 2
    signal += rng.normal(0, 0.02 + class_index * 0.003, size)
    if class_index == 6:
        signal = rng.normal(0, 0.25, size)
    if variation % 3 == 1:
        signal[size // 3:size // 3 + min(1600, size // 10)] = 0
    if variation % 4 == 2:
        locations = np.arange(350, size, 3200)
        signal[locations] += 0.7
    gain = [0.08, 0.22, 0.55, 0.8][variation % 4]
    signal *= gain / max(1.0, float(np.max(np.abs(signal))))
    lead, tail = (variation % 5) * 320, (variation % 7) * 240 + variation % 13
    return np.pad(signal, (lead, tail)).astype(np.float32)


def _wav_bytes(samples: np.ndarray) -> bytes:
    # Reference predictions decode these exact PCM16 samples, not pre-encoding floats.
    pcm = np.rint(np.clip(samples, -1, 32767 / 32768) * 32768).astype("<i2")
    destination = io.BytesIO()
    with wave.open(destination, "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(16000)
        output.writeframes(pcm.tobytes())
    return destination.getvalue()


def _pcm_samples(wav_bytes: bytes) -> np.ndarray:
    with wave.open(io.BytesIO(wav_bytes)) as source:
        return np.frombuffer(source.readframes(source.getnframes()), dtype="<i2").astype(np.float32) / 32768


def _features(samples: np.ndarray, cfg: dict, encoder: SavedModelEncoder) -> np.ndarray:
    return np.asarray([encoder.embed(item) for item in windows(preprocess(samples, cfg), cfg)])


def _synthetic_head(cfg: dict, encoder: SavedModelEncoder) -> tuple[LinearHead, dict, dict]:
    training, labels, weights, train_hashes = [], [], [], []
    validation_features, validation_labels, validation_hashes = [], [], []
    for label in range(len(cfg["classes"]) - 1):
        for variation in range(3):
            encoded = _wav_bytes(synthetic_waveform(label, variation, cfg["seed"]))
            features = _features(_pcm_samples(encoded), cfg["preprocessing"], encoder)
            training.extend(features)
            labels.extend([label] * len(features))
            weights.extend([1 / len(features)] * len(features))
            train_hashes.append(hashlib.sha256(encoded).hexdigest())
        for variation in range(10, 12):
            encoded = _wav_bytes(synthetic_waveform(label, variation, cfg["seed"]))
            validation_features.append(_features(_pcm_samples(encoded), cfg["preprocessing"], encoder))
            validation_labels.append(label)
            validation_hashes.append(hashlib.sha256(encoded).hexdigest())
    if set(train_hashes) & set(validation_hashes):
        raise ValueError("Synthetic training/validation fixtures overlap")
    head = fit_head(np.asarray(training), np.asarray(labels), np.asarray(weights),
                    {**cfg["training"], "class_count": len(cfg["classes"]) - 1}, cfg["seed"])
    if not head.converged:
        raise ValueError("Synthetic head training did not converge; no untrained fallback")
    logits = np.asarray([head.logits(features).mean(axis=0) for features in validation_features])
    fitted = fit_temperature(logits, np.asarray(validation_labels), cfg["calibration"])
    if not fitted["converged"]:
        raise ValueError("Synthetic calibration optimizer did not converge")
    calibrated = probabilities(logits, fitted["temperature"])
    calibration = {"temperature": fitted["temperature"], "rejection_threshold": choose_rejection_threshold(calibrated, cfg["calibration"]),
                   "unit": AGGREGATION}
    return head, calibration, {"synthetic_training_sha256": train_hashes, "synthetic_validation_sha256": validation_hashes,
                               "training_iterations": head.iterations,
                               "calibration_evidence": "Disjoint generated validation tones/noise only; NOT vehicle or mechanic calibration"}


def _convert(encoder: SavedModelEncoder, head: LinearHead) -> bytes:
    tf = encoder.tf
    from tensorflow.python.framework.convert_to_constants import convert_variables_to_constants_v2

    parameters = [np.asarray(value, dtype=np.float32) for value in (head.weights, head.bias, head.mean, head.scale)]
    if any(not np.isfinite(value).all() for value in parameters) or np.any(parameters[3] <= 0):
        raise ValueError("Head normalization/parameters cannot be represented in float32")
    weights, bias, mean, scale = [tf.constant(value) for value in parameters]

    @tf.function(input_signature=[tf.TensorSpec([15600], tf.float32, name="waveform")], autograph=False)
    def predict(waveform):
        outputs = encoder.function(**{encoder.cfg["input_key"]: waveform}) if encoder.cfg["input_key"] else encoder.function(waveform)
        embeddings = outputs[encoder.cfg["output_key"]] if encoder.cfg["output_key"] is not None else outputs[encoder.cfg["output_index"]]
        embedding = tf.reduce_mean(embeddings, axis=0, keepdims=True)
        logits = tf.matmul((embedding - mean) / scale, weights) + bias
        return tf.ensure_shape(logits, [1, head.weights.shape[1]], name="logits")

    # Freeze with a fixed waveform signature, propagating shape into YAMNet's
    # framing/STFT rather than accepting dynamic TensorList/Flex fallbacks.
    frozen = convert_variables_to_constants_v2(predict.get_concrete_function())
    frozen.inputs[0].set_shape([15600])
    converter = tf.lite.TFLiteConverter.from_concrete_functions([frozen])
    converter.optimizations = []
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    converter.inference_input_type = tf.float32
    converter.inference_output_type = tf.float32
    try:
        converted = converter.convert()
    except (ValueError, RuntimeError, tf.errors.OpError) as error:
        raise ValueError("Pinned YAMNet cannot export with built-in float32 ops; unsupported graph, no encoder/Flex fallback") from error
    from tensorflow.lite.python import schema_py_generated as schema
    model = schema.Model.GetRootAsModel(converted, 0)
    if any(model.OperatorCodes(index).BuiltinCode() == schema.BuiltinOperator.CUSTOM for index in range(model.OperatorCodesLength())):
        raise ValueError("Custom/Flex operators are forbidden")
    return converted


def _interpreter(model_bytes: bytes, classes: int):
    import tensorflow as tf

    interpreter = tf.lite.Interpreter(model_content=model_bytes, num_threads=1)
    interpreter.allocate_tensors()
    inputs, outputs = interpreter.get_input_details(), interpreter.get_output_details()
    if (len(inputs) != 1 or len(outputs) != 1 or inputs[0]["dtype"] != np.float32 or outputs[0]["dtype"] != np.float32
            or inputs[0]["shape"].tolist() != [15600] or outputs[0]["shape"].tolist() != [1, classes]
            or inputs[0]["shape_signature"].tolist() != [15600] or outputs[0]["shape_signature"].tolist() != [1, classes]):
        raise ValueError("Export must have fixed float32 [15600] -> [1, known_classes] tensors")
    return interpreter, inputs[0]["index"], outputs[0]["index"]


def _trim_bounds(samples: np.ndarray, cfg: dict) -> tuple[int, int]:
    # Same absolute float64 cumulative-RMS grid as audio.preprocess; retain
    # boundaries for Android's independent preprocessing parity assertions.
    starts = np.arange(0, samples.size, cfg["trim_hop_samples"])
    ends = np.minimum(starts + cfg["trim_frame_samples"], samples.size)
    energy = np.concatenate(([0.0], np.cumsum(np.square(samples, dtype=np.float64))))
    rms = np.sqrt(np.maximum(energy[ends] - energy[starts], 0) / (ends - starts))
    active = np.flatnonzero(rms > 10 ** (cfg["trim_threshold_dbfs"] / 20))
    if not len(active):
        raise ValueError("Fixture is silent")
    return max(0, int(starts[active[0]]) - cfg["trim_margin_samples"]), min(samples.size, int(ends[active[-1]]) + cfg["trim_margin_samples"])


def _export_fixtures(destination: Path, cfg: dict, encoder: SavedModelEncoder, head: LinearHead,
                     calibration: dict, model_bytes: bytes, excluded_hashes: set[str]) -> tuple[dict, bytes, float, float]:
    interpreter, input_index, output_index = _interpreter(model_bytes, len(cfg["classes"]) - 1)
    destination.mkdir(parents=True, exist_ok=True)
    rows, max_probability_error, max_logit_error = [], 0.0, 0.0
    for index in range(20):
        generated = synthetic_waveform(index % 7, 100 + index, cfg["seed"], size=15600 + 7800 * (index % 3) + index * 17)
        encoded = _wav_bytes(generated)
        sha256 = hashlib.sha256(encoded).hexdigest()
        if sha256 in excluded_hashes:
            raise ValueError("Parity fixture overlaps synthetic training/calibration")
        path = destination / f"fixture_{index:02d}.wav"
        path.write_bytes(encoded)
        samples = load_audio(path, cfg["preprocessing"])
        prepared = preprocess(samples, cfg["preprocessing"])
        batches = windows(prepared, cfg["preprocessing"])
        original_logits, lite_logits = [], []
        for item in batches:
            # This is the original pinned SavedModel and float64 Python head,
            # deliberately independent of both converted graph and interpreter.
            original_logits.append(head.logits(encoder.embed(item)[None])[0])
            interpreter.set_tensor(input_index, np.ascontiguousarray(item, dtype=np.float32))
            interpreter.invoke()
            result = interpreter.get_tensor(output_index)[0].astype(np.float64)
            if not np.isfinite(result).all():
                raise ValueError("TFLite produced nonfinite logits")
            lite_logits.append(result)
        mean_logits = np.mean(original_logits, axis=0)
        lite_mean = np.mean(lite_logits, axis=0)
        reference = probabilities(mean_logits[None], calibration["temperature"])[0]
        candidate = probabilities(lite_mean[None], calibration["temperature"])[0]
        max_probability_error = max(max_probability_error, float(np.max(np.abs(reference - candidate))))
        max_logit_error = max(max_logit_error, float(np.max(np.abs(mean_logits - lite_mean))))
        start, end = _trim_bounds(samples, cfg["preprocessing"])
        if end - start != len(prepared):
            raise ValueError("Fixture trim boundary/reference mismatch")
        rejected = float(reference.max()) < calibration["rejection_threshold"]
        if rejected != (float(candidate.max()) < calibration["rejection_threshold"]):
            raise ValueError("Python/TFLite rejection decisions differ")
        rows.append({"file": path.name, "sha256": sha256, "trim_start": start, "trim_end": end,
                     "window_count": len(batches), "mean_logits": mean_logits.tolist(),
                     "probabilities": reference.tolist(), "rejected": rejected})
    if max_probability_error >= PROBABILITY_TOLERANCE:
        raise ValueError(f"Original Python/TFLite probability error {max_probability_error} is not below {PROBABILITY_TOLERANCE}")
    latency = _wav_bytes(synthetic_waveform(6, 300, cfg["seed"], size=320000)[:320000])
    (destination / "latency_20s.wav").write_bytes(latency)
    expected = {"schema_version": 1, "purpose": "synthetic_test_only", "model_sha256": hashlib.sha256(model_bytes).hexdigest(),
                "probability_tolerance": PROBABILITY_TOLERANCE, "files": rows, "latency_file": "latency_20s.wav"}
    (destination / "expected.json").write_bytes(_json_bytes(expected) + b"\n")
    return expected, latency, max_probability_error, max_logit_error


def export_model(config_path: Path, output: Path, *, synthetic_test: bool = False,
                 phase4_report: Path | None = None, fixtures_output: Path | None = None) -> dict:
    cfg = read_config(config_path)
    base = config_path.resolve().parent
    validate_output_location(output, synthetic=synthetic_test)
    fixtures_output = fixtures_output or output / "fixtures"
    # The WAVs always contain synthetic technical stimuli, including when used
    # to check a real research head. Never place those fixtures in production.
    validate_output_location(fixtures_output, synthetic=True)
    if synthetic_test:
        for suffix in ("tflite", "json", "labels.txt", "wav", "head.npz", "export-report.json"):
            validate_output_location(output / f"synthetic_test_model.{suffix}", synthetic=True)
    for name in ["expected.json", "latency_20s.wav", *(f"fixture_{index:02d}.wav" for index in range(20))]:
        validate_output_location(fixtures_output / name, synthetic=True)
    if cfg["encoder"]["archive_sha256"] != YAMNET_ARCHIVE_SHA256:
        raise ValueError("Deployment requires the real pinned YAMNet v1 archive")
    deployment = cfg.get("deployment", {})
    multiplier = deployment.get("fusion_audio_weight_multiplier", 1.0)
    if isinstance(multiplier, bool) or not isinstance(multiplier, (int, float)) or not np.isfinite(multiplier) or not 0 <= multiplier <= 10:
        raise ValueError("fusion_audio_weight_multiplier must be finite and in [0, 10]")
    if synthetic_test and phase4_report is not None:
        raise ValueError("Choose --synthetic-test OR --phase4-report, never both")
    if not synthetic_test:
        head, calibration, evidence = load_phase4_head(phase4_report or base / cfg["output"] / "report.json", cfg)
    encoder = SavedModelEncoder(cfg["encoder"], base, cfg["seed"])
    if synthetic_test:
        head, calibration, evidence = _synthetic_head(cfg, encoder)
    probe = encoder.embed(np.sin(np.arange(15600, dtype=np.float32) * 0.1) * 0.1)
    if probe.shape != (1024,) or head.weights.shape[0] != probe.size:
        raise ValueError("Pinned YAMNet/head embedding dimension mismatch")
    calibration = _calibration(calibration)
    model_id = "synthetic_test_model" if synthetic_test else "vehicle_research_model"
    metadata = {"schema_version": 1, "model_id": model_id,
                "purpose": "synthetic_test_only" if synthetic_test else "vehicle_research", "release_eligible": False,
                "classes": cfg["classes"][:-1], "unknown_label": "unknown", "input_samples": 15600, "output": "logits",
                "preprocessing": cfg["preprocessing"], "calibration": calibration, "aggregation": AGGREGATION,
                "fusion_audio_weight_multiplier": float(multiplier), "config_sha256": digest(config_path),
                "encoder_archive_sha256": cfg["encoder"]["archive_sha256"], "encoder_provenance": cfg["encoder"]["provenance"],
                "calibration_evidence": evidence["calibration_evidence"]}
    model_bytes = embed_metadata(_convert(encoder, head), metadata)
    excluded = set(evidence.get("synthetic_training_sha256", []) + evidence.get("synthetic_validation_sha256", []))
    expected, latency, probability_error, logit_error = _export_fixtures(fixtures_output, cfg, encoder, head, calibration, model_bytes, excluded)
    output.mkdir(parents=True, exist_ok=True)
    (output / f"{model_id}.tflite").write_bytes(model_bytes)
    (output / f"{model_id}.json").write_bytes(_json_bytes({**metadata, "model_sha256": expected["model_sha256"]}) + b"\n")
    (output / f"{model_id}.labels.txt").write_text("\n".join(cfg["classes"][:-1]) + "\n", encoding="utf-8")
    if synthetic_test:
        (output / "synthetic_test_model.wav").write_bytes(latency)
        np.savez(output / "synthetic_test_model.head.npz", weights=head.weights, bias=head.bias, mean=head.mean, scale=head.scale,
                 temperature=np.asarray(calibration["temperature"]), rejection_threshold=np.asarray(calibration["rejection_threshold"]),
                 classes=np.asarray(cfg["classes"][:-1]), purpose=np.asarray("synthetic_test_only"),
                 model_id=np.asarray("synthetic_test_model"), release_eligible=np.asarray(False))
    report = {"schema_version": 1, "model_id": model_id, "purpose": metadata["purpose"], "release_eligible": False,
              "model_sha256": expected["model_sha256"], "model_bytes": len(model_bytes), "size_target_bytes": SIZE_TARGET_BYTES,
              "size_target_met": len(model_bytes) < SIZE_TARGET_BYTES, "quantization": "none",
              "python_vs_tflite_max_probability_error": probability_error, "python_vs_tflite_max_logit_error": logit_error,
              "probability_tolerance": PROBABILITY_TOLERANCE, "parity_fixture_count": 20,
              "reference": "original pinned SavedModel encoder + float64 trained head; recording mean logits then calibration",
              "automotive_accuracy": None, "automotive_calibration_measured": False,
              "latency_measured": False, "runtime_target": "LiteRT 1.4.2; desktop parity is not Android runtime certification",
              "release_blockers": ["real vehicle model and held-out automotive evaluation", "mechanic-verified calibration", "encoder provenance/license approval"],
              **evidence}
    (output / f"{model_id}.export-report.json").write_bytes(_json_bytes(report) + b"\n")
    return report
