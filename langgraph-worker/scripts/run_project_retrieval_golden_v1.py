from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_DIR = ROOT / "tests/fixtures/project_retrieval_golden/v1"
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.services.evaluation.runner import run_project_retrieval_golden_fixture


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the project retrieval golden corpus release gate.")
    parser.add_argument(
        "--update-baseline",
        action="store_true",
        help="Replace the committed baseline with the current passing corpus result.",
    )
    args = parser.parse_args()

    manifest = _read_json(FIXTURE_DIR / "manifest.json")
    baseline_path = FIXTURE_DIR / str(manifest["baselineFile"])
    report_path = FIXTURE_DIR / str(manifest["releaseReportFile"])
    if args.update_baseline:
        candidate = run_project_retrieval_golden_fixture(FIXTURE_DIR, use_baseline=False)
        if candidate["status"] != "passed":
            raise SystemExit("refusing to update baseline from a failing corpus run")
        baseline = {
            "schemaVersion": "project-retrieval-golden-baseline/v1",
            "baselineVersion": "v1",
            "corpusVersion": candidate["corpusVersion"],
            "runnerVersion": candidate["runnerVersion"],
            "caseCount": candidate["caseCount"],
            "metrics": candidate["overall"]["metrics"],
            "confidenceIntervals": candidate["overall"]["confidenceIntervals"],
        }
        _write_json(baseline_path, baseline)

    report = run_project_retrieval_golden_fixture(FIXTURE_DIR)
    _write_json(report_path, report)
    print(
        f"project retrieval golden v1: {report['status']} "
        f"({report['passedCaseCount']}/{report['caseCount']} cases)"
    )
    if report["status"] != "passed":
        raise SystemExit(1)


def _read_json(path: Path) -> dict:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"expected JSON object: {path}")
    return payload


def _write_json(path: Path, payload: object) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
