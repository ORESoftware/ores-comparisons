#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from project_matrix import ROOT, load_project_specs

MAP_PATH = ROOT / "shared/dummy-org-map.json"


def run(
    args: list[str],
    *,
    cwd: Path = ROOT,
    check: bool = True,
    capture: bool = False,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=cwd,
        check=check,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )


def output(args: list[str], *, cwd: Path = ROOT) -> str:
    return run(args, cwd=cwd, capture=True).stdout.strip()


def remove_checkout_contents(path: Path) -> None:
    for child in path.iterdir():
        if child.name == ".git":
            continue
        if child.is_symlink() or child.is_file():
            child.unlink()
        else:
            shutil.rmtree(child)


def copy_repository(source: Path, destination: Path) -> None:
    for child in source.iterdir():
        if child.name == ".git":
            raise SystemExit(f"{source}: nested .git metadata is not allowed in materialized input")
        target = destination / child.name
        if child.is_symlink():
            target.symlink_to(os.readlink(child))
        elif child.is_dir():
            shutil.copytree(child, target, symlinks=True)
        else:
            shutil.copy2(child, target)


def require_clean_superproject() -> None:
    status = output(["git", "status", "--porcelain=v1"])
    if status:
        raise SystemExit(
            "superproject must be clean before --rewrite-submodules; "
            "commit or stash local changes first"
        )


def require_git_identity() -> None:
    for key in ("user.name", "user.email"):
        result = run(["git", "config", "--get", key], check=False, capture=True)
        if result.returncode != 0 or not result.stdout.strip():
            raise SystemExit(f"git {key} must be configured before --apply")


def target_specs() -> list[tuple[str, str, str, str, Path]]:
    data = json.loads(MAP_PATH.read_text())
    projects = data["projects"]
    stacks = data["stacks"]
    targets: list[tuple[str, str, str, str, Path]] = []
    for spec in load_project_specs():
        project = projects[spec.scenario]
        org = project["org"]
        branch = stacks[spec.stack]["branch"]
        for repo in project["repos"]:
            source = spec.repos_path / repo
            targets.append((spec.stack, spec.scenario, org, branch, source))
    return targets


def unique_remote_repos(
    targets: list[tuple[str, str, str, str, Path]]
) -> list[tuple[str, str]]:
    seen: set[tuple[str, str]] = set()
    result: list[tuple[str, str]] = []
    for _stack, _scenario, org, _branch, source in targets:
        item = (org, source.name)
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


def preflight_remote_push_access(remotes: list[tuple[str, str]]) -> None:
    run(["gh", "auth", "status", "--active", "--hostname", "github.com"])
    denied: list[str] = []
    for org, repo in remotes:
        full_name = f"{org}/{repo}"
        result = run(
            ["gh", "api", f"repos/{full_name}", "--jq", ".permissions.push"],
            check=False,
            capture=True,
        )
        if result.returncode != 0 or result.stdout.strip() != "true":
            denied.append(full_name)
    if denied:
        formatted = "\n".join(f" - {name}" for name in denied)
        raise SystemExit(
            "authenticated GitHub account does not have push permission to every target repo:\n"
            + formatted
        )


def materialize_one(
    source: Path,
    *,
    org: str,
    branch: str,
    stack: str,
    scenario: str,
    workspace: Path,
) -> str:
    repo = source.name
    full_name = f"{org}/{repo}"
    url = f"https://github.com/{full_name}.git"
    clone = workspace / f"{org}__{repo.replace('.', '_')}__{stack.replace('/', '_')}"
    run(["git", "clone", "--quiet", "--no-checkout", url, str(clone)])

    remote_branch = run(
        ["git", "ls-remote", "--exit-code", "--heads", "origin", f"refs/heads/{branch}"],
        cwd=clone,
        check=False,
        capture=True,
    )
    if remote_branch.returncode == 0:
        run(["git", "fetch", "--quiet", "origin", branch], cwd=clone)
    elif remote_branch.returncode == 2:
        run(["git", "fetch", "--quiet", "origin", "main"], cwd=clone)
    else:
        raise SystemExit(
            f"failed to inspect {full_name} branch {branch}: "
            f"{remote_branch.stderr.strip()}"
        )

    run(["git", "switch", "--force-create", branch, "FETCH_HEAD"], cwd=clone)
    remove_checkout_contents(clone)
    copy_repository(source, clone)
    run(["git", "add", "-A"], cwd=clone)

    staged = run(["git", "diff", "--cached", "--quiet"], cwd=clone, check=False)
    if staged.returncode not in (0, 1):
        raise SystemExit(f"failed to inspect staged changes for {full_name}")
    if staged.returncode == 1:
        run(
            [
                "git",
                "commit",
                "-m",
                f"feat: materialize {stack} {scenario} comparison",
            ],
            cwd=clone,
        )

    run(["git", "push", "origin", f"HEAD:refs/heads/{branch}"], cwd=clone)
    sha = output(["git", "rev-parse", "HEAD"], cwd=clone)
    print(f"materialized {full_name}@{branch} -> {sha}")
    return sha


def submodule_name(stack: str, scenario: str, repo: str) -> str:
    safe_repo = repo.replace(".", "_")
    return f"{stack}--{scenario}--{safe_repo}"


def rewrite_as_submodules(
    targets: list[tuple[str, str, str, str, Path]],
) -> None:
    require_clean_superproject()

    for stack, scenario, org, branch, source in targets:
        relative = source.relative_to(ROOT).as_posix()
        repo = source.name
        url = f"https://github.com/{org}/{repo}.git"
        name = submodule_name(stack, scenario, repo)

        run(["git", "rm", "-r", "--", relative])
        run(
            [
                "git",
                "submodule",
                "add",
                "--name",
                name,
                "-b",
                branch,
                url,
                relative,
            ]
        )

    zed = ROOT / ".local/bin/zed"
    zed_cmd = str(zed) if zed.is_file() else shutil.which("zed")
    if not zed_cmd:
        raise SystemExit(
            "zed is required to finish submodule synchronization; "
            "run just tools-bootstrap first"
        )
    run([zed_cmd, "install", "--git-submodules"])
    run(["git", "submodule", "status", "--recursive"])
    run(["python3", "scripts/verify_dummy_org_map.py"])
    run(["python3", "scripts/verify_project_repo_layout.py"])

    print()
    print("submodule rewrite staged successfully")
    print("review git status, run just verify, then commit the superproject gitlinks")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Materialize every ores-comparisons repo mirror into its governed dummy "
            "GitHub org and optionally replace the in-tree copies with Git submodules."
        )
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Push stack-specific branches to all governed dummy-org repositories.",
    )
    parser.add_argument(
        "--rewrite-submodules",
        action="store_true",
        help=(
            "After successful remote materialization, replace all in-tree repository "
            "copies with git submodules and synchronize them through zed."
        ),
    )
    args = parser.parse_args()

    if args.rewrite_submodules and not args.apply:
        parser.error("--rewrite-submodules requires --apply")

    targets = target_specs()
    remotes = unique_remote_repos(targets)

    missing = [source for *_rest, source in targets if not source.is_dir()]
    if missing:
        formatted = "\n".join(f" - {path.relative_to(ROOT)}" for path in missing)
        raise SystemExit(
            "materialization requires the current in-tree repository copies; missing:\n"
            + formatted
        )

    print(
        f"plan: {len(remotes)} GitHub repositories, "
        f"{len(targets)} stack-specific branches/submodule paths"
    )
    for stack, scenario, org, branch, source in targets:
        print(
            f" - {source.relative_to(ROOT)} -> "
            f"{org}/{source.name}@{branch} ({stack}/{scenario})"
        )

    if not args.apply:
        print()
        print("dry-run only; pass --apply to push stack branches")
        return 0

    require_git_identity()
    preflight_remote_push_access(remotes)

    with tempfile.TemporaryDirectory(prefix="ores-comparisons-materialize-") as temp:
        workspace = Path(temp)
        for stack, scenario, org, branch, source in targets:
            materialize_one(
                source,
                org=org,
                branch=branch,
                stack=stack,
                scenario=scenario,
                workspace=workspace,
            )

    if args.rewrite_submodules:
        rewrite_as_submodules(targets)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
