# Benchmark run: dcb-cost-curve-ext

What the DCB consistency check costs over an unconditional append, and how that grows with the number of OR-ed facts a decision rests on. Walks append-none through to a ten-item filter, single threaded, so the number is the check itself rather than contention for it. On PostgreSQL the unconditional append is also the only one taking no advisory lock, so the first step of the curve is the whole mechanism and the rest is the predicate widening.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-08-29T14:03:56.980773038Z |
| finished | 2026-08-29T14:46:05.274655864Z |
| targets | inmem/metrics=off, postgres:external/metrics=off |
| corpus restore | restored before every iteration |

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
| effective_cache_size | 5242888kB |
| effective_io_concurrency | 16 |
| fsync | on |
| full_page_writes | on |
| jit | on |
| lc_messages | en_US.UTF-8 |
| maintenance_work_mem | 65536kB |
| max_connections | 100 |
| max_parallel_workers | 8 |
| max_parallel_workers_per_gather | 2 |
| max_wal_size | 1024MB |
| max_worker_processes | 8 |
| min_wal_size | 80MB |
| random_page_cost | 4 |
| seq_page_cost | 1 |
| server_version | 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1) |
| shared_buffers | 163848kB |
| synchronous_commit | on |
| track_io_timing | off |
| version | PostgreSQL 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1) on x86_64-pc-linux-gnu, compiled by gcc (Ubuntu 15.2.0-16ubuntu1) 15.2.0, 64-bit |
| wal_compression | off |
| work_mem | 4096kB |

## Corpus

| | |
|---|---|
| fingerprint | `bm_628labzk3k7h_` |
| volume | 100,000 events under test |
| stream design | TAGGED |
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

| workload | threads | inmem/metrics=off | postgres:external/metrics=off |
|---|---|---|---|
| append-none | 1 | 36.704 ± 0.348 ops/ms | 3.669 ± 0.048 ops/ms (0.10x) |
| append-types | 1 | 1.169 ± 0.037 ops/ms | 3.244 ± 0.076 ops/ms (2.78x) |
| append-type-and-tag | 1 | 0.348 ± 0.018 ops/ms | 0.967 ± 0.010 ops/ms (2.78x) |
| append-multi-tag | 1 | 0.175 ± 0.002 ops/ms | 0.906 ± 0.015 ops/ms (5.16x) |
| append-or-groups-2 | 1 | 0.475 ± 0.010 ops/ms | 0.073 ± 0.002 ops/ms (0.15x) |
| append-or-groups-3 | 1 | 0.460 ± 0.010 ops/ms | 0.070 ± 0.002 ops/ms (0.15x) |
| append-or-groups-4 | 1 | 0.456 ± 0.008 ops/ms | 0.065 ± 0.003 ops/ms (0.14x) |
| append-or-groups-5 | 1 | 0.449 ± 0.009 ops/ms | 0.064 ± 0.002 ops/ms (0.14x) |
| append-or-groups-10 | 1 | 0.439 ± 0.010 ops/ms | 0.064 ± 0.002 ops/ms (0.14x) |
| append-empty-boundary | 1 | 0.110 ± 0.004 ops/ms | 1.565 ± 0.037 ops/ms (14.24x) |
| decide-then-append | 1 | 0.089 ± 0.002 ops/ms | 0.832 ± 0.016 ops/ms (9.30x) |

Relative to **inmem/metrics=off**, higher is better. A ratio is only about the setting these targets differ in if it is larger than both error bars and survives running the profile with the targets in the opposite order: the first target is measured against a server the later ones then inherit warm, which is worth a few percent on its own.

### What the DCB check costs — inmem/metrics=off

| append | throughput | relative |
|---|---|---|
| no criteria | 36.704 ± 0.348 ops/ms | 1.00x |
| one type set and one tag | 0.348 ± 0.018 ops/ms | 105.50x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### How a multi-fact decision scales — inmem/metrics=off

| OR-ed filter items | throughput | relative to one |
|---|---|---|
| 1 | 0.348 ± 0.018 ops/ms | 1.00x |
| 2 | 0.475 ± 0.010 ops/ms | 0.73x |
| 3 | 0.460 ± 0.010 ops/ms | 0.76x |
| 4 | 0.456 ± 0.008 ops/ms | 0.76x |
| 5 | 0.449 ± 0.009 ops/ms | 0.77x |
| 10 | 0.439 ± 0.010 ops/ms | 0.79x |

The generated SQL gains a disjunct per item, so this is whether a decision resting on ten facts costs ten times one or barely more than it.

### What the DCB check costs — postgres:external/metrics=off

| append | throughput | relative |
|---|---|---|
| no criteria | 3.669 ± 0.048 ops/ms | 1.00x |
| one type set and one tag | 0.967 ± 0.010 ops/ms | 3.79x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### How a multi-fact decision scales — postgres:external/metrics=off

| OR-ed filter items | throughput | relative to one |
|---|---|---|
| 1 | 0.967 ± 0.010 ops/ms | 1.00x |
| 2 | 0.073 ± 0.002 ops/ms | 13.23x |
| 3 | 0.070 ± 0.002 ops/ms | 13.82x |
| 4 | 0.065 ± 0.003 ops/ms | 14.83x |
| 5 | 0.064 ± 0.002 ops/ms | 15.22x |
| 10 | 0.064 ± 0.002 ops/ms | 15.20x |

The generated SQL gains a disjunct per item, so this is whether a decision resting on ten facts costs ten times one or barely more than it.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. This run captured none of the store's own statements, so every plan here is a reconstruction.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.42..179.46 rows=500 width=313) (actual time=0.022..0.170 rows=500.00 loops=1)
  Buffers: shared hit=37
  ->  Index Scan using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..19693.41 rows=54997 width=313) (actual time=0.021..0.144 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=37
Planning:
  Buffers: shared hit=72
Planning Time: 0.332 ms
Execution Time: 0.198 ms
```

### tag needle (~10 matches)

```
Sort  (cost=57.71..57.72 rows=7 width=313) (actual time=0.267..0.267 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=59
  ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=30.24..57.61 rows=7 width=313) (actual time=0.233..0.254 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=51
        ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..30.24 rows=7 width=0) (actual time=0.224..0.224 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=41
Planning:
  Buffers: shared hit=12
Planning Time: 0.096 ms
Execution Time: 0.302 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=1604.31..1605.56 rows=500 width=313) (actual time=1.887..1.920 rows=500.00 loops=1)
  Buffers: shared hit=1041
  ->  Sort  (cost=1604.31..1605.68 rows=548 width=313) (actual time=1.886..1.898 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 442kB
        Buffers: shared hit=1041
        ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=33.08..1579.38 rows=548 width=313) (actual time=0.484..1.650 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=1000
              Buffers: shared hit=1041
              ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..32.94 rows=548 width=0) (actual time=0.416..0.416 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=41
Planning:
  Buffers: shared hit=3
Planning Time: 0.069 ms
Execution Time: 1.950 ms
```

### one entity's whole history (hot)

```
Sort  (cost=4756.79..4766.16 rows=3749 width=313) (actual time=5.373..5.538 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1910kB
  Buffers: shared hit=2026
  ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=54.01..4534.24 rows=3749 width=313) (actual time=1.287..3.465 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=1982
        Buffers: shared hit=2026
        ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..53.07 rows=3749 width=0) (actual time=1.151..1.151 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=44
Planning:
  Buffers: shared hit=3
Planning Time: 0.083 ms
Execution Time: 5.825 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.42..5.64 rows=1 width=313) (actual time=0.021..0.021 rows=1.00 loops=1)
  Buffers: shared hit=5
  ->  Index Scan Backward using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..19574.66 rows=3749 width=313) (actual time=0.020..0.020 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 20
        Index Searches: 1
        Buffers: shared hit=5
Planning:
  Buffers: shared hit=11
Planning Time: 0.138 ms
Execution Time: 0.030 ms
```

### cursor page from the midpoint (limit 500)

```
Limit  (cost=0.42..235.89 rows=500 width=313) (actual time=0.011..0.141 rows=500.00 loops=1)
  Buffers: shared hit=34
  ->  Index Scan using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..18724.22 rows=39759 width=313) (actual time=0.010..0.118 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('786'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=34
Planning:
  Buffers: shared hit=14
Planning Time: 0.097 ms
Execution Time: 0.171 ms
```

> **The plans below do not describe the store's own execution.** They inline the tag arrays 
> and the cursor as literals, which is what PostgreSQL sees when it builds a *custom* plan; 
> the store binds them as JDBC parameters and re-uses the statement, so what it actually runs 
> is whichever of the custom and generic plans the server settled on -- and for several of 
> these shapes that is the generic one, which is a different plan entirely. Read these as the 
> shape of the predicate. This run captured none of the store's own 
> *append* statements, so there is nothing here to check these against: read the shapes, and 
> take the plan a measurement actually ran on from a `jmh` run over the same corpus.

### DCB check: event types only, no tag (append-types) -- boundary 2,000 events back

```
Limit  (cost=0.42..0.50 rows=1 width=4) (actual time=0.040..0.041 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Only Scan using bm_628labzk3k7h_idx_events_stream_type_position on bm_628labzk3k7h_events  (cost=0.42..803.92 rows=10043 width=4) (actual time=0.039..0.039 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('799'::xid8, '53000'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=2
Planning Time: 0.167 ms
Execution Time: 0.059 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 2,000 events back — **sequential scan**

```
Limit  (cost=0.00..7.71 rows=1 width=4) (actual time=0.088..0.088 rows=1.00 loops=1)
  Buffers: shared hit=10
  ->  Seq Scan on bm_628labzk3k7h_events  (cost=0.00..7084.00 rows=919 width=4) (actual time=0.088..0.088 rows=1.00 loops=1)
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) AND (stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('799'::xid8, '53000'::bigint)))
        Rows Removed by Filter: 241
        Buffers: shared hit=10
Planning:
  Buffers: shared hit=3
Planning Time: 0.118 ms
Execution Time: 0.098 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 2,000 events back

```
Limit  (cost=49.67..61.83 rows=1 width=4) (actual time=1.933..1.933 rows=1.00 loops=1)
  Buffers: shared hit=72
  ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=49.67..1277.94 rows=101 width=4) (actual time=1.932..1.932 rows=1.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]))
        Filter: ((event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('799'::xid8, '53000'::bigint)))
        Rows Removed by Filter: 2
        Heap Blocks: exact=3
        Buffers: shared hit=72
        ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..49.64 rows=411 width=0) (actual time=1.885..1.885 rows=705.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]))
              Index Searches: 1
              Buffers: shared hit=68
Planning:
  Buffers: shared hit=3
Planning Time: 0.135 ms
Execution Time: 1.950 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 2,000 events back — **sequential scan**

```
Limit  (cost=0.00..7.96 rows=1 width=4) (actual time=0.091..0.092 rows=1.00 loops=1)
  Buffers: shared hit=10
  ->  Seq Scan on bm_628labzk3k7h_events  (cost=0.00..7334.00 rows=921 width=4) (actual time=0.091..0.091 rows=1.00 loops=1)
        Filter: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[])) AND (ROW(event_tx, event_position) > ROW('799'::xid8, '53000'::bigint)))
        Rows Removed by Filter: 241
        Buffers: shared hit=10
Planning:
  Buffers: shared hit=6
Planning Time: 0.115 ms
Execution Time: 0.100 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 2,000 events back — **sequential scan**

```
Limit  (cost=0.00..8.71 rows=1 width=4) (actual time=0.028..0.028 rows=1.00 loops=1)
  Buffers: shared hit=10
  ->  Seq Scan on bm_628labzk3k7h_events  (cost=0.00..8084.00 rows=928 width=4) (actual time=0.028..0.028 rows=1.00 loops=1)
        Filter: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('799'::xid8, '53000'::bigint)) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[])))
        Rows Removed by Filter: 241
        Buffers: shared hit=10
Planning:
  Buffers: shared hit=12
Planning Time: 0.091 ms
Execution Time: 0.033 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 2,000 events back — **sequential scan**

```
Limit  (cost=0.00..9.94 rows=1 width=4) (actual time=0.027..0.028 rows=1.00 loops=1)
  Buffers: shared hit=10
  ->  Seq Scan on bm_628labzk3k7h_events  (cost=0.00..9334.00 rows=939 width=4) (actual time=0.027..0.027 rows=1.00 loops=1)
        Filter: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('799'::xid8, '53000'::bigint)) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]) OR (event_tags @> '{sku:SKU-000255}'::text[]) OR (event_tags @> '{sku:SKU-000256}'::text[]) OR (event_tags @> '{sku:SKU-000257}'::text[]) OR (event_tags @> '{sku:SKU-000258}'::text[]) OR (event_tags @> '{sku:SKU-000259}'::text[])))
        Rows Removed by Filter: 241
        Buffers: shared hit=10
Planning:
  Buffers: shared hit=22
Planning Time: 0.113 ms
Execution Time: 0.031 ms
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| inmem/metrics=off | append-empty-boundary | thrpt | 1 | 0.110 | ops/ms | 3.4% | 110 | 4,408 | 0 |
| inmem/metrics=off | append-multi-tag | thrpt | 1 | 0.175 | ops/ms | 1.4% | 175 | 7,048 | 0 |
| inmem/metrics=off | append-none | thrpt | 1 | 36.704 | ops/ms | 0.9% | 36,704 | 1,468,344 | 0 |
| inmem/metrics=off | append-or-groups-10 | thrpt | 1 | 0.439 | ops/ms | 2.3% | 439 | 17,570 | 0 |
| inmem/metrics=off | append-or-groups-2 | thrpt | 1 | 0.475 | ops/ms | 2.1% | 475 | 19,015 | 0 |
| inmem/metrics=off | append-or-groups-3 | thrpt | 1 | 0.460 | ops/ms | 2.1% | 460 | 18,423 | 0 |
| inmem/metrics=off | append-or-groups-4 | thrpt | 1 | 0.456 | ops/ms | 1.8% | 456 | 18,262 | 0 |
| inmem/metrics=off | append-or-groups-5 | thrpt | 1 | 0.449 | ops/ms | 2.0% | 449 | 17,990 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 1 | 0.348 | ops/ms | 5.2% | 348 | 13,933 | 0 |
| inmem/metrics=off | append-types | thrpt | 1 | 1.169 | ops/ms | 3.1% | 1,169 | 46,756 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 1 | 0.089 | ops/ms | 2.2% | 89 | 3,590 | 0 |
| postgres:external/metrics=off | append-empty-boundary | thrpt | 1 | 1.565 | ops/ms | 2.4% | 1,565 | 62,601 | 0 |
| postgres:external/metrics=off | append-multi-tag | thrpt | 1 | 0.906 | ops/ms | 1.7% | 906 | 36,269 | 0 |
| postgres:external/metrics=off | append-none | thrpt | 1 | 3.669 | ops/ms | 1.3% | 3,669 | 146,786 | 0 |
| postgres:external/metrics=off | append-or-groups-10 | thrpt | 1 | 0.064 | ops/ms | 3.5% | 64 | 2,554 | 0 |
| postgres:external/metrics=off | append-or-groups-2 | thrpt | 1 | 0.073 | ops/ms | 2.2% | 73 | 2,933 | 0 |
| postgres:external/metrics=off | append-or-groups-3 | thrpt | 1 | 0.070 | ops/ms | 2.4% | 70 | 2,813 | 0 |
| postgres:external/metrics=off | append-or-groups-4 | thrpt | 1 | 0.065 | ops/ms | 5.1% | 65 | 2,619 | 0 |
| postgres:external/metrics=off | append-or-groups-5 | thrpt | 1 | 0.064 | ops/ms | 3.0% | 64 | 2,553 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 1 | 0.967 | ops/ms | 1.0% | 967 | 38,827 | 0 |
| postgres:external/metrics=off | append-types | thrpt | 1 | 3.244 | ops/ms | 2.4% | 3,244 | 129,786 | 0 |
| postgres:external/metrics=off | decide-then-append | thrpt | 1 | 0.832 | ops/ms | 1.9% | 832 | 33,359 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
