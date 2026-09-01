# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in **`sliceworkz-eventstore-infra-postgres`**.
These are the deep operational notes for the PostgreSQL backend -- ordering and boundary semantics,
locks, schema and trigger repair, migrations, diagnosis SQL, measured plan behaviour. The root
`CLAUDE.md` carries only a digest of the facts that bind outside this module.

## PostgreSQL Specific Notes

- Table schema can be prefixed (useful for multi-tenancy or isolation)
- Database initialization performed via `.initializeDatabase()` on builder
- Uses HikariCP for connection pooling
- Separate DataSource for monitoring queries (optional, defaults to main DataSource)
- Tests use Testcontainers for isolated PostgreSQL instances
- Requires the `btree_gin` extension (a standard contrib extension, available on the major managed Postgres offerings). Schema initialization installs it and schema validation requires `idx_events_stream_tags`, a combined stream+tags GIN index that serves DCB reads scoping by stream *and* filtering by tags in one index. The B-tree indexes are retained for ordered stream replay (GIN cannot serve `ORDER BY`)
- **Installing that extension needs `CREATE` on the *database*, which is not `CREATE` on the schema.**
  `btree_gin` is *trusted* (PG13+), so no superuser is involved — but a role granted `CREATE` on its
  schema and nothing on the database, the ordinary locked-down deployment, creates every table, index,
  function and trigger here and then cannot create the extension. Because the schema scripts are one
  transaction, that is not a missing index: the whole schema rolls back and the store does not start.
  See "Database privileges" in the postgres module's README for the table of what each
  `DatabaseInitMode` needs.
  - **The DBA-installs-it-once split is the recommended answer, and it costs the application role
    nothing afterwards.** `ensure-schema.sql` pre-checks `pg_extension` and skips the statement
    entirely when the extension is present, so an unprivileged role starts against it indefinitely —
    not even a `NOTICE`. (A bare `CREATE EXTENSION IF NOT EXISTS` would also have worked, since
    PostgreSQL's `IF NOT EXISTS` short-circuit precedes its privilege check, but it puts a `NOTICE` in
    every startup log and issues DDL a `VALIDATE`-style deployment has no business issuing.)
  - **The remaining failure names its remedies.** A `RAISE` in the `insufficient_privilege` handler
    reports the extension, the privilege distinction, and both ways out (`CREATE EXTENSION btree_gin`
    once, or `GRANT CREATE ON DATABASE`), instead of a bare `permission denied to create extension`
    wrapped in `Failed to execute database script`.
  - **The schema advisory lock does not cover this statement**, because it is keyed on the table prefix
    while an extension is database-scoped. Two stores with *different* prefixes starting together on a
    database without `btree_gin` therefore raced on `pg_extension_name_index` exactly as the tables
    used to race on `pg_type_typname_nsp_index` — the loser rolling its whole schema back. The block
    swallows `duplicate_object` and `unique_violation`: by the time either surfaces the winner has
    committed (a conflicting catalog insert blocks until it does), so the opclasses the index needs are
    visible to the loser's transaction, and it goes on to create its index.
  - **Where the extension lives is not a constraint.** `CREATE EXTENSION btree_gin SCHEMA extensions`,
    the convention on several managed offerings, serves the index with no `search_path` change and no
    `USAGE` grant on that schema — default operator class resolution is not `search_path`-filtered.
  - `PostgresBtreeGinPrivilegeTest` pins all four down per backend (16, 17, 18).
- **Ordering is the `(event_tx, event_position)` tuple everywhere — reads, the `until` boundary, and the
  optimistic-locking check.** `event_position` is a `bigserial` taken at insert time and `event_tx` is
  `pg_current_xact_id()`, assigned independently, so the two orders genuinely disagree: a transaction can
  carry a lower position and a higher tx than one that committed before it. The read path's
  `pg_snapshot_xmin` barrier makes that ordinary rather than exotic — it withholds an event whose
  transaction is still in flight, so a reader takes a reference and the event surfaces afterwards, sorting
  later on a lower position. Any predicate over the log's order goes through `addCursorBoundary` or
  `addUntilBoundary`; comparing `event_position` alone is a different order and silently drops events.
  Writers that do not hold the append lock (unconditional appends, `importEvents`, raw SQL) are where the
  inversion actually comes from, which is why the advisory lock below does not subsume this
- **That tuple comparison is written as a SQL row constructor — `(event_tx, event_position) > (?, ?)` —
  and the lexicographic expansion it is equivalent to is several times slower.** SQL defines the two as
  the same predicate, so nothing about what a boundary *means* depends on the spelling. What depends on
  it is whether PostgreSQL can use it: a boundary is nearly always conjoined with `stream_context = ?
  AND stream_purpose = ?`, and `idx_events_stream_position` is `(stream_context, stream_purpose,
  event_tx, event_position)`, so with the leading two pinned by equality a row comparison over the
  trailing two becomes an index **start condition** — descend once to the cursor, walk the leaves in
  order — and a page costs what the page returns. A disjunction is not a start condition and lands in a
  `Filter`.
  - **Measured** (PG18, 100.000-event `TAGGED` corpus, cursor at the midpoint, one page of 500). The
    expansion still used the index, but only for the stream columns, and then discarded every event
    before the cursor to reach page one: `Rows Removed by Filter: 27500`, 1295 buffers, 3.344ms. The row
    comparison starts the scan at the cursor: nothing filtered, 27 buffers, 0.165ms. End to end that was
    **2.9× on a five-page cursor walk and 2.0× on a projector run**, with `query-stream-page` and
    `append-none` — the shapes carrying no boundary — unmoved, which is what says the difference is the
    boundary and not the machine.
  - **The cost of the expansion grows with how deep the cursor already sits**, not with the page size.
    That is the wrong shape for the way `Projector` pages, and it grows silently: every result is
    correct, so only a plan shows it.
  - **It also improves the plans the server *caches* for the DCB check**, which was not what it was for.
    A conditional append's `NOT EXISTS` carries the same boundary, and under the expansion the *generic*
    plan for the canonical `type + tag` check was a whole-table sequential scan (19.5ms, 100.008 rows
    discarded for a row that is not there); with the row comparison it is an index scan (0.23ms). An
    or-groups cliff remains from two facts up — that one is the tag disjunction, not the cursor —
    and whether it extends past three varies run to run; see the plan-cache section below.
  - **There is no setting for this, deliberately.** The two spellings are semantically identical, so no
    deployment can want the slower one, and a knob whose right value is the same everywhere is an
    unmade decision left in the code. `PostgresCursorBoundaryTest` guards it per backend (16, 17, 18):
    the boundary must reach the `Index Cond`, with no `BitmapOr` and no `Sort` above the scan. That test
    exists because the expansion is what anyone rewriting this would naturally reach for, and nothing
    else would notice.
  - **A related trap in the same statement**: the read path's `ORDER BY event_tx::xid8, event_position`
    looks redundantly cast and is not. The select list projects `event_tx::text`, which PostgreSQL names
    `event_tx`, and SQL resolves a bare name in `ORDER BY` to an **output** column before it looks at the
    table — so `ORDER BY event_tx` sorts by the *text* rendering of a transaction id (`'9'` after `'10'`)
    on an expression no index can supply, putting a `Sort` above every ordered read. Written as an
    expression the name cannot be captured. Do not "simplify" that cast away
- **A long-running *writing* transaction anywhere in the cluster freezes what this store can read.** The
  barrier above is `event_tx < pg_snapshot_xmin(pg_current_snapshot())`, and `pg_snapshot_xmin` is the
  oldest transaction id still running — a property of the whole PostgreSQL cluster, not of this store.
  Every event appended since the oldest open transaction took its id is invisible here until that
  transaction ends. **Nothing fails and nothing is logged**: reads just stop advancing, projections go
  quiet, bookmarks stop moving, `SELECT count(*)` in psql shows the events are there, and when the
  blocker finally ends everything appears at once. `PostgresVisibilityStallTest` demonstrates it end to
  end.
  - **Only transactions that have *written* count** — this is what makes the hazard narrow rather than
    severe, and it is worth being precise about. PostgreSQL assigns a transaction id lazily, at the
    first write, and only assigned ids enter a snapshot's xmin. A read-only transaction pins nothing,
    however long it runs, at **any** isolation level including SERIALIZABLE, and so does an `idle in
    transaction` connection that only ever read. So `pg_dump`, reporting queries, analytics reads and a
    replica feed are all harmless. What is not harmless: a batch job, an ETL run, a migration, or an
    `idle in transaction` connection that wrote before going idle. `SELECT … FOR UPDATE` and an explicit
    `pg_current_xact_id()` also assign an id without writing a row.
  - **The blocker does not have to touch the events table, or even this database.** Transaction ids are
    cluster-wide, so a writer in a *different database of the same cluster* pins this store's barrier
    just as effectively. Verified on PG17 and PG18. The operational rule is therefore "do not share a
    cluster with long-running write transactions", not "do not share a table".
  - **Read-your-own-writes does not hold** while a blocker is open: a caller can append successfully and
    not read the event back. Under DCB that surfaces as an optimistic-locking conflict a retry loop
    **cannot clear**. The append-side `NOT EXISTS` check deliberately carries no `xmin` filter, so it
    sees committed events the reader cannot; a decider re-reads its boundary, gets the same stale
    reference, appends, and conflicts again — for as long as the stall lasts. Not spurious, exactly:
    there really is a new relevant fact, it is just being withheld from the one party that needs it.
  - **The append advisory lock does not compound this.** A transaction that has only taken
    `pg_advisory_xact_lock` holds no transaction id, so neither the lock holder before its INSERT nor
    any appender blocked behind it pins the barrier. Only the INSERT itself does, for its own duration.
  - **Diagnosing it.** `backend_xid IS NOT NULL` is the whole predicate — filtering on `state <> 'idle'`
    or on `xact_start` age reports harmless read-only sessions as suspects:
    ```sql
    SELECT pg_snapshot_xmin(pg_current_snapshot());          -- the barrier
    SELECT pid, datname, usename, application_name, state,
           now() - xact_start AS held_for, backend_xid, query
    FROM   pg_stat_activity
    WHERE  backend_xid IS NOT NULL                            -- only these can stall the store
    ORDER  BY xact_start;                                     -- the oldest is the culprit
    ```
    Deliberately unfiltered by `datname`: the culprit may be in another database of the cluster. Run it
    as a superuser or as a member of `pg_read_all_stats` — `xact_start`, `query` and `state` come back
    NULL for other roles' sessions otherwise, and you get a row identifying the blocker with no way to
    tell how old it is or what it is doing. (`backend_xid` is readable regardless, so the `WHERE` clause
    still selects the right rows for any role.)
  - **The library does not meter this**, and there is nothing in the `sliceworkz.eventstore.*` meters
    that reveals it — they count and time the calls the store makes, all of which keep succeeding
    throughout a stall. Detection is therefore external, on the database, using the query above.
    Two notes for whoever wires that up:
    - **Watch `pg_snapshot_xmin` standing still *while* something holds a transaction id**, not either
      alone. xmin also stops moving on a completely idle database, so "xmin has not advanced" on its own
      fires on every quiet store; "a transaction holds an xid" on its own fires on every append in
      flight. It is the combination that means events are being withheld.
    - **`now() - xact_start` reads zero for an ordinary application role.** `pg_stat_activity` blanks
      the columns describing another role's session for anyone who is not a superuser or a member of
      `pg_read_all_stats`, and blanks them to NULL rather than refusing — so the natural "age of the
      oldest blocking transaction" check reports a confident all-clear right through a stall another
      role is causing. `backend_xid` is *not* blanked, so a count of blocking transactions does survive
      on ordinary privileges; the age does not. Either grant `pg_read_all_stats`, or time the staleness
      of `pg_snapshot_xmin` from outside instead of asking the server how old the blocker is.
    - Do **not** measure the effect by counting withheld events: `count(*) … WHERE event_tx >=
      pg_snapshot_xmin(…)` has no index to use (there is none on `event_tx`), so it is a sequential scan
      of the whole events table every time it is sampled.
  - **The store is a mild instance of its own hazard**, which is why this is normal rather than exotic:
    an append in flight holds a transaction id, so a second append that starts later and commits first
    cannot read its own event back until the first one finishes. That window is one INSERT long and
    self-clearing, and `ConcurrentAppendVisibilityTest` is written to tolerate it (it re-polls). The
    same mechanism, with a blocker that lasts minutes instead of milliseconds, is the hazard above.
  - **A consequence for anything that would hold the store's *own* connections in a transaction.**
    Reads currently run on autocommit, which is what keeps the store out of its own blast radius. Moving
    `query()` to a cursor-based, autocommit-off streaming read would make a long-running read a
    long-running transaction — and while a purely read-only one still assigns no transaction id and so
    still pins nothing, that safety rests entirely on the streaming connection never writing. Any design
    that opens a transaction and reads for minutes needs to hold that property deliberately, or the
    store becomes its own worst blocker.
  - **Do not "fix" this by bounding the barrier** — falling back to reading everything committed once
    xmin has been pinned for a while trades a visible, self-healing stall for silent event loss in
    exactly the scenario the barrier exists to prevent. `ConcurrentAppendVisibilityTest` is what fails
    when you try.
- **Conditional appends are serialized per stream by a `pg_advisory_xact_lock`.** The optimistic-locking
  check is an `INSERT … WHERE NOT EXISTS (…)`, and under PostgreSQL's default READ COMMITTED isolation each
  statement fixes its snapshot when it starts, so two concurrent appends at the same consistency boundary
  both find it empty, both insert, and both commit — a silent DCB violation. The conflicting row is a
  *phantom* at the moment of the check, so no row lock can cover it. `append` therefore takes a
  transaction-scoped advisory lock, keyed on a SHA-256 of the table prefix plus `(stream_context,
  stream_purpose)`, before running the INSERT. Consequences worth knowing:
  - **The lock is taken as its own statement, before the INSERT** — this is load-bearing, not stylistic.
    Folded into the INSERT's `WHERE`, the statement would block with its stale snapshot already taken and
    the check would still miss the other appender's row: the same race with a lock in front of it.
  - **Only conditional appends take it.** `AppendCriteria.none()` reads nothing and so cannot observe a
    stale boundary; a conditional append that misses one is still equivalent to the two having run in the
    order conditional-then-unconditional, which is a legitimate history. Bulk ingestion stays fully parallel.
  - **The key is the stream, not the filter.** Hashing the filter would be finer grained and unsound: two
    overlapping-but-unequal filters (tag `A` vs tags `A + B`) hash differently and would not exclude each
    other. An append not confined to one fully specified stream falls back to a storage-wide key.
  - **Cost**: measured at ~5% against 8 concurrent writers spread over 1000 streams. Conditional appends to
    *one* stream serialize for the duration of a single INSERT, so a hot stream (remember a stream is
    usually a bounded context, not one aggregate) is the ceiling to watch. Key collisions only make two
    unrelated streams take turns; they can never let a real conflict through.
  - **Why not `SERIALIZABLE`**: it is correct, but a poor fit. A DCB boundary check is always
    `event_position > <recent reference>`, i.e. a scan of the log's tail, which is exactly where every
    writer writes — so SSI predicate locks collide constantly. Measured on the same workload: 86%
    serialization failures and a third of the throughput, with disjoint boundaries falsely conflicting
    because the planner's choice (seq scan → relation-level `SIRead` lock) decides the granularity.
  - No DDL change, so no migration: the lock is entirely in the write path.
- **The DCB check is a re-used prepared statement, and PostgreSQL's choice of plan for it can fall off a
  cliff.** The server holds two plans for such a statement — a *custom* one re-planned from the actual
  parameter values, and a *generic* one planned once against default selectivity — and from the tenth
  execution it adopts the generic plan if its **estimated** cost looks no worse. A DCB check is the shape
  that misleads that comparison: its expected result is *no rows*, while a `NOT EXISTS` is priced by how
  soon a row is expected to turn up. Every OR-ed fact a decision rests on makes the generic plan expect a
  match sooner, so its estimate *falls* while the custom plan's — built from real tag statistics — rises.
  - **`conditionalAppendPlanning(PER_APPEND)` takes the choice away**, planning every conditional append
    from its own values. Implemented as `setPrepareThreshold(0)` on that one statement through pgjdbc —
    no extra round trip, no effect on any other statement sharing the connection, and unlike
    `SET plan_cache_mode` it cannot leak. It is best effort against a `DataSource` that will not unwrap
    to pgjdbc, which is a single WARN rather than a failed append.
  - **The default stays `SERVER_DEFAULT`, and that is a measurement rather than a reservation.**
    `sliceworkz-eventstore-benchmark`'s `dcb-plan-cache` profile runs both modes over one corpus in one
    run, with `append-none` as the control that must not move; `dcb-plan-cache-small` is the same
    profile against a 5.000-event corpus, so the pair isolates the one property that decides whether
    planning per append pays for itself — the size of the table being planned against. On a
    100.000-event `TAGGED` corpus (PG18, Testcontainers, 0.00% store drift, control 10.960 → 11.145
    ops/ms = 1.02×, so the two targets are comparable):

    | conditional append | server's choice | planned per append | ratio |
    |---|---|---|---|
    | `append-types` | **8.096** | 3.329 | **0.41** |
    | `append-type-and-tag` (the canonical check) | 1.069 | 1.102 | 1.03 |
    | `append-multi-tag` | 0.969 | 0.923 | 0.95 |
    | `append-or-groups-2` | 0.067 | **0.733** | **10.9** |
    | `append-or-groups-3` | 0.066 | **0.568** | **8.6** |
    | `append-or-groups-5` | 0.626 | 0.425 | 0.68 |
    | `append-or-groups-10` | 0.529 | 0.590 | 1.12 |
    | `append-empty-boundary` | 1.826 | 1.881 | 1.03 |
    | `decide-then-append` | 0.866 | 0.855 | 0.99 |

    ops/ms, higher is better. What that says:

    - **The cliff begins at two OR-ed facts, and at two and three it is robust.** There the server
      adopts a generic plan that sequential-scans the whole events table — `Rows Removed by Filter:
      100044`, for a row that is not there — and a conditional append costs ~15ms instead of ~1.4ms.
      Nothing is logged and no meter moves. This is the case `PER_APPEND` exists for, and it is worth
      9–15× where it applies.
    - **Past three facts both regimes are real, and which one a run lands in is decided by the
      statistics, not by the width.** In the table above — and in the first `dcb-cost-curve-ext` run
      on a real server (0.583 / 0.623 / 0.534 ops/ms at four, five and ten) — the generic plan at
      wide widths was a cursor-driven index scan, the default already an order of magnitude faster
      than at three, and `PER_APPEND` had nothing to rescue (0.68× at five). The **second** run of
      the same profile, corpus, server and settings landed the other way: flat on the
      sequential-scan floor from two facts through ten (0.073 → 0.064 ops/ms against 0.967 at one,
      13–15×), every width internally stable at 2–5% relative error, and the baseline comparator
      flagged four, five and ten at −89% against the first run. The generic-vs-custom estimated-cost
      comparison at those widths sits close enough to the crossover that the tag statistics
      `ANALYZE` samples — re-taken on every corpus restore, and by autovacuum in production —
      decide the side, and the adopted plan then persists for the life of the cached statement. So
      read "the curve recovers" as one of two stable regimes rather than the shape of the curve:
      from two facts up the generic plan is *at risk* at every width; in the cliffed regime
      `PER_APPEND` pays its 9–15× everywhere, and its 0.68× cost at five facts belongs to the
      recovered regime only. The committed `results/0.11.0-SNAPSHOT/dcb-cost-curve-ext/` run is the
      cliffed regime — a later re-run landing on the fast side will baseline-diff as a large
      improvement that is regime, not progress.
    - **A types-only filter is now *harmed* by it, 2.4×.** That reverses what the small corpus says
      (1.49× in `PER_APPEND`'s favour there) and it is the row-comparison cursor boundary that did it:
      the generic plan for that shape became an index-only scan, so per-append planning pays
      parse+analyze to arrive at the plan the server already had. The single-item shapes went the same
      way — `append-type-and-tag` and `decide-then-append` were measured against 20–28ms generic
      sequential scans while the boundary was still a disjunction, and their generic plan is now an
      index scan at 0.23ms.
    - **Everywhere else it is flat.** 1.03, 0.95, 1.03 and 0.99 on the canonical check, multi-tag, the
      empty boundary and `decide-then-append` are "no difference", not small effects — several of these
      carry 10–17% relative error, which is enough to hide an effect of that size and nowhere near
      enough to manufacture a 9×.
    - **Small stores never need it.** At 5.000 events the server keeps the custom plan on every shape
      unaided, and the captured plan is identical at one, two, three, five and ten facts (a `BitmapOr`
      over `idx_events_stream_position`, cost 12.62 → 12.65). The 42% decline across that width is
      statement handling — 17 bound parameters against 62 — and not data access, so nothing about it is
      a planning problem. What `PER_APPEND` costs there, where it changes no plan, is 0.70–0.95×.

    So `PER_APPEND` is a remedy for a store observed to have flipped, not a setting to turn on
    generally: reach for it when appends on one shape jump by an order of magnitude with no change in
    the data, and leave it alone otherwise. Turning it on blind is as likely to cost 2.4× on a
    types-only filter as to buy 10× on a two-fact one. These are Testcontainers runs on a developer
    machine, which is enough to establish the direction and the rough magnitude and is deliberately not
    published under `results/` — the publisher refuses a Testcontainers run for exactly this reason.

  - **At ten million events the generic plan is worse again, and `PER_APPEND` is then no lever at all.**
    `large-tier-writes` on an external PG18 captured both plans for the canonical `append-type-and-tag`
    check — one type, one `sku:` tag, and a cursor:

    | | custom | generic |
    |---|---|---|
    | access path | Bitmap Heap Scan via `idx_events_stream_tags` | **Seq Scan** |
    | cursor `ROW(event_tx, event_position) >` | in the **Filter** | in the **Filter** |
    | rows discarded | 16.806 | **10.000.008** |
    | buffers | 19.965 (`exact=16125` heap blocks) | 433.299 |
    | actual time | **44.4 ms** | **1251 ms** |
    | planner's estimate | cost **130.35** | cost 250.21 |

    **The server picks correctly here, and the expensive plan is still the one it picks.** Knowing the
    tag value sends the custom plan to the tag index, so it materialises *the entity's whole history*
    and applies the cursor afterwards as a filter rather than as a start condition — that
    materialisation is the ~190× a DCB check costs over an unconditional append at this volume. The
    generic plan, with no value to be selective on, does not fall back to the stream index: it scans
    the table, 28× slower. The estimate has the ordering right, so `PER_APPEND` forces the plan already
    in effect and moved nothing on that run.

    **So there is no second remedy, and the setting that claimed to be one has been removed.** An
    earlier capture was read as showing the generic plan doing an index scan at 0.242 ms with the
    cursor as its start condition; that plan belongs to `append-types`, the *types-only* check, which
    carries no tag value to distract the planner. A `FORCE_GENERIC` mode was built on that reading and
    then measured against the same corpus: **1248.50 ms/op against the default's 66.03**, 48 useful
    appends in a trial against 731 — 20× worse. It is gone, along with the third target that measured
    it.

    **What the throughputs add to the plans: this shape is bistable, and the default target's 106%
    relative error is the finding.** About 2% of appends are served the generic plan — enough to move
    a 44 ms operation to 66 ms/op measured — and each of those costs 1.25 s, so under 2% of appends take
    roughly a third of the wall clock. Pinning one plan removed that scatter (0.8–8.6% error) while
    making the mean 20× worse. A store on this shape is not slow so much as occasionally, invisibly,
    28× slow, and no `plan_cache_mode` setting fixes it: the fix is stream layout, so the check has less
    entity history to materialise.

    `PostgresConditionalAppendPlanningTest` pins the mechanism per backend — that the statement does reach
    the plan cache by default and does not under `PER_APPEND` — and that neither mode changes what a
    consistency boundary means.
- **Oldest supported PostgreSQL is 16** (`Builder.OLDEST_SUPPORTED_MAJOR_VERSION`). The schema itself only
  needs 13 — `xid8`, `pg_current_xact_id()` — but 16 is both the oldest version with a support life worth
  committing to (13 went end-of-life in November 2025, 14 follows in November 2026, 15 in November 2027)
  and the oldest this library has ever actually worked on. The docs previously promised 13+ while the
  compliance run covered 17 and 18 only, and adding a floor backend showed the claim had never held: the
  conditional append's `SELECT * FROM ( VALUES … )` carried no alias, which PostgreSQL only made optional
  for FROM-clause subqueries in 16, so **every** conditional append — every DCB consistency check — failed
  on 15 and older with `VALUES in FROM must have an alias`. The alias (`AS new_events`) is there now and is
  kept even though 16 does not need it. An older server is **warned about, not rejected**: a hard failure
  would turn a library upgrade into an outage, and the warning names the version. `Postgres16Backend` is in
  the TCK service file so the floor is actually exercised — that is what an untested support claim is worth,
  and why the floor backend earns its CI minute
- **`ENSURE` brings functions and triggers up to date; tables, columns and indexes are only ever created.**
  The functions are `CREATE OR REPLACE`d and each trigger is compared against the shape this release wants
  (`tgtype` plus target function, in a `DO $$` block) and recreated only when it differs — so wrong timing,
  wrong orientation or a trigger pointing at the wrong function self-heal, while the ordinary startup, where
  the trigger is already correct, is a catalog read that takes no lock on the events table. `CREATE OR
  REPLACE TRIGGER` would be simpler but is PG14+ *and* rewrites unconditionally, taking `ACCESS EXCLUSIVE`
  on every start of every instance. `drop-schema.sql` drops the two functions as well as the tables, which
  is what makes `INITIALIZE` mean what it says: the triggers go with the tables via `CASCADE`, the functions
  do not, so before this a stale body survived the "drop and recreate from scratch" mode and the freshly
  created trigger was wired straight back to it — the store then reported a validated schema with its
  notifications dead
- **Schema scripts run as one transaction under a per-prefix advisory lock** (`executeSqlScripts`, keyed on
  a SHA-256 of the prefix and a scope no stream can produce, sharing `advisoryLockKey` with the append lock).
  `CREATE TABLE / INDEX / EXTENSION IF NOT EXISTS` is not atomic against a concurrent creator, so before this
  several instances starting together on a database without the schema raced on the system catalogs and 64 of
  80 failed to start, on PG17 and PG18 alike. One transaction across *all* scripts also makes `INITIALIZE`'s
  drop-then-ensure indivisible, so a second instance cannot drop what the first has just recreated
- **What is still missing: a version marker, and validation of an object's shape.** `checkDatabase()` checks
  that named tables, columns (type + nullability), functions and indexes *exist*, and that each trigger
  exists with the expected `action_orientation`; it does not check
  an index's method, columns or uniqueness, a column default, or a function body. So an index rebuilt as the
  wrong kind, or the idempotency index silently made non-unique, passes validation — and a `VALIDATE`/`NONE`
  deployment, where a DBA applies DDL, never gets the function repair either. A change needing `ALTER TABLE`
  still has to be applied by hand (see the manual migrations below). `PostgresSchemaDriftTest` pins down both
  halves per backend: what `ENSURE` now repairs, and what it still does not. See
  `sliceworkz-eventstore-infra-postgres/SCHEMA-MIGRATION.md` for the measurements and the recommended
  version-table design
- **Append notifications are emitted once per stream per statement, not once per row.** The trigger on
  `<prefix>events` is `AFTER INSERT ... REFERENCING NEW TABLE AS inserted FOR EACH STATEMENT`, and the
  function emits one `pg_notify` per distinct `(stream_context, stream_purpose)` in the transition table,
  carrying that stream's maximum over the total `(event_tx, event_position)` order. It was `FOR EACH ROW`,
  which meant a 1000-event append queued 1000 notifications and an import chunk queued 5000 — all but one
  per stream discarded by `OptimizingAppendListenerDecorator` after being built as JSON, written to the
  cluster-wide async queue, sent over the wire, parsed by Jackson and fanned out to every listener.
  Measured on the PG16 floor, a 100k-row insert: the notification count falls from 100.000 to exactly 1,
  and trigger time roughly halves — 1230ms to 460ms on one run, 808ms to 369ms on another (the absolute
  numbers move a lot between runs; the ratio is the stable part). The remaining cost is the transition
  table, which is materialised as a tuplestore and then sorted, so this is not free — just far cheaper
  than a plpgsql invocation and a queued notification per row. This also matches the in-memory backends,
  which have always notified once per stream per append. Things to keep in mind when touching this:
  - **The aggregation is `DISTINCT ON (stream_context, stream_purpose) ... ORDER BY event_tx DESC,
    event_position DESC`, not `max(event_position)`.** The two orders genuinely disagree (see the
    `(tx, position)` note above), and `DISTINCT ON` returns the whole winning row, so `event_id` belongs
    to the reference being reported instead of being aggregated independently of it. A notification naming
    a reference the reader has already passed is dropped by the optimizing decorator, so getting this
    wrong strands subscriptions silently rather than loudly.
  - **One notification per *distinct stream*, never a single collapsed "something happened".**
    `AppendsToEventStoreNotification.isRelevantFor` matches through `EventStreamId.canRead`, so the
    notification has to name a concrete stream or no concrete subscriber matches it.
  - The payload shape is unchanged — `eventTx` is still rendered as a JSON *string* by
    `jsonb_build_object` — so the Java side needed no change.
  - **The trigger's expected `tgtype` is 4** (`INSERT` with the `ROW` bit clear), where the row-level
    version was 5. That is what makes an un-migrated database fail the shape compare and get repaired by
    `ENSURE`. `tgnewtable = 'inserted'` is compared too: the function reads the transition table, so a
    statement-level trigger declared without `REFERENCING` would fail at runtime rather than at startup.
  - The bookmark trigger is deliberately still `FOR EACH ROW`: `bookmark()` is a single-row upsert, so
    per-row and per-statement are the same count there.
  - `EventAppendNotificationGranularityTest` in the TCK pins the granularity and the reference down for
    every backend.
- **`checkTrigger` validates `action_orientation`, not just the trigger's name.** A `VALIDATE`/`NONE`
  deployment never gets the `ENSURE` repair, so without this an un-migrated database would start and
  misbehave. The failure it prevents is not loud: a statement-level trigger bound to a stale row-level
  function body does *not* raise in PostgreSQL — `NEW` is unassigned, so it emits a notification with
  every field null, which becomes a wildcard stream with a zero reference that every concrete
  subscriber's `canRead` rejects. Live updates would stop with nothing thrown and nothing logged.
- **The async notification queue was never the binding constraint.** Measured on PG16, 100.000 pending
  notifications occupy 0.217% of it, so it holds ~46 million and a single transaction would need that many
  events to hit `NOTIFY queue is full` (SQLSTATE 53200) — far past where the in-memory `List<EventToImport>`
  would OOM first. `EventStoreImporter` also commits one transaction per `batchSize` (default 1000), so a
  million-event migration is a thousand commits, not one. The queue is cluster-wide and only recycled once
  every listener has consumed, so a stalled listener does make usage accumulate monotonically across
  transactions — but from a base low enough that the amplification was a throughput and latency problem,
  not a correctness-of-operation one.
- `stream_purpose` defaults to `'default'` in the DDL, matching `EventStreamId.DEFAULT_PURPOSE` — a public
  constant, so an interop layer can bind the same value the library does rather than copy the literal out
  of this file. On a database created before this alignment (default was `''`), operators doing raw SQL inserts should run `ALTER TABLE <prefix>events ALTER COLUMN stream_purpose SET DEFAULT 'default';` — no data migration is needed since all events written through the library bind the purpose explicitly
- **Idempotency keys are scoped per event stream (context + purpose), not per storage/table.** Uniqueness is enforced by the partial unique index `idx_events_stream_idempotency` on `(stream_context, stream_purpose, idempotency_key) WHERE idempotency_key IS NOT NULL` (schema validation requires it), so the same key used on two unrelated streams does not collide and dedup behaviour does not depend on how storage instances / prefixes are wired at runtime. The `idempotency_key` is persisted and surfaced on `StoredEvent` when reading (it is not exposed on the public `Event` record). A duplicate append is still silently ignored (returns an empty result). On a database created before this change (when `idempotency_key` had a table-wide `UNIQUE`), migrate with: `ALTER TABLE <prefix>events DROP CONSTRAINT <prefix>events_idempotency_key_key; CREATE UNIQUE INDEX <prefix>idx_events_stream_idempotency ON <prefix>events (stream_context, stream_purpose, idempotency_key) WHERE idempotency_key IS NOT NULL;` — no data migration is needed
  - **The duplicate is recognised by the index the server names, never by the message text.** Both the
    append and the import path go through `isIdempotencyKeyViolation`, which pairs SQLSTATE 23505 with
    `PSQLException.getServerErrorMessage().getConstraint()` — populated with the *index* name for a bare
    `CREATE UNIQUE INDEX`, and compared case-insensitively against `<prefix>idx_events_stream_idempotency`
    because PostgreSQL folds the unquoted identifier in the DDL. Two things make the message text unusable
    here, and only one of them is obvious. The subtle one is the table prefix: it is caller-supplied and
    validated only as `[a-zA-Z0-9_]+_`, so a prefix containing the word "idempotency" puts that word into
    `<prefix>events_pkey` and `<prefix>events_event_id_key` as well, and a substring match then swallows
    *every* unique violation the table can raise — reporting a successful de-duplication for an append that
    wrote nothing. (The other, message translation under a non-English `lc_messages`, turns out **not** to
    bite: an index name is an identifier, so it appears verbatim in the French and Japanese messages too.
    Verified, not assumed.) `EventStreamIdempotencyTest` builds its store with exactly such a prefix and
    pins a generated `event_id` with a `BEFORE INSERT` trigger, so the misrouting fails the build rather
    than passing silently
  - **Identifier length is a coupling to keep in mind.** PostgreSQL truncates identifiers at 63 bytes, which
    would break an exact-name comparison; `MAX_PREFIX_LENGTH` (32) keeps the longest generated index name at
    61 characters, so it cannot happen. Raising that cap needs this comparison revisited — and, more
    urgently, would let two of the schema's index names truncate to the same string, at which point
    `CREATE UNIQUE INDEX IF NOT EXISTS` silently does nothing and idempotency uniqueness stops existing
- **Importing needed no DDL change**: `event_id` is already a plain `UUID NOT NULL UNIQUE` and `event_timestamp` is nullable with a `CURRENT_TIMESTAMP` default, so both can be supplied explicitly. `importEvents` binds them per row, chunks statements at 5000 rows (9 params/row against the 65535-parameter wire ceiling) inside a single transaction, and matches `RETURNING` rows **by event_id** rather than by row order — with `ON CONFLICT` the returned rows are a subset of the input, so position in the result set means nothing. Conflicts are routed by constraint name from `PSQLException.getServerErrorMessage().getConstraint()`, not by matching message text
- **Imported event ids must be UUIDs** (the `::uuid` cast); `importEvents` validates this up front to give a clear error rather than an opaque cast failure
- **`timestamptz` keeps microseconds and rounds anything finer**, so a nanosecond-precision timestamp (as an in-memory store produces) lands up to half a microsecond away from where it started. This is the only lossy part of an inmem → Postgres → inmem round trip; `EventImportRoundTripTest` pins it down
- **A `db.properties` *value* never reaches an error message or a log line — only the key does.** Every
  non-`datasource.` key goes through `HikariConfigurationUtil.setHikariProperty`, and
  `db.<name>.password` is one of them, so a value interpolated into a message is a database password in
  every log, stack trace and error reporter downstream. The failure message names the property and the
  type the setter expected (`Error setting property 'maximumPoolSize' (expected int)`) and stops there;
  the detail is left to the cause, which for the realistic throwers — a numeric property fed a
  non-numeric value, a Hikari setter rejecting one — concerns a property that is never a secret. An
  empty property name (a stray `db.pooled.=x` line) is rejected with that explanation rather than
  reaching `charAt(0)`. `HikariConfigurationUtilTest` asserts the value is absent from the whole
  exception chain, not just its top frame
