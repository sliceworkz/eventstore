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
| started | 2026-09-05T15:50:44.833838754Z |
| finished | 2026-09-05T17:03:10.783447565Z |
| targets | postgres:external/metrics=off |
| corpus restore | restored once per trial; intra-trial drift measured |
| store drift | 1.10% during the run, against the 10% this profile allows |

> **Not suitable as a published baseline.**
>
> - 4 measurements are too noisy to compare against anything, past the 10% this report calls uncomparable: append-type-and-tag (postgres:external/metrics=off, 1 thread) at 10%, decide-then-append (postgres:external/metrics=off, 1 thread) at 23%, append-type-and-tag (postgres:external/metrics=off, 8 threads) at 12%, decide-then-append (postgres:external/metrics=off, 8 threads) at 14%

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
| no criteria | 3.166 ± 0.079 ops/ms | 1.00x |
| one type set and one tag | 0.008 ± 0.001 ops/ms | 393.07x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 3.166 ± 0.079 ops/ms | 3,166 | 0.0% |
| append-none | 8 | 4.630 ± 0.076 ops/ms | 4,630 | 0.0% |
| append-type-and-tag | 1 | 0.008 ± 0.001 ops/ms | 8 | 0.0% |
| append-type-and-tag | 8 | 0.009 ± 0.001 ops/ms | 9 | 0.0% |
| decide-then-append | 1 | 0.008 ± 0.002 ops/ms | 8 | 0.0% |
| decide-then-append | 8 | 0.009 ± 0.001 ops/ms | 9 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.56..60.82 rows=500 width=312) (actual time=0.033..0.255 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..670806.32 rows=5566237 width=312) (actual time=0.032..0.210 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=71
Planning Time: 0.433 ms
Execution Time: 0.301 ms
```

### tag needle (~10 matches)

```
Sort  (cost=892.75..894.61 rows=742 width=312) (actual time=0.581..0.582 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=83
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=21.99..857.38 rows=742 width=312) (actual time=0.530..0.563 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=75
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..21.80 rows=742 width=0) (actual time=0.512..0.512 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=65
Planning:
  Buffers: shared hit=12
Planning Time: 0.126 ms
Execution Time: 0.627 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..5640.64 rows=500 width=312) (actual time=0.025..8.494 rows=500.00 loops=1)
  Buffers: shared hit=1444
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..657182.03 rows=58260 width=312) (actual time=0.024..8.447 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=1444
Planning:
  Buffers: shared hit=3
Planning Time: 0.090 ms
Execution Time: 8.534 ms
```

### one entity's whole history (hot) — **JIT 3ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
Sort  (cost=232575.65..233203.25 rows=251037 width=312) (actual time=540.375..551.504 rows=455092.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 126338kB
  Buffers: shared hit=188174
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1447.63..210060.72 rows=251037 width=312) (actual time=117.795..406.775 rows=455092.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=184595
        Buffers: shared hit=188174
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1384.87 rows=251061 width=0) (actual time=95.872..95.873 rows=455092.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=3579
Planning:
  Buffers: shared hit=3
Planning Time: 0.177 ms
JIT:
  Functions: 6
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.313 ms (Deform 0.147 ms), Inlining 0.000 ms, Optimization 0.227 ms, Emission 2.352 ms, Total 2.892 ms
Execution Time: 575.400 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..3.19 rows=1 width=312) (actual time=0.022..0.022 rows=1.00 loops=1)
  Buffers: shared hit=6
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..658145.91 rows=251037 width=312) (actual time=0.021..0.021 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared hit=6
Planning:
  Buffers: shared hit=11
Planning Time: 0.173 ms
Execution Time: 0.033 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..76.31 rows=500 width=312) (actual time=0.015..0.155 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..613893.40 rows=4052417 width=312) (actual time=0.015..0.133 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('3087567'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=14
Planning Time: 0.093 ms
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
Limit  (cost=0.56..0.60 rows=1 width=4) (actual time=0.037..0.037 rows=1.00 loops=1)
  Buffers: shared hit=6
  ->  Index Only Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..40864.61 rows=1020871 width=4) (actual time=0.036..0.036 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=6
Planning:
  Buffers: shared hit=2
Planning Time: 0.117 ms
Execution Time: 0.050 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 100,000 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.45 rows=1 width=4) (actual time=0.032..0.032 rows=1.00 loops=1)
  Buffers: shared hit=8
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..488942.61 rows=61994 width=4) (actual time=0.031..0.031 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 8
        Index Searches: 1
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=3
Planning Time: 0.091 ms
Execution Time: 0.041 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 100,000 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..71.11 rows=1 width=4) (actual time=0.027..0.027 rows=1.00 loops=1)
  Buffers: shared hit=10
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..488942.61 rows=6930 width=4) (actual time=0.027..0.027 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[])
        Rows Removed by Filter: 18
        Index Searches: 1
        Buffers: shared hit=10
Planning:
  Buffers: shared hit=3
Planning Time: 0.078 ms
Execution Time: 0.034 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 100,000 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.48 rows=1 width=4) (actual time=0.016..0.016 rows=1.00 loops=1)
  Buffers: shared hit=8
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..492379.10 rows=62169 width=4) (actual time=0.016..0.016 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]))
        Rows Removed by Filter: 8
        Index Searches: 1
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=6
Planning Time: 0.075 ms
Execution Time: 0.022 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 100,000 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.58 rows=1 width=4) (actual time=0.016..0.017 rows=1.00 loops=1)
  Buffers: shared hit=8
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..502688.58 rows=62694 width=4) (actual time=0.016..0.016 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]))
        Rows Removed by Filter: 8
        Index Searches: 1
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=12
Planning Time: 0.084 ms
Execution Time: 0.022 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 100,000 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.74 rows=1 width=4) (actual time=0.016..0.016 rows=1.00 loops=1)
  Buffers: shared hit=8
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..519871.04 rows=63569 width=4) (actual time=0.015..0.015 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]) OR (event_tags @> '{sku:SKU-012505}'::text[]) OR (event_tags @> '{sku:SKU-012506}'::text[]) OR (event_tags @> '{sku:SKU-012507}'::text[]) OR (event_tags @> '{sku:SKU-012508}'::text[]) OR (event_tags @> '{sku:SKU-012509}'::text[]))
        Rows Removed by Filter: 8
        Index Searches: 1
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=22
Planning Time: 0.104 ms
Execution Time: 0.021 ms
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

### DCB check as issued: append-type-and-tag @ postgres:external/metrics=off (collision=spread, generic plan) — measured 124.15 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-058495","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-058495}', $8 = 'inventory', $9 = 'default', $10 = '3088691', $11 = '4993497', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-058495'
	Insert on bm_n3tx9gechuj9_events  (cost=166.19..166.21 rows=1 width=264) (actual time=102.862..102.865 rows=1.00 loops=1)
	  Buffers: shared hit=25565
	  InitPlan 1
	    ->  Limit  (cost=0.56..166.19 rows=1 width=16) (actual time=102.754..102.754 rows=0.00 loops=1)
	          Buffers: shared hit=25547
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..502331.83 rows=3033 width=16) (actual time=102.753..102.753 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 506511
	                Index Searches: 1
	                Buffers: shared hit=25547
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=102.794..102.795 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=25548
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.027..0.027 rows=1.00 loops=1)
```

### DCB check as issued: append-type-and-tag @ postgres:external/metrics=off (collision=spread, custom plan, first executions only) — measured 124.15 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-070945","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-070945}', $8 = 'inventory', $9 = 'default', $10 = '3086557', $11 = '728073', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-070945'
	Insert on bm_n3tx9gechuj9_events  (cost=858.63..858.65 rows=1 width=264) (actual time=0.167..0.169 rows=1.00 loops=1)
	  Buffers: shared hit=40
	  InitPlan 1
	    ->  Limit  (cost=858.63..858.63 rows=1 width=16) (actual time=0.093..0.093 rows=0.00 loops=1)
	          Buffers: shared hit=22
	          ->  Sort  (cost=858.63..859.56 rows=370 width=16) (actual time=0.093..0.093 rows=0.00 loops=1)
	                Sort Key: bm_n3tx9gechuj9_events_1.event_tx, bm_n3tx9gechuj9_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=22
	                ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=23.25..856.78 rows=370 width=16) (actual time=0.091..0.091 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-070945}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('3086557'::xid8, '728073'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Rows Removed by Filter: 1
	                      Heap Blocks: exact=1
	                      Buffers: shared hit=22
	                      ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..23.15 rows=742 width=0) (actual time=0.085..0.085 rows=1.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-070945}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=21
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.111..0.112 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=23
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.010..0.010 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:external/metrics=off (collision=spread, generic plan) — measured 122.38 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-014728","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,sku:SKU-014728,channel:web}', $8 = 'inventory', $9 = 'default', $10 = '3088880', $11 = '5371615', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-014728'
	Insert on bm_n3tx9gechuj9_events  (cost=166.19..166.21 rows=1 width=264) (actual time=24.707..24.709 rows=1.00 loops=1)
	  Buffers: shared hit=6067
	  InitPlan 1
	    ->  Limit  (cost=0.56..166.19 rows=1 width=16) (actual time=24.595..24.596 rows=0.00 loops=1)
	          Buffers: shared hit=6049
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..502331.83 rows=3033 width=16) (actual time=24.594..24.595 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 128402
	                Index Searches: 1
	                Buffers: shared hit=6049
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=24.640..24.640 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=6050
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.032..0.032 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:external/metrics=off (collision=spread, custom plan, first executions only) — measured 122.38 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-027178","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-027178}', $8 = 'inventory', $9 = 'default', $10 = '3088788', $11 = '5187751', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-027178'
	Insert on bm_n3tx9gechuj9_events  (cost=857.70..857.72 rows=1 width=264) (actual time=0.382..0.383 rows=1.00 loops=1)
	  Buffers: shared hit=134
	  InitPlan 1
	    ->  Limit  (cost=857.70..857.70 rows=1 width=16) (actual time=0.331..0.331 rows=0.00 loops=1)
	          Buffers: shared hit=116
	          ->  Sort  (cost=857.70..858.18 rows=192 width=16) (actual time=0.330..0.330 rows=0.00 loops=1)
	                Sort Key: bm_n3tx9gechuj9_events_1.event_tx, bm_n3tx9gechuj9_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=116
	                ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=23.20..856.74 rows=192 width=16) (actual time=0.328..0.329 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-027178}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('3088788'::xid8, '5187751'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Rows Removed by Filter: 18
	                      Heap Blocks: exact=18
	                      Buffers: shared hit=116
	                      ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..23.15 rows=742 width=0) (actual time=0.311..0.311 rows=18.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-027178}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=98
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.345..0.346 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=117
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.009..0.009 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | append-none | thrpt | 1 | 3.166 | ops/ms | 2.5% | 3,166 | 151,984 | 0 |
| postgres:external/metrics=off | append-none | thrpt | 8 | 4.630 | ops/ms | 1.6% | 4,630 | 223,048 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 1 | 0.008 | ops/ms | 10.3% | 8 | 412 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 8 | 0.009 | ops/ms | 11.9% | 9 | 588 | 0 |
| postgres:external/metrics=off | decide-then-append | thrpt | 1 | 0.008 | ops/ms | 22.5% | 8 | 412 | 0 |
| postgres:external/metrics=off | decide-then-append | thrpt | 8 | 0.009 | ops/ms | 14.5% | 9 | 621 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
