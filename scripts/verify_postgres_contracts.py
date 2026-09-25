#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import subprocess
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROJECTS = sorted(
    p for p in ROOT.glob("stacks/*/projects/*")
    if p.is_dir() and (p / "contracts/projection.json").is_file()
)
SAFE = re.compile(r"^[a-z][a-z0-9_]*$")
TYPE_MAP = {
    "TEXT": "text",
    "INTEGER": "integer",
    "TIMESTAMPTZ": "timestamp with time zone",
    "JSONB": "jsonb",
}
BASE_ENV = os.environ.copy()
BASE_ENV.setdefault("PGHOST", "127.0.0.1")
BASE_ENV.setdefault("PGPORT", "5432")
BASE_ENV.setdefault("PGUSER", "postgres")

if len(PROJECTS) != 9:
    raise SystemExit(f"expected 9 comparison projects, found {len(PROJECTS)}")

def run(argv: list[str], *, database: str | None = None, capture: bool = False) -> str:
    env = BASE_ENV.copy()
    if database:
        env["PGDATABASE"] = database
    result = subprocess.run(
        argv,
        env=env,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
    )
    return result.stdout if capture else ""

def psql(database: str, sql: str) -> list[str]:
    output = run(
        ["psql", "-v", "ON_ERROR_STOP=1", "-A", "-t", "-F", "\t", "-c", sql],
        database=database,
        capture=True,
    )
    return [line for line in output.splitlines() if line]

def safe(value: str) -> str:
    if not SAFE.fullmatch(value):
        raise ValueError(f"unsafe SQL identifier {value!r}")
    return value

def database_name(project: Path) -> str:
    stack = project.parents[1].name.replace("-", "_")
    scenario = project.name.replace("-", "_")
    return safe(f"cmp_{stack}_{scenario}")[:60]

errors: list[str] = []

for project in PROJECTS:
    db = database_name(project)
    projection = json.loads((project / "contracts/projection.json").read_text())
    migration = project / "contracts/generated/sql/001_init.sql"
    seed = project / "contracts/generated/sql/002_seed.sql"

    try:
        run(["dropdb", "--if-exists", db])
        run(["createdb", db])

        # Run both lanes twice: the contract promises idempotent developer startup.
        for _ in range(2):
            run(["psql", "-v", "ON_ERROR_STOP=1", "-f", str(migration)], database=db)
        for _ in range(2):
            run(["psql", "-v", "ON_ERROR_STOP=1", "-f", str(seed)], database=db)

        seeded = defaultdict(int)
        for item in projection.get("seed", []):
            seeded[safe(item["table"])] += len(item.get("rows", []))

        for table in projection["tables"]:
            table_name = safe(table["name"])
            expected_columns = []
            for col in table["columns"]:
                name = safe(col["name"])
                sql_type = col["sql_type"]
                if sql_type not in TYPE_MAP:
                    raise ValueError(f"{table_name}.{name} uses unknown SQL type {sql_type}")
                expected_columns.append(
                    (name, TYPE_MAP[sql_type], "YES" if col.get("nullable", False) else "NO")
                )

            rows = psql(
                db,
                "SELECT column_name, data_type, is_nullable "
                "FROM information_schema.columns "
                f"WHERE table_schema='public' AND table_name='{table_name}' "
                "ORDER BY ordinal_position",
            )
            actual_columns = [tuple(row.split("\t")) for row in rows]
            if actual_columns != expected_columns:
                raise AssertionError(
                    f"{table_name} column drift: expected={expected_columns!r} actual={actual_columns!r}"
                )

            pk_rows = psql(
                db,
                "SELECT kcu.column_name "
                "FROM information_schema.table_constraints tc "
                "JOIN information_schema.key_column_usage kcu "
                "ON tc.constraint_name=kcu.constraint_name "
                "AND tc.table_schema=kcu.table_schema "
                f"WHERE tc.table_schema='public' AND tc.table_name='{table_name}' "
                "AND tc.constraint_type='PRIMARY KEY' "
                "ORDER BY kcu.ordinal_position",
            )
            expected_pk = [safe(table["primary_key"])]
            if pk_rows != expected_pk:
                raise AssertionError(
                    f"{table_name} primary-key drift: expected={expected_pk!r} actual={pk_rows!r}"
                )

            count_rows = psql(db, f"SELECT COUNT(*) FROM {table_name}")
            actual_count = int(count_rows[0])
            expected_count = seeded.get(table_name, 0)
            if actual_count != expected_count:
                raise AssertionError(
                    f"{table_name} seed/idempotence drift: expected {expected_count}, got {actual_count}"
                )

        print(f"postgres contract OK: {project.relative_to(ROOT)}")
    except Exception as exc:
        errors.append(f"{project.relative_to(ROOT)}: {exc}")
    finally:
        try:
            run(["dropdb", "--if-exists", db])
        except Exception as exc:
            errors.append(f"{project.relative_to(ROOT)} cleanup: {exc}")

if errors:
    print("postgres contract verification FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("postgres contract verification OK: all 9 projects migrated and seeded twice")
