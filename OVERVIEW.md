# Dependency status of sliceworkz/eventstore

Written by the nightly dependency pipeline after every run (sliceworkz/software-factory, `docs/dependency-updates.md`). Per branch: the vulnerabilities in what its head would ship, and the pipeline PRs waiting; per release line also its last release, scanned from the SBOMs attached to it. Every finding is in `latest/<branch>.json`, every run in `history/`.

| Branch | Last run | Head | Critical | High | Medium | Low | Blocking | Released version | Open PRs | Unreleased updates | SBOMs |
|---|---|---|---:|---:|---:|---:|---:|---|---:|---|---|
| [`develop`](#develop) | 2026-11-26T10:33:53+01:00 | `08ab1420ce` | 0 | 0 | 0 | 0 | 0 | - | 0 | - | ci-artifact (run 45591485577) |
| [`release/0.15`](#release015) | 2026-11-27T10:10:21+01:00 | `cbac9dd415` | 0 | 0 | 0 | 0 | 0 | v0.15.0: none critical/high | 0 | none since v0.15.0 | ci-artifact (run 45892575937) |
| [`release/0.14`](#release014) | 2026-11-26T09:57:22+01:00 | `715c232a78` | 0 | 0 | 0 | 0 | 0 | v0.14.0: none critical/high | 1 | **2 since v0.14.0**, can be released | ci-artifact (run 45585629388) |

## develop

No critical or high findings on the branch head.

## release/0.15

No dependency updates since v0.15.0 (2026-11-26).

**Nothing to release: no changes since v0.15.0.**

Automatic release: off (`release.automatic: never` in the policy).

No critical or high findings in the released v0.15.0 (scanned from the SBOMs attached to it).

No critical or high findings on the branch head.

## release/0.14

**2 dependency update(s) merged since v0.14.0 (2026-11-20), not released yet**: a patch release (*Release 2 · Tag* on this line) ships them.

**Can be released: 2 dependency updates since v0.14.0.**

- pipeline PR #303 waits for review: its updates are not in this release

Release it with [Release 2 · Tag](https://github.com/sliceworkz/eventstore/actions/workflows/create-release-tag-and-bump.yaml) (*Run workflow*, branch `release/0.14`), or:

```bash
gh workflow run create-release-tag-and-bump.yaml --ref release/0.14 -R sliceworkz/eventstore
```

Automatic release: off (`release.automatic: never` in the policy).

No critical or high findings in the released v0.14.0 (scanned from the SBOMs attached to it).

No critical or high findings on the branch head.

Pipeline PRs open: [#303](https://github.com/sliceworkz/eventstore/pull/303) (review, CI green)
