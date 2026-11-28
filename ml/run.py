"""Run from repository root: uv run --project ml --locked python ml/run.py --config ml/config.json."""
import os

# Set before importing NumPy/TensorFlow, including BLAS initialization.
os.environ["CUDA_VISIBLE_DEVICES"] = "-1"
os.environ["TF_DETERMINISTIC_OPS"] = "1"
os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"
os.environ["OMP_NUM_THREADS"] = "1"
os.environ["OPENBLAS_NUM_THREADS"] = "1"
os.environ["MKL_NUM_THREADS"] = "1"
os.environ["NUMBA_DISABLE_JIT"] = "1"

import argparse
from pathlib import Path
from workshop_audio.pipeline import run


def main() -> int:
    parser = argparse.ArgumentParser(description="Reproducible offline audio research pipeline; never release promotion")
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    report = run(args.config)
    print(f"status={report['status']} release_eligible={report['release_eligible']}")
    print(f"report={args.config.resolve().parent / report['config']['output'] / 'report.md'}")
    for warning in report["warnings"]:
        print(warning)
    return 0 if report["status"] == "research_trained" else 2


if __name__ == "__main__":
    raise SystemExit(main())
