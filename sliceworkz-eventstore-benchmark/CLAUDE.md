# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in **`sliceworkz-eventstore-benchmark`**.
This is the full record of what the suite measures, how it measures it, and every figure with its
caveats; the root `CLAUDE.md` carries only a digest of the conclusions. The module README documents
the operator surface -- subcommands, profiles, provisioning, publishing.

## Benchmarking

Measurement lives in **`sliceworkz-eventstore-benchmark`** and never runs during a build: the module
is `src/main` only, so `mvn package` compiles and shades it and runs nothing. It is a
*capacity-characterisation* harness — JMH for operation-level numbers, a separate runner for
sustained load and live latency, both driving one shared `Workload` catalogue over corpora that are
provisioned once and reused. See that module's README for the full picture; what matters from here:

- **Every number is published with a manifest**, and the comparators refuse rather than guess. A
  percentage between two runs on different machines is not a statement about the store, and nothing
  about the two numbers says so. `report --baseline` diffs the same configuration over time and
  refuses when the corpus, targets or environment differ; `compare --a --b` diffs two configurations
  measured here and refuses when the *environment* differs.
- **Curated runs are committed** to `sliceworkz-eventstore-benchmark/results/<version>/<profile>/`,
  so a figure quoted here or in a README has something behind it that a pull request can review.
  Publishing refuses a Testcontainers run, a run whose store drifted over 2%, and — under any flag —
  a run that failed a correctness check.
- **The profiles are mostly pairs.** `stream-design-tagged` against `stream-design-per-entity`,
  `read-shapes` against `crowded-store` and `crowded-database`, the three `write-contention-*`
  collision modes. Each pair differs in one property, which is what makes the difference between them
  attributable.

**The figures quoted in this document are not yet reproduced by that suite.** They were measured ad
hoc while the behaviour they describe was being fixed, and the code that produced them is gone — which
is the reason the suite exists. Treat each as a recorded observation rather than a current
measurement, and where one matters, run the profile that would replace it:

| figure in this document | profile that would re-derive it |
|---|---|
| `~5%` for the append advisory lock | not covered — that is the lock's *uncontended* overhead, which needs a build without it. What the suite measures is the sentence after it, the hot-stream ceiling: see "What a shared append lock costs" below |
| `~175µs / 139KB` vs `~36µs / 69KB` for a fresh vs shared serde | not covered — the suite always shares, since that is what the store does now |
| `15 meters / ~5.5 KB` per distinct purpose | the heap figure has no workload and stays a recorded observation; the *throughput* half is now measured — see the metrics section in the root `CLAUDE.md`, and it is nil |
| `1230ms → 460ms` for the statement-level append trigger | `ingest-saturation`, and only as a total — the trigger is not timed separately |

Two of those four have no profile behind them, deliberately: a per-meter heap figure and a per-trigger
time are properties of a snapshot rather than of a throughput, and inventing a workload to produce a
number that shape would produce a worse one. They stay as recorded observations, and are marked as
such rather than quietly dropped.

### What a read costs, and how much of it is the library rather than the database

`read-shapes` is the profile that asks what each query shape costs against a store holding nothing but
the context under test, on the in-memory backend and PostgreSQL side by side. Two things came out of
it that are worth carrying here.

**Deserialisation is roughly 2µs per event, and on an ordinary page it is most of the wait.**
Subtracting the server's own execution time from the measured operation, on PG18 over the
100.000-event `TAGGED` corpus:

| workload | events returned | server | measured | per event |
|---|---|---|---|---|
| `query-by-entity-hot` | 6,876 | 5.68 ms | 21.32 ms | **2.27 µs** |
| `query-stream-page` | 500 | 0.42 ms | 1.05 ms | **1.25 µs** (lower bound) |

So a 500-event page spends **60–83%** of its time in JDBC and the serde rather than in PostgreSQL —
the range being how much of the server's *reported* time is the `auto_explain` instrumentation that
reported it, which inflates a fast statement and so deflates the remainder. The 6.876-event read is
the trustworthy row, because there the server's own work dwarfs the observer. Two consequences either
way: bounding a read with `EventQuery.limit(n)` is worth more than it looks, because the cost being
bounded is mostly per-event and downstream of the query; and tuning the database is the wrong first
move for a read that returns thousands of events, because the database is not where the time is going.
(Testcontainers on a developer machine — the magnitude, not the third digit.)

**Two read shapes do not use the index you would assume, and the captured plans say so.**

- **A wildcard read scans the whole table, whatever its limit.** `EventStreamId.anyContext()` binds no
  stream column, so `ORDER BY event_tx, event_position` has no index to walk: PG18 answers
  `query-wildcard`'s `limit(500)` with a **parallel sequential scan over all 100.000 rows** feeding a
  top-N heapsort — 4.428 buffers and ~20ms for 500 events. Its cost is the size of the store and not
  the size of the page, which matters for the paths that legitimately use a wildcard stream: the raw
  import check, an export, a store-wide projection.
- **An OR-of-facts read does not use the tag index at all.** `query-by-or-groups` (five items, each a
  type set plus a `sku:` tag) plans as an ordered index scan on `idx_events_stream_position` with the
  whole disjunction as a `Filter` — 2.196 rows discarded to return 500. The tag index serves a single
  containment predicate, not a disjunction of them, which is the read-side counterpart of the
  or-groups cliff on the append side.
- **The savepoint probe's two plans are 30× apart, and the server currently picks the right one.**
  `query-last-event` (`.backwards().limit(1)`, type + tag) has a custom plan that is an index scan
  backward over `idx_events_stream_type_position` — 6 buffers, 0.03ms — and a generic plan that is a
  `BitmapAnd` of the tag and stream-type indexes, 231 buffers and 1.0ms. The custom plan's estimate is
  16× cheaper, so PostgreSQL keeps it; the measurement (0.074 ms/op) agrees. Worth knowing that the
  most common read in a DCB application has a 30× worse plan one statistics change away.

**The in-memory backends are an unindexed linear scan, so they are not a "fast store" the database
should beat.** `InMemoryEventStorageImpl` holds a `List` and matches in Java, with `Stream.limit` on
top: its cost is *how far into the log the scan walks*, not how many events come back. At 100.000
events that makes it **31× slower than PostgreSQL on a needle tag query** (0.155 against 4.886 ops/ms)
and **78× slower reading one long-tail entity's history** (0.104 against 8.064), while still being
3–90× *faster* on the shapes where a limit fills immediately — a page, a wildcard read, `getEventById`.
The rule that fits every row is that a limit only helps when the matches are dense enough to fill it
early.

- **What this means in practice**: an application prototyped against the in-memory store learns
  nothing about what its tag queries will cost in production, and learns it backwards. Selective tag
  queries are the case Postgres's GIN index exists for, and the case the in-memory store is worst at.
- It also means the two backends converge on any read returning thousands of events —
  `query-by-entity-hot` is 0.054 against 0.046 — because at that size both are paying the same ~2µs
  per event and the storage difference washes out. That is the same figure arrived at from the other
  side.
- The in-memory backends stay the right thing for tests and for the TCK. They are a correctness
  substitute, never a performance one.

### What five other domains in the same table cost

`crowded-store` is `read-shapes` over a store holding five further bounded contexts at five times
the volume — 100.000 events under test inside a 600.000-event table. **Ten of the twelve read
shapes do not move at all** (0.93–1.18× against the control, inside the run-to-run band). Two
collapse, and the captured plans say why in a way the throughputs alone would not.

**A tag's selectivity is a property of the table, not of your context.** `idx_events_tags` is not
stream-scoped, so a bitmap scan on it returns every context's events carrying that tag and the
stream scoping only prunes afterwards. `sku:SKU-000000` identifies **6.876** events in `inventory`
and **40.227** in the crowded table, because catalog, payments and shipping tag their events with
`sku:` too — realistically, since those events really are about a SKU.

**That flips `query-by-or-groups` from a limit-bounded plan to a full-materialisation one**, which
is the whole 5.4× (0.347 → 0.064 ops/ms):

| | `read-shapes` | `crowded-store` |
|---|---|---|
| plan | ordered index scan on `idx_events_stream_position`, OR as a `Filter` | `BitmapOr` of five `BitmapAnd`s over `idx_events_tags` + `idx_events_stream_type_position` |
| rows touched to return 500 | 2.196 discarded | **11.122 materialised** |
| buffers | 129 | **4.139** |
| server time | 1.7 ms | 15.4 ms |

The first plan walks the stream in order and stops when the limit fills; the second computes the
whole bitmap union and then top-N sorts it. **`EventQuery.limit(n)` only bounds work while the plan
is one the limit can stop early**, and which plan that is depends on what else is in the table.

**The savepoint probe survived, and its bad plan got much worse.** `query-last-event`'s custom plan
is unchanged — index scan backward, 6 buffers, 0.029ms — and the server still keeps it, so the
measurement is unharmed (14.819 against the control's 13.489). But its *generic* plan now pulls
those same 40.227 rows through the bare tag index: 239 buffers and 4.43ms, against 231 buffers and
1.007ms uncrowded. The gap between the plan PostgreSQL picks and the one it might pick widens from
**30× to 153×** — so crowding a table does not slow the most common DCB read down, it enlarges the
blast radius of a statistics change that would.

**A wildcard read costs the size of the table, confirmed.** 0.051 → 0.013 ops/ms (19.6 → 76.9
ms/op) for a 6× bigger table: parallel sequential scan over all 600.000 rows, 23.731 buffers, and
JIT compilation on top. Sub-linear only because two parallel workers absorb some of it.

**The caveat that keeps this honest: the noise is written as contiguous blocks after the context
under test** (`CorpusGenerator` generates inventory, then sales, then each noise context in turn),
so `inventory`'s heap pages and its range of every stream-scoped index are untouched — the hot
entity reads 1.998 heap blocks in both corpora, identically. A real store accumulating six domains
over time would interleave them, adding heap scatter and index bloat *within* the range this
corpus leaves pristine. So read the ten unchanged workloads as "sharing a table costs nothing that
a stream-scoped index can prune", not as "sharing a table is free".

### What three million-event stores in the same database cost

`crowded-database` is the other half of that pair, and it isolates the mechanism `crowded-store`
cannot: the context under test is the *same* 100.000-event `CLEAN` corpus as `read-shapes`, in its
own tables, with three further stores of 1.000.000 events each under their own prefixes in the same
database. Nothing is shared but the cluster — shared buffers, WAL, autovacuum, the notification
queue, `pg_snapshot_xmin`.

**The answer is nothing measurable.** All twelve read shapes land inside the run-to-run band against
the `read-shapes` control (0.94–1.18×, and the two ends of that are the needle tag query and
`query-by-id`, both of which move that much between two runs of the *same* profile). The wildcard
read — the one shape whose cost is the size of the table — is 0.051 against 0.051, which is the row
that says the neighbours really are in different tables: it scans the store under test and never
touches them.

**The captured plans confirm it behaves as the control rather than merely scoring like it.**
`query-by-or-groups` gets the ordered `idx_events_stream_position` scan with the disjunction as a
`Filter`, 2.196 rows discarded and 129 buffers — the `read-shapes` plan, not the `BitmapOr` that
`crowded-store` flips to. `query-last-event`'s *generic* plan pulls 6.876 rows through
`idx_events_tags` (231 buffers), against 40.227 in `crowded-store`. So the or-groups collapse and
the widened savepoint blast radius in the previous section are attributable to **events of other
domains in the same table**, and not to table size, index size, or the presence of other stores.

**The caveat is the important part: the neighbours are idle.** They are written once during
provisioning and never read or written again, so none of the mechanisms this profile names is
actually exercised — nothing of theirs competes for shared buffers, nothing dirties pages, autovacuum
has nothing to do, and no transaction of theirs holds an xid. What is established is that a store
does not pay for *coexisting* with large neighbours. A busy neighbour is a different question, and
the one to keep in mind is the `pg_snapshot_xmin` stall documented in the postgres module's `CLAUDE.md`: a
long-running **writing** transaction in a neighbouring store — or in another database of the same
cluster — freezes what this store can read, and no amount of table separation prevents it.

### Reading at ten million events, and what the writes run got wrong

`large-tier` is the first profile whose numbers are publishable — an external, deliberately
configured PG18 rather than a container — and it is compared against `read-shapes-ext` on the same
machine and the same server, so the ratio is attributable to volume and nothing else.

**Eight of eleven read shapes do not move at a hundred times the volume**, all at 2–6% relative
error: `query-stream-page` 1.00×, `query-by-id` 0.98×, `query-by-type` 0.98×, `query-by-entity-cold`
0.97×, `query-cursor-walk` 0.97×, `query-last-event` 0.94×, `query-by-tag-needle` **0.88×**,
`query-by-or-groups` 1.08×. The needle query is the one this tier exists to check, and the index
holds.

**The ~2µs per event holds across two orders of magnitude.** `query-by-entity-hot` returns 6.876
events at 100.000 and **455.092** at 10.000.000 — 66× the rows for 79× the time, so **3.07 µs/event
against 3.66 µs/event**. The hot-entity read is linear in rows returned, not in store size, which is
the same conclusion the medium tier reached from the other side and is now measured on real hardware.

The three shapes that do scale each have a cause, and two are in the plans:

- **`query-by-entity-hot` falls off three `work_mem` and `jit` cliffs at once**, not off the index:
  the bitmap goes **lossy** (`exact=19090 lossy=43315`, `Rows Removed by Index Recheck: 1070109`)
  against a 4 MB `work_mem`, the sort spills to disk (`external merge Disk: 38112kB`), and JIT adds
  **205 ms of the 580 ms**. The per-event arithmetic above is what says the index is fine.
- **`query-by-tag-swathe` (0.38×) changed plan, correctly.** At 100.000 the swathe is 1.000 matches
  and PostgreSQL bitmaps all of them off `idx_events_stream_tags` — 1.037 buffers, every one a cache
  **hit**. At 10.000.000 it is 100.000 matches, so the planner walks `idx_events_stream_position` and
  filters instead, discarding 26.973 rows to return 500, with 1.199 of 1.279 buffers **read** rather
  than hit. The right plan on a table far past 160 MB of `shared_buffers`.
- **`query-by-multi-tag` (0.18×) is unexplained**, and no plan in the report covers it.

**External runs now capture plans, and the first one that did overturned a finding.** Both
`ReadPlanCapture` and `AppendPlanCapture` used to read a Testcontainers log, so they were inert on
`EXTERNAL` — the only target the publisher accepts — and every plan in every publishable baseline was
a *reconstruction*. `ServerLog` reads the server's own log file through `pg_read_binary_file` instead,
with `AutoExplain` turning the module on for the run, so an external target's plans are the store's own
statements. What it costs is a privilege chain on the benchmark host, and `doctor` now fetches a plan
end to end rather than checking the links, so a missing piece is named before an hour-long run rather
than after one:

```sql
GRANT pg_monitor TO <role>;                                    -- pg_current_logfile()
GRANT EXECUTE ON FUNCTION pg_stat_file(text, boolean) TO <role>;
GRANT EXECUTE ON FUNCTION pg_read_binary_file(text, bigint, bigint, boolean) TO <role>;
GRANT pg_read_server_files TO <role>;                          -- Debian/Ubuntu: log_directory outside PGDATA
GRANT SET ON PARAMETER session_preload_libraries, auto_explain.log_min_duration,
      auto_explain.log_analyze, auto_explain.log_buffers, auto_explain.log_timing,
      auto_explain.log_format, auto_explain.log_nested_statements TO <role>;
```

Two of those are worth remembering because they mislead. `pg_read_server_files` governs *which paths*
may be read, not whether the functions may be called — a role holding it still gets `permission denied
for function pg_read_binary_file` without the `EXECUTE` grants. And `auto_explain.*` are *placeholder*
GUCs until the module loads, so each needs its own `GRANT SET ON PARAMETER`; granting
`session_preload_libraries` alone gets you as far as the next refusal.

**`large-tier-writes` as first published was not a measurement, for two independent reasons**, both
since fixed — the figures below come from the re-run:

- Three of its six rows sit past the 10% the report calls uncomparable — `append-type-and-tag` at
  **121%** and 55%, `decide-then-append` at 24%. Publishing now refuses this; it did not then.
- **Its one-thread against eight-thread comparison was measuring the entity distribution, not
  concurrency.** `WorkloadContext.rotation` was a field on a context JMH rebuilds per iteration, so
  the entity walk restarted at the head of a steeply skewed corpus twelve times a trial. At one
  thread `decide-then-append` completed **39 operations across twelve iterations** — three each, so
  entities 0, 1 and 2 nearly every time, and entity 0 holds 455.092 events at ~1.6 s a read. At eight
  threads the same budget covered entities 0–7 and beyond, diluting the hot entity from about a third
  of operations to about 1/185th. That ratio *is* the 71× the report shows between one thread and
  eight; eight threads cannot make one operation seventy times faster. The counter now has the
  lifetime of the trial (`ThreadContext`), so a slow workload samples the distribution instead of
  re-drawing its head. The defect only bites where a trial completes tens of operations rather than
  tens of thousands, which is why the medium tier never showed it.

**Drift came in at 1.12%**, under even the default 2%, so the 10% cap the profile declares has never
been needed — it was sized against an assumed ~25 ops/ms at eight writers where the real figure is 4.7.
And `append-none` scales only 3.01 → 4.69 ops/ms from one writer to eight (1.56×) against 11.3 → 33.5
(3×) at 100.000 on a container. An unconditional append takes no advisory lock, so that ceiling is GIN
maintenance and WAL at ten million rows — the most interesting thing in the writes profile, and it
deserves measuring on purpose rather than as a control.

#### How the DCB check got its shape: four runs at this tier, and what each retired

**This corpus is where the check's SQL shape was settled.** The library now derives the shape from
the criteria per append — an ordered probe walking `idx_events_stream_position` forward from the
cursor when the criteria carry one, the tag path planned from its bound values when they do not —
and there is no mode to configure. That design was not reasoned out; it was forced, one run at a
time, and each run's report is committed as the record. The sequence, because every step retired
something plausible:

**Run one (historical `NOT EXISTS`, three targets): a ~190-220× check and a remedy that was not
one.** A conditional append carrying one type and one tag measured 66.03 ms/op against 0.328 ms/op
unconditional at one writer, with 47–147% relative error. The captured plans said why:

| | custom | generic |
|---|---|---|
| access path | Bitmap Heap Scan via `idx_events_stream_tags` | **Seq Scan** |
| cursor `ROW(event_tx, event_position) >` | in the **Filter** | in the Filter |
| tag | in the Recheck Cond | in the Filter |
| rows discarded | 16.806 | **10.000.008** |
| buffers | 19.965 (`exact=16125` heap blocks) | 433.299 |
| actual time | **44.4 ms** | **1251 ms** |
| planner's estimate | cost **130.35** | cost 250.21 |

- The custom plan was the one in effect, and PostgreSQL was right to keep it: binding the tag value
  sends the planner to `idx_events_stream_tags`, and the cursor demotes from a start condition to a
  filter — so the check exhausted *the entity's whole history* (455.092 events on the head entity)
  instead of the events after the cursor. That materialisation was the ~190×.
- A `FORCE_GENERIC` mode had been built on a misread capture (an index-scan generic plan that
  belonged to `append-types`, the types-only check). Measured, it was **1248.50 ms/op — 20× worse**,
  matching its captured Seq Scan almost exactly, and it was removed. The suite's clearest negative
  result: reading a ratio off two plans is not measuring it.
- A `PER_APPEND` planning mode changed nothing here — it forces the custom plan, which was the plan
  already in effect.

**Run two (the ordered probe, on a branch target): 5.5× better on every cursor-bearing row.** The
rewrite that actually addresses the materialisation is not a planning mode but a different check:
`(SELECT event_position ... WHERE stream and cursor and filter ORDER BY event_tx, event_position
LIMIT 1) IS NULL` walks the position index *forward from the cursor* and stops at the first match,
so its cost is the events after the cursor (~0.2µs per row walked) rather than the entity's
history. `append-type-and-tag` went 66 → 11.93 ms/op — and the 47–147% error bars collapsed to
under 10%, which also settled the variance question run one could not: the scatter was the plan
cache and the entity ramp *under a plan that materialises history*, and the probe removed both at
once.

**Run three (`dcb-boundary-staleness`): what `NOT EXISTS` still owned, it forfeited to the plan
cache.** The probe needs a cursor to start from, so the no-cursor case — the uniqueness pattern,
"this basket was never checked out", criteria with `Optional.empty()` — stays on the tag path. Left
as a reused prepared statement, that path's steady state was the **1164 ms/op sequential scan** on
stale and empty boundaries while a 0.06 ms custom plan sat unused: `NOT EXISTS` is priced by
expected-first-row, DCB expects *no* row, and from the tenth execution the generic plan wins the
estimate and loses the store. Forcing the no-cursor statement to plan from its bound values every
time (`setPrepareThreshold(0)`) took the empty boundary from 1164 to **2.42 ms/op** — per-execution
planning is noise against a 486× plan defect.

**Run four (validation of the criteria-shaped check, now the shipped behaviour):** fresh boundary
~12 ms/op (the probe finding ~170 rows after the cursor), stale boundary ~605 ms/op (2.75M rows
walked, linear and predictable at under 3% error — the probe's one accepted cost, avoided by
re-reading the boundary before appending, which the decide-then-append cycle does anyway), empty
boundary ~2.4 ms/op. **~37× an unconditional append on this corpus's boundaries, against the
~190-220× it replaced**, at error bars that finally describe the store. The or-groups cliff the
plan-cache profiles measured under `NOT EXISTS` (14× at two facts) is absent under the probe (2.62×
at ten facts), which is why those two profiles are gone: their question is closed. Their scratch
reports were never published and did not survive, so the figures in this section are the record of
runs two and three; what is committed under `results/` is the old shape's regime (the
`-not-exists`-suffixed baselines, kept under that name so a re-publish of the shipped check cannot
overwrite the before half of the comparison) beside the shipped check's own `large-tier-writes` and
`dcb-boundary-staleness` baselines.

Two findings from run one that are about the workload rather than the check, still standing:

- **`decide-then-append` at this tier is 94–99% its decide read**, not its append. The read returns
  455.092 rows and falls off three cliffs at once — lossy bitmap (`exact=17849 lossy=44563`), an
  `external merge Disk: 38120kB` sort, and 190.7 ms of JIT. No check shape helps there; bounding
  the read does.
- **The 1-thread against 8-thread gaps here are entity coverage, not concurrency**, the same effect
  the `ThreadContext` fix addressed. Permuting the entity walk so each trial samples the
  distribution rather than re-drawing its head remains outstanding — though the probe's tight error
  bars have already answered the question that walk was going to separate.

### Choosing a stream design: one stream per context, or one per entity

The question every application author has to answer first — a stream per bounded context with
entities told apart by tags (`inventory/default`, every event tagged `sku:`), or a stream per entity
with the id as the purpose (`inventory/WIDGET-42`). Both are ordinary uses of `EventStreamId`, and
until now the answer here was a guess. `stream-design-tagged` and `stream-design-per-entity` are the
same corpus in every property but that one — 100.000 events, `CLEAN`, `REALISTIC`, 2000 entities,
one seed — so the difference between them is attributable.

**Per-entity wins or ties everything except reading a context in order.** Measured per-entity ÷
tagged, PG18, 0.00% store drift on both sides, with `append-none` as the control that must not move
(1.04× at one thread, 0.98× at eight):

| workload | 1 thread | 8 threads |
|---|---|---|
| `append-type-and-tag` (the canonical DCB check) | 4.2× | **16.8×** |
| `decide-then-append` | 1.4× | 2.3× |
| `query-by-stream-cold` | 2.4× | 2.2× |
| `query-by-tag-needle` | 1.5× | 1.4× |
| `query-by-stream-hot` | 1.2× | 1.2× |
| `query-last-event-by-stream` | 1.1× | 1.2× |
| `query-by-tag-swathe` | 1.1× | 1.0× |
| **`query-stream-page`** (matchAll, 500 events, in order) | **0.074** | **0.066** |

- **The 16.8× on conditional appends is the advisory lock, not the index.** Appends are serialized
  per stream by `pg_advisory_xact_lock` keyed on `(prefix, context, purpose)` — so under `tagged`
  every conditional append in a context takes the *same* lock and eight writers take turns, while
  under `per-entity` writers to different entities take different locks and do not meet. The gap is
  4.2× single-threaded and grows with writers, which is the signature of contention rather than of a
  cheaper plan.
- **The one real cost is paging a context in order: 13–15× slower.** That is not an artefact and
  there is no addressing trick that recovers it — under `per-entity`, reading a whole context *is* a
  cross-entity read, so `stream_purpose` is unbound, and it is the second column of both
  `idx_events_stream_position` and `idx_events_stream_tags`. An ordered read loses its start
  condition and the `LIMIT` cannot be pushed into the scan. Anything shaped like `query-stream-page`
  is expected to pay it — a whole-context replay, a `Projector` over a context, an export — though
  only the page is measured: `replay-batches` is not in these two profiles, so the replay cost is
  inferred from the shape it shares rather than observed. Weigh the trade against how often the
  application reads a whole context versus how often it reads or writes one entity.
- **Read one entity through its own stream, or the design buys you nothing.** This is the trap, and
  it is entirely in the calling code: `EventStreamId.forContext("inventory")` with a wildcard purpose
  addresses a per-entity corpus *the tagged way* and lands in exactly the unbound-column-2 case
  above. Measured on the same per-entity corpus, the savepoint probe (`.backwards().limit(1)`) is
  **0.562 ops/ms addressed by tag against 16.183 addressed by stream** at one thread, and 2.938
  against 67.448 at eight — 23–29×. Read by tag it looks like a 24× regression against `tagged`; read
  by stream it is ahead. Choosing `per-entity` and then querying by tag is the worst of both.
- **The suite guards that distinction rather than trusting it.** Each `query-by-entity-*` /
  `query-by-stream-*` pair is the *same* query asked two ways, so on the `tagged` corpus the two must
  report the same number — a gap there is a harness fault, not a finding. They do agree (0.046/0.047,
  8.062/8.517, 13.915/14.387 at one thread), which is what licenses reading the per-entity gap as the
  cost of addressing.
- **Not measured here, and it is the real bill for `per-entity`:** 2000 distinct purposes is past the
  default `MeterOptions.maxPurposeTagValues()` cap of 1000, so a store with metrics on pools the tail
  under `_other`. Both profiles run with metrics **off** deliberately, so this comparison says nothing
  about that cost — `metrics-cost` is where it is measured rather than assumed. See the metrics
  section above for what a distinct purpose costs.

**Caveats, in the suite's own terms.** These are Testcontainers runs on a developer machine:
direction and rough magnitude, deliberately not published under `results/` — the publisher refuses a
Testcontainers run for exactly this reason. Several per-entity figures carry 29–36% relative error
(`append-type-and-tag` at one thread is 7.069 ± 2.042; `query-by-stream-cold` at eight is 76.944 ±
28.053), so read those as "clearly faster" rather than as ratios to two digits. The `append-*`
workloads grow the store 165–172% within an iteration on **both** sides, which is why the pair is
compared and neither figure is quoted alone. The entity count at which the answer flips is not
established: 2000 is one point on that curve, and the corpus knob to move is `entityCount`.

### What a shared append lock costs, and what a shared boundary costs

The three `write-contention-*` profiles are one corpus (100.000 events, `PER_ENTITY`, 2000
entities) driven three ways, so the difference between them is where the writers meet: `spread`
gives each thread its own entity, hence its own stream and its own advisory lock; `one-stream`
draws the *same* rotation of entities and writes every append into the hot entity's stream, so the
lock is shared and no two appends conflict; `one-boundary` puts every thread on the hot entity, so
they share the lock and the boundary. PG18, metrics off, `append-none` as the control that must not
move (11.3 / 11.6 / 10.9 at one thread; 33.5 / 34.2 / 33.6 at eight — it does not).

`append-type-and-tag`, the canonical DCB check, in ops/ms:

| threads | `spread` | `one-stream` | `one-boundary` | `one-boundary` useful |
|---|---|---|---|---|
| 1 | 5.450 | 1.303 | 8.150 | 8.150 |
| 4 | 16.982 | 1.358 | 6.399 | 6.09 |
| 8 | 24.058 | 1.394 | 4.657 | 4.02 |
| 16 | 23.587 | 1.335 | 1.241 | 0.22 |

- **A shared lock does not slow an append down; it stops throughput scaling at all.** Spread
  writers go 5.45 → 24.06 from one to eight threads (4.4×, saturating at sixteen); writers sharing
  one stream's lock go 1.30 → 1.39 (1.07×) across the same sixteen-fold increase. That flatness is
  the ceiling the advisory-lock note above warns about, and it is the half of that note the suite
  can now put a number on. The `~5%` in it is a different quantity — what the lock costs when
  nothing contends for it — and measuring that needs a build with the lock removed, which is why it
  stays a recorded observation.
- **Read the one-thread ordering as the arrangement, not the lock** — with one writer nothing
  contends, so what separates 1.30 from 5.45 from 8.15 ops/ms is *where each profile addresses its
  append*, and the captured plans now say so directly rather than by elimination. Each profile
  explains the append it actually issues (the capture used to be hardwired to `spread`, so all three
  came back byte-identical while their throughputs differed fourfold — a harness fault, now fixed;
  a plan heading names the mode it was captured under). These captures predate the criteria-shaped
  check — a cursor-bearing append now runs the ordered probe rather than `NOT EXISTS` — but the
  arrangement story carries over unchanged, because what separates the three is where the scan
  starts relative to the cursor, and the probe reads the same predicate:
  - **`spread`** — stream and tag are the same entity, and the cursor is that entity's own last
    event. The `NOT EXISTS` starts at the cursor and stops: no rows removed, 3 buffers, **0.017ms**.
  - **`one-stream`** — the artificial one, and it shows: the append is addressed at the hot entity's
    stream while the tag and the cursor come from the *rotated* entity, so the scan starts at a
    cursor belonging to a different entity and walks the hot stream's rows to reach the tag —
    `Rows Removed by Filter: 13`, 9 buffers, **0.059ms** (70 rows and 24 buffers on
    `decide-then-append`). That is the cost of a boundary check hunting one SKU inside a stream every
    thread is growing, and it is the whole of the one-thread gap. Its *custom* plan even changes
    shape — a bitmap index scan on `idx_events_stream_tags`, cost 52.03 against the generic plan's
    15.57 — so this is the one arrangement where the two plans disagree.
  - **`one-boundary`** — stream, tag and cursor are all the hot entity, and the cursor is the
    boundary the writer itself just wrote, so it sits at the head of the index (`7349218/100008`, not
    the corpus midpoint). Nothing to walk, nothing removed, **0.027ms**. That is why it is *faster*
    than `spread` at one thread despite dumping into one stream, and it is what the earlier write-up
    called unestablished.
  - **`decide-then-append` is a read cost, not an append cost.** On `one-boundary` at one thread,
    where nothing conflicts, it measures **22.30 ms/op** against an append statement the server
    executes in **0.223ms** — so ~99% of it is the decide read. That is what "it re-reads the
    boundary every writer is growing" costs when the writer growing it is you.
- **The trio was re-run against the fixed capture and reproduces**: `append-type-and-tag` at
  1/4/8/16 threads came back 5.77 / 16.91 / 23.91 / 23.44 (`spread`), 1.36 / 1.37 / 1.42 / 1.34
  (`one-stream`) and 8.85 / 6.30 / 4.76 / 1.23 (`one-boundary`), with the control flat and the
  one-boundary conflict rate again 0% → 5.0% → 13.4% → 82%. The table above is the first run; the
  second agrees within its error bars.
- **At one shared boundary, adding writers makes the system strictly worse.** Not slower per
  operation — worse in work done: useful appends fall 8.15 → 0.22 ops/ms from one writer to
  sixteen, a 37× collapse, while the conflict rate climbs 0% → 4.9% → 13.6% → **82%**. Throughput
  alone hides it (1.241 ops/ms at sixteen threads still looks like work), which is exactly why the
  report carries a useful/s column beside the score. `decide-then-append` is worse still —
  0.042 → 0.222 ops/ms at 93% conflicts, so 0.042 → 0.016 useful — because it re-reads the whole
  boundary each time and that boundary is the thing every writer is growing.
- **What an application does with this**: a coupon redeemed at most N times, a counter, a single
  aggregate everyone touches — a boundary that hot has a one-writer ceiling, and more instances
  behind it buy nothing. Widening the boundary (one per basket rather than one per coupon) is the
  fix; the lock, by contrast, is bought off by the stream layout, which is what `PER_ENTITY` above
  is for.
- **Same caveats as the stream-design pair**: Testcontainers on a developer machine, so direction
  and magnitude rather than publishable figures, and both profiles grow the store 68–69% within an
  iteration. The relative errors here are tighter than in that pair (2–10%), and the comparator
  reports the `spread` → `one-stream` gap as outside both error bars at every thread count.
