#!/usr/bin/env python3
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "shared/project-matrix.json"


@dataclass(frozen=True, order=True)
class ProjectSpec:
    stack: str
    scenario: str
    contracts: bool
    benchmark: bool

    @property
    def path(self) -> Path:
        return ROOT / "stacks" / self.stack / "projects" / self.scenario


def load_project_specs() -> list[ProjectSpec]:
    data = json.loads(MATRIX_PATH.read_text())
    if data.get("schema") != "ores.comparisons.project-matrix/v1":
        raise ValueError(f"unsupported project matrix schema: {data.get('schema')!r}")
    raw_projects = data.get("projects")
    if not isinstance(raw_projects, list) or not raw_projects:
        raise ValueError("project matrix must contain a non-empty projects array")

    specs: list[ProjectSpec] = []
    seen: set[tuple[str, str]] = set()
    for raw in raw_projects:
        stack = raw.get("stack")
        scenario = raw.get("scenario")
        if not isinstance(stack, str) or not stack:
            raise ValueError(f"invalid stack in project matrix entry: {raw!r}")
        if not isinstance(scenario, str) or not scenario:
            raise ValueError(f"invalid scenario in project matrix entry: {raw!r}")
        key = (stack, scenario)
        if key in seen:
            raise ValueError(f"duplicate project matrix entry: {stack}/{scenario}")
        seen.add(key)
        specs.append(ProjectSpec(
            stack=stack,
            scenario=scenario,
            contracts=bool(raw.get("contracts", False)),
            benchmark=bool(raw.get("benchmark", False)),
        ))
    return sorted(specs)


def contract_project_specs() -> list[ProjectSpec]:
    return [spec for spec in load_project_specs() if spec.contracts]
