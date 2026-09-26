# Dummy-org remote reachability

The superproject pins exact component commits in
`shared/dummy-org-gitlinks.json`. Offline verification proves those SHAs agree
with the Git index and `.gitmodules`, but a force-push in a dummy organization
could otherwise make a locally consistent pin unreachable from its governed
`stack/<stack>` branch.

`scripts/verify_dummy_org_remote_reachability.py` closes that gap. It groups
the 99 ledger entries by their 33 repositories, fetches the three governed stack
branch histories with Git's blob filter, and requires every pinned ledger commit
to remain an ancestor of the branch recorded for it.

The check is intentionally networked and separate from the fastest offline
verification path. CI runs it whenever the dummy-org mapping, ledger,
submodule metadata, or verifier changes.
