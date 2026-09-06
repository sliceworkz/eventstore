# Benchmark run: large-tier-writes

What an append costs at ten million events, against the same corpus large-tier reads. Split out from that profile rather than sharing it, because the two cannot be measured the same way: a read leaves the store alone and can be given as long as it needs, while an append grows it, and above a million events the corpus is restored once per trial rather than once per iteration -- a template copy of ten million rows between iterations would cost far more than the drift it prevents.
So an append workload here has a budget denominated in events, not seconds, and the budget does not grow with the tier. That is the whole reason this profile declares a drift cap of its own. At two percent, the suite-wide default, ten million events allow 200.000 appends per trial: about eighty seconds at one writer, and under ten at eight -- less than a single JMH iteration. No cadence fits that, and the run that discovered it died at 72% having thrown away forty minutes of clean reads.
Ten percent is a judgement, and it is about the label rather than the measurement. What a fraction of growth threatens is the claim "measured over ten million events"; it does not change a B-tree's depth or a GIN index's shape, so an append measured while the store went from 10.0M to 10.9M is measuring the same operation throughout. The cap is recorded in the manifest and printed in the report beside the drift, so a reader comparing this against a run measured under the default two percent can see that the allowance was widened deliberately. In the event the first captured run came in at 1.12%, so the allowance has never been needed: it was sized against an assumed ~25 ops/ms at eight writers where the real figure is 4.7. Left as it is rather than tightened to the default, because a server faster than that one should not fail a two-hour run over a label.
One caveat before reading any of these against the medium tier: the walk draws entities with the corpus's own skew, so the head of the distribution recurs fast enough to keep its cached boundary while the tail presents a cold, stale one -- and at a hundred thousand entities the tail is most draws, so many append-type-and-tag invocations here carry a boundary read and a long probe walk the medium tier's denser recurrence mostly avoids. Compare decide-then-append across tiers -- it always includes its read -- and treat this tier's append-type-and-tag as a different operation mix rather than the same workload at more volume. The staleness itself is measured deliberately, with the cursor's age pinned, in dcb-boundary-staleness.
Budget more wall clock than the estimate promises. The estimator counts iteration time only, and at this tier the restore dominates it: taking the template costs seconds, but handing the store back at the end of each trial is a truncate-and-copy of ten million rows, about two and a half minutes, once per trial.
This profile is also where the DCB check's shape was settled against its alternatives, and the figures stay quotable from here. One uniform NOT EXISTS check for every criteria -- the obvious spelling -- measures ~190-220x an unconditional append at this tier, with 50-150% error bars from the plan cache flipping between a 44ms custom plan and a 1.25s sequential scan; pinning the generic plan makes it 20x worse still. The check the library ships derives its shape from the criteria instead -- an ordered probe from the cursor when one is present, the custom-planned tag path when not -- and dcb-boundary-staleness brackets what that probe costs against cursor age (traffic-mix cursors ~26ms/op, a pinned half-stream-stale cursor ~590ms/op at 0.7% error, no cursor ~2.3ms/op; append-none 0.32ms/op beside them). What this profile adds beside those pinned points is the check under concurrency and against a growing store, plus the decider pair: decide-then-append reads its boundary unbounded and presents the last matching event -- so it pays the full history read on hot entities and the staleness walk on idle ones, which is most of its mean and all of its error bar -- while decide-then-append-fresh bounds the read and presents the freshest reference it observed, the recommended pattern on a tagged stream at scale. The gap between the pair is what that pattern is worth. This profile keeps characterising the check as the store and the workloads evolve.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-06T05:54:25.623595306Z |
| finished | 2026-09-06T07:30:25.567278089Z |
| targets | postgres:external/metrics=off |
| corpus restore | restored once per trial; intra-trial drift measured |
| store drift | 1.10% during the run, against the 10% this profile allows |

> **Not suitable as a published baseline.**
>
> - 5 measurements are too noisy to compare against anything, past the 10% this report calls uncomparable: append-type-and-tag (postgres:external/metrics=off, 1 thread) at 16%, decide-then-append (postgres:external/metrics=off, 1 thread) at 78%, append-type-and-tag (postgres:external/metrics=off, 8 threads) at 17%, decide-then-append (postgres:external/metrics=off, 8 threads) at 16%, decide-then-append-fresh (postgres:external/metrics=off, 8 threads) at 13%

## Environment

These are the settings the numbers below depend on. Two runs whose environments differ are not comparable, and the comparator refuses rather than reporting a difference in hardware as a change in the store.

### JVM

| setting | value |
|---|---|
| java.vendor | Ubuntu |
| java.version | 21.0.12 |
| java.vm.name | OpenJDK 64-Bit Server VM |
| java.vm.version | 21.0.12+8-1-26.04-Ubuntu |
| max.heap.bytes | 16399728640 |

### Host

| setting | value |
|---|---|
| available.processors | 16 |
| cpu.model | AMD Ryzen AI 7 350 w/ Radeon 860M |
| memory.total | 64055560 kB |
| os.arch | amd64 |
| os.name | Linux |
| os.version | 7.0.0-30-generic |

### PostgreSQL

| setting | value |
|---|---|
| autovacuum | on |
| autovacuum_analyze_scale_factor | 0.1 |
| autovacuum_vacuum_scale_factor | 0.2 |
| checkpoint_completion_target | 0.9 |
| current_database | bmdb |
| effective_cache_size | 41943040kB |
| effective_io_concurrency | 200 |
| fsync | on |
| full_page_writes | on |
| jit | on |
| lc_messages | en_US.UTF-8 |
| maintenance_work_mem | 1048576kB |
| max_connections | 100 |
| max_parallel_workers | 8 |
| max_parallel_workers_per_gather | 2 |
| max_wal_size | 8192MB |
| max_worker_processes | 8 |
| min_wal_size | 1024MB |
| random_page_cost | 1.1 |
| seq_page_cost | 1 |
| server_version | 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1) |
| shared_buffers | 12582912kB |
| synchronous_commit | on |
| track_io_timing | off |
| version | PostgreSQL 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1) on x86_64-pc-linux-gnu, compiled by gcc (Ubuntu 15.2.0-16ubuntu1) 15.2.0, 64-bit |
| wal_compression | off |
| work_mem | 131072kB |

## Corpus

| | |
|---|---|
| fingerprint | `bm_n3tx9gechuj9_` |
| volume | 10,000,000 events under test |
| stream design | TAGGED |
| composition | CLEAN |
| payload | REALISTIC |
| entities | 100,000 |
| hot entity | `SKU-000000`, 455,092 events |
| cold entity | `SKU-094269`, 1 events |
| needle tag | 10 matches |
| swathe tag | 100,000 matches |
| mean payload | 114 bytes (sales) |

## What this run says

### What the DCB check costs

| append | throughput | relative |
|---|---|---|
| no criteria | 3.129 ± 0.073 ops/ms | 1.00x |
| one type set and one tag | 0.038 ± 0.006 ops/ms | 81.80x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 3.129 ± 0.073 ops/ms | 3,129 | 0.0% |
| append-none | 8 | 4.596 ± 0.052 ops/ms | 4,596 | 0.0% |
| append-type-and-tag | 1 | 0.038 ± 0.006 ops/ms | 38 | 0.0% |
| append-type-and-tag | 8 | 0.042 ± 0.007 ops/ms | 42 | 0.0% |
| decide-then-append | 1 | 0.002 ± 0.002 ops/ms | 2 | 0.0% |
| decide-then-append | 8 | 0.022 ± 0.003 ops/ms | 22 | 0.0% |
| decide-then-append-fresh | 1 | 0.250 ± 0.011 ops/ms | 250 | 0.0% |
| decide-then-append-fresh | 8 | 0.741 ± 0.100 ops/ms | 741 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.56..61.25 rows=500 width=314) (actual time=0.026..0.166 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..667439.00 rows=5499575 width=314) (actual time=0.025..0.143 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=70
Planning Time: 0.262 ms
Execution Time: 0.190 ms
```

### tag needle (~10 matches)

```
Sort  (cost=882.10..883.93 rows=733 width=314) (actual time=0.366..0.367 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=83
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=21.94..847.22 rows=733 width=314) (actual time=0.328..0.355 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=75
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..21.76 rows=733 width=0) (actual time=0.317..0.317 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=65
Planning:
  Buffers: shared hit=12
Planning Time: 0.069 ms
Execution Time: 0.394 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..6215.31 rows=500 width=314) (actual time=0.014..5.328 rows=500.00 loops=1)
  Buffers: shared hit=1444
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..653953.13 rows=52613 width=314) (actual time=0.013..5.304 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=1444
Planning:
  Buffers: shared hit=3
Planning Time: 0.047 ms
Execution Time: 5.350 ms
```

### one entity's whole history (hot) — **JIT 3ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
Sort  (cost=230095.41..230714.57 rows=247664 width=314) (actual time=551.953..562.754 rows=455092.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 126338kB
  Buffers: shared hit=188205
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1428.70..207907.16 rows=247664 width=314) (actual time=119.285..418.624 rows=455092.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=184595
        Buffers: shared hit=188205
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1366.78 rows=247688 width=0) (actual time=96.650..96.650 rows=455092.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=3610
Planning:
  Buffers: shared hit=3
Planning Time: 0.091 ms
JIT:
  Functions: 6
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.264 ms (Deform 0.123 ms), Inlining 0.000 ms, Optimization 0.232 ms, Emission 2.576 ms, Total 3.072 ms
Execution Time: 585.580 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..3.21 rows=1 width=314) (actual time=0.022..0.022 rows=1.00 loops=1)
  Buffers: shared hit=6
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..654928.38 rows=247664 width=314) (actual time=0.021..0.021 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared hit=6
Planning:
  Buffers: shared hit=11
Planning Time: 0.159 ms
Execution Time: 0.034 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..76.99 rows=500 width=314) (actual time=0.016..0.154 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..610617.34 rows=3995000 width=314) (actual time=0.015..0.132 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('3087567'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=14
Planning Time: 0.127 ms
Execution Time: 0.187 ms
```

> **The plans below do not describe the store's own execution.** They inline the tag arrays 
> and the cursor as literals, which is what PostgreSQL sees when it builds a *custom* plan; 
> the store binds them as JDBC parameters and re-uses the statement, so what it actually runs 
> is whichever of the custom and generic plans the server settled on -- and for several of 
> these shapes that is the generic one, which is a different plan entirely. Read these as the 
> shape of the predicate. The captured plans further down are the ones to read against the 
> measurements.

### DCB check: event types only, no tag (append-types) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..0.60 rows=1 width=4) (actual time=0.033..0.034 rows=1.00 loops=1)
  Buffers: shared hit=5
  ->  Index Only Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..39386.56 rows=982825 width=4) (actual time=0.032..0.033 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=5
Planning:
  Buffers: shared hit=2
Planning Time: 0.122 ms
Execution Time: 0.046 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.74 rows=1 width=4) (actual time=0.060..0.061 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..485433.37 rows=59355 width=4) (actual time=0.060..0.060 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=3
Planning Time: 0.090 ms
Execution Time: 0.069 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..74.47 rows=1 width=4) (actual time=0.053..0.053 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..485433.37 rows=6568 width=4) (actual time=0.052..0.052 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[])
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=3
Planning Time: 0.106 ms
Execution Time: 0.064 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.77 rows=1 width=4) (actual time=0.040..0.040 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..488728.41 rows=59522 width=4) (actual time=0.039..0.040 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]))
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=6
Planning Time: 0.113 ms
Execution Time: 0.049 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.87 rows=1 width=4) (actual time=0.029..0.029 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..498613.50 rows=60026 width=4) (actual time=0.029..0.029 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]))
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=12
Planning Time: 0.090 ms
Execution Time: 0.035 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..9.02 rows=1 width=4) (actual time=0.027..0.027 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..515088.67 rows=60864 width=4) (actual time=0.027..0.027 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]) OR (event_tags @> '{sku:SKU-012505}'::text[]) OR (event_tags @> '{sku:SKU-012506}'::text[]) OR (event_tags @> '{sku:SKU-012507}'::text[]) OR (event_tags @> '{sku:SKU-012508}'::text[]) OR (event_tags @> '{sku:SKU-012509}'::text[]))
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=22
Planning Time: 0.108 ms
Execution Time: 0.033 ms
```

> **These are the store's own statements, explained by the server.** Captured by running each 
> workload with `auto_explain` on, after the last measurement, so the SQL is the one the backend 
> built, the parameters are bound as it binds them, and the plan is the one PostgreSQL chose. 
> Where these and the reconstructed plans above disagree, these are the ones that describe what 
> was measured.
>
> **A plan says which collision mode it was captured under, and it is the profile's own.** So a 
> contention profile's plan is addressed at the stream and the boundary its measured appends 
> were. It runs on one thread, which is what it can be: contention between writers is not a 
> property of a plan and `auto_explain` would not attribute it, so these explain *where* a 
> profile's appends go and never what they wait for. A plan whose heading names no mode was 
> captured before the capture honoured the profile's -- it was addressed as `spread`, whatever 
> the profile ran, and describes that.
>
> **Generic against custom, and both are shown.** The backend re-uses its prepared statements, 
> so PostgreSQL holds two plans for each: a *generic* one planned once against default 
> selectivity, and a *custom* one re-planned from the actual parameter values. From the tenth 
> execution it compares their **estimated** costs and adopts the generic plan if it looks no 
> worse. So neither one is automatically what the throughput above was measured on: match the 
> plans by their `cost=` estimates -- the cheaper-looking of the two is the one the server 
> chose. Where the two are the same plan only one is shown.
>
> **A captured plan's own `actual time` is an upper bound, not a measurement.** It was 
> produced under `auto_explain` with timing and buffers on, which costs the server real work 
> per node per row, and it is one execution rather than a steady-state average. On a fast 
> statement that overhead is most of what the plan reports: a needle tag query captured at 
> 0.374ms belongs to an operation measured at **0.239ms end to end**, deserialisation 
> included. So read the plan for its shape, its indexes, its row counts and its buffers, and 
> do not subtract its time from the measured ms/op expecting the remainder to mean anything 
> on a sub-millisecond read. The subtraction is only safe where the plan's time dominates the 
> instrumentation -- a read returning thousands of rows.
>
> That comparison is on estimates, and a DCB check is exactly the shape that defeats it: the 
> expected result is *no rows*, while the planner prices a `NOT EXISTS` by how soon it expects 
> to find one. A wider filter makes it expect a match sooner, so the generic plan's estimate 
> **falls** as facts are added while the custom plan's rises -- and once it drops below, the 
> server switches to a plan that scans the whole table for a row that is not there.

### DCB check as issued: append-type-and-tag @ postgres:external/metrics=off (collision=spread, generic plan) — measured 26.14 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-046045","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{sku:SKU-046045,warehouse:WH-1,channel:web}', $8 = 'inventory', $9 = 'default', $10 = '3088820', $11 = '5250421', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-046045'
	Insert on bm_n3tx9gechuj9_events  (cost=166.09..166.11 rows=1 width=264) (actual time=55.891..55.895 rows=1.00 loops=1)
	  Buffers: shared hit=12317
	  InitPlan 1
	    ->  Limit  (cost=0.56..166.09 rows=1 width=16) (actual time=55.763..55.764 rows=0.00 loops=1)
	          Buffers: shared hit=12299
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..501378.79 rows=3029 width=16) (actual time=55.762..55.762 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 249587
	                Index Searches: 1
	                Buffers: shared hit=12299
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=55.813..55.813 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=12300
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.036..0.037 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:external/metrics=off (collision=spread, generic plan) — measured 401.95 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-001135","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-001135}', $8 = 'inventory', $9 = 'default', $10 = '3088926', $11 = '5463326', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-001135'
	Insert on bm_n3tx9gechuj9_events  (cost=166.09..166.11 rows=1 width=264) (actual time=7.672..7.674 rows=1.00 loops=1)
	  Buffers: shared hit=1708
	  InitPlan 1
	    ->  Limit  (cost=0.56..166.09 rows=1 width=16) (actual time=7.583..7.583 rows=0.00 loops=1)
	          Buffers: shared hit=1690
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..501378.79 rows=3029 width=16) (actual time=7.582..7.582 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 36691
	                Index Searches: 1
	                Buffers: shared hit=1690
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=7.615..7.616 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=1691
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.024..0.024 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | append-none | thrpt | 1 | 3.129 | ops/ms | 2.3% | 3,129 | 150,200 | 0 |
| postgres:external/metrics=off | append-none | thrpt | 8 | 4.596 | ops/ms | 1.1% | 4,596 | 221,255 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 1 | 0.038 | ops/ms | 15.6% | 38 | 1,874 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 8 | 0.042 | ops/ms | 17.2% | 42 | 2,222 | 0 |
| postgres:external/metrics=off | decide-then-append | thrpt | 1 | 0.002 | ops/ms | 77.6% | 2 | 130 | 0 |
| postgres:external/metrics=off | decide-then-append | thrpt | 8 | 0.022 | ops/ms | 15.9% | 22 | 1,539 | 0 |
| postgres:external/metrics=off | decide-then-append-fresh | thrpt | 1 | 0.250 | ops/ms | 4.2% | 250 | 12,020 | 0 |
| postgres:external/metrics=off | decide-then-append-fresh | thrpt | 8 | 0.741 | ops/ms | 13.5% | 741 | 35,876 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
