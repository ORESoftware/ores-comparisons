# Dummy GitHub organizations and submodule topology

`ores-comparisons` models six comparison projects across three execution stacks.
Each project owns one dummy GitHub organization. Repository identity is stable
across stacks; stack-specific implementations live on dedicated branches in the
same remote repository.

| Project | GitHub organization |
| --- | --- |
| `big-org-example-collaboration` | `ores-dummy-org-1` |
| `big-org-example-commerce` | `ores-dummy-org-2` |
| `big-org-example-operations` | `ores-dummy-org-3` |
| `cached-rpc` | `ores-dummy-org-4` |
| `forms-chat-workflow` | `ores-dummy-org-5` |
| `http-observability` | `ores-dummy-org-6` |

The authoritative machine-readable mapping is
`shared/dummy-org-map.json`.

## Branch model

Each remote component repository carries three stack branches:

- `stack/beamscale`
- `stack/ores-stack`
- `stack/scintilla-run`

The default `main` branch is not used as a mutable comparison target. This
keeps one stable repository identity such as
`ores-dummy-org-1/app` while allowing every stack-specific submodule path to
pin its own immutable commit.

After migration, paths such as

`stacks/beamscale/projects/big-org-example-collaboration/repos/app`

and

`stacks/ores-stack/projects/big-org-example-collaboration/repos/app`

are separate gitlinks to the same GitHub repository, checked out at different
stack branches and commits.

## Materialize and cut over

Prerequisites:

1. `gh auth status --active --hostname github.com` succeeds.
2. The authenticated account has push permission to all 33 governed component
   repositories.
3. Git `user.name` and `user.email` are configured.
4. The superproject worktree is clean before the gitlink rewrite.

Preview the complete 99-path plan without writes:

```sh
just dummy-org-plan
```

Install the exact pinned toolchain, push all stack branches, replace all
in-tree component repositories with submodules, and synchronize them through
Zed:

```sh
just dummy-org-materialize
```

The materializer preflights write permission to all 33 remote repositories
before the first push. It never force-pushes and never overwrites `main`.
Existing `stack/*` branches are updated only by ordinary fast-forward pushes.

## Zed is the submodule coordinator

The root `.zpkg.toml` declares:

```toml
[interop.git]
consume_gitmodules = true
```

The pinned `zed-cli` therefore owns the normal synchronization path:

```sh
just submodules-sync
# equivalent transport behavior:
# git submodule sync --recursive
# git submodule update --init --recursive --checkout
```

Zed deliberately uses checkout semantics so a local
`submodule.<name>.update = !command` hook cannot replace the governed
operation.

Inspect and verify the graph with:

```sh
just submodules-status
just submodules-verify
```

A fresh authorized clone is restored with:

```sh
just tools-bootstrap
./.local/bin/zed install --git-submodules
just verify
```

## Authority boundary

`repos/readme.md` remains a normal superproject file because it documents the
organization envelope and is not itself a GitHub repository. Every governed
repository child, including `repos/.github`, becomes a gitlink.

`.gitmodules` is the transport projection. The committed gitlink SHA is the
immutable source pin. `shared/dummy-org-map.json` governs the allowed
organization, repository, and stack-branch mapping, and
`scripts/verify_dummy_org_map.py` fails closed on URL, branch, repo-set, or
mixed materialized/submodule drift.

## Private repository CI

The dummy repositories are private. CI that dereferences their gitlinks needs
`COMPARISON_REPO_READ_TOKEN` with read access to all six dummy organizations.
Always-on CI can still verify the superproject matrix, gitlinks,
`.gitmodules`, mapping, workflow pins, and toolchain pins without checking out
private submodules.
