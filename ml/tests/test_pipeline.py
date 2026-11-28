"""Synthetic engineering fixtures prove plumbing, never automotive model quality."""
import copy
import json
from pathlib import Path

import numpy as np
import soundfile as sf

from workshop_audio.data import digest
from workshop_audio.encoder import SavedModelEncoder
from workshop_audio.pipeline import run

PROJECT = Path(__file__).resolve().parents[1]


def test_empty_inventory_reproduces_honest_report_without_training(tmp_path):
    cfg = json.loads((PROJECT / "config.json").read_text())
    cfg["output"] = "report"
    path = tmp_path / "config.json"
    path.write_text(json.dumps(cfg))
    (tmp_path / "manifest.jsonl").write_text("")
    first = run(path)
    json_before = (tmp_path / "report/report.json").read_bytes()
    second = run(path)
    assert first == second
    assert json_before == (tmp_path / "report/report.json").read_bytes()
    assert first["status"] == "blocked"
    assert first["model_artifact"] is None
    assert first["test"]["top1_accuracy"] is None
    assert first["release_eligible"] is False
    assert all(row["support"] == 0 for row in first["test"]["weakest_classes"])
    assert "No consented" in first["warnings"][-1]


def test_real_yamnet_weighted_head_calibration_and_report_are_reproducible(tmp_path):
    cfg = json.loads((PROJECT / "config.json").read_text())
    cfg["classes"] = ["normal_idle", "background_noise", "unknown"]
    cfg["output"] = "report"
    cfg["encoder"]["path"] = str(PROJECT / "artifacts/encoders/yamnet-1")
    cfg["datasets"] = [{"id": "synthetic_engineering_fixture", "source_url": "generated://pytest-fixtures-not-vehicles",
        "license": "test-code-generated-fixture-only", "license_review_status": "verified", "training_allowed": True,
        "commercial_allowed": False, "requires_bundle_metadata": False, "youtube_derived_audio": False,
        "attribution": "Synthetic unit-test fixture, NOT inspected engine recordings"}]
    root = tmp_path / "data"
    root.mkdir()
    manifest = []
    for group in range(3):
        for role, label, scene in [("target", "normal_idle", None), ("target", "background_noise", None),
                                   ("target", "unknown", None), ("noise", "background_noise", "workshop"),
                                   ("noise", "background_noise", "road")]:
            identifier = f"fixture-{group}-{role}-{label}-{scene}"
            rng = np.random.default_rng(100 + len(manifest))
            waveform = rng.normal(0, .015, 15600)
            if label == "normal_idle":
                waveform += .2 * np.sin(2 * np.pi * 400 * np.arange(15600) / 16000 + group * .2)
            elif label == "unknown":
                waveform += .1 * np.sin(2 * np.pi * 1300 * np.arange(15600) / 16000 + group * .3)
            sf.write(root / f"{identifier}.wav", waveform, 16000, subtype="PCM_16")
            manifest.append({"id": identifier, "path": f"{identifier}.wav", "label": label,
                "dataset_id": "synthetic_engineering_fixture", "source_id": f"fixture-source-{group}",
                "session_id": f"fictional-session-{group}", "vehicle_id": f"fictional-vehicle-{group}",
                "device_id": f"fictional-device-{group}", "sha256": digest(root / f"{identifier}.wav"),
                "role": role, "scene": scene, "mechanic_verified": True, "training_consent": True,
                "grouping_verified": True, "verification_reference": "SCHEMA TEST ONLY; no real mechanic or vehicle"})
    (tmp_path / "manifest.jsonl").write_text("\n".join(json.dumps(row) for row in manifest) + "\n")
    path = tmp_path / "config.json"
    path.write_text(json.dumps(cfg))
    encoder = SavedModelEncoder(cfg["encoder"], PROJECT, cfg["seed"])
    probe = encoder.embed(np.sin(np.arange(15600, dtype=np.float32) * .2) * .1)
    assert probe.shape == (1024,)
    assert np.isfinite(probe).all()
    first = run(path, encoder=encoder)
    assert first["status"] == "research_trained", first["warnings"]
    report_bytes = (tmp_path / "report/report.json").read_bytes()
    second = run(path, encoder=encoder)
    assert second == first
    assert report_bytes == (tmp_path / "report/report.json").read_bytes()
    assert first["release_eligible"] is False
    assert first["calibration"]["fit_split"] == "val"
    assert first["calibration"]["nll_after"] <= first["calibration"]["nll_before"]
    assert first["test"]["counts"]["total"] == 3
    assert first["unseen_vehicles"]["vehicle_count"] == 1
    assert first["small_data"] is True
    assert first["test"]["weakest_classes"]
    splits = first["split"]["recordings"]
    for operation in first["augmentation_ledger"]:
        assert splits[operation["clip_id"]]["split"] == "train"
        assert splits[operation["noise_id"]]["split"] == "train"
    assert {row["scene"] for row in first["augmentation_ledger"]} == {"workshop", "road"}
    artifact = tmp_path / "report" / first["model_artifact"]["path"]
    with np.load(artifact, allow_pickle=False) as head:
        assert head["weights"].shape == (1024, 2)
        assert head["classes"].tolist() == cfg["classes"][:-1]
    tampered = copy.deepcopy(cfg)
    tampered["datasets"][0]["youtube_derived_audio"] = True
    path.write_text(json.dumps(tampered))
    blocked = run(path, encoder=encoder)
    assert blocked["status"] == "blocked"
    assert blocked["model_artifact"] is None
    assert any("YouTube" in warning for warning in blocked["warnings"])
