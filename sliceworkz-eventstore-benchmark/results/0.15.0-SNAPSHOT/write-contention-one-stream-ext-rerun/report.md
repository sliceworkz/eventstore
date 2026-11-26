# Benchmark run: write-contention-one-stream-ext

write-contention-one-stream against an external server, PostgreSQL only: every writer conditionally appending to one stream, serialised on that stream's advisory lock with no logical conflict. The external target is what lets the run record the server's CPU per decision (-Dbenchmark.server.cpu=<the server's cgroup cpu.stat>) and be published. Pair it with write-contention-one-stream-ext-latency, the same profile with the server a network hop away: the difference between the two is what the round trips inside the lock hold cost. See docs/design/append-lock-hold.md.

| | |
|---|---|
| suite version | 0.15.0-SNAPSHOT |
| started | 2026-11-26T19:24:38.596264941Z |
| finished | 2026-11-26T19:57:30.036295612Z |
| targets | postgres:external |
| corpus restore | restored before every iteration |

## Environment

These are the settings the numbers below depend on. Two runs whose environments differ are not comparable, and the comparator refuses rather than reporting a difference in hardware as a change in the store.

### JVM

| setting | value |
|---|---|
| java.vendor | Ubuntu |
| java.version | 25.0.5.1 |
| java.vm.name | OpenJDK 64-Bit Server VM |
| java.vm.version | 25.0.5.1+2-1-26.04.1-Ubuntu |
| max.heap.bytes | 16399728640 |

### Host

| setting | value |
|---|---|
| available.processors | 16 |
| cpu.model | AMD Ryzen AI 7 350 w/ Radeon 860M |
| memory.total | 64055508 kB |
| os.arch | amd64 |
| os.name | Linux |
| os.version | 7.0.0-43-generic |

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
| fingerprint | `bm_63j2j1n30jm3_` |
| volume | 100,000 events under test |
| stream design | PER_ENTITY |
| composition | CLEAN |
| payload | REALISTIC |
| entities | 2,000 |
| hot entity | `SKU-000000`, 6,876 events |
| cold entity | `SKU-001729`, 1 events |
| needle tag | 10 matches |
| swathe tag | 1,000 matches |
| mean payload | 141 bytes (sales) |

## What this run says

### What the DCB check costs

| append | throughput | relative |
|---|---|---|
| no criteria | 3.648 ± 0.062 ops/ms | 1.00x |
| one type set and one tag | 1.916 ± 0.041 ops/ms | 1.90x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 3.648 ± 0.062 ops/ms | 3,648 | 0.0% |
| append-none | 4 | 4.998 ± 0.070 ops/ms | 4,998 | 0.0% |
| append-none | 8 | 5.019 ± 0.066 ops/ms | 5,019 | 0.0% |
| append-none | 16 | 4.956 ± 0.076 ops/ms | 4,956 | 0.0% |
| append-type-and-tag | 1 | 1.916 ± 0.041 ops/ms | 1,916 | 0.0% |
| append-type-and-tag | 4 | 2.085 ± 0.035 ops/ms | 2,085 | 0.0% |
| append-type-and-tag | 8 | 2.018 ± 0.028 ops/ms | 2,018 | 0.0% |
| append-type-and-tag | 16 | 1.914 ± 0.022 ops/ms | 1,914 | 0.0% |
| decide-then-append | 1 | 0.205 ± 0.011 ops/ms | 205 | 0.0% |
| decide-then-append | 4 | 0.759 ± 0.014 ops/ms | 759 | 0.0% |
| decide-then-append | 8 | 1.212 ± 0.014 ops/ms | 1,212 | 0.0% |
| decide-then-append | 16 | 1.311 ± 0.026 ops/ms | 1,311 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.42..47.60 rows=500 width=313) (actual time=0.035..0.245 rows=500.00 loops=1)
  Buffers: shared hit=26
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_context_order on bm_63j2j1n30jm3_events  (cost=0.42..5216.14 rows=55283 width=313) (actual time=0.034..0.219 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=26
Planning:
  Buffers: shared hit=28
Planning Time: 0.359 ms
Execution Time: 0.279 ms
```

### tag needle (~10 matches)

```
Sort  (cost=15.03..15.05 rows=7 width=313) (actual time=0.226..0.226 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=39
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=7.05..14.94 rows=7 width=313) (actual time=0.182..0.209 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: ((event_tx > '0'::xid8) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Heap Blocks: exact=10
        Buffers: shared hit=31
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..7.05 rows=7 width=0) (actual time=0.171..0.171 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=21
Planning:
  Buffers: shared hit=12
Planning Time: 0.131 ms
Execution Time: 0.270 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=573.57..574.82 rows=500 width=313) (actual time=3.423..3.464 rows=500.00 loops=1)
  Buffers: shared hit=1021
  ->  Sort  (cost=573.57..574.87 rows=520 width=313) (actual time=3.422..3.438 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 527kB
        Buffers: shared hit=1021
        ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=9.74..550.11 rows=520 width=313) (actual time=0.431..3.108 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: ((event_tx > '0'::xid8) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
              Heap Blocks: exact=1000
              Buffers: shared hit=1021
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..9.61 rows=520 width=0) (actual time=0.344..0.344 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=21
Planning:
  Buffers: shared hit=3
Planning Time: 0.090 ms
Execution Time: 3.517 ms
```

### one entity's whole history (hot)

```
Sort  (cost=316.74..317.42 rows=274 width=313) (actual time=6.602..6.768 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1953kB
  Buffers: shared hit=2066
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=11.36..305.64 rows=274 width=313) (actual time=1.482..4.848 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=2037
        Buffers: shared hit=2066
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..11.29 rows=274 width=0) (actual time=1.297..1.298 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=29
Planning:
  Buffers: shared hit=3
Planning Time: 0.132 ms
Execution Time: 7.124 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.42..6.05 rows=1 width=313) (actual time=0.025..0.025 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan Backward using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events  (cost=0.42..1543.06 rows=274 width=313) (actual time=0.024..0.024 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=3
Planning Time: 0.125 ms
Execution Time: 0.038 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..56.32 rows=500 width=313) (actual time=0.014..0.135 rows=500.00 loops=1)
  Buffers: shared hit=25
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_context_order on bm_63j2j1n30jm3_events  (cost=0.42..4450.56 rows=39808 width=313) (actual time=0.014..0.112 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('15882336'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=25
Planning:
  Buffers: shared hit=14
Planning Time: 0.091 ms
Execution Time: 0.165 ms
```

> **The plans below do not describe the store's own execution.** They inline the tag arrays 
> and the cursor as literals, which is what PostgreSQL sees when it builds a *custom* plan; 
> the store binds them as JDBC parameters and re-uses the statement, so what it actually runs 
> is whichever of the custom and generic plans the server settled on -- and for several of 
> these shapes that is the generic one, which is a different plan entirely. Read these as the 
> shape of the predicate. The captured plans further down are the ones to read against the 
> measurements.

### DCB check: event types only, no tag (append-types) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..0.46 rows=1 width=4) (actual time=0.013..0.013 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Only Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..28.63 rows=672 width=4) (actual time=0.012..0.013 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=2
Planning Time: 0.053 ms
Execution Time: 0.019 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..7.62 rows=1 width=4) (actual time=0.014..0.015 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..461.06 rows=64 width=4) (actual time=0.014..0.014 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=3
Planning Time: 0.051 ms
Execution Time: 0.018 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=15.86..20.66 rows=1 width=4) (actual time=1.787..1.788 rows=0.00 loops=1)
  Buffers: shared hit=650
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=15.86..49.44 rows=7 width=4) (actual time=1.787..1.787 rows=0.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]))
        Filter: ((event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Rows Removed by Filter: 705
        Heap Blocks: exact=602
        Buffers: shared hit=650
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..15.86 rows=30 width=0) (actual time=1.226..1.226 rows=705.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]))
              Index Searches: 1
              Buffers: shared hit=48
Planning:
  Buffers: shared hit=3
Planning Time: 0.041 ms
Execution Time: 1.795 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..7.65 rows=1 width=4) (actual time=0.011..0.012 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..463.34 rows=64 width=4) (actual time=0.011..0.011 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=6
Planning Time: 0.070 ms
Execution Time: 0.016 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..7.64 rows=1 width=4) (actual time=0.006..0.007 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..470.18 rows=65 width=4) (actual time=0.006..0.006 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=12
Planning Time: 0.075 ms
Execution Time: 0.010 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..7.71 rows=1 width=4) (actual time=0.006..0.006 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..481.58 rows=66 width=4) (actual time=0.006..0.006 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]) OR (event_tags @> '{sku:SKU-000255}'::text[]) OR (event_tags @> '{sku:SKU-000256}'::text[]) OR (event_tags @> '{sku:SKU-000257}'::text[]) OR (event_tags @> '{sku:SKU-000258}'::text[]) OR (event_tags @> '{sku:SKU-000259}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=22
Planning Time: 0.118 ms
Execution Time: 0.010 ms
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

### DCB check as issued: append-type-and-tag @ postgres:external (collision=one-stream, generic plan) — measured 0.52 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text:  INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-001183","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-001183}', $7 = 'inventory', $8 = 'SKU-000000', $9 = '15882344', $10 = '42148', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-001183'
	Insert on bm_63j2j1n30jm3_events  (cost=4.70..4.72 rows=1 width=232) (actual time=1.112..1.114 rows=1.00 loops=1)
	  Buffers: shared hit=531
	  InitPlan 1
	    ->  Limit  (cost=0.42..4.70 rows=1 width=16) (actual time=1.033..1.033 rows=0.00 loops=1)
	          Buffers: shared hit=512
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..4.70 rows=1 width=16) (actual time=1.033..1.033 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Rows Removed by Filter: 1713
	                Index Searches: 1
	                Buffers: shared hit=512
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=1.061..1.062 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=513
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.021..0.021 rows=1.00 loops=1)
```

### DCB check as issued: append-type-and-tag @ postgres:external (collision=one-stream, custom plan, first executions only) — measured 0.52 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text:  INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-000024","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-000024}', $7 = 'inventory', $8 = 'SKU-000000', $9 = '15882350', $10 = '54812', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-000024'
	Insert on bm_63j2j1n30jm3_events  (cost=22.56..22.58 rows=1 width=232) (actual time=0.283..0.284 rows=1.00 loops=1)
	  Buffers: shared hit=47
	  InitPlan 1
	    ->  Limit  (cost=22.56..22.56 rows=1 width=16) (actual time=0.223..0.224 rows=0.00 loops=1)
	          Buffers: shared hit=28
	          ->  Sort  (cost=22.56..22.56 rows=2 width=16) (actual time=0.223..0.223 rows=0.00 loops=1)
	                Sort Key: bm_63j2j1n30jm3_events_1.event_tx, bm_63j2j1n30jm3_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=28
	                ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=11.32..22.55 rows=2 width=16) (actual time=0.220..0.221 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000024}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('15882350'::xid8, '54812'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Buffers: shared hit=28
	                      ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..11.32 rows=10 width=0) (actual time=0.217..0.217 rows=0.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000024}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=28
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.239..0.239 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=29
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.010..0.010 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:external (collision=one-stream, generic plan) — measured 4.88 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text:  INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-000096","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-000096}', $7 = 'inventory', $8 = 'SKU-000000', $9 = '15882350', $10 = '54934', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-000096'
	Insert on bm_63j2j1n30jm3_events  (cost=4.70..4.72 rows=1 width=232) (actual time=0.238..0.241 rows=1.00 loops=1)
	  Buffers: shared hit=32
	  InitPlan 1
	    ->  Limit  (cost=0.42..4.70 rows=1 width=16) (actual time=0.082..0.083 rows=0.00 loops=1)
	          Buffers: shared hit=13
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..4.70 rows=1 width=16) (actual time=0.081..0.081 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Rows Removed by Filter: 26
	                Index Searches: 1
	                Buffers: shared hit=13
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.130..0.131 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=14
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.036..0.037 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts | server CPU/op |
|---|---|---|---|---|---|---|---|---|---|---|
| postgres:external | append-none | thrpt | 1 | 3.648 | ops/ms | 1.7% | 3,648 | 145,949 | 0 | 108 µs |
| postgres:external | append-none | thrpt | 4 | 4.998 | ops/ms | 1.4% | 4,998 | 199,981 | 0 | 129 µs |
| postgres:external | append-none | thrpt | 8 | 5.019 | ops/ms | 1.3% | 5,019 | 200,924 | 0 | 132 µs |
| postgres:external | append-none | thrpt | 16 | 4.956 | ops/ms | 1.5% | 4,956 | 198,777 | 0 | 148 µs |
| postgres:external | append-type-and-tag | thrpt | 1 | 1.916 | ops/ms | 2.1% | 1,916 | 76,653 | 0 | 350 µs |
| postgres:external | append-type-and-tag | thrpt | 4 | 2.085 | ops/ms | 1.7% | 2,085 | 83,484 | 0 | 428 µs |
| postgres:external | append-type-and-tag | thrpt | 8 | 2.018 | ops/ms | 1.4% | 2,018 | 80,937 | 0 | 446 µs |
| postgres:external | append-type-and-tag | thrpt | 16 | 1.914 | ops/ms | 1.1% | 1,914 | 77,018 | 0 | 481 µs |
| postgres:external | decide-then-append | thrpt | 1 | 0.205 | ops/ms | 5.2% | 205 | 8,230 | 0 | 2,757 µs |
| postgres:external | decide-then-append | thrpt | 4 | 0.759 | ops/ms | 1.9% | 759 | 30,594 | 0 | 2,483 µs |
| postgres:external | decide-then-append | thrpt | 8 | 1.212 | ops/ms | 1.2% | 1,212 | 48,932 | 0 | 2,685 µs |
| postgres:external | decide-then-append | thrpt | 16 | 1.311 | ops/ms | 2.0% | 1,311 | 53,137 | 0 | 2,388 µs |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
