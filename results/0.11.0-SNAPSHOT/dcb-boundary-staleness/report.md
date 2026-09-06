# Benchmark run: dcb-boundary-staleness

What cursor age does to the DCB check, over the large-tier corpus. The check's shape is derived from the criteria -- the ordered probe walking the position index forward from the cursor when one is present, the custom-planned tag path when not -- and cursor age is the one variable the probe pays for: every stream event after the cursor is a row it walks past to prove absence. This profile pins the age at the points that bracket the regime:

  append-type-and-tag       the boundary as the traffic-weighted walk presents it -- cursors as
                            fresh or as stale as the corpus's own write mix makes them
  decide-then-append-fresh  the recommended decider: boundary read bounded, and the append
                            presents the freshest reference the read observed (the stream head,
                            read before the boundary), so the probe starts where the reader
                            stopped -- the cursor path's floor
  append-stale-boundary     a cursor pinned half the stream old, filter matching nothing, so
                            every invocation proves absence over the full walk -- the ceiling
  append-empty-boundary     no cursor at all -- routed to the tag path, so this row is what the
                            uniqueness pattern ("this basket was never checked out") costs

The measured curve, from this profile's published run (external PG18, one thread, append-none at 0.32ms/op as the floor): empty ~2.3ms/op, traffic mix ~26ms/op, pinned-stale ~590ms/op at 0.7% relative error -- 2.75M rows walked at ~0.215us each, linear and predictable. The traffic-mix row is also a renewal identity made visible: under any stationary write mix the mean probe walk is about one event per entity active in the stream, so it lands at ~0.22us times the entity count (100k here) whatever the skew. The rejected alternative -- one uniform NOT EXISTS check left to the plan cache -- measures 66ms at 117% error, 1164ms and 1164ms on comparable rows: the plan cache settles on a whole-table sequential scan for the stale and empty boundaries while a 0.06ms custom plan sits unused, which is the measurement behind deriving the check's shape from the criteria instead.
The stale row is the check's one accepted cost, and how a caller avoids it depends on why the cursor is old. A decider whose entity has been moving gets a fresh cursor by re-reading the boundary, which a conflict-retry loop does anyway. A decider whose entity is long idle does not -- its last matching event simply is old, and re-reading cannot advance a cursor past events that do not exist. What the read does establish is that nothing matching sits between that old cursor and the head it read at, so presenting the head claims exactly what was proven and the probe walk collapses: that is decide-then-append-fresh, and the one rule it must keep is to read the head before the boundary, so everything at or below the presented head was visible to the read that decided.
Same corpus as large-tier-writes, so nothing re-provisions; same drift reasoning, same warning about the estimate: the per-trial restore of ten million rows dominates the wall clock, so expect around triple the estimate for the twelve trials.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-06T08:39:34.063541038Z |
| finished | 2026-09-06T09:25:19.110307993Z |
| targets | postgres:external/metrics=off |
| corpus restore | restored once per trial; intra-trial drift measured |
| store drift | 0.72% during the run, against the 10% this profile allows |

> **Not suitable as a published baseline.**
>
> - 2 measurements are too noisy to compare against anything, past the 10% this report calls uncomparable: append-type-and-tag (postgres:external/metrics=off, 1 thread) at 24%, append-empty-boundary (postgres:external/metrics=off, 1 thread) at 21%

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
| no criteria | 3.026 ± 0.114 ops/ms | 1.00x |
| one type set and one tag | 0.038 ± 0.009 ops/ms | 79.32x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.56..61.10 rows=500 width=314) (actual time=0.028..0.168 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..667627.36 rows=5514438 width=314) (actual time=0.027..0.144 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=75
Planning Time: 0.317 ms
Execution Time: 0.195 ms
```

### tag needle (~10 matches)

```
Sort  (cost=884.47..886.31 rows=735 width=314) (actual time=0.518..0.518 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=83
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=21.95..849.48 rows=735 width=314) (actual time=0.474..0.503 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=75
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..21.77 rows=735 width=0) (actual time=0.460..0.461 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=65
Planning:
  Buffers: shared hit=12
Planning Time: 0.116 ms
Execution Time: 0.558 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..5777.51 rows=500 width=314) (actual time=0.021..7.504 rows=500.00 loops=1)
  Buffers: shared hit=1444
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..654124.34 rows=56615 width=314) (actual time=0.021..7.477 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=1444
Planning:
  Buffers: shared hit=3
Planning Time: 0.078 ms
Execution Time: 7.534 ms
```

### one entity's whole history (hot) — **JIT 3ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
Sort  (cost=222808.37..223403.01 rows=237856 width=314) (actual time=554.517..565.483 rows=455092.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 126338kB
  Buffers: shared hit=188245
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1373.52..201568.15 rows=237856 width=314) (actual time=119.550..419.008 rows=455092.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=184595
        Buffers: shared hit=188245
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1314.06 rows=237879 width=0) (actual time=96.210..96.211 rows=455092.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=3650
Planning:
  Buffers: shared hit=3
Planning Time: 0.123 ms
JIT:
  Functions: 6
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.274 ms (Deform 0.125 ms), Inlining 0.000 ms, Optimization 0.228 ms, Emission 2.481 ms, Total 2.983 ms
Execution Time: 592.974 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..3.32 rows=1 width=314) (actual time=0.025..0.025 rows=1.00 loops=1)
  Buffers: shared hit=6
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..655030.54 rows=237856 width=314) (actual time=0.024..0.024 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared hit=6
Planning:
  Buffers: shared hit=11
Planning Time: 0.159 ms
Execution Time: 0.038 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..76.70 rows=500 width=314) (actual time=0.021..0.166 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..610799.28 rows=4011051 width=314) (actual time=0.021..0.143 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('3087567'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=14
Planning Time: 0.121 ms
Execution Time: 0.201 ms
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
Limit  (cost=0.56..0.60 rows=1 width=4) (actual time=0.033..0.033 rows=1.00 loops=1)
  Buffers: shared hit=5
  ->  Index Only Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..38749.12 rows=966832 width=4) (actual time=0.032..0.032 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=5
Planning:
  Buffers: shared hit=2
Planning Time: 0.120 ms
Execution Time: 0.046 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..9.11 rows=1 width=4) (actual time=0.067..0.067 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..484475.63 rows=56659 width=4) (actual time=0.066..0.066 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=3
Planning Time: 0.114 ms
Execution Time: 0.078 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..76.02 rows=1 width=4) (actual time=0.042..0.043 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..484475.63 rows=6420 width=4) (actual time=0.042..0.042 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[])
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=3
Planning Time: 0.100 ms
Execution Time: 0.052 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..9.14 rows=1 width=4) (actual time=0.033..0.033 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..487759.54 rows=56826 width=4) (actual time=0.033..0.033 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]))
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=6
Planning Time: 0.082 ms
Execution Time: 0.040 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..9.24 rows=1 width=4) (actual time=0.029..0.029 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..497611.30 rows=57329 width=4) (actual time=0.029..0.029 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]))
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=12
Planning Time: 0.091 ms
Execution Time: 0.035 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..9.40 rows=1 width=4) (actual time=0.029..0.029 rows=0.00 loops=1)
  Buffers: shared hit=23
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..514030.88 rows=58166 width=4) (actual time=0.028..0.029 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088944'::xid8, '5499988'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]) OR (event_tags @> '{sku:SKU-012505}'::text[]) OR (event_tags @> '{sku:SKU-012506}'::text[]) OR (event_tags @> '{sku:SKU-012507}'::text[]) OR (event_tags @> '{sku:SKU-012508}'::text[]) OR (event_tags @> '{sku:SKU-012509}'::text[]))
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=23
Planning:
  Buffers: shared hit=22
Planning Time: 0.122 ms
Execution Time: 0.036 ms
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

### DCB check as issued: append-type-and-tag @ postgres:external/metrics=off (collision=spread, generic plan) — measured 26.21 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-046045","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{sku:SKU-046045,warehouse:WH-1,channel:web}', $8 = 'inventory', $9 = 'default', $10 = '3088820', $11 = '5250421', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-046045'
	Insert on bm_n3tx9gechuj9_events  (cost=165.76..165.78 rows=1 width=264) (actual time=50.687..50.690 rows=1.00 loops=1)
	  Buffers: shared hit=12317
	  InitPlan 1
	    ->  Limit  (cost=0.56..165.76 rows=1 width=16) (actual time=50.564..50.564 rows=0.00 loops=1)
	          Buffers: shared hit=12299
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..500696.99 rows=3031 width=16) (actual time=50.563..50.563 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 249587
	                Index Searches: 1
	                Buffers: shared hit=12299
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=50.617..50.617 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=12300
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.032..0.032 rows=1.00 loops=1)
```

### DCB check as issued: append-type-and-tag @ postgres:external/metrics=off (collision=spread, custom plan, first executions only) — measured 26.21 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-053343","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-053343}', $8 = 'inventory', $9 = 'default', $10 = '3088919', $11 = '5449844', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-053343'
	Insert on bm_n3tx9gechuj9_events  (cost=849.75..849.77 rows=1 width=264) (actual time=0.272..0.273 rows=1.00 loops=1)
	  Buffers: shared hit=88
	  InitPlan 1
	    ->  Limit  (cost=849.74..849.75 rows=1 width=16) (actual time=0.228..0.228 rows=0.00 loops=1)
	          Buffers: shared hit=70
	          ->  Sort  (cost=849.74..850.19 rows=179 width=16) (actual time=0.228..0.228 rows=0.00 loops=1)
	                Sort Key: bm_n3tx9gechuj9_events_1.event_tx, bm_n3tx9gechuj9_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=70
	                ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=23.16..848.85 rows=179 width=16) (actual time=0.227..0.227 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-053343}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('3088919'::xid8, '5449844'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Rows Removed by Filter: 9
	                      Heap Blocks: exact=9
	                      Buffers: shared hit=70
	                      ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..23.12 rows=735 width=0) (actual time=0.219..0.219 rows=9.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-053343}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=61
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.240..0.240 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=71
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.008..0.008 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append-fresh @ postgres:external/metrics=off (collision=spread, generic plan) — measured 3.94 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-001135","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-001135}', $8 = 'inventory', $9 = 'default', $10 = '12895483', $11 = '10000017', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-001135'
	Insert on bm_n3tx9gechuj9_events  (cost=165.76..165.78 rows=1 width=264) (actual time=0.078..0.079 rows=1.00 loops=1)
	  Buffers: shared hit=22
	  InitPlan 1
	    ->  Limit  (cost=0.56..165.76 rows=1 width=16) (actual time=0.011..0.012 rows=0.00 loops=1)
	          Buffers: shared hit=4
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..500696.99 rows=3031 width=16) (actual time=0.011..0.011 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Index Searches: 1
	                Buffers: shared hit=4
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.036..0.036 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=5
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.018..0.018 rows=1.00 loops=1)
```

### DCB check as issued: append-stale-boundary @ postgres:external/metrics=off (collision=spread, generic plan) — measured 545.37 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-000153","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-000153}', $8 = 'inventory', $9 = 'default', $10 = '3087567', $11 = '2750000', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-STALE-PROBE'
	Insert on bm_n3tx9gechuj9_events  (cost=165.76..165.78 rows=1 width=264) (actual time=533.485..533.488 rows=1.00 loops=1)
	  Buffers: shared hit=141858
	  InitPlan 1
	    ->  Limit  (cost=0.56..165.76 rows=1 width=16) (actual time=533.369..533.369 rows=0.00 loops=1)
	          Buffers: shared hit=141840
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..500696.99 rows=3031 width=16) (actual time=533.368..533.368 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 2750026
	                Index Searches: 1
	                Buffers: shared hit=141840
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=533.418..533.419 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=141841
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.036..0.036 rows=1.00 loops=1)
```

### DCB check as issued: append-stale-boundary @ postgres:external/metrics=off (collision=spread, custom plan, first executions only) — measured 545.37 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-076201","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-076201}', $8 = 'inventory', $9 = 'default', $10 = '3087567', $11 = '2750000', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-STALE-PROBE'
	Insert on bm_n3tx9gechuj9_events  (cost=851.66..851.68 rows=1 width=264) (actual time=0.072..0.073 rows=1.00 loops=1)
	  Buffers: shared hit=35
	  InitPlan 1
	    ->  Limit  (cost=851.66..851.66 rows=1 width=16) (actual time=0.043..0.044 rows=0.00 loops=1)
	          Buffers: shared hit=17
	          ->  Sort  (cost=851.66..852.37 rows=286 width=16) (actual time=0.043..0.043 rows=0.00 loops=1)
	                Sort Key: bm_n3tx9gechuj9_events_1.event_tx, bm_n3tx9gechuj9_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=17
	                ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=24.54..850.23 rows=286 width=16) (actual time=0.042..0.042 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-STALE-PROBE}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('3087567'::xid8, '2750000'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Buffers: shared hit=17
	                      ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..24.47 rows=735 width=0) (actual time=0.033..0.033 rows=0.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-STALE-PROBE}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=17
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.051..0.051 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=18
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.004..0.005 rows=1.00 loops=1)
```

### DCB check as issued: append-empty-boundary @ postgres:external/metrics=off (collision=spread, generic plan) — measured 2.31 ms/op

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE NOT EXISTS (
		SELECT 1 FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND ((event_type IN ($10, $11, $12, $13) AND event_tags @> ARRAY[$14]::text[]))) RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-N0-8","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-N0-8}', $8 = 'inventory', $9 = 'default', $10 = 'StockReleased', $11 = 'StockReserved', $12 = 'StockPicked', $13 = 'StockReceived', $14 = 'sku:SKU-N0-8'
	Insert on bm_n3tx9gechuj9_events  (cost=55.90..55.92 rows=1 width=264) (actual time=1849.277..1849.280 rows=1.00 loops=1)
	  Buffers: shared hit=892816
	  InitPlan 1
	    ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..503169.16 rows=9093 width=0) (actual time=1849.148..1849.149 rows=0.00 loops=1)
	          Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (event_type = ANY (ARRAY[($10)::text, ($11)::text, ($12)::text, ($13)::text])))
	          Filter: (event_tags @> ARRAY[($14)::text])
	          Rows Removed by Filter: 5334842
	          Index Searches: 1
	          Buffers: shared hit=892794
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=1849.202..1849.202 rows=1.00 loops=1)
	        One-Time Filter: (NOT (InitPlan 1).col1)
	        Buffers: shared hit=892795
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.039..0.039 rows=1.00 loops=1)
```

### DCB check as issued: append-empty-boundary @ postgres:external/metrics=off (collision=spread, custom plan, first executions only) — measured 2.31 ms/op

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE NOT EXISTS (
		SELECT 1 FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND ((event_type IN ($10, $11, $12, $13) AND event_tags @> ARRAY[$14]::text[]))) RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-N0-17","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-N0-17}', $8 = 'inventory', $9 = 'default', $10 = 'StockReleased', $11 = 'StockReserved', $12 = 'StockPicked', $13 = 'StockReceived', $14 = 'sku:SKU-N0-17'
	Insert on bm_n3tx9gechuj9_events  (cost=26.65..26.67 rows=1 width=264) (actual time=0.064..0.065 rows=1.00 loops=1)
	  Buffers: shared hit=35
	  InitPlan 1
	    ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=24.57..846.58 rows=394 width=0) (actual time=0.035..0.035 rows=0.00 loops=1)
	          Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-N0-17}'::text[]))
	          Filter: (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[]))
	          Buffers: shared hit=17
	          ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..24.47 rows=735 width=0) (actual time=0.033..0.033 rows=0.00 loops=1)
	                Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-N0-17}'::text[]))
	                Index Searches: 1
	                Buffers: shared hit=17
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.042..0.042 rows=1.00 loops=1)
	        One-Time Filter: (NOT (InitPlan 1).col1)
	        Buffers: shared hit=18
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.005..0.005 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | append-empty-boundary | thrpt | 1 | 0.432 | ops/ms | 21.0% | 432 | 20,750 | 0 |
| postgres:external/metrics=off | append-none | thrpt | 1 | 3.026 | ops/ms | 3.8% | 3,026 | 145,252 | 0 |
| postgres:external/metrics=off | append-stale-boundary | thrpt | 1 | 0.002 | ops/ms | 1.0% | 2 | 96 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 1 | 0.038 | ops/ms | 23.5% | 38 | 1,851 | 0 |
| postgres:external/metrics=off | decide-then-append-fresh | thrpt | 1 | 0.254 | ops/ms | 7.6% | 254 | 12,200 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
