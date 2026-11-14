# dependency-status

Written by the nightly dependency pipeline (sliceworkz/software-factory). Nothing else writes here.

- `OVERVIEW.md`: every branch side by side: its vulnerabilities and the pipeline PRs waiting
- `latest/<branch>.json`: the last run per branch
- `history/<branch>/<run time>.json`: every run, for charts
- `sbom/<branch>/`: the SBOMs the last run scanned, re-scanned while the branch head does not move
- `sbom/<branch>/release/`: on a release line, the SBOMs of its last release, as attached to the GitHub release

A branch that no longer exists is removed from all of these by the next run of any branch.
