# Append-pipelining harness

Load harness for the experimental append pipelining (`AppendPipelining`): the shipped conditional append
(lock, INSERT, commit as three round trips) against all three in one round trip. The design, the results and the
recommendation are in `docs/design/dcb-single-round-trip-append.md`. Nothing here runs during a build.

| file | what it is |
|---|---|
| `../java/.../DcbAppendBenchmark.java` | one harness phase per invocation: `init`, `run`, `verify`, `explain` |
| `scenarios.txt` | the scenario matrix: a name and the harness arguments per line |
| `run.sh` | runs every scenario in both modes (`off`, `single-round-trip`), `REPS` times, against a dedicated PostgreSQL container |
| `aggregate.py` | turns the runs into per-scenario tables (`--markdown`, `--csv`) |

## Running it

```bash
# from the repository root: build, and resolve the test classpath
mvn install -DskipTests -pl sliceworkz-eventstore-infra-postgres -am
mvn test-compile dependency:build-classpath -pl sliceworkz-eventstore-infra-postgres \
    -Dmdep.includeScope=test -Dmdep.outputFile=/tmp/cp-deps.txt
M=$PWD/sliceworkz-eventstore-infra-postgres/target
export CP="$M/test-classes:$M/classes:$(cat /tmp/cp-deps.txt)"

# run the matrix (about 1.5 hours with 3 repetitions), then aggregate
sliceworkz-eventstore-infra-postgres/src/test/dcb-append/run.sh /tmp/dcbappend \
    sliceworkz-eventstore-infra-postgres/src/test/dcb-append/scenarios.txt 3
python3 sliceworkz-eventstore-infra-postgres/src/test/dcb-append/aggregate.py /tmp/dcbappend --markdown report.md
```

- **Copy the classes before a long run.** `run.sh` runs whatever `CP` points at, so copy `classes` and
  `test-classes` elsewhere first, or a rebuild part-way through mixes two binaries into one matrix.
- **The database.** `run.sh` starts a `postgres:18` container named `dcbbench-pg` on port 55432, pinned
  to `PG_CPUS` (default `0-3`), with the JVMs on `JVM_CPUS` (default `4-11`). Override the image with
  `PG_IMAGE`. Remove the container and its data volume when done: `docker rm -f -v dcbbench-pg` (without `-v` the volume stays behind).
- **Resumable.** A run directory holding `done` is skipped, so an interrupted matrix continues where it
  stopped.
- **The invariant check.** Every run ends with `phase=verify`. A violation is printed as
  `INVARIANT VIOLATION` and counted in the aggregate.
- **Plans.** `phase=explain` prints a standalone `EXPLAIN` of the probe, which does not show the plan an
  append runs under load: capture that with `auto_explain` on the container
  (`session_preload_libraries`, `auto_explain.log_min_duration`), as the design note does.
