"""Replaceable waveform->embedding SavedModel contract; official weights are research-only."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request

import numpy as np

from .data import digest


def tree_digest(root: Path) -> str:
    value = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError("Encoder symlinks forbidden")
        if path.is_file() and path.name != ".verified.json":
            value.update(path.relative_to(root).as_posix().encode())
            value.update(bytes.fromhex(digest(path)))
    return value.hexdigest()


def ensure_model(cfg: dict, base: Path) -> Path:
    target = (base / cfg["path"]).resolve()
    expected = cfg["archive_sha256"]
    if len(expected) != 64 or any(char not in "0123456789abcdef" for char in expected):
        raise ValueError("Encoder archive SHA-256 must be explicitly pinned")
    marker = target / ".verified.json"
    if target.exists():
        if not marker.is_file():
            raise ValueError("Existing encoder has no integrity manifest; do not trust or overwrite it")
        verified = json.loads(marker.read_text())
        if verified["archive_sha256"] != expected or verified["tree_sha256"] != tree_digest(target):
            raise ValueError("Encoder integrity mismatch")
        return target
    if not str(cfg["archive_url"]).startswith("https://"):
        raise ValueError("Encoder archive must have an HTTPS source URL")
    target.parent.mkdir(parents=True, exist_ok=True)
    archive = target.parent / f"{expected}.tar.gz"
    if not archive.exists():
        descriptor, name = tempfile.mkstemp(prefix=".download-", dir=target.parent)
        os.close(descriptor)
        temporary = Path(name)
        try:
            request = urllib.request.Request(cfg["archive_url"], headers={"User-Agent": "Bengkel-Scan-Research/1"})
            with urllib.request.urlopen(request, timeout=45) as response, temporary.open("wb") as output:
                total = 0
                while block := response.read(1024 * 1024):
                    total += len(block)
                    if total > cfg["max_archive_bytes"]:
                        raise ValueError("Encoder download exceeds configured byte cap")
                    output.write(block)
            if digest(temporary) != expected:
                raise ValueError("Encoder download SHA-256 mismatch")
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    if archive.stat().st_size > cfg["max_archive_bytes"] or digest(archive) != expected:
        raise ValueError("Cached encoder archive integrity mismatch")
    temporary = Path(tempfile.mkdtemp(prefix=".encoder-", dir=target.parent))
    try:
        with tarfile.open(archive) as bundle:
            members = bundle.getmembers()
            if len(members) > 1024 or sum(member.size for member in members) > cfg["max_archive_bytes"]:
                raise ValueError("Encoder archive expands beyond cap")
            for member in members:
                destination = (temporary / member.name).resolve()
                if not destination.is_relative_to(temporary.resolve()) or not (member.isdir() or member.isfile()):
                    raise ValueError("Unsafe encoder archive entry")
                if member.isdir():
                    destination.mkdir(parents=True, exist_ok=True)
                else:
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    source = bundle.extractfile(member)
                    if source is None:
                        raise ValueError("Unreadable encoder member")
                    with source, destination.open("wb") as output:
                        shutil.copyfileobj(source, output)
        if not (temporary / "saved_model.pb").is_file():
            raise ValueError("Encoder archive is not a SavedModel")
        (temporary / ".verified.json").write_text(json.dumps({"archive_sha256": expected,
            "tree_sha256": tree_digest(temporary)}, sort_keys=True), encoding="utf-8")
        temporary.rename(target)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return target


class SavedModelEncoder:
    """Any compatible SavedModel can replace the encoder via config; the head sees only [D]."""
    def __init__(self, cfg: dict, base: Path, seed: int):
        if cfg["format"] != "tensorflow_saved_model":
            raise ValueError("Encoder must implement configured mono waveform SavedModel contract")
        self.cfg = cfg
        os.environ.setdefault("CUDA_VISIBLE_DEVICES", "-1")
        os.environ.setdefault("TF_DETERMINISTIC_OPS", "1")
        os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")
        os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
        import tensorflow as tf
        if tf.config.get_visible_devices("GPU"):
            tf.config.set_visible_devices([], "GPU")
        if tf.config.threading.get_intra_op_parallelism_threads() != 1:
            tf.config.threading.set_intra_op_parallelism_threads(1)
        if tf.config.threading.get_inter_op_parallelism_threads() != 1:
            tf.config.threading.set_inter_op_parallelism_threads(1)
        tf.random.set_seed(seed)
        tf.config.experimental.enable_op_determinism()
        self.tf = tf
        self.path = ensure_model(cfg, base)
        try:
            self.model = tf.saved_model.load(str(self.path))
        except tf.errors.OpError as error:
            raise ValueError(f"Cannot load pinned encoder: {type(error).__name__}") from error
        self.function = self.model.signatures[cfg["signature"]] if cfg["signature"] else self.model
        self.dimension: int | None = None

    def embed(self, window: np.ndarray) -> np.ndarray:
        values = np.asarray(window, dtype=np.float32)
        if values.shape != (self.cfg["window_samples"],) or not np.isfinite(values).all() or np.max(np.abs(values)) > 1:
            raise ValueError("Encoder input must be finite normalized mono window of configured length")
        tensor = self.tf.convert_to_tensor(values)
        try:
            outputs = self.function(**{self.cfg["input_key"]: tensor}) if self.cfg["input_key"] else self.function(tensor)
        except self.tf.errors.OpError as error:
            raise ValueError(f"Encoder inference failed: {type(error).__name__}") from error
        output = outputs[self.cfg["output_key"]] if self.cfg["output_key"] is not None else outputs[self.cfg["output_index"]]
        embeddings = np.asarray(output.numpy(), dtype=np.float64)
        if embeddings.ndim != 2 or embeddings.shape[0] == 0 or embeddings.shape[1] == 0 or not np.isfinite(embeddings).all():
            raise ValueError("Encoder output must be finite [frames, embedding_dimension]")
        if self.dimension is not None and embeddings.shape[1] != self.dimension:
            raise ValueError("Encoder dimension changed within run")
        self.dimension = embeddings.shape[1]
        return embeddings.mean(axis=0)
