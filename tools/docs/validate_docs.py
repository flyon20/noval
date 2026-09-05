#!/usr/bin/env python3
"""Validate Noval's governed documentation catalog and local links."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import date
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlsplit


ALLOWED_KINDS = {
    "catalog",
    "design",
    "guide",
    "index",
    "plan",
    "report",
    "runbook",
    "spec",
    "template",
}
ALLOWED_STATUSES = {"archived", "current", "draft", "historical", "superseded"}
ALLOWED_PUBLICATIONS = {"private", "repository"}
REQUIRED_FIELDS = {
    "id",
    "path",
    "title",
    "kind",
    "status",
    "publication",
    "owner",
    "last_reviewed",
    "review_interval_days",
    "supersedes",
    "superseded_by",
}
OPTIONAL_FIELDS = {"evidence"}
EVIDENCE_FIELDS = {"implementation", "acceptance", "verified_at", "verified_commit"}
LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
COVERAGE_RE = re.compile(
    r"<!-- docs-validator: migration-coverage begin -->\s*"
    r"```text\s*(?P<body>.*?)\s*```\s*"
    r"<!-- docs-validator: migration-coverage end -->",
    re.DOTALL,
)
PHASE_SQL_RE = re.compile(r"phase(\d+)-[a-z0-9-]+\.sql$")


class DuplicateKeyError(ValueError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        help="Repository root. Defaults to discovery from this script.",
    )
    return parser.parse_args()


def discover_root(explicit_root: Path | None) -> Path:
    if explicit_root is not None:
        return explicit_root.resolve()

    script = Path(__file__).resolve()
    for candidate in (script.parent, *script.parents):
        if (candidate / ".git").exists() and (candidate / "README.md").is_file():
            return candidate
    raise RuntimeError("Could not discover the repository root")


def reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_catalog(path: Path) -> dict[str, object]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle, object_pairs_hook=reject_duplicate_keys)
    if not isinstance(value, dict):
        raise ValueError("catalog root must be an object")
    return value


def resolve_catalog_path(root: Path, raw_path: object) -> Path:
    if not isinstance(raw_path, str) or not raw_path:
        raise ValueError("path must be a non-empty string")
    if "\\" in raw_path:
        raise ValueError(f"path must use forward slashes: {raw_path}")

    posix_path = PurePosixPath(raw_path)
    if posix_path.is_absolute() or ".." in posix_path.parts:
        raise ValueError(f"path must stay inside the repository: {raw_path}")

    resolved = (root / Path(*posix_path.parts)).resolve()
    if resolved != root and root not in resolved.parents:
        raise ValueError(f"path escapes the repository: {raw_path}")
    return resolved


def parse_iso_date(raw_value: object, field: str) -> date:
    if not isinstance(raw_value, str):
        raise ValueError(f"{field} must be an ISO date string")
    try:
        return date.fromisoformat(raw_value)
    except ValueError as exc:
        raise ValueError(f"{field} must use YYYY-MM-DD: {raw_value}") from exc


def git_ignore_state(root: Path, relative_path: str) -> bool:
    result = subprocess.run(
        ["git", "check-ignore", "-q", "--no-index", "--", relative_path],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode not in {0, 1}:
        detail = result.stderr.strip() or "git check-ignore failed"
        raise RuntimeError(detail)
    return result.returncode == 0


def markdown_link_targets(document: Path, root: Path) -> list[tuple[str, Path]]:
    text = document.read_text(encoding="utf-8")
    targets: list[tuple[str, Path]] = []
    for match in LINK_RE.finditer(text):
        raw_target = match.group(1).strip()
        if raw_target.startswith("<") and raw_target.endswith(">"):
            raw_target = raw_target[1:-1]
        else:
            raw_target = raw_target.split(maxsplit=1)[0]

        parsed = urlsplit(raw_target)
        if parsed.scheme or parsed.netloc or not parsed.path:
            continue

        decoded_path = unquote(parsed.path)
        if decoded_path.startswith("/"):
            target = root / decoded_path.lstrip("/")
        else:
            target = document.parent / decoded_path
        targets.append((raw_target, target.resolve()))
    return targets


def phase_number(sql_name: str) -> int | None:
    match = PHASE_SQL_RE.fullmatch(sql_name)
    return int(match.group(1)) if match else None


def extract_compose_migrations(compose_text: str) -> list[str]:
    service = re.search(
        r"(?ms)^  schema-migrate:\s*\n(?P<body>.*?)(?=^  [A-Za-z0-9_][A-Za-z0-9_-]*:\s*$|\Z)",
        compose_text,
    )
    if not service:
        return []
    return re.findall(
        r"<\s*/opt/noval/sql/mysql/(phase\d+-[a-z0-9-]+\.sql)",
        service.group("body"),
    )


def extract_init_migrations(init_text: str) -> list[str]:
    return re.findall(r"\"(phase\d+-[a-z0-9-]+\.sql)\"", init_text)


def parse_migration_coverage(runbook_text: str) -> tuple[tuple[int, int] | None, list[str], list[str], list[str]]:
    match = COVERAGE_RE.search(runbook_text)
    if not match:
        return None, [], [], []

    scope_match = re.search(r"(?m)^scope:\s*phase(\d+)-(\d+)\s*$", match.group("body"))
    scope = (int(scope_match.group(1)), int(scope_match.group(2))) if scope_match else None
    migrations = re.findall(r"(?m)^migration:\s*(\S+)\s*$", match.group("body"))
    verifies = re.findall(r"(?m)^verify:\s*(\S+)\s*$", match.group("body"))
    policies = re.findall(r"(?m)^policy:\s*(\S+)\s*$", match.group("body"))
    return scope, migrations, verifies, policies


def validate_migration_coverage(root: Path) -> list[str]:
    runbook = root / "docs" / "j3160-production-update-runbook.md"
    if not runbook.is_file():
        return []

    compose = root / "docker-compose.yml"
    init_script = root / "docker" / "mysql" / "00-initialize-noval.sh"
    sql_dir = root / "backend" / "sql" / "mysql"
    if not compose.is_file() or not init_script.is_file() or not sql_dir.is_dir():
        return ["migration coverage sources are incomplete"]

    errors: list[str] = []
    scope, documented_migrations, documented_verifies, policies = parse_migration_coverage(
        runbook.read_text(encoding="utf-8")
    )
    if scope != (23, 30):
        errors.append("J3160 Runbook migration coverage scope must be phase23-30")
        return errors

    compose_migrations = extract_compose_migrations(compose.read_text(encoding="utf-8"))
    init_migrations = extract_init_migrations(init_script.read_text(encoding="utf-8"))
    if not compose_migrations:
        errors.append("docker-compose.yml schema-migrate migration list is empty")
        return errors

    def scoped(values: list[str]) -> list[str]:
        return [
            value
            for value in values
            if phase_number(value) is not None and 23 <= phase_number(value) <= 30
        ]

    expected_migrations = scoped(compose_migrations)
    init_scope = scoped(init_migrations)
    if len(expected_migrations) != len(set(expected_migrations)):
        errors.append("docker-compose.yml schema-migrate contains duplicate Phase23-30 migrations")
    if expected_migrations != init_scope:
        errors.append("docker-compose.yml and 00-initialize-noval.sh Phase23-30 order differs")
    if documented_migrations != expected_migrations:
        errors.append("J3160 Runbook migration block differs from Compose Phase23-30 order")

    expected_verifies = [
        f"{migration[:-4]}-verify.sql"
        for migration in expected_migrations
        if (sql_dir / f"{migration[:-4]}-verify.sql").is_file()
    ]
    if documented_verifies != expected_verifies:
        errors.append("J3160 Runbook verify block differs from existing Phase23-30 verify files")

    expected_policies = {"phase24-26=second-column-zero", "phase27-30=no-violation-rows"}
    if set(policies) != expected_policies:
        errors.append("J3160 Runbook verify policies must define Phase24-26 and Phase27-30")
    if len(documented_migrations) != len(set(documented_migrations)):
        errors.append("J3160 Runbook migration block contains duplicates")
    if len(documented_verifies) != len(set(documented_verifies)):
        errors.append("J3160 Runbook verify block contains duplicates")
    return errors


def find_relation_cycle(documents: dict[str, dict[str, object]]) -> list[str] | None:
    visited: set[str] = set()
    active: list[str] = []

    def visit(document_id: str) -> list[str] | None:
        if document_id not in documents:
            return None
        if document_id in active:
            start = active.index(document_id)
            return [*active[start:], document_id]
        if document_id in visited:
            return None

        active.append(document_id)
        for older_id in documents[document_id]["supersedes"]:
            cycle = visit(older_id)
            if cycle:
                return cycle
        active.pop()
        visited.add(document_id)
        return None

    for document_id in documents:
        cycle = visit(document_id)
        if cycle:
            return cycle
    return None


def validate(root: Path) -> list[str]:
    errors: list[str] = []
    catalog_path = root / "docs" / "governance" / "catalog.json"
    try:
        catalog = load_catalog(catalog_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"catalog: {exc}"]

    if catalog.get("schema_version") != 1:
        errors.append("catalog.schema_version must equal 1")

    raw_documents = catalog.get("documents")
    if not isinstance(raw_documents, list):
        return [*errors, "catalog.documents must be an array"]

    documents: dict[str, dict[str, object]] = {}
    resolved_paths: dict[str, Path] = {}
    path_owners: dict[str, str] = {}
    today = date.today()

    for index, raw_document in enumerate(raw_documents):
        label = f"documents[{index}]"
        if not isinstance(raw_document, dict):
            errors.append(f"{label} must be an object")
            continue

        missing = REQUIRED_FIELDS - raw_document.keys()
        extra = raw_document.keys() - REQUIRED_FIELDS - OPTIONAL_FIELDS
        if missing:
            errors.append(f"{label} missing fields: {', '.join(sorted(missing))}")
        if extra:
            errors.append(f"{label} has unknown fields: {', '.join(sorted(extra))}")
        if missing:
            continue

        document_id = raw_document["id"]
        if not isinstance(document_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]*", document_id):
            errors.append(f"{label}.id must be stable lowercase kebab-case")
            continue
        if document_id in documents:
            errors.append(f"duplicate document id: {document_id}")
            continue
        documents[document_id] = raw_document

        for string_field in ("title", "owner"):
            if not isinstance(raw_document[string_field], str) or not raw_document[string_field].strip():
                errors.append(f"{document_id}.{string_field} must be a non-empty string")

        if raw_document["kind"] not in ALLOWED_KINDS:
            errors.append(f"{document_id}.kind is invalid: {raw_document['kind']}")
        if raw_document["status"] not in ALLOWED_STATUSES:
            errors.append(f"{document_id}.status is invalid: {raw_document['status']}")
        if raw_document["publication"] not in ALLOWED_PUBLICATIONS:
            errors.append(
                f"{document_id}.publication is invalid: {raw_document['publication']}"
            )

        try:
            reviewed_on = parse_iso_date(raw_document["last_reviewed"], "last_reviewed")
            if reviewed_on > today:
                errors.append(f"{document_id}.last_reviewed is in the future")
            interval = raw_document["review_interval_days"]
            if not isinstance(interval, int) or isinstance(interval, bool) or interval <= 0:
                errors.append(f"{document_id}.review_interval_days must be a positive integer")
            elif raw_document["status"] == "current" and (today - reviewed_on).days > interval:
                errors.append(f"{document_id} is past its review interval")
        except ValueError as exc:
            errors.append(f"{document_id}: {exc}")

        for relation in ("supersedes", "superseded_by"):
            value = raw_document[relation]
            if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
                errors.append(f"{document_id}.{relation} must be an array of document ids")
            elif len(value) != len(set(value)):
                errors.append(f"{document_id}.{relation} contains duplicates")

        evidence = raw_document.get("evidence")
        if evidence is not None:
            if not isinstance(evidence, dict):
                errors.append(f"{document_id}.evidence must be an object")
            else:
                missing_evidence = EVIDENCE_FIELDS - evidence.keys()
                extra_evidence = evidence.keys() - EVIDENCE_FIELDS
                if missing_evidence:
                    errors.append(
                        f"{document_id}.evidence missing fields: {', '.join(sorted(missing_evidence))}"
                    )
                if extra_evidence:
                    errors.append(
                        f"{document_id}.evidence has unknown fields: {', '.join(sorted(extra_evidence))}"
                    )
                for evidence_kind in ("implementation", "acceptance"):
                    paths = evidence.get(evidence_kind)
                    if not isinstance(paths, list) or not all(isinstance(path, str) for path in paths):
                        errors.append(
                            f"{document_id}.evidence.{evidence_kind} must be an array of paths"
                        )
                        continue
                    for evidence_path in paths:
                        try:
                            evidence_target = resolve_catalog_path(root, evidence_path)
                            if not evidence_target.exists() and raw_document["publication"] == "repository":
                                errors.append(
                                    f"{document_id} evidence path does not exist: {evidence_path}"
                                )
                        except ValueError as exc:
                            errors.append(f"{document_id} evidence path: {exc}")
                for date_field in ("verified_at",):
                    if evidence.get(date_field) is not None:
                        try:
                            parse_iso_date(evidence[date_field], date_field)
                        except ValueError as exc:
                            errors.append(f"{document_id}.evidence: {exc}")
                commit = evidence.get("verified_commit")
                if commit is not None and (not isinstance(commit, str) or not commit.strip()):
                    errors.append(f"{document_id}.evidence.verified_commit must be null or non-empty")

        if raw_document["status"] in {"historical", "superseded"} and not raw_document["superseded_by"]:
            errors.append(f"{document_id} requires superseded_by for status {raw_document['status']}")
        if raw_document["status"] == "current" and raw_document["superseded_by"]:
            errors.append(f"{document_id} is current and cannot have superseded_by")

        try:
            resolved = resolve_catalog_path(root, raw_document["path"])
            relative_path = raw_document["path"]
            if relative_path in path_owners:
                errors.append(
                    f"duplicate catalog path {relative_path}: {path_owners[relative_path]} and {document_id}"
                )
            path_owners[relative_path] = document_id
            resolved_paths[document_id] = resolved
            ignored = git_ignore_state(root, relative_path)
            if raw_document["publication"] == "repository":
                if not resolved.is_file():
                    errors.append(f"repository document does not exist: {relative_path}")
                if ignored:
                    errors.append(f"repository document is ignored: {relative_path}")
            if raw_document["publication"] == "private" and not ignored:
                errors.append(f"private document is not ignored: {relative_path}")
        except (RuntimeError, ValueError) as exc:
            errors.append(f"{document_id}: {exc}")

    for document_id, document in documents.items():
        older_ids = document["supersedes"]
        newer_ids = document["superseded_by"]
        if not isinstance(older_ids, list) or not isinstance(newer_ids, list):
            continue
        for older_id in older_ids:
            if older_id not in documents:
                errors.append(f"{document_id}.supersedes references unknown id: {older_id}")
            elif document_id not in documents[older_id]["superseded_by"]:
                errors.append(f"{document_id} -> {older_id} supersession is not reciprocal")
        for newer_id in newer_ids:
            if newer_id not in documents:
                errors.append(f"{document_id}.superseded_by references unknown id: {newer_id}")
            elif document_id not in documents[newer_id]["supersedes"]:
                errors.append(f"{document_id} <- {newer_id} supersession is not reciprocal")

    if documents and all(
        isinstance(document.get("supersedes"), list) for document in documents.values()
    ):
        cycle = find_relation_cycle(documents)
        if cycle:
            errors.append(f"supersession cycle: {' -> '.join(cycle)}")

    for document_id, document in documents.items():
        path = resolved_paths.get(document_id)
        if (
            path is None
            or document["publication"] != "repository"
            or path.suffix.lower() != ".md"
            or not path.is_file()
        ):
            continue
        for raw_target, target in markdown_link_targets(path, root):
            if target != root and root not in target.parents:
                errors.append(f"{document['path']} link escapes repository: {raw_target}")
            elif any(
                target == private_path
                for private_id, private_path in resolved_paths.items()
                if documents[private_id]["publication"] == "private"
            ):
                errors.append(f"{document['path']} links private catalog document: {raw_target}")
            elif not target.exists():
                errors.append(f"{document['path']} has missing link: {raw_target}")

    index = root / "docs" / "README.md"
    if index.is_file():
        index_text = index.read_text(encoding="utf-8")
        for document_id, document in documents.items():
            if document_id == "docs-index" or document["publication"] != "repository":
                continue
            target = resolved_paths.get(document_id)
            if target is None:
                continue
            relative_link = os.path.relpath(target, index.parent).replace(os.sep, "/")
            if f"]({relative_link})" not in index_text:
                errors.append(f"docs index does not link repository document: {document['path']}")
    else:
        errors.append("docs index does not exist: docs/README.md")

    errors.extend(validate_migration_coverage(root))

    return errors


def main() -> int:
    args = parse_args()
    try:
        root = discover_root(args.root)
        errors = validate(root)
    except (OSError, RuntimeError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    if errors:
        print(f"Documentation validation failed with {len(errors)} error(s):")
        for error in errors:
            print(f"- {error}")
        return 1

    print("Documentation validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
