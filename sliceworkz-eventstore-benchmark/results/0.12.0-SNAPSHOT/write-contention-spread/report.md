# Benchmark run: write-contention-spread

How append throughput scales with writers when they do not collide -- each thread on its own SKU, so no two conditional appends take the same advisory lock and no boundary is shared. The control for the other two contention profiles: whatever this curve does is the store scaling, and the gap between it and one-stream or one-boundary is what contention costs.
PER_ENTITY is load-bearing here and the three profiles were previously TAGGED, which quietly made the set unable to ask its own question. The append lock is keyed on (prefix, context, purpose), so with one stream per context every writer takes the same lock whatever the collision mode: "spread" spread the boundaries and not the locks, and its gap to one-stream was zero by construction. Under PER_ENTITY a SKU is a purpose, so distinct SKUs really are distinct locks.

| | |
|---|---|
| suite version | 0.12.0-SNAPSHOT |
| started | 2026-09-20T08:55:11.723493934Z |
| finished | 2026-09-20T09:56:31.334726467Z |
| targets | inmem/metrics=off, postgres:18/metrics=off |
| corpus restore | restored before every iteration |

> **Not suitable as a published baseline.**
>
> - measured against a Testcontainers PostgreSQL running stock defaults; publish from an external server whose configuration is deliberate

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
| current_database | integration-tests-db |
| effective_cache_size | 4194304kB |
| effective_io_concurrency | 16 |
| fsync | off |
| full_page_writes | on |
| jit | on |
| lc_messages | en_US.utf8 |
| maintenance_work_mem | 65536kB |
| max_connections | 100 |
| max_parallel_workers | 8 |
| max_parallel_workers_per_gather | 2 |
| max_wal_size | 1024MB |
| max_worker_processes | 8 |
| min_wal_size | 80MB |
| random_page_cost | 4 |
| seq_page_cost | 1 |
| server_version | 18.3 (Debian 18.3-1.pgdg13+1) |
| shared_buffers | 131072kB |
| synchronous_commit | on |
| track_io_timing | off |
| version | PostgreSQL 18.3 (Debian 18.3-1.pgdg13+1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit |
| wal_compression | off |
| work_mem | 4096kB |

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

### The targets side by side

| workload | threads | inmem/metrics=off | postgres:18/metrics=off |
|---|---|---|---|
| append-none | 1 | 35.843 ± 0.351 ops/ms | 11.213 ± 0.317 ops/ms (0.31x) |
| append-type-and-tag | 1 | 0.590 ± 0.037 ops/ms | 4.539 ± 0.104 ops/ms (7.69x) |
| decide-then-append | 1 | 0.091 ± 0.003 ops/ms | 0.202 ± 0.008 ops/ms (2.23x) |

Relative to **inmem/metrics=off**, higher is better. A ratio is only about the setting these targets differ in if it is larger than both error bars and survives running the profile with the targets in the opposite order: the first target is measured against a server the later ones then inherit warm, which is worth a few percent on its own.

### What the DCB check costs — inmem/metrics=off

| append | throughput | relative |
|---|---|---|
| no criteria | 35.843 ± 0.351 ops/ms | 1.00x |
| one type set and one tag | 0.590 ± 0.037 ops/ms | 60.75x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added — inmem/metrics=off

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 35.843 ± 0.351 ops/ms | 35,843 | 0.0% |
| append-none | 4 | 35.114 ± 0.601 ops/ms | 35,114 | 0.0% |
| append-none | 8 | 34.895 ± 0.288 ops/ms | 34,895 | 0.0% |
| append-none | 16 | 33.940 ± 0.277 ops/ms | 33,940 | 0.0% |
| append-type-and-tag | 1 | 0.590 ± 0.037 ops/ms | 590 | 0.0% |
| append-type-and-tag | 4 | 0.610 ± 0.043 ops/ms | 610 | 0.0% |
| append-type-and-tag | 8 | 0.589 ± 0.041 ops/ms | 589 | 0.0% |
| append-type-and-tag | 16 | 0.593 ± 0.038 ops/ms | 593 | 0.0% |
| decide-then-append | 1 | 0.091 ± 0.003 ops/ms | 91 | 0.0% |
| decide-then-append | 4 | 0.083 ± 0.003 ops/ms | 83 | 0.0% |
| decide-then-append | 8 | 0.085 ± 0.002 ops/ms | 85 | 0.0% |
| decide-then-append | 16 | 0.089 ± 0.002 ops/ms | 89 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

### What the DCB check costs — postgres:18/metrics=off

| append | throughput | relative |
|---|---|---|
| no criteria | 11.213 ± 0.317 ops/ms | 1.00x |
| one type set and one tag | 4.539 ± 0.104 ops/ms | 2.47x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added — postgres:18/metrics=off

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 11.213 ± 0.317 ops/ms | 11,213 | 0.0% |
| append-none | 4 | 24.761 ± 0.416 ops/ms | 24,761 | 0.0% |
| append-none | 8 | 33.497 ± 0.202 ops/ms | 33,497 | 0.0% |
| append-none | 16 | 32.295 ± 0.268 ops/ms | 32,295 | 0.0% |
| append-type-and-tag | 1 | 4.539 ± 0.104 ops/ms | 4,539 | 0.0% |
| append-type-and-tag | 4 | 13.185 ± 0.187 ops/ms | 13,185 | 0.0% |
| append-type-and-tag | 8 | 19.394 ± 0.188 ops/ms | 19,394 | 0.0% |
| append-type-and-tag | 16 | 18.884 ± 0.471 ops/ms | 18,884 | 0.0% |
| decide-then-append | 1 | 0.202 ± 0.008 ops/ms | 202 | 0.0% |
| decide-then-append | 4 | 0.758 ± 0.020 ops/ms | 758 | 0.0% |
| decide-then-append | 8 | 1.421 ± 0.011 ops/ms | 1,421 | 0.0% |
| decide-then-append | 16 | 1.870 ± 0.022 ops/ms | 1,869 | 0.1% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.42..106.90 rows=500 width=313) (actual time=0.024..0.168 rows=500.00 loops=1)
  Buffers: shared hit=26
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_context_order on bm_63j2j1n30jm3_events  (cost=0.42..11679.30 rows=54840 width=313) (actual time=0.019..0.141 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=26
Planning:
  Buffers: shared hit=103
Planning Time: 0.491 ms
Execution Time: 0.195 ms
```

### tag needle (~10 matches)

```
Sort  (cost=49.02..49.04 rows=7 width=313) (actual time=0.171..0.172 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=39
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=21.55..48.93 rows=7 width=313) (actual time=0.141..0.156 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: ((event_tx > '0'::xid8) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Heap Blocks: exact=10
        Buffers: shared hit=31
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..21.55 rows=7 width=0) (actual time=0.133..0.133 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=21
Planning:
  Buffers: shared hit=12
Planning Time: 0.095 ms
Execution Time: 0.205 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=1605.34..1606.59 rows=500 width=313) (actual time=2.187..2.221 rows=500.00 loops=1)
  Buffers: shared hit=1021
  ->  Sort  (cost=1605.34..1606.72 rows=550 width=313) (actual time=2.187..2.199 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 442kB
        Buffers: shared hit=1021
        ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=24.40..1580.31 rows=550 width=313) (actual time=0.346..1.947 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: ((event_tx > '0'::xid8) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
              Heap Blocks: exact=1000
              Buffers: shared hit=1021
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..24.27 rows=550 width=0) (actual time=0.281..0.281 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=21
Planning:
  Buffers: shared hit=3
Planning Time: 0.069 ms
Execution Time: 2.269 ms
```

### one entity's whole history (hot)

```
Sort  (cost=898.01..898.67 rows=266 width=313) (actual time=4.713..4.879 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1910kB
  Buffers: shared hit=2066
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=31.61..887.29 rows=266 width=313) (actual time=1.111..3.357 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=2037
        Buffers: shared hit=2066
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..31.55 rows=266 width=0) (actual time=0.976..0.976 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=29
Planning:
  Buffers: shared hit=3
Planning Time: 0.064 ms
Execution Time: 5.188 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.42..19.32 rows=1 width=313) (actual time=0.024..0.024 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan Backward using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events  (cost=0.42..5027.48 rows=266 width=313) (actual time=0.023..0.023 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=11
Planning Time: 0.165 ms
Execution Time: 0.036 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..135.64 rows=500 width=313) (actual time=0.015..0.133 rows=500.00 loops=1)
  Buffers: shared hit=25
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_context_order on bm_63j2j1n30jm3_events  (cost=0.42..10667.04 rows=39443 width=313) (actual time=0.014..0.110 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('771'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=25
Planning:
  Buffers: shared hit=14
Planning Time: 0.103 ms
Execution Time: 0.162 ms
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
Limit  (cost=0.42..0.50 rows=1 width=4) (actual time=0.013..0.013 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Only Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..57.44 rows=664 width=4) (actual time=0.013..0.013 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=2
Planning Time: 0.054 ms
Execution Time: 0.020 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..25.00 rows=1 width=4) (actual time=0.015..0.016 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1524.76 rows=62 width=4) (actual time=0.015..0.015 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=3
Planning Time: 0.058 ms
Execution Time: 0.021 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=97.44..101.35 rows=1 width=4) (actual time=0.625..0.625 rows=0.00 loops=1)
  Buffers: shared hit=34
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=97.44..124.80 rows=7 width=4) (actual time=0.625..0.625 rows=0.00 loops=1)
        Recheck Cond: ((event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]) AND (stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Buffers: shared hit=34
        ->  BitmapAnd  (cost=97.44..97.44 rows=7 width=0) (actual time=0.621..0.621 rows=0.00 loops=1)
              Buffers: shared hit=34
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_tags  (cost=0.00..34.08 rows=779 width=0) (actual time=0.593..0.594 rows=705.00 loops=1)
                    Index Cond: (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[])
                    Index Searches: 1
                    Buffers: shared hit=22
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_type_position  (cost=0.00..63.11 rows=897 width=0) (actual time=0.019..0.019 rows=1.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
                    Index Searches: 4
                    Buffers: shared hit=12
Planning:
  Buffers: shared hit=3
Planning Time: 0.053 ms
Execution Time: 0.634 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..24.65 rows=1 width=4) (actual time=0.009..0.009 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1527.00 rows=63 width=4) (actual time=0.009..0.009 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=6
Planning Time: 0.090 ms
Execution Time: 0.015 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..24.76 rows=1 width=4) (actual time=0.008..0.008 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1533.73 rows=63 width=4) (actual time=0.008..0.008 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=12
Planning Time: 0.096 ms
Execution Time: 0.013 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..24.55 rows=1 width=4) (actual time=0.007..0.008 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1544.94 rows=64 width=4) (actual time=0.007..0.007 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]) OR (event_tags @> '{sku:SKU-000255}'::text[]) OR (event_tags @> '{sku:SKU-000256}'::text[]) OR (event_tags @> '{sku:SKU-000257}'::text[]) OR (event_tags @> '{sku:SKU-000258}'::text[]) OR (event_tags @> '{sku:SKU-000259}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=22
Planning Time: 0.138 ms
Execution Time: 0.013 ms
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

### DCB check as issued: append-type-and-tag @ postgres:18/metrics=off (collision=spread, generic plan) — measured 0.22 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-001183', $4 = 'StockReserved', $5 = '{"sku":"SKU-001183","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-001183}', $7 = 'inventory', $8 = 'SKU-001183', $9 = '779', $10 = '42148', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-001183'
	Insert on bm_63j2j1n30jm3_events  (cost=15.57..15.59 rows=1 width=232) (actual time=0.116..0.118 rows=1.00 loops=1)
	  Buffers: shared hit=22
	  InitPlan 1
	    ->  Limit  (cost=0.42..15.57 rows=1 width=16) (actual time=0.019..0.019 rows=0.00 loops=1)
	          Buffers: shared hit=3
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..15.57 rows=1 width=16) (actual time=0.019..0.019 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Index Searches: 1
	                Buffers: shared hit=3
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.057..0.057 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=4
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.030..0.030 rows=1.00 loops=1)
```

### DCB check as issued: append-type-and-tag @ postgres:18/metrics=off (collision=spread, custom plan, first executions only) — measured 0.22 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000024', $4 = 'StockReserved', $5 = '{"sku":"SKU-000024","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-000024}', $7 = 'inventory', $8 = 'SKU-000024', $9 = '785', $10 = '54812', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-000024'
	Insert on bm_63j2j1n30jm3_events  (cost=27.65..27.67 rows=1 width=232) (actual time=0.111..0.113 rows=1.00 loops=1)
	  Buffers: shared hit=22
	  InitPlan 1
	    ->  Limit  (cost=27.65..27.65 rows=1 width=16) (actual time=0.024..0.024 rows=0.00 loops=1)
	          Buffers: shared hit=3
	          ->  Sort  (cost=27.65..27.65 rows=1 width=16) (actual time=0.023..0.024 rows=0.00 loops=1)
	                Sort Key: bm_63j2j1n30jm3_events_1.event_tx, bm_63j2j1n30jm3_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=3
	                ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=23.61..27.64 rows=1 width=16) (actual time=0.020..0.021 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000024'::text) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54812'::bigint)) AND (event_tags @> '{sku:SKU-000024}'::text[]))
	                      Filter: (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[]))
	                      Buffers: shared hit=3
	                      ->  BitmapAnd  (cost=23.61..23.61 rows=1 width=0) (actual time=0.016..0.017 rows=0.00 loops=1)
	                            Buffers: shared hit=3
	                            ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_position  (cost=0.00..5.13 rows=57 width=0) (actual time=0.016..0.016 rows=0.00 loops=1)
	                                  Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000024'::text) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54812'::bigint)))
	                                  Index Searches: 1
	                                  Buffers: shared hit=3
	                            ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_tags  (cost=0.00..18.23 rows=233 width=0) (never executed)
	                                  Index Cond: (event_tags @> '{sku:SKU-000024}'::text[])
	                                  Index Searches: 0
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.054..0.055 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=4
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.024..0.024 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:18/metrics=off (collision=spread, generic plan) — measured 4.95 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000096', $4 = 'StockReserved', $5 = '{"sku":"SKU-000096","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-000096}', $7 = 'inventory', $8 = 'SKU-000096', $9 = '785', $10 = '54934', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-000096'
	Insert on bm_63j2j1n30jm3_events  (cost=15.57..15.59 rows=1 width=232) (actual time=0.255..0.258 rows=1.00 loops=1)
	  Buffers: shared hit=22
	  InitPlan 1
	    ->  Limit  (cost=0.42..15.57 rows=1 width=16) (actual time=0.056..0.056 rows=0.00 loops=1)
	          Buffers: shared hit=3
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..15.57 rows=1 width=16) (actual time=0.054..0.054 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Index Searches: 1
	                Buffers: shared hit=3
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.128..0.129 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=4
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.056..0.056 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| inmem/metrics=off | append-none | thrpt | 1 | 35.843 | ops/ms | 1.0% | 35,843 | 1,434,198 | 0 |
| inmem/metrics=off | append-none | thrpt | 4 | 35.114 | ops/ms | 1.7% | 35,114 | 1,405,207 | 0 |
| inmem/metrics=off | append-none | thrpt | 8 | 34.895 | ops/ms | 0.8% | 34,895 | 1,395,504 | 0 |
| inmem/metrics=off | append-none | thrpt | 16 | 33.940 | ops/ms | 0.8% | 33,940 | 1,357,018 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 1 | 0.590 | ops/ms | 6.2% | 590 | 23,617 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 4 | 0.610 | ops/ms | 7.0% | 610 | 25,154 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 8 | 0.589 | ops/ms | 7.0% | 589 | 25,349 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 16 | 0.593 | ops/ms | 6.3% | 593 | 27,620 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 1 | 0.091 | ops/ms | 3.4% | 91 | 3,641 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 4 | 0.083 | ops/ms | 3.8% | 83 | 3,435 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 8 | 0.085 | ops/ms | 2.7% | 85 | 3,686 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 16 | 0.089 | ops/ms | 2.5% | 89 | 4,161 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 1 | 11.213 | ops/ms | 2.8% | 11,213 | 448,570 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 4 | 24.761 | ops/ms | 1.7% | 24,761 | 1,001,191 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 8 | 33.497 | ops/ms | 0.6% | 33,497 | 1,339,707 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 16 | 32.295 | ops/ms | 0.8% | 32,295 | 1,291,755 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 1 | 4.539 | ops/ms | 2.3% | 4,539 | 181,581 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 4 | 13.185 | ops/ms | 1.4% | 13,185 | 529,541 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 8 | 19.394 | ops/ms | 1.0% | 19,394 | 776,294 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 16 | 18.884 | ops/ms | 2.5% | 18,884 | 756,299 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 1 | 0.202 | ops/ms | 4.1% | 202 | 8,110 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 4 | 0.758 | ops/ms | 2.7% | 758 | 30,548 | 7 |
| postgres:18/metrics=off | decide-then-append | thrpt | 8 | 1.421 | ops/ms | 0.8% | 1,421 | 57,460 | 11 |
| postgres:18/metrics=off | decide-then-append | thrpt | 16 | 1.870 | ops/ms | 1.2% | 1,869 | 75,872 | 39 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
