# Benchmark run: write-contention-one-stream-ext

write-contention-one-stream against an external server, PostgreSQL only: every writer conditionally appending to one stream, serialised on that stream's advisory lock with no logical conflict. The external target is what lets the run record the server's CPU per decision (-Dbenchmark.server.cpu=<the server's cgroup cpu.stat>) and be published. Pair it with write-contention-one-stream-ext-latency, the same profile with the server a network hop away: the difference between the two is what the round trips inside the lock hold cost. See docs/design/append-lock-hold.md.

| | |
|---|---|
| suite version | 0.15.0-SNAPSHOT |
| started | 2026-11-26T18:51:40.097179176Z |
| finished | 2026-11-26T19:24:37.880179015Z |
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
| no criteria | 3.637 ± 0.055 ops/ms | 1.00x |
| one type set and one tag | 1.839 ± 0.034 ops/ms | 1.98x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 3.637 ± 0.055 ops/ms | 3,637 | 0.0% |
| append-none | 4 | 5.028 ± 0.070 ops/ms | 5,028 | 0.0% |
| append-none | 8 | 5.000 ± 0.076 ops/ms | 5,000 | 0.0% |
| append-none | 16 | 5.009 ± 0.071 ops/ms | 5,009 | 0.0% |
| append-type-and-tag | 1 | 1.839 ± 0.034 ops/ms | 1,839 | 0.0% |
| append-type-and-tag | 4 | 1.877 ± 0.030 ops/ms | 1,877 | 0.0% |
| append-type-and-tag | 8 | 1.836 ± 0.027 ops/ms | 1,836 | 0.0% |
| append-type-and-tag | 16 | 1.730 ± 0.017 ops/ms | 1,730 | 0.0% |
| decide-then-append | 1 | 0.205 ± 0.009 ops/ms | 205 | 0.0% |
| decide-then-append | 4 | 0.747 ± 0.013 ops/ms | 747 | 0.0% |
| decide-then-append | 8 | 1.158 ± 0.010 ops/ms | 1,158 | 0.0% |
| decide-then-append | 16 | 1.175 ± 0.018 ops/ms | 1,175 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.42..47.75 rows=500 width=313) (actual time=0.028..0.170 rows=500.00 loops=1)
  Buffers: shared hit=26
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_context_order on bm_63j2j1n30jm3_events  (cost=0.42..5192.35 rows=54847 width=313) (actual time=0.027..0.147 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=26
Planning:
  Buffers: shared hit=28
Planning Time: 0.313 ms
Execution Time: 0.200 ms
```

### tag needle (~10 matches)

```
Sort  (cost=15.03..15.05 rows=7 width=313) (actual time=0.180..0.180 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=39
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=7.05..14.94 rows=7 width=313) (actual time=0.145..0.167 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: ((event_tx > '0'::xid8) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Heap Blocks: exact=10
        Buffers: shared hit=31
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..7.05 rows=7 width=0) (actual time=0.136..0.136 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=21
Planning:
  Buffers: shared hit=12
Planning Time: 0.108 ms
Execution Time: 0.214 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=524.19..525.37 rows=472 width=313) (actual time=2.367..2.401 rows=500.00 loops=1)
  Buffers: shared hit=1021
  ->  Sort  (cost=524.19..525.37 rows=472 width=313) (actual time=2.367..2.379 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 527kB
        Buffers: shared hit=1021
        ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=9.49..503.22 rows=472 width=313) (actual time=0.310..2.176 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: ((event_tx > '0'::xid8) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
              Heap Blocks: exact=1000
              Buffers: shared hit=1021
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..9.37 rows=472 width=0) (actual time=0.242..0.242 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=21
Planning:
  Buffers: shared hit=3
Planning Time: 0.076 ms
Execution Time: 2.431 ms
```

### one entity's whole history (hot)

```
Sort  (cost=301.75..302.40 rows=260 width=313) (actual time=4.952..5.121 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1953kB
  Buffers: shared hit=2066
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=11.28..291.32 rows=260 width=313) (actual time=1.114..3.590 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=2037
        Buffers: shared hit=2066
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..11.22 rows=260 width=0) (actual time=0.964..0.965 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=29
Planning:
  Buffers: shared hit=3
Planning Time: 0.100 ms
Execution Time: 5.479 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.42..6.22 rows=1 width=313) (actual time=0.029..0.029 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan Backward using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events  (cost=0.42..1508.18 rows=260 width=313) (actual time=0.028..0.028 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=3
Planning Time: 0.147 ms
Execution Time: 0.044 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..56.42 rows=500 width=313) (actual time=0.016..0.149 rows=500.00 loops=1)
  Buffers: shared hit=25
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_context_order on bm_63j2j1n30jm3_events  (cost=0.42..4442.04 rows=39661 width=313) (actual time=0.016..0.127 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('15882336'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=25
Planning:
  Buffers: shared hit=14
Planning Time: 0.126 ms
Execution Time: 0.183 ms
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
Limit  (cost=0.42..0.46 rows=1 width=4) (actual time=0.018..0.019 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Only Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..28.24 rows=656 width=4) (actual time=0.018..0.018 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=2
Planning Time: 0.085 ms
Execution Time: 0.028 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..7.78 rows=1 width=4) (actual time=0.022..0.022 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..449.72 rows=61 width=4) (actual time=0.022..0.022 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=3
Planning Time: 0.087 ms
Execution Time: 0.030 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=15.86..20.50 rows=1 width=4) (actual time=2.351..2.351 rows=0.00 loops=1)
  Buffers: shared hit=650
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=15.86..48.32 rows=7 width=4) (actual time=2.351..2.351 rows=0.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]))
        Filter: ((event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Rows Removed by Filter: 705
        Heap Blocks: exact=602
        Buffers: shared hit=650
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..15.86 rows=29 width=0) (actual time=1.647..1.647 rows=705.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]))
              Index Searches: 1
              Buffers: shared hit=48
Planning:
  Buffers: shared hit=3
Planning Time: 0.081 ms
Execution Time: 2.363 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..7.82 rows=1 width=4) (actual time=0.019..0.019 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..451.94 rows=61 width=4) (actual time=0.018..0.018 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=6
Planning Time: 0.116 ms
Execution Time: 0.027 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..7.81 rows=1 width=4) (actual time=0.015..0.015 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..458.59 rows=62 width=4) (actual time=0.015..0.015 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=12
Planning Time: 0.146 ms
Execution Time: 0.023 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..7.87 rows=1 width=4) (actual time=0.014..0.014 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..469.68 rows=63 width=4) (actual time=0.014..0.014 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('15882350'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]) OR (event_tags @> '{sku:SKU-000255}'::text[]) OR (event_tags @> '{sku:SKU-000256}'::text[]) OR (event_tags @> '{sku:SKU-000257}'::text[]) OR (event_tags @> '{sku:SKU-000258}'::text[]) OR (event_tags @> '{sku:SKU-000259}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=22
Planning Time: 0.207 ms
Execution Time: 0.022 ms
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

### DCB check as issued: append-type-and-tag @ postgres:external (collision=one-stream, generic plan) — measured 0.54 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-001183","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-001183}', $7 = 'inventory', $8 = 'SKU-000000', $9 = '15882344', $10 = '42148', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-001183'
	Insert on bm_63j2j1n30jm3_events  (cost=4.70..4.72 rows=1 width=232) (actual time=0.845..0.847 rows=1.00 loops=1)
	  Buffers: shared hit=531
	  InitPlan 1
	    ->  Limit  (cost=0.42..4.70 rows=1 width=16) (actual time=0.753..0.753 rows=0.00 loops=1)
	          Buffers: shared hit=512
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..4.70 rows=1 width=16) (actual time=0.749..0.749 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Rows Removed by Filter: 1713
	                Index Searches: 1
	                Buffers: shared hit=512
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.790..0.790 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=513
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.023..0.023 rows=1.00 loops=1)
```

### DCB check as issued: append-type-and-tag @ postgres:external (collision=one-stream, custom plan, first executions only) — measured 0.54 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-000024","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-000024}', $7 = 'inventory', $8 = 'SKU-000000', $9 = '15882350', $10 = '54812', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-000024'
	Insert on bm_63j2j1n30jm3_events  (cost=22.56..22.58 rows=1 width=232) (actual time=0.323..0.325 rows=1.00 loops=1)
	  Buffers: shared hit=47
	  InitPlan 1
	    ->  Limit  (cost=22.55..22.56 rows=1 width=16) (actual time=0.249..0.249 rows=0.00 loops=1)
	          Buffers: shared hit=28
	          ->  Sort  (cost=22.55..22.56 rows=2 width=16) (actual time=0.248..0.249 rows=0.00 loops=1)
	                Sort Key: bm_63j2j1n30jm3_events_1.event_tx, bm_63j2j1n30jm3_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=28
	                ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=11.32..22.54 rows=2 width=16) (actual time=0.245..0.245 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000024}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('15882350'::xid8, '54812'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Buffers: shared hit=28
	                      ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..11.32 rows=10 width=0) (actual time=0.240..0.240 rows=0.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000024}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=28
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.268..0.268 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=29
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.012..0.012 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:external (collision=one-stream, generic plan) — measured 4.89 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-000096","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-000096}', $7 = 'inventory', $8 = 'SKU-000000', $9 = '15882350', $10 = '54934', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-000096'
	Insert on bm_63j2j1n30jm3_events  (cost=4.70..4.72 rows=1 width=232) (actual time=0.194..0.196 rows=1.00 loops=1)
	  Buffers: shared hit=32
	  InitPlan 1
	    ->  Limit  (cost=0.42..4.70 rows=1 width=16) (actual time=0.068..0.069 rows=0.00 loops=1)
	          Buffers: shared hit=13
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..4.70 rows=1 width=16) (actual time=0.068..0.068 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Rows Removed by Filter: 26
	                Index Searches: 1
	                Buffers: shared hit=13
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.109..0.109 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=14
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.030..0.030 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts | server CPU/op |
|---|---|---|---|---|---|---|---|---|---|---|
| postgres:external | append-none | thrpt | 1 | 3.637 | ops/ms | 1.5% | 3,637 | 145,488 | 0 | 111 µs |
| postgres:external | append-none | thrpt | 4 | 5.028 | ops/ms | 1.4% | 5,028 | 201,144 | 0 | 128 µs |
| postgres:external | append-none | thrpt | 8 | 5.000 | ops/ms | 1.5% | 5,000 | 200,180 | 0 | 132 µs |
| postgres:external | append-none | thrpt | 16 | 5.009 | ops/ms | 1.4% | 5,009 | 200,857 | 0 | 148 µs |
| postgres:external | append-type-and-tag | thrpt | 1 | 1.839 | ops/ms | 1.9% | 1,839 | 73,573 | 0 | 359 µs |
| postgres:external | append-type-and-tag | thrpt | 4 | 1.877 | ops/ms | 1.6% | 1,877 | 75,173 | 0 | 449 µs |
| postgres:external | append-type-and-tag | thrpt | 8 | 1.836 | ops/ms | 1.4% | 1,836 | 73,648 | 0 | 458 µs |
| postgres:external | append-type-and-tag | thrpt | 16 | 1.730 | ops/ms | 1.0% | 1,730 | 69,673 | 0 | 493 µs |
| postgres:external | decide-then-append | thrpt | 1 | 0.205 | ops/ms | 4.2% | 205 | 8,214 | 0 | 2,737 µs |
| postgres:external | decide-then-append | thrpt | 4 | 0.747 | ops/ms | 1.7% | 747 | 30,104 | 0 | 2,521 µs |
| postgres:external | decide-then-append | thrpt | 8 | 1.158 | ops/ms | 0.9% | 1,158 | 46,744 | 0 | 2,647 µs |
| postgres:external | decide-then-append | thrpt | 16 | 1.175 | ops/ms | 1.5% | 1,175 | 47,619 | 0 | 2,309 µs |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
