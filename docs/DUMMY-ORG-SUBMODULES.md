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

## Cutover state

The one-time materialization is complete. The 33 component repositories now
exist in the six dummy organizations, all 99 stack/repository combinations are
committed as gitlinks, and their immutable SHAs are recorded in
`shared/dummy-org-gitlinks.json`.

`scripts/materialize_dummy_orgs.py` is retained only as migration history and
now fails closed when invoked with `--apply` after cutover. It must not be
used to copy a submodule checkout back over its remote repository.

The normal fresh-clone/bootstrap flow is:

```sh
just tools-bootstrap
./.local/bin/zed task list
./.local/bin/zed task run check
./.local/bin/zed install --git-submodules
just submodules-verify
just verify
```

The schema-v2 `zed-env.toml` task plan provides `check`,
`submodules-sync`, `submodules-status`, and `submodules-verify` as the
portable Zed execution surface. The `just` recipes remain convenience aliases.

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
organization, repository, and stack-branch mapping, while
`shared/dummy-org-gitlinks.json` records the exact immutable SHA for every
stack/repository path. `scripts/verify_dummy_org_map.py` validates topology and
`scripts/verify_dummy_org_gitlinks.py` cross-checks all 99 ledger entries against
`.gitmodules` and the Git index without requiring private submodules to be
checked out.

## Private repository CI

The dummy repositories are private. CI that dereferences their gitlinks needs
`COMPARISON_REPO_READ_TOKEN` with read access to all six dummy organizations.
Always-on CI verifies the superproject matrix, all 99 gitlinks, `.gitmodules`,
the immutable ledger, mapping, workflow pins, and toolchain pins without
checking out private submodules. The authorized private lane uses invocation-local
Git configuration via `GIT_CONFIG_COUNT` for token URL rewriting; it does not
modify global Git configuration on the runner.

## Fresh-clone recovery proof

The authorized CI lane runs `scripts/verify_fresh_clone_submodules.sh`. It clones the exact superproject commit into a temporary directory, materializes all 99 private gitlinks through the pinned Zed CLI, verifies every checkout against `shared/dummy-org-gitlinks.json`, deliberately deinitializes one `app` submodule, and proves a second `zed install --git-submodules` restores the exact committed revision. The proof uses the same invocation-local Git credential rewrite as the private CI lane and never changes global Git configuration.


## Post-cutover materializer behavior

The materializer is a one-time migration tool. Once the submodule metadata and
immutable gitlink ledger exist, the script no longer treats their presence as
proof of a healthy cutover.

Before reporting success it runs the dummy-org map verifier, the exact gitlink
ledger verifier, and the project repository-layout verifier. Together those
checks prove that all 99 governed repository paths are direct gitlinks, the
superproject index SHA matches the ledger SHA, submodule URL and stack branch
metadata match the governed map, and each organization envelope README remains
owned by the superproject.

After cutover the script remains read-only. Component revision changes belong to
the normal Zed/submodule synchronization flow rather than the one-time copier.
