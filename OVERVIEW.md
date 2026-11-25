# Dependency status of sliceworkz/eventstore

Written by the nightly dependency pipeline after every run (sliceworkz/software-factory, `docs/dependency-updates.md`). Per branch: the vulnerabilities in what its head would ship, and the pipeline PRs waiting; per release line also its last release, scanned from the SBOMs attached to it. Every finding is in `latest/<branch>.json`, every run in `history/`.

| Branch | Last run | Head | Critical | High | Medium | Low | Blocking | Released version | Open PRs | Unreleased updates | SBOMs |
|---|---|---|---:|---:|---:|---:|---:|---|---:|---|---|
| [`develop`](#develop) | 2026-11-24T11:58:22+01:00 | `1500687856` | 0 | 0 | 0 | 0 | 0 | - | 0 | - | ci-artifact (run 45212361103) |
| [`release/0.14`](#release014) | 2026-11-24T11:29:25+01:00 | `5a3fa32427` | 0 | 0 | 0 | 0 | 0 | v0.14.0: none critical/high | 1 | none since v0.14.0 | ci-artifact (run 44803853083) |
| [`release/0.13`](#release013) | 2026-11-25T10:17:11+01:00 | `97103fd736` | 0 | 0 | 0 | 0 | 0 | v0.13.0: none critical/high | 1 | **3 since v0.13.0**, can be released | ci-artifact (run 45376866509) |

## develop

No critical or high findings on the branch head.

## release/0.14

No dependency updates since v0.14.0 (2026-11-20).

**Nothing to release: no changes since v0.14.0.**

- pipeline PR #303 waits for review: its updates are not in this release

Automatic release: off (`release.automatic: never` in the policy).

No critical or high findings in the released v0.14.0 (scanned from the SBOMs attached to it).

No critical or high findings on the branch head.

Pipeline PRs open: [#303](https://github.com/sliceworkz/eventstore/pull/303) (review, CI green)

## release/0.13

**3 dependency update(s) merged since v0.13.0 (2026-11-12), not released yet**: a patch release (*Release 2 · Tag* on this line) ships them.

**Can be released: 3 dependency updates since v0.13.0.**

- pipeline PR #302 waits for review: its updates are not in this release

Release it with [Release 2 · Tag](https://github.com/sliceworkz/eventstore/actions/workflows/create-release-tag-and-bump.yaml) (*Run workflow*, branch `release/0.13`), or:

```bash
gh workflow run create-release-tag-and-bump.yaml --ref release/0.13 -R sliceworkz/eventstore
```

Automatic release: off (`release.automatic: never` in the policy).

No critical or high findings in the released v0.13.0 (scanned from the SBOMs attached to it).

No critical or high findings on the branch head.

Pipeline PRs open: [#302](https://github.com/sliceworkz/eventstore/pull/302) (review, CI green)
