# Committed benchmark results

Curated runs, one directory per `<suite version>/<profile>`, each holding the raw JMH JSON, the
`report.json` manifest and a rendered `report.md`.

These are in the repository on purpose. The figures this project's documentation used to quote —
`~2µs` per `getEventStream`, `~5%` for the append advisory lock, `1230ms → 460ms` for the
statement-level trigger — were measured once, by hand, and nothing that could reproduce them
survived. A committed run is reviewable in a pull request, comparable across releases, and safe to
quote from because the conditions it was measured under are sitting next to it.

## What belongs here

Only runs that pass `report --publish`, which refuses a run that:

- was measured against a Testcontainers PostgreSQL. That is stock defaults on whatever the host
  happened to be — fine for comparing two runs on one machine, not fine as a number other people
  will quote. Publish from an external server whose configuration is deliberate.
- grew by more than 2% during measurement, since those numbers are not about the corpus they name.
- has no suite version, so it could not be attributed to a release.

A run that failed a **correctness check** is never publishable, under any flag. Its numbers describe
work that did not happen.

`--force` overrides the first three for a run somebody has decided is worth keeping anyway. The
reasons stay recorded in the report, so a caveated baseline stays caveated.

## Reading one

Start at `report.md`. The derived comparisons come first — what the DCB check costs, how a
multi-fact decision scales, what happens as threads are added — because those are the questions the
suite exists to answer. The full table of scores is last.

Check the **Environment** section before quoting anything. Two runs whose environments differ are
not comparable, and `report --baseline=<path>` refuses to diff them rather than reporting a
difference in hardware as a change in the store.
