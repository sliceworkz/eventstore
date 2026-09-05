# Benchmark run: dcb-boundary-staleness

What cursor age does to the DCB check, over the large-tier corpus. The check's shape is derived from the criteria -- the ordered probe walking the position index forward from the cursor when one is present, the custom-planned tag path when not -- and cursor age is the one variable the probe pays for: every stream event after the cursor is a row it walks past to prove absence. This profile pins the age at the three points that bracket the regime:

  append-type-and-tag     the boundary read at append time -- fresh-ish, the ordinary decider
  append-stale-boundary   a cursor half the stream old, filter matching nothing, so every
                          invocation proves absence over the full walk -- the probe's bad case
  append-empty-boundary   no cursor at all -- routed to the tag path, so this row is what the
                          uniqueness pattern ("this basket was never checked out") costs

What the validation runs of this profile established, and the report record holds: fresh ~12ms/op (the probe finding ~170 rows after the cursor), stale ~605ms/op (2.75M rows walked at ~0.22us each -- linear, predictable, error bars under 3%), empty ~2.4ms/op (a tag-index miss planned from its bound values, plus per-execution planning and a fresh GIN key per insert). The rejected alternative -- one uniform NOT EXISTS check left to the plan cache -- measures 66ms at 117% error, 1164ms and 1164ms on the same three rows: the plan cache settles on a whole-table sequential scan for the stale and empty boundaries while a 0.06ms custom plan sits unused, which is the measurement behind deriving the check's shape from the criteria instead.
The stale row is the check's one accepted cost, and it is the caller's to avoid: a decider that re-reads its boundary before appending -- the ordinary decide-then-append cycle -- has a fresh cursor by construction. A process manager holding an hour-old reference on a busy stream pays the walk; re-reading is both the fix and what a conflict-retry loop does anyway.
Same corpus as large-tier-writes, so nothing re-provisions; same drift reasoning, same warning about the estimate: the per-trial restore of ten million rows dominates the wall clock, so expect around triple the estimate for the twelve trials.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-05T20:20:18.252019702Z |
| finished | 2026-09-05T20:57:19.568727731Z |
| targets | postgres:external/metrics=off |
| corpus restore | restored once per trial; intra-trial drift measured |
| store drift | 0.76% during the run, against the 10% this profile allows |

> **Not suitable as a published baseline.**
>
> - 2 measurements are too noisy to compare against anything, past the 10% this report calls uncomparable: append-type-and-tag (postgres:external/metrics=off, 1 thread) at 13%, append-empty-boundary (postgres:external/metrics=off, 1 thread) at 21%

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
| no criteria | 3.159 ± 0.117 ops/ms | 1.00x |
| one type set and one tag | 0.038 ± 0.005 ops/ms | 82.58x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.56..61.63 rows=500 width=312) (actual time=0.028..0.165 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..664271.67 rows=5439137 width=312) (actual time=0.027..0.141 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=74
Planning Time: 0.315 ms
Execution Time: 0.193 ms
```

### tag needle (~10 matches)

```
Sort  (cost=872.63..874.44 rows=725 width=312) (actual time=0.410..0.410 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=83
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=21.90..838.18 rows=725 width=312) (actual time=0.371..0.398 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=75
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..21.72 rows=725 width=0) (actual time=0.360..0.360 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=65
Planning:
  Buffers: shared hit=12
Planning Time: 0.075 ms
Execution Time: 0.438 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..5925.28 rows=500 width=312) (actual time=0.016..5.526 rows=500.00 loops=1)
  Buffers: shared hit=1444
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..650948.51 rows=54935 width=312) (actual time=0.016..5.503 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=1444
Planning:
  Buffers: shared hit=3
Planning Time: 0.054 ms
Execution Time: 5.547 ms
```

### one entity's whole history (hot) — **JIT 3ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
Sort  (cost=226470.51..227077.43 rows=242767 width=312) (actual time=557.465..568.466 rows=455092.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 126338kB
  Buffers: shared hit=188197
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1400.53..204755.96 rows=242767 width=312) (actual time=122.078..421.638 rows=455092.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=184595
        Buffers: shared hit=188197
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1339.84 rows=242790 width=0) (actual time=99.646..99.646 rows=455092.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=3602
Planning:
  Buffers: shared hit=3
Planning Time: 0.059 ms
JIT:
  Functions: 6
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.271 ms (Deform 0.122 ms), Inlining 0.000 ms, Optimization 0.227 ms, Emission 2.276 ms, Total 2.774 ms
Execution Time: 592.098 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..3.25 rows=1 width=312) (actual time=0.024..0.025 rows=1.00 loops=1)
  Buffers: shared hit=6
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..651887.67 rows=242767 width=312) (actual time=0.024..0.024 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared hit=6
Planning:
  Buffers: shared hit=11
Planning Time: 0.178 ms
Execution Time: 0.037 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..77.91 rows=500 width=312) (actual time=0.021..0.157 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..606438.51 rows=3920144 width=312) (actual time=0.021..0.134 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('3087567'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=14
Planning Time: 0.127 ms
Execution Time: 0.190 ms
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
Limit  (cost=0.56..0.60 rows=1 width=4) (actual time=0.034..0.034 rows=1.00 loops=1)
  Buffers: shared hit=5
  ->  Index Only Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..39204.27 rows=977822 width=4) (actual time=0.033..0.033 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=5
Planning:
  Buffers: shared hit=2
Planning Time: 0.122 ms
Execution Time: 0.047 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.79 rows=1 width=4) (actual time=0.059..0.060 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..484509.34 rows=58881 width=4) (actual time=0.059..0.059 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=3
Planning Time: 0.080 ms
Execution Time: 0.067 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..73.59 rows=1 width=4) (actual time=0.041..0.042 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..484509.34 rows=6634 width=4) (actual time=0.041..0.041 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[])
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=3
Planning Time: 0.069 ms
Execution Time: 0.289 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.82 rows=1 width=4) (actual time=0.034..0.034 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..487807.36 rows=59049 width=4) (actual time=0.034..0.034 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]))
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=6
Planning Time: 0.080 ms
Execution Time: 0.041 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..8.92 rows=1 width=4) (actual time=0.029..0.029 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..497701.45 rows=59553 width=4) (actual time=0.028..0.028 rows=0.00 loops=1)
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
Limit  (cost=0.56..9.07 rows=1 width=4) (actual time=0.037..0.037 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..514191.58 rows=60392 width=4) (actual time=0.037..0.037 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]) OR (event_tags @> '{sku:SKU-012505}'::text[]) OR (event_tags @> '{sku:SKU-012506}'::text[]) OR (event_tags @> '{sku:SKU-012507}'::text[]) OR (event_tags @> '{sku:SKU-012508}'::text[]) OR (event_tags @> '{sku:SKU-012509}'::text[]))
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=22
Planning Time: 0.118 ms
Execution Time: 0.044 ms
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
	Insert on bm_n3tx9gechuj9_events  (cost=165.92..165.94 rows=1 width=264) (actual time=50.036..50.038 rows=1.00 loops=1)
	  Buffers: shared hit=12317
	  InitPlan 1
	    ->  Limit  (cost=0.56..165.92 rows=1 width=16) (actual time=49.941..49.942 rows=0.00 loops=1)
	          Buffers: shared hit=12299
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..500359.94 rows=3026 width=16) (actual time=49.940..49.941 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 249587
	                Index Searches: 1
	                Buffers: shared hit=12299
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=49.977..49.977 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=12300
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.025..0.025 rows=1.00 loops=1)
```

### DCB check as issued: append-stale-boundary @ postgres:external/metrics=off (collision=spread, generic plan) — measured 550.24 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-001135","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-001135}', $8 = 'inventory', $9 = 'default', $10 = '3087567', $11 = '2750000', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-STALE-PROBE'
	Insert on bm_n3tx9gechuj9_events  (cost=165.92..165.94 rows=1 width=264) (actual time=534.368..534.371 rows=1.00 loops=1)
	  Buffers: shared hit=141858
	  InitPlan 1
	    ->  Limit  (cost=0.56..165.92 rows=1 width=16) (actual time=534.246..534.246 rows=0.00 loops=1)
	          Buffers: shared hit=141840
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..500359.94 rows=3026 width=16) (actual time=534.244..534.245 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 2750017
	                Index Searches: 1
	                Buffers: shared hit=141840
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=534.300..534.301 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=141841
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.039..0.040 rows=1.00 loops=1)
```

### DCB check as issued: append-stale-boundary @ postgres:external/metrics=off (collision=spread, custom plan, first executions only) — measured 550.24 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-053343","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-053343}', $8 = 'inventory', $9 = 'default', $10 = '3087567', $11 = '2750000', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-STALE-PROBE'
	Insert on bm_n3tx9gechuj9_events  (cost=838.99..839.01 rows=1 width=264) (actual time=0.082..0.084 rows=1.00 loops=1)
	  Buffers: shared hit=34
	  InitPlan 1
	    ->  Limit  (cost=838.99..838.99 rows=1 width=16) (actual time=0.039..0.040 rows=0.00 loops=1)
	          Buffers: shared hit=16
	          ->  Sort  (cost=838.99..839.68 rows=276 width=16) (actual time=0.039..0.039 rows=0.00 loops=1)
	                Sort Key: bm_n3tx9gechuj9_events_1.event_tx, bm_n3tx9gechuj9_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=16
	                ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=23.14..837.61 rows=276 width=16) (actual time=0.038..0.038 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-STALE-PROBE}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('3087567'::xid8, '2750000'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Buffers: shared hit=16
	                      ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..23.07 rows=725 width=0) (actual time=0.035..0.035 rows=0.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-STALE-PROBE}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=16
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.051..0.051 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=17
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.007..0.007 rows=1.00 loops=1)
```

### DCB check as issued: append-empty-boundary @ postgres:external/metrics=off (collision=spread, generic plan) — measured 2.31 ms/op

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE NOT EXISTS (
		SELECT 1 FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND ((event_type IN ($10, $11, $12, $13) AND event_tags @> ARRAY[$14]::text[]))) RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-N0-8","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-N0-8}', $8 = 'inventory', $9 = 'default', $10 = 'StockReleased', $11 = 'StockReserved', $12 = 'StockPicked', $13 = 'StockReceived', $14 = 'sku:SKU-N0-8'
	Insert on bm_n3tx9gechuj9_events  (cost=55.95..55.97 rows=1 width=264) (actual time=1847.745..1847.747 rows=1.00 loops=1)
	  Buffers: shared hit=892807
	  InitPlan 1
	    ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..502843.44 rows=9078 width=0) (actual time=1847.629..1847.629 rows=0.00 loops=1)
	          Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (event_type = ANY (ARRAY[($10)::text, ($11)::text, ($12)::text, ($13)::text])))
	          Filter: (event_tags @> ARRAY[($14)::text])
	          Rows Removed by Filter: 5334833
	          Index Searches: 1
	          Buffers: shared hit=892789
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=1847.678..1847.679 rows=1.00 loops=1)
	        One-Time Filter: (NOT (InitPlan 1).col1)
	        Buffers: shared hit=892790
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.036..0.036 rows=1.00 loops=1)
```

### DCB check as issued: append-empty-boundary @ postgres:external/metrics=off (collision=spread, custom plan, first executions only) — measured 2.31 ms/op

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE NOT EXISTS (
		SELECT 1 FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND ((event_type IN ($10, $11, $12, $13) AND event_tags @> ARRAY[$14]::text[]))) RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-N0-17","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-N0-17}', $8 = 'inventory', $9 = 'default', $10 = 'StockReleased', $11 = 'StockReserved', $12 = 'StockPicked', $13 = 'StockReceived', $14 = 'sku:SKU-N0-17'
	Insert on bm_n3tx9gechuj9_events  (cost=25.28..25.30 rows=1 width=264) (actual time=0.090..0.091 rows=1.00 loops=1)
	  Buffers: shared hit=39
	  InitPlan 1
	    ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=23.16..834.01 rows=383 width=0) (actual time=0.032..0.032 rows=0.00 loops=1)
	          Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-N0-17}'::text[]))
	          Filter: (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[]))
	          Buffers: shared hit=16
	          ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..23.07 rows=725 width=0) (actual time=0.030..0.030 rows=0.00 loops=1)
	                Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-N0-17}'::text[]))
	                Index Searches: 1
	                Buffers: shared hit=16
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.040..0.040 rows=1.00 loops=1)
	        One-Time Filter: (NOT (InitPlan 1).col1)
	        Buffers: shared hit=17
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.005..0.005 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | append-empty-boundary | thrpt | 1 | 0.432 | ops/ms | 21.1% | 432 | 20,749 | 0 |
| postgres:external/metrics=off | append-none | thrpt | 1 | 3.159 | ops/ms | 3.7% | 3,159 | 152,030 | 0 |
| postgres:external/metrics=off | append-stale-boundary | thrpt | 1 | 0.002 | ops/ms | 0.7% | 2 | 96 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 1 | 0.038 | ops/ms | 13.0% | 38 | 1,880 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
