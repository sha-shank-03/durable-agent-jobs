"""Read-only packaged mock evidence/SQLite/license verification. Python 3 stdlib only."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import zipfile

REPO = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--happy", type=Path, required=True)
    parser.add_argument("--all", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=REPO / "work/acceptance/release-audit.json")
    args = parser.parse_args()
    happy = json.loads(args.happy.read_text(encoding="utf-8-sig"))
    demo = json.loads(args.all.read_text(encoding="utf-8-sig"))
    assert happy["execution_mode"] == demo["execution_mode"] == "deterministic_mock"
    assert happy["assertions_failed"] == demo["assertions_failed"] == 0
    assert happy["state"] == "SUCCEEDED" and happy["invocation_count"] == happy["effect_count"] == 1
    assert happy["duplicate_same_job"] and happy["changed_spec_rejected"] and happy["completed_checkpoint_persisted"]
    scenarios = {item["scenario"]: item for item in demo["scenarios"]}
    assert len(scenarios) == 6
    assert scenarios["crash-idempotent"]["child_exit"] == scenarios["crash-unsafe"]["child_exit"] == 86
    assert scenarios["retry-exhaustion"]["restart_preserved_exhaustion"]
    assert scenarios["cooperative-timeout"]["late_result_committed"] is False
    assert scenarios["worker-lock"]["second_worker_exit"] == 4
    root = Path(demo["run_dir"])
    observations = []

    def query(relative, sql, expected):
        database = (root / relative).resolve()
        connection = sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)
        try:
            actual = connection.execute(sql).fetchall()
        finally:
            connection.close()
        observations.append(dict(database=str(database), query=sql, rows=actual))
        assert actual == expected, (relative, sql, actual, expected)

    query("keyed/jobs.db", "SELECT state,checkpoint_seq,generation FROM jobs", [("SUCCEEDED", 2, None)])
    query("keyed/jobs.db", "SELECT attempt_count FROM operations WHERE operation_key='create-ticket'", [(2,)])
    query("keyed/tool.db", "SELECT count(*),count(DISTINCT invocation_key) FROM tool_invocations", [(2, 1)])
    query("keyed/tool.db", "SELECT count(*) FROM ticket_receipts", [(1,)])
    query("unsafe/jobs.db", "SELECT state,checkpoint_seq,generation FROM jobs", [("NEEDS_REVIEW", 1, None)])
    query("unsafe/jobs.db", "SELECT state,attempt_count,result_json FROM operations WHERE operation_key='create-ticket'", [("UNKNOWN", 1, None)])
    query("unsafe/tool.db", "SELECT count(*) FROM unsafe_ticket_effects", [(1,)])
    query("exhaustion/jobs.db", "SELECT state,error_code FROM jobs", [("FAILED", "ATTEMPTS_EXHAUSTED")])
    query("exhaustion/jobs.db", "SELECT attempt_count FROM operations WHERE operation_key='create-ticket'", [(3,)])
    query("exhaustion/tool.db", "SELECT count(*) FROM ticket_receipts", [(0,)])
    query("controls/jobs.db", "SELECT request_key,state,checkpoint_seq FROM jobs ORDER BY request_key", [("cancel-001", "CANCELLED", 0), ("expire-001", "TIMED_OUT", 0)])
    query("controls/jobs.db", "SELECT count(*) FROM operations", [(0,)])
    query("timeout/jobs.db", "SELECT state,error_code,checkpoint_seq FROM jobs", [("FAILED", "ATTEMPTS_EXHAUSTED", 0)])
    query("timeout/jobs.db", "SELECT attempt_count,result_json FROM operations", [(3, None)])
    query("timeout/tool.db", "SELECT count(*) FROM ticket_receipts", [(0,)])
    jar = REPO / "target/durable-agent-jobs.jar"
    with zipfile.ZipFile(jar) as archive:
        for file in sorted((REPO / "licenses").glob("*.txt")):
            assert archive.read("META-INF/licenses/" + file.name) == file.read_bytes(), file.name
        assert archive.read("META-INF/THIRD_PARTY_NOTICES.md") == (REPO / "THIRD_PARTY_NOTICES.md").read_bytes()
        assert archive.read("META-INF/services/java.sql.Driver").decode().strip() == "org.sqlite.JDBC"
        assert "Main-Class: dev.durablejobs.cli.Main" in archive.read("META-INF/MANIFEST.MF").decode()
        for name in ("schema.sql", "mock-tool-schema.sql", "unsafe-tool-schema.sql"):
            assert archive.read(name) == (REPO / "src/main/resources" / name).read_bytes()
        assert archive.read("META-INF/NOTICE")
    result = dict(execution_mode="deterministic_mock", assertions_failed=0,
                  jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(), jar_bytes=jar.stat().st_size,
                  independent_sql=observations, runtime_license_files_verified=7)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(assertions_failed=0, independent_sql_queries=len(observations),
                         runtime_license_files_verified=7, jar_sha256=result["jar_sha256"])))


if __name__ == "__main__":
    main()
