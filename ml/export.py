#!/usr/bin/env python3
"""Export a pinned-encoder research model; synthetic mode requires explicit consent."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys

# Configure determinism before importing NumPy, SciPy, or TensorFlow.
for name in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ[name] = "1"
os.environ["CUDA_VISIBLE_DEVICES"] = "-1"
os.environ["TF_DETERMINISTIC_OPS"] = "1"
os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "2"

from workshop_audio.deployment import export_model


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("config.json"))
    parser.add_argument("--output", type=Path, required=True, help="Artifact directory; synthetic models must stay in debug/test source sets")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--synthetic-test", action="store_true", help="Train a deterministic tones/noise head; NEVER vehicle diagnosis")
    source.add_argument("--phase4-report", type=Path, help="Real trained Phase 4 report with hash-bound .npz head; no fallback if missing")
    parser.add_argument("--fixtures-output", type=Path, help="20 technical parity WAVs, original-Python expectations and 20-second timing WAV (default OUTPUT/fixtures)")
    args = parser.parse_args()
    try:
        report = export_model(args.config, args.output, synthetic_test=args.synthetic_test,
                              phase4_report=args.phase4_report, fixtures_output=args.fixtures_output)
    except (ValueError, KeyError, TypeError, OSError, RuntimeError) as error:
        print(f"Export blocked: {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True, indent=2, allow_nan=False))
    if not report["size_target_met"]:
        print("UNMET: float32 model is not strictly below 10,000,000 bytes; research artifact retained, no quantization applied.", file=sys.stderr)
    print("RESEARCH ONLY: automotive accuracy is unmeasured; release approval remains blocked.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
