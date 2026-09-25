#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import subprocess
from collections import defaultdict
from pathlib import Path

from project_matrix import ROOT, contract_project_specs

PROJECTS = [spec.path for spec in contract_project_specs()]
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

if not PROJECTS:
    raise SystemExit("project matrix contains no contract-enabled projects")


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


def sql_literal(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def constraint_name(table: str, column: str) -> str:
    value = f"ck_{table}_{column}_enum"
    if len(value.encode("utf-8")) > 63:
        raise ValueError(f"constraint name exceeds PostgreSQL limit: {value}")
    return safe(value)


def psql_should_fail(database: str, sql: str) -> bool:
    env = BASE_ENV.copy()
    env["PGDATABASE"] = database
    result = subprocess.run(
        ["psql", "-v", "ON_ERROR_STOP=1", "-c", sql],
        env=env,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result.returncode != 0


def database_name(project: Path) -> str:
    stack = project.parents[1].name.replace("-", "_")
    scenario = project.name.replace("-", "_")
    return safe(f"cmp_{stack}_{scenario}")[:60]


errors: list[str] = []

for project in PROJECTS:
    db = database_name(project)
    projection_path = project / "contracts/projection.json"
    schema_path = project / "contracts/json-schema/domain.schema.json"
    migrations = [
        project / "contracts/generated/sql/001_init.sql",
        project / "contracts/generated/sql/010_domain_constraints.sql",
    ]
    seed = project / "contracts/generated/sql/002_seed.sql"
    repos_readme = project / "repos/readme.md"

    try:
        for required in (projection_path, schema_path, *migrations, seed, repos_readme):
            if not required.is_file():
                raise FileNotFoundError(f"missing governed artifact: {required.relative_to(ROOT)}")
        projection = json.loads(projection_path.read_text())
        schema = json.loads(schema_path.read_text())
        defs = schema.get("$defs", {})

        run(["dropdb", "--if-exists", db])
        run(["createdb", db])

        for _ in range(2):
            for migration in migrations:
                run(["psql", "-v", "ON_ERROR_STOP=1", "-f", str(migration)], database=db)
        for _ in range(2):
            run(["psql", "-v", "ON_ERROR_STOP=1", "-f", str(seed)], database=db)

        seeded = defaultdict(int)
        seed_rows: dict[str, list[dict]] = defaultdict(list)
        for item in projection.get("seed", []):
            table = safe(item["table"])
            rows = item.get("rows", [])
            seeded[table] += len(rows)
            seed_rows[table].extend(rows)

        for table in projection["tables"]:
            table_name = safe(table["name"])
            expected_columns = []
            enum_columns: list[tuple[str, list]] = []
            model = table["model"]
            model_schema = defs.get(model, {})
            properties = model_schema.get("properties", {})
            for col in table["columns"]:
                name = safe(col["name"])
                sql_type = col["sql_type"]
                if sql_type not in TYPE_MAP:
                    raise ValueError(f"{table_name}.{name} uses unknown SQL type {sql_type}")
                expected_columns.append(
                    (name, TYPE_MAP[sql_type], "YES" if col.get("nullable", False) else "NO")
                )
                field = properties.get(col["source"], {})
                ref = field.get("$ref")
                if ref:
                    target = ref.rsplit("/", 1)[-1]
                    values = defs.get(target, {}).get("enum")
                    if values is not None:
                        enum_columns.append((name, values))

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

            actual_checks = set(
                psql(
                    db,
                    "SELECT conname FROM pg_constraint "
                    f"WHERE conrelid='public.{table_name}'::regclass AND contype='c' "
                    "ORDER BY conname",
                )
            )
            expected_checks = {
                constraint_name(table_name, column)
                for column, _ in enum_columns
            }
            missing_checks = expected_checks - actual_checks
            if missing_checks:
                raise AssertionError(
                    f"{table_name} missing generated domain constraints: {sorted(missing_checks)}"
                )

            for column, values in enum_columns:
                if not seed_rows.get(table_name):
                    raise AssertionError(
                        f"{table_name}.{column} needs a seed fixture for rejection evidence"
                    )
                invalid = dict(seed_rows[table_name][0])
                primary_key = safe(table["primary_key"])
                if primary_key not in invalid:
                    raise AssertionError(
                        f"{table_name} seed fixture does not populate primary key {primary_key}"
                    )
                invalid[primary_key] = f"{invalid[primary_key]}-invalid-{column}"
                invalid[column] = "__outside_contract_enum__"
                names = [safe(name) for name in invalid]
                values_sql = [sql_literal(invalid[name]) for name in invalid]
                statement = (
                    "BEGIN; "
                    f"INSERT INTO {table_name} ({', '.join(names)}) "
                    f"VALUES ({', '.join(values_sql)}); "
                    "ROLLBACK;"
                )
                if not psql_should_fail(db, statement):
                    raise AssertionError(
                        f"{table_name}.{column} accepted an out-of-contract enum value; "
                        f"allowed={values!r}"
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

print(f"postgres contract verification OK: all {len(PROJECTS)} matrix-governed projects migrated/seeded twice and reject invalid enum domains")
