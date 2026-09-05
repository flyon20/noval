from __future__ import annotations

import json
import tempfile
import unittest
from datetime import date
from pathlib import Path
from unittest.mock import Mock, patch

from tools.docs import validate_docs


def document(
    document_id: str,
    path: str,
    *,
    status: str = "current",
    publication: str = "repository",
    supersedes: list[str] | None = None,
    superseded_by: list[str] | None = None,
) -> dict[str, object]:
    return {
        "id": document_id,
        "path": path,
        "title": document_id,
        "kind": "guide",
        "status": status,
        "publication": publication,
        "owner": "test",
        "last_reviewed": date.today().isoformat(),
        "review_interval_days": 30,
        "supersedes": supersedes or [],
        "superseded_by": superseded_by or [],
    }


class ValidateDocsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        governance = self.root / "docs" / "governance"
        governance.mkdir(parents=True)
        (self.root / "README.md").write_text("# Root\n", encoding="utf-8")
        (self.root / "docs" / "README.md").write_text(
            "# Docs\n\n[Root](../README.md)\n", encoding="utf-8"
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_catalog(self, documents: list[dict[str, object]]) -> None:
        catalog = {"schema_version": 1, "documents": documents}
        path = self.root / "docs" / "governance" / "catalog.json"
        path.write_text(json.dumps(catalog), encoding="utf-8")

    def base_documents(self) -> list[dict[str, object]]:
        return [
            document("docs-index", "docs/README.md"),
            document(
                "root-readme",
                "README.md",
                supersedes=["private-history"],
            ),
            document(
                "private-history",
                "docs/private-history.md",
                status="historical",
                publication="private",
                superseded_by=["root-readme"],
            ),
        ]

    def test_missing_private_document_is_allowed_when_ignored(self) -> None:
        self.write_catalog(self.base_documents())
        with patch.object(
            validate_docs,
            "git_ignore_state",
            side_effect=lambda _root, path: path == "docs/private-history.md",
        ):
            self.assertEqual([], validate_docs.validate(self.root))

    def test_private_document_must_still_match_ignore_rule(self) -> None:
        self.write_catalog(self.base_documents())
        with patch.object(validate_docs, "git_ignore_state", return_value=False):
            errors = validate_docs.validate(self.root)
        self.assertIn(
            "private document is not ignored: docs/private-history.md",
            errors,
        )

    def test_repository_document_cannot_link_private_catalog_document(self) -> None:
        self.write_catalog(self.base_documents())
        (self.root / "docs" / "README.md").write_text(
            "# Docs\n\n[Root](../README.md)\n[Private](private-history.md)\n",
            encoding="utf-8",
        )
        with patch.object(
            validate_docs,
            "git_ignore_state",
            side_effect=lambda _root, path: path == "docs/private-history.md",
        ):
            errors = validate_docs.validate(self.root)
        self.assertIn(
            "docs/README.md links private catalog document: private-history.md",
            errors,
        )

    def test_git_ignore_state_uses_untracked_view_for_tracked_private_docs(self) -> None:
        process = Mock(returncode=0, stderr="", stdout="")
        with patch.object(validate_docs.subprocess, "run", return_value=process) as run:
            self.assertTrue(validate_docs.git_ignore_state(self.root, "docs/private-history.md"))
        command = run.call_args.args[0]
        self.assertIn("--no-index", command)

    def test_supersession_must_be_reciprocal(self) -> None:
        documents = self.base_documents()
        documents[0]["supersedes"] = ["private-history"]
        self.write_catalog(documents)
        with patch.object(
            validate_docs,
            "git_ignore_state",
            side_effect=lambda _root, path: path == "docs/private-history.md",
        ):
            errors = validate_docs.validate(self.root)
        self.assertTrue(any("supersession is not reciprocal" in error for error in errors))

    def test_repository_evidence_paths_are_required(self) -> None:
        documents = self.base_documents()[:2]
        documents[1]["evidence"] = {
            "implementation": ["missing/source.py"],
            "acceptance": [],
            "verified_at": date.today().isoformat(),
            "verified_commit": None,
        }
        self.write_catalog(documents)
        with patch.object(validate_docs, "git_ignore_state", return_value=False):
            errors = validate_docs.validate(self.root)
        self.assertIn(
            "root-readme evidence path does not exist: missing/source.py",
            errors,
        )

    def write_migration_fixture(self, runbook_migrations: list[str] | None = None) -> None:
        migrations = [
            f"phase{phase}-{name}.sql"
            for phase, name in enumerate(
                [
                    "skill-memory-lifecycle",
                    "project-ingest-generation",
                    "project-hybrid-retrieval-story-graph",
                    "project-retrieval-eval-observability",
                    "agent-skill-contract",
                    "mysql-resource-optimization",
                    "project-document-batch",
                    "long-form-memory-foundation",
                ],
                start=23,
            )
        ]
        sql_dir = self.root / "backend" / "sql" / "mysql"
        sql_dir.mkdir(parents=True)
        for migration in migrations:
            (sql_dir / migration).write_text("-- fixture\n", encoding="utf-8")
            if not migration.startswith("phase23-"):
                (sql_dir / f"{migration[:-4]}-verify.sql").write_text(
                    "-- fixture\n", encoding="utf-8"
                )

        compose_lines = ["  schema-migrate:", "    command: |"]
        compose_lines.extend(f"      < /opt/noval/sql/mysql/{migration}" for migration in migrations)
        compose_lines.extend(["  redis:", "    image: redis:7"])
        (self.root / "docker-compose.yml").write_text(
            "\n".join(compose_lines) + "\n", encoding="utf-8"
        )
        init_lines = [f'    "{migration}"' for migration in migrations]
        init_path = self.root / "docker" / "mysql"
        init_path.mkdir(parents=True)
        (init_path / "00-initialize-noval.sh").write_text(
            "NOVAL_MYSQL_INIT_SCRIPTS=(\n" + "\n".join(init_lines) + "\n)\n",
            encoding="utf-8",
        )
        documented_migrations = runbook_migrations or migrations
        documented_verifies = [
            f"{migration[:-4]}-verify.sql" for migration in migrations if not migration.startswith("phase23-")
        ]
        coverage = [
            "<!-- docs-validator: migration-coverage begin -->",
            "```text",
            "scope: phase23-30",
            *(f"migration: {migration}" for migration in documented_migrations),
            *(f"verify: {verify}" for verify in documented_verifies),
            "policy: phase24-26=second-column-zero",
            "policy: phase27-30=no-violation-rows",
            "```",
            "<!-- docs-validator: migration-coverage end -->",
        ]
        (self.root / "docs" / "j3160-production-update-runbook.md").write_text(
            "\n".join(coverage) + "\n", encoding="utf-8"
        )

    def test_migration_coverage_matches_compose_and_init_order(self) -> None:
        self.write_migration_fixture()
        self.assertEqual([], validate_docs.validate_migration_coverage(self.root))

    def test_migration_coverage_rejects_runbook_order_drift(self) -> None:
        self.write_migration_fixture(
            [
                "phase23-skill-memory-lifecycle.sql",
                "phase24-project-ingest-generation.sql",
                "phase25-project-hybrid-retrieval-story-graph.sql",
                "phase26-project-retrieval-eval-observability.sql",
                "phase27-agent-skill-contract.sql",
                "phase29-project-document-batch.sql",
                "phase28-mysql-resource-optimization.sql",
                "phase30-long-form-memory-foundation.sql",
            ]
        )
        errors = validate_docs.validate_migration_coverage(self.root)
        self.assertIn("J3160 Runbook migration block differs from Compose Phase23-30 order", errors)


if __name__ == "__main__":
    unittest.main()
