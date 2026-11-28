"""One reproducible research run: rights audit -> grouped data -> frozen encoder -> head -> report."""
from __future__ import annotations

from collections import Counter
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import random
import sys

import numpy as np

from .audio import augment_training, load_audio, preprocess, windows
from .data import Clip, audit_split, digest, grouped_split, load_manifest, with_pcm_hash
from .encoder import SavedModelEncoder
from .evaluation import evaluate
from .learning import choose_rejection_threshold, fit_head, fit_temperature, probabilities


def read_config(path: Path) -> dict:
    cfg = json.loads(path.read_text(encoding="utf-8"))
    if cfg["schema_version"] != 1 or type(cfg["seed"]) is not int or cfg["seed"] < 0:
        raise ValueError("Unsupported config version/seed")
    classes = cfg["classes"]
    if len(classes) < 3 or classes[-1] != "unknown" or "background_noise" not in classes or len(set(classes)) != len(classes):
        raise ValueError("Classes must uniquely include background_noise and end with rejection-only unknown")
    prep, encoder = cfg["preprocessing"], cfg["encoder"]
    if prep["sample_rate"] != 16000 or prep["window_samples"] != 15600:
        raise ValueError("Protocol requires 16kHz and 0.975s windows")
    if encoder["sample_rate"] != prep["sample_rate"] or encoder["window_samples"] != prep["window_samples"]:
        raise ValueError("Encoder/preprocessing input contracts differ")
    if cfg["augmentation"]["sample_rate"] != 16000 or type(cfg["augmentation"]["copies"]) is not int or cfg["augmentation"]["copies"] < 0:
        raise ValueError("Invalid augmentation config")
    if not 0 < prep["hop_samples"] < prep["window_samples"]:
        raise ValueError("Windows must overlap with a positive hop")
    if not 1 <= cfg["evaluation"]["ece_bins"] <= 100 or cfg["evaluation"]["minimum_clips_per_class"] < 1:
        raise ValueError("Invalid evaluation counts")
    return cfg


def _inventory(clips: list[Clip], cfg: dict) -> list[dict]:
    return [{**dataset, "available_clips": sum(clip.dataset_id == dataset["id"] for clip in clips),
             "classes": dict(Counter(clip.label for clip in clips if clip.dataset_id == dataset["id"])),
             "vehicles": len({clip.vehicle_id for clip in clips if clip.dataset_id == dataset["id"] and clip.vehicle_id}),
             "devices": len({clip.device_id for clip in clips if clip.dataset_id == dataset["id"]})}
            for dataset in cfg["datasets"]]


def _seed(seed: int, clip_id: str, window: int, copy: int, scene: str) -> int:
    value = f"{seed}:{clip_id}:{window}:{copy}:{scene}".encode()
    return int.from_bytes(hashlib.sha256(value).digest()[:8], "little")


def _write_report(output: Path, report: dict) -> None:
    output.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(report, indent=2, sort_keys=True, allow_nan=False) + "\n"
    temporary = output / ".report.json.tmp"
    temporary.write_text(encoded, encoding="utf-8")
    temporary.replace(output / "report.json")
    metrics = report["test"]
    lines = ["# Audio model research evaluation", "", f"Status: **{report['status']}**. Release eligible: **{report['release_eligible']}**.",
             "", "## Evidence limits", ""]
    lines.extend(f"- {warning}" for warning in report["warnings"])
    unseen = report.get("unseen_vehicles") or {}
    unseen_metrics = unseen.get("metrics") or {}
    lines.extend(["", "## Clip-level held-out test metrics", "", "Known-class top-1/top-3 exclude the rejection label; unknown is an abstention, not a calibrated probability.",
                  "", "| Metric | All held-out test | Unseen-vehicle subset |", "| --- | --- | --- |"])
    for key in ("top1_accuracy", "top3_accuracy", "open_set_accuracy", "coverage", "ece"):
        values = [group.get(key) if group.get(key) is not None else "Not measurable" for group in (metrics, unseen_metrics)]
        lines.append(f"| {key} | {values[0]} | {values[1]} |")
    lines.extend(["", "## Per-class results", "", "| Class | Support | Precision | Recall | F1 |", "| --- | ---: | ---: | ---: | ---: |"])
    for row in metrics["per_class"]:
        values = [row.get(key) if row.get(key) is not None else "Not measurable" for key in ("class", "support", "precision", "recall", "f1")]
        lines.append("| " + " | ".join(str(value) for value in values) + " |")
    lines.extend(["", "## Weakest / unsupported classes", "",
                  "Priority order (unsupported first, then lowest F1): " + ", ".join(row["class"] for row in metrics["weakest_classes"]),
                  "", "## Confusion matrix", "", "Rows=true, columns=predicted, including unknown.",
                  "", "| True / predicted | " + " | ".join(report["classes"]) + " |",
                  "| --- | " + " | ".join("---:" for _ in report["classes"]) + " |"])
    for name, row in zip(report["classes"], metrics["confusion_matrix"], strict=True):
        lines.append("| " + name + " | " + " | ".join(str(value) for value in row) + " |")
    calibration = report.get("calibration")
    if calibration:
        lines.extend(["", "## Validation-only calibration", "", "| Temperature | NLL before | NLL after |",
                      "| ---: | ---: | ---: |",
                      f"| {calibration['temperature']} | {calibration['nll_before']} | {calibration['nll_after']} |"])
    lines.extend(["", "Unseen-vehicle subset: " + str(unseen.get("vehicle_count", "not measurable")) +
                  " vehicles, " + str(unseen.get("recording_count", "not measurable")) + " recordings.",
                  "", "See report.json for group audits, calibration, full configuration, hashes, provenance and augmentation ledger.", ""])
    temporary = output / ".report.md.tmp"
    temporary.write_text("\n".join(lines), encoding="utf-8")
    temporary.replace(output / "report.md")


def run(config_path: Path, encoder=None) -> dict:
    cfg = read_config(config_path)
    base = config_path.resolve().parent
    output = base / cfg["output"]
    os.environ.setdefault("OMP_NUM_THREADS", "1")
    os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
    random.seed(cfg["seed"])
    np.random.seed(cfg["seed"])
    known = cfg["classes"][:-1]
    empty = evaluate(np.empty(0, dtype=int), np.empty((0, len(known))), cfg["classes"], 0.0, cfg["evaluation"]["ece_bins"])
    source = hashlib.sha256()
    for path in sorted(Path(__file__).parent.glob("*.py")):
        source.update(path.name.encode()); source.update(path.read_bytes())
    report = {"schema_version": 1, "status": "blocked", "release_eligible": False, "classes": cfg["classes"],
              "config": cfg, "config_sha256": digest(config_path), "code_sha256": source.hexdigest(),
              "lock_sha256": digest(base / "uv.lock") if (base / "uv.lock").exists() else None,
              "python_version": sys.version.split()[0], "packages": {name: importlib.metadata.version(name)
                for name in ("numpy", "scipy", "librosa", "soundfile", "tensorflow-cpu")},
              "warnings": [], "test": empty, "calibration": None, "unseen_vehicles": None,
              "split": None, "model_artifact": None, "augmentation_ledger": [], "datasets": _inventory([], cfg)}
    report["class_clip_counts"] = {label: 0 for label in cfg["classes"]}
    if cfg["encoder"]["research_only"] or cfg["encoder"]["provenance"]["license_review_status"] != "verified":
        report["warnings"].append("RESEARCH ONLY: encoder provenance/weight license/attribution need verification; no release authorization.")
    report["warnings"].append("Confidence rejection is not an OOD guarantee; unknown has no calibrated fault probability.")
    try:
        manifest = base / cfg["manifest"]
        report["manifest_sha256"] = digest(manifest)
        clips = load_manifest(manifest, base / cfg["audio_root"], cfg["datasets"], cfg["classes"])
        report["datasets"] = _inventory(clips, cfg)
        report["class_clip_counts"] = {label: sum(clip.label == label and clip.role == "target" for clip in clips) for label in cfg["classes"]}
        if not clips:
            raise ValueError("No consented, rights-cleared, mechanic-verified training recordings; metrics cannot be measured")
        report["data_sha256"] = hashlib.sha256((report["manifest_sha256"] + "\n" +
            "\n".join(f"{clip.id}:{clip.sha256}" for clip in clips)).encode()).hexdigest()
        waveforms = {clip.id: preprocess(load_audio(clip.path, cfg["preprocessing"]), cfg["preprocessing"]) for clip in clips}
        clips = [with_pcm_hash(clip, waveforms[clip.id]) for clip in clips]
        duplicate_labels: dict[str, set[str]] = {}
        for clip in clips:
            duplicate_labels.setdefault(clip.pcm_sha256, set()).add(clip.label)
        if any(len(labels) > 1 for labels in duplicate_labels.values()):
            raise ValueError("Identical canonical audio has conflicting single-class labels")
        mapping, audit = grouped_split(clips, cfg["split"], cfg["seed"], known)
        audit_split(clips, mapping)
        report["split"] = {**audit, "recordings": {clip.id: {"split": mapping[clip.id], "vehicle_id": clip.vehicle_id,
            "source_id": clip.source_id, "session_id": clip.session_id, "device_id": clip.device_id,
            "audio_sha256": clip.sha256, "canonical_pcm_sha256": clip.pcm_sha256, "role": clip.role, "class": clip.label} for clip in clips}}
        targets = [clip for clip in clips if clip.role == "target"]
        train = [clip for clip in targets if mapping[clip.id] == "train" and clip.label in known]
        train_counts = Counter(clip.label for clip in train)
        if any(train_counts[label] == 0 for label in known):
            raise ValueError("Training split lacks known classes; collect more independent groups, not overlapping windows")
        noise_pools = {scene: [clip for clip in clips if clip.role == "noise" and mapping[clip.id] == "train" and clip.scene == scene]
                       for scene in ("workshop", "road")}
        if cfg["augmentation"]["copies"] and not all(noise_pools.values()):
            raise ValueError("Training-only rights-cleared workshop AND road noise required for configured augmentation")
        if encoder is None:
            encoder = SavedModelEncoder(cfg["encoder"], base, cfg["seed"])
        features: dict[str, np.ndarray] = {}
        x_train, y_train, weights = [], [], []
        for clip in targets:
            original = windows(waveforms[clip.id], cfg["preprocessing"])
            vectors = [encoder.embed(window) for window in original]
            features[clip.id] = np.asarray(vectors)
            if clip not in train:
                continue
            expanded = list(vectors)
            for index, window in enumerate(original):
                for copy in range(cfg["augmentation"]["copies"]):
                    for scene, pool in noise_pools.items():
                        seed = _seed(cfg["seed"], clip.id, index, copy, scene)
                        rng = np.random.default_rng(seed)
                        noise_clip = pool[int(rng.integers(len(pool)))]
                        augmented = augment_training(window, waveforms[noise_clip.id], rng, cfg["augmentation"], split="train")
                        expanded.append(encoder.embed(augmented))
                        report["augmentation_ledger"].append({"clip_id": clip.id, "window": index, "copy": copy,
                            "noise_id": noise_clip.id, "scene": scene, "split": "train", "seed": seed})
            class_weight = len(train) / (len(known) * train_counts[clip.label])
            x_train.extend(expanded)
            y_train.extend([known.index(clip.label)] * len(expanded))
            weights.extend([class_weight / len(expanded)] * len(expanded))
        head = fit_head(np.asarray(x_train), np.asarray(y_train), np.asarray(weights),
                        {**cfg["training"], "class_count": len(known)}, cfg["seed"])
        if not head.converged:
            report["warnings"].append("Classifier optimizer did not converge; research metrics only, no release.")
        def clip_logits(rows: list[Clip]) -> np.ndarray:
            return np.asarray([head.logits(features[clip.id]).mean(axis=0) for clip in rows]).reshape(-1, len(known))
        validation = [clip for clip in targets if mapping[clip.id] == "val" and clip.label in known]
        if not validation:
            raise ValueError("No known validation recordings; cannot calibrate or tune rejection without leakage")
        validation_logits = clip_logits(validation)
        labels_val = np.asarray([known.index(clip.label) for clip in validation])
        calibration = fit_temperature(validation_logits, labels_val, cfg["calibration"])
        temperature = calibration["temperature"]
        threshold = choose_rejection_threshold(probabilities(validation_logits, temperature), cfg["calibration"])
        report["calibration"] = {**calibration, "rejection_threshold": threshold, "fit_split": "val",
            "unit": "recording_mean_window_logits", "known_validation_recordings": len(validation),
            "unknown_strategy": "reject_if_calibrated_known_max_below_validation_known_coverage_quantile",
            "warning": "Validation is reused for temperature and threshold; no independent calibration sample. OOD success requires unknown test examples."}
        test = [clip for clip in targets if mapping[clip.id] == "test"]
        if not test:
            raise ValueError("No held-out test recordings")
        if not calibration["converged"]:
            report["warnings"].append("Temperature optimizer did not converge; no calibration reliability claim.")
        test_logits = clip_logits(test)
        labels_test = np.asarray([cfg["classes"].index(clip.label) for clip in test])
        calibrated = probabilities(test_logits, temperature)
        report["test_before_calibration"] = evaluate(labels_test, probabilities(test_logits), cfg["classes"], threshold, cfg["evaluation"]["ece_bins"])
        report["test"] = evaluate(labels_test, calibrated, cfg["classes"], threshold, cfg["evaluation"]["ece_bins"])
        train_vehicles = {clip.vehicle_id for clip in clips if mapping[clip.id] == "train" and clip.vehicle_id}
        unseen = [index for index, clip in enumerate(test) if clip.vehicle_id and clip.vehicle_id not in train_vehicles]
        report["unseen_vehicles"] = {"vehicle_count": len({test[i].vehicle_id for i in unseen}), "recording_count": len(unseen),
            "metrics": evaluate(labels_test[unseen], calibrated[unseen], cfg["classes"], threshold, cfg["evaluation"]["ece_bins"])}
        report["warnings"].extend(report["test"].get("warnings", []))
        report["small_data"] = any(report["class_clip_counts"][label] < cfg["evaluation"]["minimum_clips_per_class"] for label in cfg["classes"])
        if report["small_data"]:
            report["warnings"].append("DATA TOO SMALL: at least one class has fewer than configured minimum recordings; clip count alone does not establish independence or diagnostic validity.")
        if report["unseen_vehicles"]["vehicle_count"] < cfg["evaluation"]["minimum_test_vehicles"]:
            report["warnings"].append("Too few unseen test vehicles for generalization conclusions.")
        model_name = f"research-head-{report['config_sha256'][:12]}-{report['data_sha256'][:12]}.npz"
        output.mkdir(parents=True, exist_ok=True)
        np.savez_compressed(output / model_name, weights=head.weights, bias=head.bias, mean=head.mean, scale=head.scale,
                            temperature=np.asarray(temperature), rejection_threshold=np.asarray(threshold), classes=np.asarray(known))
        report["model_artifact"] = {"path": model_name, "sha256": digest(output / model_name), "embedding_dimension": int(head.weights.shape[0])}
        report["status"] = "research_trained"
        # Phase 4 does not promote artifacts. Provenance, clinical/mechanical validation and OOD review remain human gates.
        report["release_eligible"] = False
    except (ValueError, KeyError, TypeError, OSError, RuntimeError) as error:
        report["warnings"].append(f"BLOCKED: {error}")
        report["status"] = "blocked"
        report["model_artifact"] = None
    _write_report(output, report)
    return report
