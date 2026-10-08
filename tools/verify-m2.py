"""Optional Python 3 stdlib verifier; runtime remains Java. Only owned child JVMs."""
import json
from pathlib import Path
import sqlite3
import subprocess
import uuid

REPO = Path(__file__).resolve().parents[1]
JAR = REPO / "target/durable-agent-jobs.jar"
ROOT = REPO / "work/acceptance" / ("m2-" + uuid.uuid4().hex)
ROOT.mkdir(parents=True)
COMMANDS = []
SQL = []


def save(name, value):
    (ROOT / name).write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding="utf-8")


def invoke(args, expected=0):
    number = len(COMMANDS) + 1
    output = ROOT / f"command-{number}.stdout.json"
    error = ROOT / f"command-{number}.stderr.json"
    command = ["java", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-jar", str(JAR), *map(str, args)]
    with output.open("wb") as out, error.open("wb") as err:
        process = subprocess.Popen(command, cwd=REPO, stdout=out, stderr=err)
        try:
            code = process.wait(timeout=20)
        except BaseException:
            process.kill()
            process.wait(timeout=5)
            raise
    COMMANDS.append(dict(arguments=command, pid=process.pid, exited=True, exit=code,
                         expected_exit=expected, stdout=str(output), stderr=str(error)))
    save("commands.json", COMMANDS)
    assert code == expected, f"Command {number}: {code} != {expected}; inspect {error}"
    if expected == 86:
        return None
    report = output.read_text(encoding="utf-8-sig").strip()
    if not report:
        report = error.read_text(encoding="utf-8-sig").strip()
    return json.loads(report)


def rows(db, query):
    connection = sqlite3.connect(db.resolve().as_uri() + "?mode=ro", uri=True)
    try:
        result = connection.execute(query).fetchall()
    finally:
        connection.close()
    SQL.append(dict(database=str(db), query=query, rows=result))
    save("independent-sql.json", SQL)
    return result


def crash(unsafe):
    directory = ROOT / ("unsafe" if unsafe else "keyed")
    directory.mkdir()
    db, tool = directory / "jobs.db", directory / "tool.db"
    spec = directory / "spec.json"
    spec.write_text(json.dumps(dict(schema_version=1, agent="mock-unsafe-ticket/v1" if unsafe else "mock-ticket/v1",
                                   request=dict(title="Fictional independent crash", body="Synthetic."))), encoding="utf-8")
    invoke(["--db", db, "submit", "--key", "crash", "--spec", spec])
    worker = ["--db", db, "--tool-db", tool, "worker", "--drain"]
    if unsafe:
        worker.append("--unsafe-mock")
    invoke([*worker, "--mock-fault", "after-tool-commit"], 86)
    # Python sqlite3 reads the actual retained gap before any recovery process starts.
    assert rows(db, "SELECT state,checkpoint_seq FROM jobs") == [("RUNNING", 1)]
    gap = rows(db, "SELECT state,attempt_count,result_json,invocation_key FROM operations WHERE operation_key='create-ticket'")
    assert len(gap) == 1 and gap[0][:3] == ("INTENT", 1, None)
    table = "unsafe_ticket_effects" if unsafe else "ticket_receipts"
    assert rows(tool, "SELECT count(*) FROM " + table) == [(1,)]
    invoke(worker, 3 if unsafe else 0)
    state = "NEEDS_REVIEW" if unsafe else "SUCCEEDED"
    assert rows(db, "SELECT state,checkpoint_seq,generation FROM jobs") == [(state, 1 if unsafe else 2, None)]
    operation = rows(db, "SELECT state,attempt_count,invocation_key FROM operations WHERE operation_key='create-ticket'")
    assert operation == [("UNKNOWN" if unsafe else "COMPLETED", 1 if unsafe else 2, gap[0][3])]
    calls = rows(tool, "SELECT count(*) FROM " + ("unsafe_ticket_effects" if unsafe else "tool_invocations"))[0][0]
    effects = rows(tool, "SELECT count(*) FROM " + table)[0][0]
    assert calls == (1 if unsafe else 2) and effects == 1
    if not unsafe:
        assert rows(tool, "SELECT count(DISTINCT invocation_key) FROM tool_invocations") == [(1,)]
    return dict(scenario="crash-unsafe" if unsafe else "crash-idempotent", state=state,
                invocation_count=calls, effect_count=effects, independent_gap_sql=True)


def main():
    assert JAR.is_file(), "Build the executable JAR first."
    crashes = [crash(False), crash(True)]
    cancel_db = ROOT / "cancel/jobs.db"
    invoke(["--db", cancel_db, "submit", "--key", "cancel-cli", "--spec", REPO / "examples/ticket.json"])
    cancelled = invoke(["--db", cancel_db, "cancel", "--key", "cancel-cli"])
    assert cancelled["cancel_requested"] and cancelled["job"]["state"] == "CANCELLED"
    drained = invoke(["--db", cancel_db, "worker", "--drain"])
    assert drained["processed"] == 0 and drained["effect_count"] == 0
    assert rows(cancel_db, "SELECT cancel_requested,state FROM jobs") == [(1, "CANCELLED")]
    assert rows(cancel_db, "SELECT count(*) FROM operations") == [(0,)]
    summary = dict(execution_mode="deterministic_mock", run_dir=str(ROOT), crashes=crashes,
                   commands_executed=len(COMMANDS), all_owned_children_exited=True,
                   independent_sql_queries=len(SQL), assertions_failed=0)
    save("summary.json", summary)
    print(json.dumps(dict(run_dir=str(ROOT), commands_executed=len(COMMANDS),
                         independent_sql_queries=len(SQL), assertions_failed=0)))


if __name__ == "__main__":
    main()
