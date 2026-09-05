# Benchmark run: large-tier-writes

What an append costs at ten million events, against the same corpus large-tier reads. Split out from that profile rather than sharing it, because the two cannot be measured the same way: a read leaves the store alone and can be given as long as it needs, while an append grows it, and above a million events the corpus is restored once per trial rather than once per iteration -- a template copy of ten million rows between iterations would cost far more than the drift it prevents.
So an append workload here has a budget denominated in events, not seconds, and the budget does not grow with the tier. That is the whole reason this profile declares a drift cap of its own. At two percent, the suite-wide default, ten million events allow 200.000 appends per trial: about eighty seconds at one writer, and under ten at eight -- less than a single JMH iteration. No cadence fits that, and the run that discovered it died at 72% having thrown away forty minutes of clean reads.
Ten percent is a judgement, and it is about the label rather than the measurement. What a fraction of growth threatens is the claim "measured over ten million events"; it does not change a B-tree's depth or a GIN index's shape, so an append measured while the store went from 10.0M to 10.9M is measuring the same operation throughout. The cap is recorded in the manifest and printed in the report beside the drift, so a reader comparing this against a run measured under the default two percent can see that the allowance was widened deliberately. In the event the first captured run came in at 1.12%, so the allowance has never been needed: it was sized against an assumed ~25 ops/ms at eight writers where the real figure is 4.7. Left as it is rather than tightened to the default, because a server faster than that one should not fail a two-hour run over a label.
One caveat before reading any of these against the medium tier: at a hundred thousand entities the per-iteration boundary cache almost never gets a hit, so nearly every append-type-and-tag invocation here is a boundary probe plus the append, where the medium tier amortizes the probe over a whole iteration. Compare decide-then-append across tiers -- it always includes its read -- and treat this tier's append-type-and-tag as a different operation mix rather than the same workload at more volume.
Budget more wall clock than the estimate promises. The estimator counts iteration time only, and at this tier the restore dominates it: taking the template costs seconds, but handing the store back at the end of each trial is a truncate-and-copy of ten million rows, about two and a half minutes, once per trial.
This profile is also where the DCB check's shape was settled against its alternatives, and the figures stay quotable from here. One uniform NOT EXISTS check for every criteria -- the obvious spelling -- measures ~190-220x an unconditional append at this tier, with 50-150% error bars from the plan cache flipping between a 44ms custom plan and a 1.25s sequential scan; pinning the generic plan makes it 20x worse still. The check the library ships derives its shape from the criteria instead -- an ordered probe from the cursor when one is present, the custom-planned tag path when not -- and measures ~37x an unconditional append on this corpus's boundaries, at error bars that describe the store. This profile keeps characterising that check as the store and the workloads evolve.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-05T07:15:21.228068341Z |
| finished | 2026-09-05T08:26:59.006713026Z |
| targets | postgres:external/metrics=off |
| corpus restore | restored once per trial; intra-trial drift measured |
| store drift | 1.12% during the run, against the 10% this profile allows |

> **Not suitable as a published baseline.**
>
> - 4 measurements are too noisy to compare against anything, past the 10% this report calls uncomparable: append-type-and-tag (postgres:external/metrics=off, 1 thread) at 12%, decide-then-append (postgres:external/metrics=off, 1 thread) at 56%, append-type-and-tag (postgres:external/metrics=off, 8 threads) at 21%, decide-then-append (postgres:external/metrics=off, 8 threads) at 33%

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
| effective_cache_size | 52428808kB |
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
| shared_buffers | 15728648kB |
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
| no criteria | 3.099 ± 0.160 ops/ms | 1.00x |
| one type set and one tag | 0.087 ± 0.010 ops/ms | 35.58x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 3.099 ± 0.160 ops/ms | 3,099 | 0.0% |
| append-none | 8 | 4.711 ± 0.066 ops/ms | 4,711 | 0.0% |
| append-type-and-tag | 1 | 0.087 ± 0.010 ops/ms | 87 | 0.0% |
| append-type-and-tag | 8 | 0.109 ± 0.022 ops/ms | 109 | 0.0% |
| decide-then-append | 1 | 0.032 ± 0.018 ops/ms | 32 | 0.0% |
| decide-then-append | 8 | 0.132 ± 0.044 ops/ms | 132 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.56..61.31 rows=500 width=312) (actual time=0.051..0.349 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..666616.87 rows=5487216 width=312) (actual time=0.049..0.282 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=70
Planning Time: 0.506 ms
Execution Time: 0.412 ms
```

### tag needle (~10 matches)

```
Sort  (cost=880.91..882.74 rows=732 width=312) (actual time=0.336..0.336 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=83
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=21.93..846.09 rows=732 width=312) (actual time=0.299..0.325 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=75
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..21.75 rows=732 width=0) (actual time=0.287..0.287 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=65
Planning:
  Buffers: shared hit=12
Planning Time: 0.066 ms
Execution Time: 0.361 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..6493.24 rows=500 width=312) (actual time=0.013..5.272 rows=500.00 loops=1)
  Buffers: shared hit=1444
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..653150.33 rows=50299 width=312) (actual time=0.013..5.250 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=1444
Planning:
  Buffers: shared hit=3
Planning Time: 0.047 ms
Execution Time: 5.291 ms
```

### one entity's whole history (hot) — **JIT 3ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
Sort  (cost=228197.85..228810.59 rows=245096 width=312) (actual time=538.944..549.918 rows=455092.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 126338kB
  Buffers: shared hit=188210
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1413.99..206258.10 rows=245096 width=312) (actual time=119.005..408.128 rows=455092.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=184595
        Buffers: shared hit=188210
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1352.71 rows=245119 width=0) (actual time=95.716..95.716 rows=455092.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=3615
Planning:
  Buffers: shared hit=3
Planning Time: 0.060 ms
JIT:
  Functions: 6
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.249 ms (Deform 0.116 ms), Inlining 0.000 ms, Optimization 0.233 ms, Emission 2.392 ms, Total 2.874 ms
Execution Time: 573.020 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..3.23 rows=1 width=312) (actual time=0.023..0.023 rows=1.00 loops=1)
  Buffers: shared hit=6
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..654124.31 rows=245096 width=312) (actual time=0.022..0.022 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared hit=6
Planning:
  Buffers: shared hit=11
Planning Time: 0.183 ms
Execution Time: 0.036 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..77.24 rows=500 width=312) (actual time=0.021..0.157 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..609251.10 rows=3972932 width=312) (actual time=0.020..0.135 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('3087567'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=14
Planning Time: 0.096 ms
Execution Time: 0.190 ms
```

> **The plans below do not describe the store's own execution.** They inline the tag arrays 
> and the cursor as literals, which is what PostgreSQL sees when it builds a *custom* plan; 
> the store binds them as JDBC parameters and re-uses the statement, so what it actually runs 
> is whichever of the custom and generic plans the server settled on -- and for several of 
> these shapes that is the generic one, which is a different plan entirely. Read these as the 
> shape of the predicate. The captured plans further down are the ones to read against the 
> measurements.

### DCB check: event types only, no tag (append-types) -- boundary 100,000 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..0.60 rows=1 width=4) (actual time=0.037..0.038 rows=1.00 loops=1)
  Buffers: shared hit=6
  ->  Index Only Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..39713.61 rows=991243 width=4) (actual time=0.037..0.037 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=6
Planning:
  Buffers: shared hit=2
Planning Time: 0.122 ms
Execution Time: 0.050 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 100,000 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.66 rows=1 width=4) (actual time=0.023..0.023 rows=1.00 loops=1)
  Buffers: shared hit=8
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..486196.51 rows=60025 width=4) (actual time=0.023..0.023 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 8
        Index Searches: 1
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=3
Planning Time: 0.054 ms
Execution Time: 0.029 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 100,000 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..72.81 rows=1 width=4) (actual time=0.016..0.016 rows=1.00 loops=1)
  Buffers: shared hit=10
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..486196.51 rows=6729 width=4) (actual time=0.016..0.016 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[])
        Rows Removed by Filter: 18
        Index Searches: 1
        Buffers: shared hit=10
Planning:
  Buffers: shared hit=3
Planning Time: 0.040 ms
Execution Time: 0.019 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 100,000 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.69 rows=1 width=4) (actual time=0.010..0.010 rows=1.00 loops=1)
  Buffers: shared hit=8
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..489556.13 rows=60196 width=4) (actual time=0.010..0.010 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]))
        Rows Removed by Filter: 8
        Index Searches: 1
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=6
Planning Time: 0.051 ms
Execution Time: 0.014 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 100,000 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.79 rows=1 width=4) (actual time=0.011..0.012 rows=1.00 loops=1)
  Buffers: shared hit=8
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..499635.01 rows=60710 width=4) (actual time=0.011..0.011 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]))
        Rows Removed by Filter: 8
        Index Searches: 1
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=12
Planning Time: 0.067 ms
Execution Time: 0.015 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 100,000 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.95 rows=1 width=4) (actual time=0.013..0.013 rows=1.00 loops=1)
  Buffers: shared hit=8
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..516433.13 rows=61565 width=4) (actual time=0.013..0.013 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]) OR (event_tags @> '{sku:SKU-012505}'::text[]) OR (event_tags @> '{sku:SKU-012506}'::text[]) OR (event_tags @> '{sku:SKU-012507}'::text[]) OR (event_tags @> '{sku:SKU-012508}'::text[]) OR (event_tags @> '{sku:SKU-012509}'::text[]))
        Rows Removed by Filter: 8
        Index Searches: 1
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=22
Planning Time: 0.095 ms
Execution Time: 0.018 ms
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

### DCB check as issued: append-type-and-tag @ postgres:external/metrics=off (collision=spread, generic plan) — measured 11.48 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-000008","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-000008}', $8 = 'inventory', $9 = 'default', $10 = '3088944', $11 = '5499837', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-000008'
	Insert on bm_n3tx9gechuj9_events  (cost=165.91..165.93 rows=1 width=264) (actual time=0.266..0.270 rows=1.00 loops=1)
	  Buffers: shared hit=32
	  InitPlan 1
	    ->  Limit  (cost=0.56..165.91 rows=1 width=16) (actual time=0.140..0.140 rows=0.00 loops=1)
	          Buffers: shared hit=14
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..500988.27 rows=3030 width=16) (actual time=0.139..0.140 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 171
	                Index Searches: 1
	                Buffers: shared hit=14
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.185..0.185 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=15
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.033..0.034 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:external/metrics=off (collision=spread, generic plan) — measured 31.16 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-000017","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{sku:SKU-000017,warehouse:WH-1,channel:web}', $8 = 'inventory', $9 = 'default', $10 = '3088944', $11 = '5499894', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-000017'
	Insert on bm_n3tx9gechuj9_events  (cost=165.91..165.93 rows=1 width=264) (actual time=0.269..0.272 rows=1.00 loops=1)
	  Buffers: shared hit=29
	  InitPlan 1
	    ->  Limit  (cost=0.56..165.91 rows=1 width=16) (actual time=0.131..0.131 rows=0.00 loops=1)
	          Buffers: shared hit=11
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..500988.27 rows=3030 width=16) (actual time=0.130..0.130 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 123
	                Index Searches: 1
	                Buffers: shared hit=11
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.178..0.178 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=12
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.035..0.035 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | append-none | thrpt | 1 | 3.099 | ops/ms | 5.2% | 3,099 | 149,320 | 0 |
| postgres:external/metrics=off | append-none | thrpt | 8 | 4.711 | ops/ms | 1.4% | 4,711 | 226,298 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 1 | 0.087 | ops/ms | 12.0% | 87 | 4,187 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 8 | 0.109 | ops/ms | 20.6% | 109 | 5,418 | 0 |
| postgres:external/metrics=off | decide-then-append | thrpt | 1 | 0.032 | ops/ms | 55.6% | 32 | 1,544 | 0 |
| postgres:external/metrics=off | decide-then-append | thrpt | 8 | 0.132 | ops/ms | 33.3% | 132 | 6,517 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
