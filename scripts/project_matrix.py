#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "shared/project-matrix.json"
TOKEN = re.compile(r"^[a-z][a-z0-9-]*$")
ALLOWED_STACKS = frozenset({"beamscale", "scintilla-run", "ores-stack"})
ALLOWED_KEYS = frozenset({"stack", "scenario", "contracts", "benchmark"})


@dataclass(frozen=True, order=True)
class ProjectSpec:
    stack: str
    scenario: str
    contracts: bool
    benchmark: bool

    @property
    def path(self) -> Path:
        return ROOT / "stacks" / self.stack / "projects" / self.scenario


def require_bool(raw: dict, key: str) -> bool:
    value = raw.get(key)
    if not isinstance(value, bool):
        raise ValueError(f"project matrix field {key!r} must be boolean, got {value!r}")
    return value


def load_project_specs() -> list[ProjectSpec]:
    data = json.loads(MATRIX_PATH.read_text())
    if data.get("schema") != "ores.comparisons.project-matrix/v1":
        raise ValueError(f"unsupported project matrix schema: {data.get('schema')!r}")
    if set(data) != {"schema", "projects"}:
        raise ValueError(f"project matrix has unsupported top-level keys: {sorted(set(data) - {'schema', 'projects'})}")

    raw_projects = data.get("projects")
    if not isinstance(raw_projects, list) or not raw_projects:
        raise ValueError("project matrix must contain a non-empty projects array")

    specs: list[ProjectSpec] = []
    seen: set[tuple[str, str]] = set()
    for raw in raw_projects:
        if not isinstance(raw, dict):
            raise ValueError(f"project matrix entries must be objects: {raw!r}")
        unknown = set(raw) - ALLOWED_KEYS
        missing = ALLOWED_KEYS - set(raw)
        if unknown or missing:
            raise ValueError(f"project matrix entry keys invalid; missing={sorted(missing)} unknown={sorted(unknown)}")

        stack = raw["stack"]
        scenario = raw["scenario"]
        if not isinstance(stack, str) or stack not in ALLOWED_STACKS:
            raise ValueError(f"invalid stack in project matrix entry: {stack!r}")
        if not isinstance(scenario, str) or not TOKEN.fullmatch(scenario):
            raise ValueError(f"invalid scenario token in project matrix entry: {scenario!r}")

        contracts = require_bool(raw, "contracts")
        benchmark = require_bool(raw, "benchmark")
        if benchmark and not contracts:
            raise ValueError(f"benchmark project must be contract-enabled: {stack}/{scenario}")

        key = (stack, scenario)
        if key in seen:
            raise ValueError(f"duplicate project matrix entry: {stack}/{scenario}")
        seen.add(key)
        specs.append(ProjectSpec(stack=stack, scenario=scenario, contracts=contracts, benchmark=benchmark))

    return sorted(specs)


def contract_project_specs() -> list[ProjectSpec]:
    return [spec for spec in load_project_specs() if spec.contracts]


def benchmark_project_specs() -> list[ProjectSpec]:
    return [spec for spec in load_project_specs() if spec.benchmark]
