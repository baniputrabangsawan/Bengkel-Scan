from dataclasses import replace
import hashlib
import json
from pathlib import Path

import numpy as np
import pytest

from workshop_audio.data import Clip, audit_split, grouped_split, load_manifest, with_pcm_hash


def clip(identifier: str, vehicle: str | None = None, **changes) -> Clip:
    row = Clip(identifier, Path(identifier + ".wav"), "normal_idle", "owned", "source-" + identifier,
               "session-" + identifier, vehicle or "vehicle-" + identifier, "device-" + identifier,
               hashlib.sha256(identifier.encode()).hexdigest(), "target", None, True, "test fixture evidence")
    return replace(row, **changes)


def test_transitive_vehicle_session_device_and_source_components_never_leak():
    rows = [clip("a"), clip("b", vehicle="vehicle-a"), clip("c", session_id="session-b"),
            clip("d", device_id="device-c"), clip("e", source_id="source-d")]
    rows += [clip(str(i), label=label) for i in range(15) for label in ["normal_idle" if i % 2 else "background_noise"]]
    mapping, audit = grouped_split(rows, {"fractions": [.7, .15, .15], "search_attempts": 500}, 42817,
                                   ["normal_idle", "background_noise"])
    assert len({mapping[key] for key in "abcde"}) == 1
    assert set(mapping.values()) == {"train", "val", "test"}
    assert not audit["leakage"]
    assert mapping == grouped_split(list(reversed(rows)), {"fractions": [.7, .15, .15], "search_attempts": 500},
                                   42817, ["normal_idle", "background_noise"])[0]
    broken = dict(mapping)
    broken["b"] = "test" if mapping["a"] != "test" else "train"
    with pytest.raises(ValueError, match="Leakage"):
        audit_split(rows, broken)


def test_duplicate_canonical_audio_is_grouped_and_one_phone_cannot_be_randomly_split():
    waveform = np.array([.1, .2, .3], dtype=np.float32)
    rows = [with_pcm_hash(clip("a"), waveform), with_pcm_hash(clip("b"), waveform), clip("c"), clip("d")]
    mapping, _ = grouped_split(rows, {"fractions": [.7, .15, .15], "search_attempts": 500}, 1, ["normal_idle"])
    assert mapping["a"] == mapping["b"]
    with pytest.raises(ValueError, match="independent"):
        grouped_split([clip(str(i), device_id="same-phone") for i in range(50)],
                      {"fractions": [.7, .15, .15], "search_attempts": 50}, 1, ["normal_idle"])


@pytest.fixture
def manifest(tmp_path):
    audio = tmp_path / "owned.wav"
    audio.write_bytes(b"test fixture container; decode not under test here")
    dataset = {"id": "owned", "source_url": "generated://unit-test-only", "license": "unit-test-fixture",
               "license_review_status": "verified", "training_allowed": True, "youtube_derived_audio": False,
               "requires_bundle_metadata": False}
    row = {"id": "fixture", "path": audio.name, "label": "normal_idle", "dataset_id": "owned", "source_id": "one",
           "session_id": "one-session", "vehicle_id": "fictional-vehicle", "device_id": "fictional-device",
           "sha256": hashlib.sha256(audio.read_bytes()).hexdigest(), "role": "target", "scene": None,
           "mechanic_verified": True, "training_consent": True, "grouping_verified": True,
           "verification_reference": "TEST FIXTURE ONLY; not a real mechanic claim"}
    path = tmp_path / "manifest.jsonl"
    def load():
        path.write_text(json.dumps(row) + "\n")
        return load_manifest(path, tmp_path, [dataset], ["normal_idle", "background_noise", "unknown"])
    return row, dataset, load


def test_manifest_accepts_only_explicit_rights_consent_and_verified_groups(manifest):
    row, dataset, load = manifest
    assert load()[0].id == "fixture"
    for key in ("training_consent", "grouping_verified", "mechanic_verified"):
        row[key] = False
        with pytest.raises(ValueError):
            load()
        row[key] = True
    for key, bad in (("training_allowed", False), ("youtube_derived_audio", True), ("license_review_status", "needs_verification")):
        old = dataset[key]; dataset[key] = bad
        with pytest.raises(ValueError):
            load()
        dataset[key] = old
    row["path"] = "../escape.wav"
    with pytest.raises(ValueError, match="escapes"):
        load()


def test_phase_three_capture_consent_does_not_imply_training_consent(manifest, tmp_path):
    row, dataset, load = manifest
    dataset["requires_bundle_metadata"] = True
    row["metadata_path"] = "metadata.json"
    metadata = {"consent": {"audio_capture": True, "local_storage": True, "training": False},
                "label": {"mechanic_verified": False}}
    (tmp_path / "metadata.json").write_text(json.dumps(metadata))
    with pytest.raises(ValueError, match="original bundle"):
        load()
