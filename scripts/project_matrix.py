#!/usr/bin/env python3
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "shared/project-matrix.json"
MATERIALIZATION_MATRIX_PATH = ROOT / "shared/materialization-matrix.json"


@dataclass(frozen=True, order=True)
class ProjectSpec:
    stack: str
    scenario: str
    contracts: bool
    benchmark: bool

    @property
    def path(self) -> Path:
        """Project envelope containing only the local GitHub-org mirror."""
        return ROOT / "stacks" / self.stack / "projects" / self.scenario

    @property
    def repos_path(self) -> Path:
        """Local mirror of the GitHub organization root."""
        return self.path / "repos"

    @property
    def shared_repo_path(self) -> Path:
        """Simulated organization .github repository."""
        return self.repos_path / ".github"

    @property
    def app_repo_path(self) -> Path:
        """Current runnable application repository within the org mirror."""
        return self.repos_path / "app"

    @property
    def profile_readme_path(self) -> Path:
        return self.shared_repo_path / "profile" / "README.md"


def _load_specs(path: Path, schema: str, label: str) -> list[ProjectSpec]:
    data = json.loads(path.read_text())
    if data.get("schema") != schema:
        raise ValueError(f"unsupported {label} schema: {data.get('schema')!r}")
    raw_projects = data.get("projects")
    if not isinstance(raw_projects, list) or not raw_projects:
        raise ValueError(f"{label} must contain a non-empty projects array")

    specs: list[ProjectSpec] = []
    seen: set[tuple[str, str]] = set()
    for raw in raw_projects:
        if not isinstance(raw, dict):
            raise ValueError(f"invalid {label} entry: {raw!r}")
        stack = raw.get("stack")
        scenario = raw.get("scenario")
        if not isinstance(stack, str) or not stack:
            raise ValueError(f"invalid stack in {label} entry: {raw!r}")
        if not isinstance(scenario, str) or not scenario:
            raise ValueError(f"invalid scenario in {label} entry: {raw!r}")
        key = (stack, scenario)
        if key in seen:
            raise ValueError(f"duplicate {label} entry: {stack}/{scenario}")
        seen.add(key)
        specs.append(ProjectSpec(
            stack=stack,
            scenario=scenario,
            contracts=bool(raw.get("contracts", False)),
            benchmark=bool(raw.get("benchmark", False)),
        ))
    return sorted(specs)


def load_project_specs() -> list[ProjectSpec]:
    """Load runtime-verified projects admitted to executable comparison coverage."""
    return _load_specs(
        MATRIX_PATH,
        "ores.comparisons.project-matrix/v1",
        "project matrix",
    )


def load_materialization_specs() -> list[ProjectSpec]:
    """Load every topology-materialized stack/scenario project envelope."""
    return _load_specs(
        MATERIALIZATION_MATRIX_PATH,
        "ores.comparisons.materialization-matrix/v1",
        "materialization matrix",
    )


def contract_project_specs() -> list[ProjectSpec]:
    return [spec for spec in load_project_specs() if spec.contracts]
