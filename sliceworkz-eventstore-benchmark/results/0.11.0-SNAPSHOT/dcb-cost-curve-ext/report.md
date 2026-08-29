# Benchmark run: dcb-cost-curve-ext

What the DCB consistency check costs over an unconditional append, and how that grows with the number of OR-ed facts a decision rests on. Walks append-none through to a ten-item filter, single threaded, so the number is the check itself rather than contention for it. On PostgreSQL the unconditional append is also the only one taking no advisory lock, so the first step of the curve is the whole mechanism and the rest is the predicate widening.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-08-29T13:30:15.118647924Z |
| finished | 2026-08-29T13:30:15.174183296Z |
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
| append-none | 1 | 36.712 ± 0.278 ops/ms | 3.540 ± 0.052 ops/ms (0.10x) |
| append-types | 1 | 1.161 ± 0.041 ops/ms | 3.114 ± 0.039 ops/ms (2.68x) |
| append-type-and-tag | 1 | 0.333 ± 0.021 ops/ms | 0.933 ± 0.023 ops/ms (2.80x) |
| append-multi-tag | 1 | 0.165 ± 0.004 ops/ms | 0.904 ± 0.016 ops/ms (5.48x) |
| append-or-groups-2 | 1 | 0.448 ± 0.012 ops/ms | 0.156 ± 0.162 ops/ms (0.35x) |
| append-or-groups-3 | 1 | 0.438 ± 0.012 ops/ms | 0.067 ± 0.005 ops/ms (0.15x) |
| append-or-groups-4 | 1 | 0.434 ± 0.009 ops/ms | 0.583 ± 0.052 ops/ms (1.34x) |
| append-or-groups-5 | 1 | 0.426 ± 0.010 ops/ms | 0.623 ± 0.017 ops/ms (1.46x) |
| append-or-groups-10 | 1 | 0.421 ± 0.008 ops/ms | 0.534 ± 0.006 ops/ms (1.27x) |
| append-empty-boundary | 1 | 0.097 ± 0.003 ops/ms | 1.592 ± 0.018 ops/ms (16.35x) |
| decide-then-append | 1 | 0.080 ± 0.003 ops/ms | 0.823 ± 0.011 ops/ms (10.25x) |

Relative to **inmem/metrics=off**, higher is better. A ratio is only about the setting these targets differ in if it is larger than both error bars and survives running the profile with the targets in the opposite order: the first target is measured against a server the later ones then inherit warm, which is worth a few percent on its own.

### What the DCB check costs — inmem/metrics=off

| append | throughput | relative |
|---|---|---|
| no criteria | 36.712 ± 0.278 ops/ms | 1.00x |
| one type set and one tag | 0.333 ± 0.021 ops/ms | 110.35x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### How a multi-fact decision scales — inmem/metrics=off

| OR-ed filter items | throughput | relative to one |
|---|---|---|
| 1 | 0.333 ± 0.021 ops/ms | 1.00x |
| 2 | 0.448 ± 0.012 ops/ms | 0.74x |
| 3 | 0.438 ± 0.012 ops/ms | 0.76x |
| 4 | 0.434 ± 0.009 ops/ms | 0.77x |
| 5 | 0.426 ± 0.010 ops/ms | 0.78x |
| 10 | 0.421 ± 0.008 ops/ms | 0.79x |

The generated SQL gains a disjunct per item, so this is whether a decision resting on ten facts costs ten times one or barely more than it.

### What the DCB check costs — postgres:external/metrics=off

| append | throughput | relative |
|---|---|---|
| no criteria | 3.540 ± 0.052 ops/ms | 1.00x |
| one type set and one tag | 0.933 ± 0.023 ops/ms | 3.80x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### How a multi-fact decision scales — postgres:external/metrics=off

| OR-ed filter items | throughput | relative to one |
|---|---|---|
| 1 | 0.933 ± 0.023 ops/ms | 1.00x |
| 2 | 0.156 ± 0.162 ops/ms | 5.99x |
| 3 | 0.067 ± 0.005 ops/ms | 13.95x |
| 4 | 0.583 ± 0.052 ops/ms | 1.60x |
| 5 | 0.623 ± 0.017 ops/ms | 1.50x |
| 10 | 0.534 ± 0.006 ops/ms | 1.75x |

The generated SQL gains a disjunct per item, so this is whether a decision resting on ten facts costs ten times one or barely more than it.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. This run captured none of the store's own statements, so every plan here is a reconstruction.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.42..108.99 rows=500 width=314) (actual time=0.039..0.273 rows=500.00 loops=1)
  Buffers: shared hit=26
  ->  Index Scan using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..11902.58 rows=54817 width=314) (actual time=0.038..0.221 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=26
Planning:
  Buffers: shared hit=74
Planning Time: 0.455 ms
Execution Time: 0.329 ms
```

### tag needle (~10 matches)

```
Sort  (cost=57.71..57.72 rows=7 width=314) (actual time=0.455..0.456 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=55
  ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=30.24..57.61 rows=7 width=314) (actual time=0.398..0.435 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=47
        ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..30.24 rows=7 width=0) (actual time=0.384..0.384 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=37
Planning:
  Buffers: shared hit=12
Planning Time: 0.167 ms
Execution Time: 0.517 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=1482.96..1484.20 rows=495 width=314) (actual time=3.772..3.840 rows=500.00 loops=1)
  Buffers: shared hit=1037
  ->  Sort  (cost=1482.96..1484.20 rows=495 width=314) (actual time=3.770..3.795 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 442kB
        Buffers: shared hit=1037
        ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=32.80..1460.81 rows=495 width=314) (actual time=0.988..3.419 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=1000
              Buffers: shared hit=1037
              ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..32.68 rows=495 width=0) (actual time=0.837..0.837 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=37
Planning:
  Buffers: shared hit=3
Planning Time: 0.120 ms
Execution Time: 3.900 ms
```

### one entity's whole history (hot)

```
Sort  (cost=4771.68..4781.16 rows=3793 width=314) (actual time=6.344..6.525 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1910kB
  Buffers: shared hit=2029
  ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=54.24..4546.20 rows=3793 width=314) (actual time=2.190..4.701 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=1993
        Buffers: shared hit=2029
        ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..53.29 rows=3793 width=0) (actual time=2.027..2.027 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=36
Planning:
  Buffers: shared hit=3
Planning Time: 0.185 ms
Execution Time: 6.867 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.42..3.53 rows=1 width=314) (actual time=0.024..0.024 rows=1.00 loops=1)
  Buffers: shared hit=5
  ->  Index Scan Backward using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..11784.50 rows=3793 width=314) (actual time=0.023..0.023 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 20
        Index Searches: 1
        Buffers: shared hit=5
Planning:
  Buffers: shared hit=11
Planning Time: 0.175 ms
Execution Time: 0.038 ms
```

### cursor page from the midpoint (limit 500)

```
Limit  (cost=0.42..137.14 rows=500 width=314) (actual time=0.016..0.148 rows=500.00 loops=1)
  Buffers: shared hit=27
  ->  Index Scan using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..10793.15 rows=39470 width=314) (actual time=0.015..0.123 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('786'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=27
Planning:
  Buffers: shared hit=14
Planning Time: 0.129 ms
Execution Time: 0.183 ms
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
Limit  (cost=0.42..0.49 rows=1 width=4) (actual time=0.025..0.025 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Only Scan using bm_628labzk3k7h_idx_events_stream_type_position on bm_628labzk3k7h_events  (cost=0.42..729.27 rows=9937 width=4) (actual time=0.024..0.024 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('799'::xid8, '53000'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=2
Planning Time: 0.122 ms
Execution Time: 0.038 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 2,000 events back — **sequential scan**

```
Limit  (cost=0.00..7.60 rows=1 width=4) (actual time=9.617..9.620 rows=1.00 loops=1)
  Buffers: shared hit=1978
  ->  Seq Scan on bm_628labzk3k7h_events  (cost=0.00..7084.00 rows=932 width=4) (actual time=9.616..9.618 rows=1.00 loops=1)
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) AND (stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('799'::xid8, '53000'::bigint)))
        Rows Removed by Filter: 53007
        Buffers: shared hit=1978
Planning:
  Buffers: shared hit=3
Planning Time: 0.092 ms
Execution Time: 9.632 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 2,000 events back — **sequential scan**

```
Limit  (cost=0.00..68.78 rows=1 width=4) (actual time=0.042..0.042 rows=1.00 loops=1)
  Buffers: shared hit=11
  ->  Seq Scan on bm_628labzk3k7h_events  (cost=0.00..7084.00 rows=103 width=4) (actual time=0.041..0.041 rows=1.00 loops=1)
        Filter: ((event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]) AND (stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('799'::xid8, '53000'::bigint)))
        Rows Removed by Filter: 248
        Buffers: shared hit=11
Planning:
  Buffers: shared hit=3
Planning Time: 0.133 ms
Execution Time: 0.052 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 2,000 events back — **sequential scan**

```
Limit  (cost=0.00..7.85 rows=1 width=4) (actual time=0.060..0.061 rows=1.00 loops=1)
  Buffers: shared hit=10
  ->  Seq Scan on bm_628labzk3k7h_events  (cost=0.00..7334.00 rows=934 width=4) (actual time=0.060..0.060 rows=1.00 loops=1)
        Filter: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[])) AND (ROW(event_tx, event_position) > ROW('799'::xid8, '53000'::bigint)))
        Rows Removed by Filter: 241
        Buffers: shared hit=10
Planning:
  Buffers: shared hit=6
Planning Time: 0.112 ms
Execution Time: 0.068 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 2,000 events back — **sequential scan**

```
Limit  (cost=0.00..8.58 rows=1 width=4) (actual time=0.029..0.029 rows=1.00 loops=1)
  Buffers: shared hit=10
  ->  Seq Scan on bm_628labzk3k7h_events  (cost=0.00..8084.00 rows=942 width=4) (actual time=0.029..0.029 rows=1.00 loops=1)
        Filter: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('799'::xid8, '53000'::bigint)) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[])))
        Rows Removed by Filter: 241
        Buffers: shared hit=10
Planning:
  Buffers: shared hit=12
Planning Time: 0.096 ms
Execution Time: 0.035 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 2,000 events back — **sequential scan**

```
Limit  (cost=0.00..9.73 rows=1 width=4) (actual time=0.032..0.032 rows=1.00 loops=1)
  Buffers: shared hit=10
  ->  Seq Scan on bm_628labzk3k7h_events  (cost=0.00..9334.00 rows=959 width=4) (actual time=0.032..0.032 rows=1.00 loops=1)
        Filter: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('799'::xid8, '53000'::bigint)) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]) OR (event_tags @> '{sku:SKU-000255}'::text[]) OR (event_tags @> '{sku:SKU-000256}'::text[]) OR (event_tags @> '{sku:SKU-000257}'::text[]) OR (event_tags @> '{sku:SKU-000258}'::text[]) OR (event_tags @> '{sku:SKU-000259}'::text[])))
        Rows Removed by Filter: 241
        Buffers: shared hit=10
Planning:
  Buffers: shared hit=22
Planning Time: 0.146 ms
Execution Time: 0.040 ms
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| inmem/metrics=off | append-empty-boundary | thrpt | 1 | 0.097 | ops/ms | 2.8% | 97 | 3,905 | 0 |
| inmem/metrics=off | append-multi-tag | thrpt | 1 | 0.165 | ops/ms | 2.5% | 165 | 6,619 | 0 |
| inmem/metrics=off | append-none | thrpt | 1 | 36.712 | ops/ms | 0.8% | 36,712 | 1,469,090 | 0 |
| inmem/metrics=off | append-or-groups-10 | thrpt | 1 | 0.421 | ops/ms | 1.8% | 421 | 16,864 | 0 |
| inmem/metrics=off | append-or-groups-2 | thrpt | 1 | 0.448 | ops/ms | 2.7% | 448 | 17,917 | 0 |
| inmem/metrics=off | append-or-groups-3 | thrpt | 1 | 0.438 | ops/ms | 2.7% | 438 | 17,516 | 0 |
| inmem/metrics=off | append-or-groups-4 | thrpt | 1 | 0.434 | ops/ms | 2.2% | 434 | 17,364 | 0 |
| inmem/metrics=off | append-or-groups-5 | thrpt | 1 | 0.426 | ops/ms | 2.3% | 426 | 17,066 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 1 | 0.333 | ops/ms | 6.2% | 333 | 13,322 | 0 |
| inmem/metrics=off | append-types | thrpt | 1 | 1.161 | ops/ms | 3.5% | 1,161 | 46,445 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 1 | 0.080 | ops/ms | 3.4% | 80 | 3,221 | 0 |
| postgres:external/metrics=off | append-empty-boundary | thrpt | 1 | 1.592 | ops/ms | 1.1% | 1,592 | 63,682 | 0 |
| postgres:external/metrics=off | append-multi-tag | thrpt | 1 | 0.904 | ops/ms | 1.8% | 904 | 36,190 | 0 |
| postgres:external/metrics=off | append-none | thrpt | 1 | 3.540 | ops/ms | 1.5% | 3,540 | 141,618 | 0 |
| postgres:external/metrics=off | append-or-groups-10 | thrpt | 1 | 0.534 | ops/ms | 1.1% | 534 | 21,394 | 0 |
| postgres:external/metrics=off | append-or-groups-2 | thrpt | 1 | 0.156 | ops/ms | 104.3% | 156 | 6,242 | 0 |
| postgres:external/metrics=off | append-or-groups-3 | thrpt | 1 | 0.067 | ops/ms | 7.3% | 67 | 2,688 | 0 |
| postgres:external/metrics=off | append-or-groups-4 | thrpt | 1 | 0.583 | ops/ms | 8.9% | 583 | 23,341 | 0 |
| postgres:external/metrics=off | append-or-groups-5 | thrpt | 1 | 0.623 | ops/ms | 2.7% | 623 | 24,956 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 1 | 0.933 | ops/ms | 2.5% | 933 | 37,341 | 0 |
| postgres:external/metrics=off | append-types | thrpt | 1 | 3.114 | ops/ms | 1.3% | 3,114 | 124,583 | 0 |
| postgres:external/metrics=off | decide-then-append | thrpt | 1 | 0.823 | ops/ms | 1.3% | 823 | 33,000 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
