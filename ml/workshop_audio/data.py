"""Consent/provenance validation and transitive leakage groups, never random window splits."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, replace
import hashlib
import json
from pathlib import Path
import re

import numpy as np


@dataclass(frozen=True)
class Clip:
    id: str
    path: Path
    label: str
    dataset_id: str
    source_id: str
    session_id: str
    vehicle_id: str | None
    device_id: str
    sha256: str
    role: str
    scene: str | None
    mechanic_verified: bool
    verification_reference: str
    pcm_sha256: str = ""


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def confined(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or Path(relative).is_absolute():
        raise ValueError("Audio/metadata path escapes configured audio_root")
    return path


def load_manifest(path: Path, audio_root: Path, datasets: list[dict], classes: list[str]) -> list[Clip]:
    registry = {entry["id"]: entry for entry in datasets}
    if len(registry) != len(datasets):
        raise ValueError("Duplicate dataset IDs")
    if path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("Manifest exceeds 16 MiB")
    clips = []
    seen = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        identifier = row.get("id")
        if not isinstance(identifier, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", identifier) or identifier in seen:
            raise ValueError(f"Invalid/duplicate recording ID at line {line_number}")
        seen.add(identifier)
        dataset = registry.get(row.get("dataset_id"))
        if dataset is None:
            raise ValueError(f"{identifier}: unregistered dataset")
        if dataset.get("youtube_derived_audio") is not False:
            raise ValueError(f"{identifier}: YouTube-derived/unknown audio provenance forbidden")
        if dataset.get("training_allowed") is not True or dataset.get("license_review_status") != "verified":
            raise ValueError(f"{identifier}: dataset training rights not verified")
        if not dataset.get("license") or not dataset.get("source_url"):
            raise ValueError(f"{identifier}: missing source/license")
        if row.get("training_consent") is not True or row.get("grouping_verified") is not True:
            raise ValueError(f"{identifier}: training consent/group identity verification missing")
        for key in ("source_id", "session_id", "device_id", "verification_reference"):
            if not isinstance(row.get(key), str) or not row[key].strip() or len(row[key]) > 256:
                raise ValueError(f"{identifier}: missing/invalid {key}")
        if row.get("label") not in classes or row.get("role") not in {"target", "noise"}:
            raise ValueError(f"{identifier}: unsupported label/role")
        if row.get("mechanic_verified") is not True:
            raise ValueError(f"{identifier}: mechanic-verified label evidence required")
        vehicle = row.get("vehicle_id")
        if vehicle is not None and (not isinstance(vehicle, str) or not vehicle.strip() or len(vehicle) > 256):
            raise ValueError(f"{identifier}: invalid vehicle identity")
        if row["label"] not in {"unknown", "background_noise"} and vehicle is None:
            raise ValueError(f"{identifier}: automotive label requires vehicle ID")
        if row["role"] == "noise" and (row.get("scene") not in {"workshop", "road"} or row["label"] != "background_noise"):
            raise ValueError(f"{identifier}: noise must be labelled workshop/road background")
        audio = confined(audio_root, row["path"])
        expected = row.get("sha256", "")
        if not re.fullmatch(r"[0-9a-f]{64}", expected) or digest(audio) != expected:
            raise ValueError(f"{identifier}: audio SHA-256 mismatch")
        if dataset.get("requires_bundle_metadata") is True:
            metadata = json.loads(confined(audio_root, row["metadata_path"]).read_text(encoding="utf-8"))
            if metadata.get("consent", {}).get("training") is not True or metadata.get("label", {}).get("mechanic_verified") is not True:
                raise ValueError(f"{identifier}: original bundle still lacks training consent/verified label")
            if metadata.get("vehicle_group_id") != vehicle or metadata.get("device", {}).get("device_group_id") != row["device_id"]:
                raise ValueError(f"{identifier}: bundle grouping mismatch")
        clips.append(Clip(identifier, audio, row["label"], row["dataset_id"], row["source_id"], row["session_id"],
                          vehicle, row["device_id"], expected, row["role"], row.get("scene"), True,
                          row["verification_reference"]))
    return sorted(clips, key=lambda clip: clip.id)


def with_pcm_hash(clip: Clip, waveform: np.ndarray) -> Clip:
    # Canonical preprocessed float32 LE bytes catch duplicate content in different containers.
    return replace(clip, pcm_sha256=hashlib.sha256(np.asarray(waveform, dtype="<f4").tobytes()).hexdigest())


def group_keys(clip: Clip) -> list[tuple[str, str]]:
    values = [(key, str(getattr(clip, key))) for key in ("source_id", "session_id", "vehicle_id", "device_id", "sha256", "pcm_sha256")
              if getattr(clip, key)]
    return values


def grouped_split(clips: list[Clip], cfg: dict, seed: int, known_classes: list[str]) -> tuple[dict[str, str], dict]:
    fractions = np.asarray(cfg["fractions"], dtype=np.float64)
    if fractions.shape != (3,) or not np.all(fractions > 0) or not np.isclose(fractions.sum(), 1):
        raise ValueError("Split fractions must be three positive values summing to one")
    if not clips:
        return {}, {"components": 0, "counts": {name: 0 for name in ("train", "val", "test")}, "leakage": False}
    parents = list(range(len(clips)))
    def find(index: int) -> int:
        while parents[index] != index:
            parents[index] = parents[parents[index]]
            index = parents[index]
        return index
    keys = {}
    for index, clip in enumerate(clips):
        for key in group_keys(clip):
            if key in keys:
                parents[find(index)] = find(keys[key])
            else:
                keys[key] = index
    components: dict[int, list[Clip]] = {}
    for index, clip in enumerate(clips):
        components.setdefault(find(index), []).append(clip)
    groups = sorted(components.values(), key=lambda rows: min(clip.id for clip in rows))
    if len(groups) < 3:
        raise ValueError("Fewer than three independent vehicle/source/session/device components; do not relax grouping")
    rng = np.random.default_rng(seed)
    best = None
    names = ("train", "val", "test")
    totals = Counter(clip.label for clip in clips if clip.role == "target" and clip.label in known_classes)
    for _ in range(int(cfg["search_attempts"])):
        assignments = rng.choice(3, size=len(groups), p=fractions)
        if len(set(assignments)) != 3:
            continue
        class_counts = [Counter() for _ in names]
        sizes = np.zeros(3)
        for group, assigned in zip(groups, assignments, strict=True):
            sizes[assigned] += len(group)
            class_counts[assigned].update(clip.label for clip in group if clip.role == "target")
        missing = sum(class_counts[split][label] == 0 for split in range(3) for label in known_classes)
        score = missing * 1000.0 + float(np.sum((sizes / len(clips) - fractions) ** 2))
        for label, total in totals.items():
            score += sum((class_counts[split][label] / total - fractions[split]) ** 2 for split in range(3))
        candidate = (score, tuple(int(x) for x in assignments))
        if best is None or candidate < best:
            best = candidate
    if best is None:
        raise ValueError("No nonempty grouped split found; increase independent source groups, not window count")
    mapping = {clip.id: names[assigned] for group, assigned in zip(groups, best[1], strict=True) for clip in group}
    audit = audit_split(clips, mapping)
    audit["components"] = len(groups)
    audit["counts"] = dict(Counter(mapping.values()))
    return mapping, audit


def audit_split(clips: list[Clip], mapping: dict[str, str]) -> dict:
    if set(mapping) != {clip.id for clip in clips} or not set(mapping.values()) <= {"train", "val", "test"}:
        raise ValueError("Every recording must have exactly one valid split")
    seen: dict[tuple[str, str], str] = {}
    for clip in clips:
        for key in group_keys(clip):
            if key in seen and seen[key] != mapping[clip.id]:
                raise ValueError(f"Leakage detected for grouping field {key[0]}")
            seen[key] = mapping[clip.id]
    return {"leakage": False, "grouping_fields": ["vehicle_id", "source_id", "session_id", "device_id", "sha256", "pcm_sha256"]}
