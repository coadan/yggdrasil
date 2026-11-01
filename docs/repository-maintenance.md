# Repository maintenance

Verified on 2026-11-01 against fetched origin refs and the GitHub releases API.

## Branches

Local `main` at `356ad2f9` tracks `origin/main`, rather than the legacy branch.
It is an ancestor of `origin/search-query-expressivity` (`ea76b201`), so that
search branch already contains its history. Local `main` and `origin/main`
(`bd763bf2`) have no merge base. Do not reset or force-push either history
while reconciling them. The legacy implementation remains available at
`origin/legacy/xtdb-final` (`db1d91e7`).

Before changing the public default branch, compare the two trees and preserve
both tips under archival refs. Choose the authoritative tree explicitly, then
prepare a reviewed integration that retains both histories. This cleanup
makes no remote branch changes; fresh installs from the remote default branch
will continue to use that branch until the migration is completed.

## Releases and builds

GitHub's latest published stable release is `v0.3.0`. The `v0.4.0` tag points
to `bd763bf2`, but has no published release. A tag alone does not establish
that release checks passed. Inspect the tag's workflow run before publishing
artifacts; do not move the tag to the unrelated local history.

README source installs use the published stable version. Unversioned source
builds report `devel`; `make build VERSION=<version>` and
`make release VERSION=<version>` stamp an explicit version. The tag workflow
already passes its version to the release target. Previously installed
binaries and immutable published source tags retain their original version
strings until a replacement binary or new release is installed.

## Agent setup

`AGENTS.md` contains checkout-level search guidance. No global Claude file was
found at `~/.claude/CLAUDE.md` in this environment. Global configuration and
plugin installation were not changed by this repository cleanup.
