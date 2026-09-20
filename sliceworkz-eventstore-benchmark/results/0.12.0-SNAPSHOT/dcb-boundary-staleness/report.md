# Benchmark run: dcb-boundary-staleness

What cursor age does to the DCB check, over the large-tier corpus. The check's shape is derived from the criteria -- the ordered probe walking the position index forward from the cursor when one is present, the custom-planned tag path when not -- and cursor age is the one variable the probe pays for: every stream event after the cursor is a row it walks past to prove absence. This profile pins the age at the points that bracket the regime:

  append-type-and-tag       the boundary as the traffic-weighted walk presents it -- cursors as
                            fresh or as stale as the corpus's own write mix makes them
  decide-then-append-fresh  the recommended decider: the stream head (EventSource.head(), no
                            payload) taken first, the boundary read bounded at it, and the append
                            presents that head, so the probe starts where the reader stopped --
                            the cursor path's floor
  append-stale-boundary     a cursor pinned half the stream old, filter matching nothing, so
                            every invocation proves absence over the full walk -- the ceiling
  append-empty-boundary     no cursor at all -- routed to the tag path, so this row is what the
                            uniqueness pattern ("this basket was never checked out") costs

The measured curve, from this profile's published run (external PG18, one thread, append-none at 0.32ms/op as the floor): empty ~2.3ms/op, traffic mix ~26ms/op, pinned-stale ~590ms/op at 0.7% relative error -- 2.75M rows walked at ~0.215us each, linear and predictable. The traffic-mix row is also a renewal identity made visible: under any stationary write mix the mean probe walk is about one event per entity active in the stream, so it lands at ~0.22us times the entity count (100k here) whatever the skew. The rejected alternative -- one uniform NOT EXISTS check left to the plan cache -- measures 66ms at 117% error, 1164ms and 1164ms on comparable rows: the plan cache settles on a whole-table sequential scan for the stale and empty boundaries while a 0.06ms custom plan sits unused, which is the measurement behind deriving the check's shape from the criteria instead.
The stale row is the check's one accepted cost, and how a caller avoids it depends on why the cursor is old. A decider whose entity has been moving gets a fresh cursor by re-reading the boundary, which a conflict-retry loop does anyway. A decider whose entity is long idle does not -- its last matching event simply is old, and re-reading cannot advance a cursor past events that do not exist. What the read does establish is that nothing matching sits between that old cursor and the head it read at, so presenting the head claims exactly what was proven and the probe walk collapses: that is decide-then-append-fresh, and the one rule it must keep is to read the head (EventSource.head()) before the boundary and bound the boundary read at it, so everything at or below the presented head was visible to the read that decided.
Same corpus as large-tier-writes, so nothing re-provisions; same drift reasoning, same warning about the estimate: the per-trial restore of ten million rows dominates the wall clock, so expect around triple the estimate for the twelve trials.

| | |
|---|---|
| suite version | 0.12.0-SNAPSHOT |
| started | 2026-09-20T12:33:58.572184867Z |
| finished | 2026-09-20T13:24:20.747654687Z |
| targets | postgres:external/metrics=off |
| corpus restore | restored once per trial; intra-trial drift measured |
| store drift | 0.75% during the run, against the 10% this profile allows |

> **Not suitable as a published baseline.**
>
> - 2 measurements are too noisy to compare against anything, past the 10% this report calls uncomparable: append-type-and-tag (postgres:external/metrics=off, 1 thread) at 14%, append-empty-boundary (postgres:external/metrics=off, 1 thread) at 21%

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
| memory.total | 64055544 kB |
| os.arch | amd64 |
| os.name | Linux |
| os.version | 7.0.0-31-generic |

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
| no criteria | 3.107 ± 0.113 ops/ms | 1.00x |
| one type set and one tag | 0.040 ± 0.006 ops/ms | 78.59x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.56..50.28 rows=500 width=314) (actual time=0.027..0.163 rows=500.00 loops=1)
  Buffers: shared hit=27
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..540786.35 rows=5438681 width=314) (actual time=0.026..0.139 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=27
Planning:
  Buffers: shared hit=23
Planning Time: 0.265 ms
Execution Time: 0.190 ms
```

### tag needle (~10 matches)

```
Sort  (cost=872.63..874.44 rows=725 width=314) (actual time=0.338..0.338 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=78
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=21.90..838.18 rows=725 width=314) (actual time=0.298..0.324 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=70
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..21.72 rows=725 width=0) (actual time=0.287..0.287 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=60
Planning:
  Buffers: shared hit=12
Planning Time: 0.094 ms
Execution Time: 0.371 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..4770.30 rows=500 width=314) (actual time=0.020..5.405 rows=500.00 loops=1)
  Buffers: shared hit=1279
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..527466.11 rows=55293 width=314) (actual time=0.019..5.382 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=1279
Planning:
  Buffers: shared hit=3
Planning Time: 0.065 ms
Execution Time: 5.427 ms
```

### one entity's whole history (hot) — **JIT 3ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
Sort  (cost=221852.03..222443.49 rows=236583 width=314) (actual time=520.598..531.600 rows=455092.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 127577kB
  Buffers: shared hit=188203
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1365.61..200734.65 rows=236583 width=314) (actual time=120.981..386.316 rows=455092.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=184595
        Buffers: shared hit=188203
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1306.47 rows=236605 width=0) (actual time=98.469..98.470 rows=455092.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=3608
Planning:
  Buffers: shared hit=3
Planning Time: 0.066 ms
JIT:
  Functions: 6
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.259 ms (Deform 0.118 ms), Inlining 0.000 ms, Optimization 0.213 ms, Emission 2.323 ms, Total 2.795 ms
Execution Time: 555.692 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..2.80 rows=1 width=314) (actual time=0.028..0.029 rows=1.00 loops=1)
  Buffers: shared hit=6
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..528372.56 rows=236583 width=314) (actual time=0.027..0.027 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared hit=6
Planning:
  Buffers: shared hit=3
Planning Time: 0.138 ms
Execution Time: 0.042 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..58.59 rows=500 width=314) (actual time=0.016..0.152 rows=500.00 loops=1)
  Buffers: shared hit=29
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..461296.24 rows=3974903 width=314) (actual time=0.015..0.130 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('15014800'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=29
Planning:
  Buffers: shared hit=14
Planning Time: 0.128 ms
Execution Time: 0.185 ms
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
Limit  (cost=0.56..0.60 rows=1 width=4) (actual time=0.053..0.054 rows=1.00 loops=1)
  Buffers: shared hit=5
  ->  Index Only Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..37260.47 rows=976278 width=4) (actual time=0.051..0.051 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15016176'::xid8, '5499988'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=5
Planning:
  Buffers: shared hit=2
Planning Time: 0.276 ms
Execution Time: 0.085 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..5.70 rows=1 width=4) (actual time=0.061..0.061 rows=0.00 loops=1)
  Buffers: shared hit=22
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..293760.35 rows=57188 width=4) (actual time=0.060..0.060 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15016176'::xid8, '5499988'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=22
Planning:
  Buffers: shared hit=3
Planning Time: 0.075 ms
Execution Time: 0.068 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..46.49 rows=1 width=4) (actual time=0.030..0.030 rows=0.00 loops=1)
  Buffers: shared hit=22
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..293760.35 rows=6396 width=4) (actual time=0.030..0.030 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15016176'::xid8, '5499988'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[])
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=22
Planning:
  Buffers: shared hit=3
Planning Time: 0.048 ms
Execution Time: 0.034 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..5.74 rows=1 width=4) (actual time=0.026..0.026 rows=0.00 loops=1)
  Buffers: shared hit=22
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..297047.02 rows=57356 width=4) (actual time=0.025..0.025 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15016176'::xid8, '5499988'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]))
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=22
Planning:
  Buffers: shared hit=6
Planning Time: 0.061 ms
Execution Time: 0.030 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..5.86 rows=1 width=4) (actual time=0.023..0.023 rows=0.00 loops=1)
  Buffers: shared hit=22
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..306907.03 rows=57859 width=4) (actual time=0.022..0.022 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15016176'::xid8, '5499988'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]))
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=22
Planning:
  Buffers: shared hit=12
Planning Time: 0.069 ms
Execution Time: 0.026 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 12 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..6.07 rows=1 width=4) (actual time=0.024..0.024 rows=0.00 loops=1)
  Buffers: shared hit=22
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..323340.38 rows=58696 width=4) (actual time=0.023..0.023 rows=0.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15016176'::xid8, '5499988'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]) OR (event_tags @> '{sku:SKU-012505}'::text[]) OR (event_tags @> '{sku:SKU-012506}'::text[]) OR (event_tags @> '{sku:SKU-012507}'::text[]) OR (event_tags @> '{sku:SKU-012508}'::text[]) OR (event_tags @> '{sku:SKU-012509}'::text[]))
        Rows Removed by Filter: 12
        Index Searches: 4
        Buffers: shared hit=22
Planning:
  Buffers: shared hit=22
Planning Time: 0.102 ms
Execution Time: 0.028 ms
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

### DCB check as issued: append-type-and-tag @ postgres:external/metrics=off (collision=spread, generic plan) — measured 25.29 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-046045","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{sku:SKU-046045,warehouse:WH-1,channel:web}', $7 = 'inventory', $8 = 'default', $9 = '15016052', $10 = '5250421', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-046045'
	Insert on bm_n3tx9gechuj9_events  (cost=104.41..104.43 rows=1 width=232) (actual time=49.799..49.802 rows=1.00 loops=1)
	  Buffers: shared hit=11464
	  InitPlan 1
	    ->  Limit  (cost=0.56..104.41 rows=1 width=16) (actual time=49.668..49.669 rows=0.00 loops=1)
	          Buffers: shared hit=11440
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..314437.87 rows=3028 width=16) (actual time=49.667..49.668 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Rows Removed by Filter: 249587
	                Index Searches: 1
	                Buffers: shared hit=11440
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=49.713..49.713 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=11441
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.033..0.033 rows=1.00 loops=1)
```

### DCB check as issued: append-type-and-tag @ postgres:external/metrics=off (collision=spread, custom plan, first executions only) — measured 25.29 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-053343","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-053343}', $7 = 'inventory', $8 = 'default', $9 = '15016151', $10 = '5449844', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-053343'
	Insert on bm_n3tx9gechuj9_events  (cost=838.46..838.48 rows=1 width=232) (actual time=0.246..0.248 rows=1.00 loops=1)
	  Buffers: shared hit=91
	  InitPlan 1
	    ->  Limit  (cost=838.46..838.46 rows=1 width=16) (actual time=0.208..0.208 rows=0.00 loops=1)
	          Buffers: shared hit=70
	          ->  Sort  (cost=838.46..838.90 rows=175 width=16) (actual time=0.208..0.208 rows=0.00 loops=1)
	                Sort Key: bm_n3tx9gechuj9_events_1.event_tx, bm_n3tx9gechuj9_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=70
	                ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=23.11..837.58 rows=175 width=16) (actual time=0.207..0.207 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-053343}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('15016151'::xid8, '5449844'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Rows Removed by Filter: 9
	                      Heap Blocks: exact=9
	                      Buffers: shared hit=70
	                      ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..23.07 rows=725 width=0) (actual time=0.200..0.200 rows=9.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-053343}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=61
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.217..0.217 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=71
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.006..0.006 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append-fresh @ postgres:external/metrics=off (collision=spread, generic plan) — measured 3.98 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-001135","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-001135}', $7 = 'inventory', $8 = 'default', $9 = '15302142', $10 = '10000017', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-001135'
	Insert on bm_n3tx9gechuj9_events  (cost=104.41..104.43 rows=1 width=232) (actual time=0.104..0.106 rows=1.00 loops=1)
	  Buffers: shared hit=25
	  InitPlan 1
	    ->  Limit  (cost=0.56..104.41 rows=1 width=16) (actual time=0.016..0.016 rows=0.00 loops=1)
	          Buffers: shared hit=4
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..314437.87 rows=3028 width=16) (actual time=0.016..0.016 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Index Searches: 1
	                Buffers: shared hit=4
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.045..0.045 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=5
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.022..0.022 rows=1.00 loops=1)
```

### DCB check as issued: append-stale-boundary @ postgres:external/metrics=off (collision=spread, generic plan) — measured 517.86 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-000153","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-000153}', $7 = 'inventory', $8 = 'default', $9 = '15014800', $10 = '2750000', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-STALE-PROBE'
	Insert on bm_n3tx9gechuj9_events  (cost=104.41..104.43 rows=1 width=232) (actual time=511.735..511.738 rows=1.00 loops=1)
	  Buffers: shared hit=126794
	  InitPlan 1
	    ->  Limit  (cost=0.56..104.41 rows=1 width=16) (actual time=511.599..511.599 rows=0.00 loops=1)
	          Buffers: shared hit=126770
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..314437.87 rows=3028 width=16) (actual time=511.597..511.597 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Rows Removed by Filter: 2750026
	                Index Searches: 1
	                Buffers: shared hit=126770
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=511.653..511.653 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=126771
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.039..0.039 rows=1.00 loops=1)
```

### DCB check as issued: append-stale-boundary @ postgres:external/metrics=off (collision=spread, custom plan, first executions only) — measured 517.86 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-076201","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-076201}', $7 = 'inventory', $8 = 'default', $9 = '15014800', $10 = '2750000', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-STALE-PROBE'
	Insert on bm_n3tx9gechuj9_events  (cost=840.36..840.38 rows=1 width=232) (actual time=0.082..0.083 rows=1.00 loops=1)
	  Buffers: shared hit=38
	  InitPlan 1
	    ->  Limit  (cost=840.36..840.36 rows=1 width=16) (actual time=0.043..0.043 rows=0.00 loops=1)
	          Buffers: shared hit=17
	          ->  Sort  (cost=840.36..841.06 rows=280 width=16) (actual time=0.042..0.042 rows=0.00 loops=1)
	                Sort Key: bm_n3tx9gechuj9_events_1.event_tx, bm_n3tx9gechuj9_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=17
	                ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=24.49..838.96 rows=280 width=16) (actual time=0.041..0.041 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-STALE-PROBE}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('15014800'::xid8, '2750000'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Buffers: shared hit=17
	                      ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..24.42 rows=725 width=0) (actual time=0.034..0.034 rows=0.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-STALE-PROBE}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=17
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.052..0.052 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=18
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.005..0.006 rows=1.00 loops=1)
```

### DCB check as issued: append-empty-boundary @ postgres:external/metrics=off (collision=spread, generic plan) — measured 2.35 ms/op

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE NOT EXISTS (
		SELECT 1 FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND ((event_type IN ($9, $10, $11, $12) AND event_tags @> ARRAY[$13]::text[]))) RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-N0-8","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-N0-8}', $7 = 'inventory', $8 = 'default', $9 = 'StockReleased', $10 = 'StockReserved', $11 = 'StockPicked', $12 = 'StockReceived', $13 = 'sku:SKU-N0-8'
	Insert on bm_n3tx9gechuj9_events  (cost=35.92..35.94 rows=1 width=232) (actual time=1729.693..1729.696 rows=1.00 loops=1)
	  Buffers: shared hit=874820
	  InitPlan 1
	    ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..321248.02 rows=9085 width=0) (actual time=1729.554..1729.554 rows=0.00 loops=1)
	          Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (event_type = ANY (ARRAY[($9)::text, ($10)::text, ($11)::text, ($12)::text])))
	          Filter: (event_tags @> ARRAY[($13)::text])
	          Rows Removed by Filter: 5334842
	          Index Searches: 1
	          Buffers: shared hit=874796
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=1729.605..1729.605 rows=1.00 loops=1)
	        One-Time Filter: (NOT (InitPlan 1).col1)
	        Buffers: shared hit=874797
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.037..0.037 rows=1.00 loops=1)
```

### DCB check as issued: append-empty-boundary @ postgres:external/metrics=off (collision=spread, custom plan, first executions only) — measured 2.35 ms/op

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE NOT EXISTS (
		SELECT 1 FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND ((event_type IN ($9, $10, $11, $12) AND event_tags @> ARRAY[$13]::text[]))) RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-N0-17","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-N0-17}', $7 = 'inventory', $8 = 'default', $9 = 'StockReleased', $10 = 'StockReserved', $11 = 'StockPicked', $12 = 'StockReceived', $13 = 'sku:SKU-N0-17'
	Insert on bm_n3tx9gechuj9_events  (cost=26.64..26.66 rows=1 width=232) (actual time=0.128..0.130 rows=1.00 loops=1)
	  Buffers: shared hit=38
	  InitPlan 1
	    ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=24.51..835.36 rows=382 width=0) (actual time=0.070..0.070 rows=0.00 loops=1)
	          Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-N0-17}'::text[]))
	          Filter: (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[]))
	          Buffers: shared hit=17
	          ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..24.42 rows=725 width=0) (actual time=0.067..0.067 rows=0.00 loops=1)
	                Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-N0-17}'::text[]))
	                Index Searches: 1
	                Buffers: shared hit=17
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.083..0.084 rows=1.00 loops=1)
	        One-Time Filter: (NOT (InitPlan 1).col1)
	        Buffers: shared hit=18
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.008..0.009 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | append-empty-boundary | thrpt | 1 | 0.425 | ops/ms | 21.4% | 425 | 20,410 | 0 |
| postgres:external/metrics=off | append-none | thrpt | 1 | 3.107 | ops/ms | 3.6% | 3,107 | 149,287 | 0 |
| postgres:external/metrics=off | append-stale-boundary | thrpt | 1 | 0.002 | ops/ms | 1.0% | 2 | 96 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 1 | 0.040 | ops/ms | 14.2% | 40 | 1,932 | 0 |
| postgres:external/metrics=off | decide-then-append-fresh | thrpt | 1 | 0.251 | ops/ms | 5.4% | 251 | 12,070 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
