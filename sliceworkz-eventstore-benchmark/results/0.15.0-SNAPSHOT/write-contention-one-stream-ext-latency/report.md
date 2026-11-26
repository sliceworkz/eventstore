# Benchmark run: write-contention-one-stream-ext-latency

write-contention-one-stream-ext with the server a network hop away: run it with db.properties pointing at a server whose round trip has been lengthened -- kernel shaping (tc qdisc ... netem delay) on the server's interface, or the suite's own delay-proxy -- and state the measured round trip when quoting it, since the manifest cannot. Against write-contention-one-stream-ext, the difference is what the round trips inside the advisory lock hold cost a stream every writer appends to. See docs/design/append-lock-hold.md.

| | |
|---|---|
| suite version | 0.15.0-SNAPSHOT |
| started | 2026-11-26T17:50:07.003367257Z |
| finished | 2026-11-26T18:23:14.901007310Z |
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
| no criteria | 0.260 ± 0.002 ops/ms | 1.00x |
| one type set and one tag | 0.312 ± 0.003 ops/ms | 1.20x faster |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

> The conditional append came out **faster** than the unconditional one, which nothing in the store can explain -- it does strictly more work. This is the measurement, not a result: too few iterations, too short an iteration, or a busy machine. Do not quote this table.

### What happens as threads are added

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 0.260 ± 0.002 ops/ms | 260 | 0.0% |
| append-none | 4 | 1.058 ± 0.004 ops/ms | 1,058 | 0.0% |
| append-none | 8 | 2.103 ± 0.009 ops/ms | 2,103 | 0.0% |
| append-none | 16 | 4.141 ± 0.021 ops/ms | 4,141 | 0.0% |
| append-type-and-tag | 1 | 0.312 ± 0.003 ops/ms | 312 | 0.0% |
| append-type-and-tag | 4 | 1.326 ± 0.013 ops/ms | 1,326 | 0.0% |
| append-type-and-tag | 8 | 1.975 ± 0.022 ops/ms | 1,975 | 0.0% |
| append-type-and-tag | 16 | 1.882 ± 0.028 ops/ms | 1,882 | 0.0% |
| decide-then-append | 1 | 0.123 ± 0.003 ops/ms | 123 | 0.0% |
| decide-then-append | 4 | 0.478 ± 0.009 ops/ms | 478 | 0.0% |
| decide-then-append | 8 | 0.925 ± 0.014 ops/ms | 925 | 0.0% |
| decide-then-append | 16 | 1.318 ± 0.014 ops/ms | 1,318 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.42..47.84 rows=500 width=313) (actual time=0.041..0.184 rows=500.00 loops=1)
  Buffers: shared hit=26
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_context_order on bm_63j2j1n30jm3_events  (cost=0.42..5177.62 rows=54593 width=313) (actual time=0.040..0.158 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=26
Planning:
  Buffers: shared hit=28
Planning Time: 0.524 ms
Execution Time: 0.221 ms
```

### tag needle (~10 matches)

```
Sort  (cost=15.03..15.05 rows=7 width=313) (actual time=0.183..0.183 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=39
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=7.05..14.94 rows=7 width=313) (actual time=0.144..0.169 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: ((event_tx > '0'::xid8) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Heap Blocks: exact=10
        Buffers: shared hit=31
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..7.05 rows=7 width=0) (actual time=0.134..0.134 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=21
Planning:
  Buffers: shared hit=12
Planning Time: 0.108 ms
Execution Time: 0.220 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=556.42..557.67 rows=500 width=313) (actual time=2.353..2.386 rows=500.00 loops=1)
  Buffers: shared hit=1021
  ->  Sort  (cost=556.42..557.68 rows=504 width=313) (actual time=2.352..2.364 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 527kB
        Buffers: shared hit=1021
        ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=9.66..533.79 rows=504 width=313) (actual time=0.304..2.158 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: ((event_tx > '0'::xid8) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
              Heap Blocks: exact=1000
              Buffers: shared hit=1021
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..9.54 rows=504 width=0) (actual time=0.236..0.236 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=21
Planning:
  Buffers: shared hit=3
Planning Time: 0.073 ms
Execution Time: 2.418 ms
```

### one entity's whole history (hot)

```
Sort  (cost=307.49..308.15 rows=265 width=313) (actual time=5.897..6.063 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1953kB
  Buffers: shared hit=2066
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=11.31..296.82 rows=265 width=313) (actual time=1.153..4.188 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=2037
        Buffers: shared hit=2066
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..11.24 rows=265 width=0) (actual time=1.004..1.004 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=29
Planning:
  Buffers: shared hit=3
Planning Time: 0.126 ms
Execution Time: 6.424 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.42..6.15 rows=1 width=313) (actual time=0.050..0.051 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan Backward using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events  (cost=0.42..1517.27 rows=265 width=313) (actual time=0.049..0.049 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=3
Planning Time: 0.276 ms
Execution Time: 0.078 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..56.60 rows=500 width=313) (actual time=0.033..0.277 rows=500.00 loops=1)
  Buffers: shared hit=25
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_context_order on bm_63j2j1n30jm3_events  (cost=0.42..4424.80 rows=39380 width=313) (actual time=0.032..0.227 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('15882336'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=25
Planning:
  Buffers: shared hit=14
Planning Time: 0.222 ms
Execution Time: 0.338 ms
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
Limit  (cost=0.42..0.46 rows=1 width=4) (actual time=0.015..0.015 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Only Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..28.23 rows=655 width=4) (actual time=0.014..0.014 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=2
Planning Time: 0.071 ms
Execution Time: 0.023 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..7.71 rows=1 width=4) (actual time=0.041..0.042 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..452.26 rows=62 width=4) (actual time=0.041..0.041 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=3
Planning Time: 0.189 ms
Execution Time: 0.059 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=15.86..20.50 rows=1 width=4) (actual time=2.531..2.531 rows=0.00 loops=1)
  Buffers: shared hit=650
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=15.86..48.32 rows=7 width=4) (actual time=2.531..2.531 rows=0.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]))
        Filter: ((event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Rows Removed by Filter: 705
        Heap Blocks: exact=602
        Buffers: shared hit=650
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..15.86 rows=29 width=0) (actual time=1.914..1.914 rows=705.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]))
              Index Searches: 1
              Buffers: shared hit=48
Planning:
  Buffers: shared hit=3
Planning Time: 0.060 ms
Execution Time: 2.542 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..7.74 rows=1 width=4) (actual time=0.017..0.017 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..454.50 rows=62 width=4) (actual time=0.016..0.016 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=6
Planning Time: 0.095 ms
Execution Time: 0.024 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..7.73 rows=1 width=4) (actual time=0.008..0.008 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..461.19 rows=63 width=4) (actual time=0.008..0.008 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=12
Planning Time: 0.087 ms
Execution Time: 0.013 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..7.79 rows=1 width=4) (actual time=0.007..0.007 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..472.36 rows=64 width=4) (actual time=0.006..0.006 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]) OR (event_tags @> '{sku:SKU-000255}'::text[]) OR (event_tags @> '{sku:SKU-000256}'::text[]) OR (event_tags @> '{sku:SKU-000257}'::text[]) OR (event_tags @> '{sku:SKU-000258}'::text[]) OR (event_tags @> '{sku:SKU-000259}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=22
Planning Time: 0.118 ms
Execution Time: 0.011 ms
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

### DCB check as issued: append-type-and-tag @ postgres:external (collision=one-stream, generic plan) — measured 3.20 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text:  INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-001183","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-001183}', $7 = 'inventory', $8 = 'SKU-000000', $9 = '15882344', $10 = '42148', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-001183'
	Insert on bm_63j2j1n30jm3_events  (cost=4.70..4.72 rows=1 width=232) (actual time=0.774..0.776 rows=1.00 loops=1)
	  Buffers: shared hit=531
	  InitPlan 1
	    ->  Limit  (cost=0.42..4.70 rows=1 width=16) (actual time=0.704..0.705 rows=0.00 loops=1)
	          Buffers: shared hit=512
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..4.70 rows=1 width=16) (actual time=0.704..0.704 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Rows Removed by Filter: 1713
	                Index Searches: 1
	                Buffers: shared hit=512
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.729..0.729 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=513
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.014..0.014 rows=1.00 loops=1)
```

### DCB check as issued: append-type-and-tag @ postgres:external (collision=one-stream, custom plan, first executions only) — measured 3.20 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text:  INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-000024","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-000024}', $7 = 'inventory', $8 = 'SKU-000000', $9 = '15882350', $10 = '54812', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-000024'
	Insert on bm_63j2j1n30jm3_events  (cost=22.56..22.58 rows=1 width=232) (actual time=0.252..0.253 rows=1.00 loops=1)
	  Buffers: shared hit=47
	  InitPlan 1
	    ->  Limit  (cost=22.55..22.56 rows=1 width=16) (actual time=0.198..0.198 rows=0.00 loops=1)
	          Buffers: shared hit=28
	          ->  Sort  (cost=22.55..22.56 rows=2 width=16) (actual time=0.198..0.198 rows=0.00 loops=1)
	                Sort Key: bm_63j2j1n30jm3_events_1.event_tx, bm_63j2j1n30jm3_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=28
	                ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=11.32..22.54 rows=2 width=16) (actual time=0.196..0.196 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000024}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('15882350'::xid8, '54812'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Buffers: shared hit=28
	                      ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..11.32 rows=10 width=0) (actual time=0.194..0.194 rows=0.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000024}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=28
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.215..0.215 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=29
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.008..0.008 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:external (collision=one-stream, generic plan) — measured 8.11 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text:  INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-000096","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-000096}', $7 = 'inventory', $8 = 'SKU-000000', $9 = '15882350', $10 = '54934', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-000096'
	Insert on bm_63j2j1n30jm3_events  (cost=4.70..4.72 rows=1 width=232) (actual time=0.213..0.217 rows=1.00 loops=1)
	  Buffers: shared hit=32
	  InitPlan 1
	    ->  Limit  (cost=0.42..4.70 rows=1 width=16) (actual time=0.069..0.070 rows=0.00 loops=1)
	          Buffers: shared hit=13
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..4.70 rows=1 width=16) (actual time=0.068..0.068 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Rows Removed by Filter: 26
	                Index Searches: 1
	                Buffers: shared hit=13
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.110..0.110 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=14
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.029..0.030 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts | server CPU/op |
|---|---|---|---|---|---|---|---|---|---|---|
| postgres:external | append-none | thrpt | 1 | 0.260 | ops/ms | 0.6% | 260 | 10,419 | 0 | 557 µs |
| postgres:external | append-none | thrpt | 4 | 1.058 | ops/ms | 0.4% | 1,058 | 42,395 | 0 | 185 µs |
| postgres:external | append-none | thrpt | 8 | 2.103 | ops/ms | 0.4% | 2,103 | 84,247 | 0 | 152 µs |
| postgres:external | append-none | thrpt | 16 | 4.141 | ops/ms | 0.5% | 4,141 | 165,817 | 0 | 150 µs |
| postgres:external | append-type-and-tag | thrpt | 1 | 0.312 | ops/ms | 0.8% | 312 | 12,496 | 0 | 710 µs |
| postgres:external | append-type-and-tag | thrpt | 4 | 1.326 | ops/ms | 1.0% | 1,326 | 53,053 | 0 | 425 µs |
| postgres:external | append-type-and-tag | thrpt | 8 | 1.975 | ops/ms | 1.1% | 1,975 | 79,122 | 0 | 441 µs |
| postgres:external | append-type-and-tag | thrpt | 16 | 1.882 | ops/ms | 1.5% | 1,882 | 75,666 | 0 | 494 µs |
| postgres:external | decide-then-append | thrpt | 1 | 0.123 | ops/ms | 2.2% | 123 | 4,945 | 0 | 2,876 µs |
| postgres:external | decide-then-append | thrpt | 4 | 0.478 | ops/ms | 2.0% | 478 | 19,242 | 0 | 2,378 µs |
| postgres:external | decide-then-append | thrpt | 8 | 0.925 | ops/ms | 1.5% | 925 | 37,350 | 0 | 2,416 µs |
| postgres:external | decide-then-append | thrpt | 16 | 1.318 | ops/ms | 1.0% | 1,318 | 53,325 | 0 | 2,424 µs |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
