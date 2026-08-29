# Benchmark run: read-shapes-ext

What each query shape costs against a store holding nothing but the context under test. The control for the composition profiles: crowded-store and crowded-database run these exact workloads over the same volume and the same targets, so `compare` between them attributes the difference to what else is in the way and nothing else.
Selectivity is two workloads rather than one on purpose. A tag matching ten events and a tag matching one percent of the store are different plans, and a single "tag query" number would be an average of two regimes that never occur together.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-08-29T12:00:33.638862750Z |
| finished | 2026-08-29T12:00:33.658405162Z |
| targets | inmem/metrics=off, postgres:external/metrics=off |
| corpus restore | no restore needed: every workload in this run is read-only |

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
| query-stream-page | 1 | 3.216 ± 0.221 ops/ms | 0.963 ± 0.027 ops/ms (0.30x) |
| query-by-type | 1 | 2.725 ± 0.061 ops/ms | 0.996 ± 0.022 ops/ms (0.37x) |
| query-by-tag-needle | 1 | 0.140 ± 0.008 ops/ms | 4.081 ± 0.094 ops/ms (29.10x) |
| query-by-tag-swathe | 1 | 0.397 ± 0.021 ops/ms | 0.514 ± 0.029 ops/ms (1.30x) |
| query-by-entity-hot | 1 | 0.062 ± 0.006 ops/ms | 0.047 ± 0.001 ops/ms (0.77x) |
| query-by-entity-cold | 1 | 0.115 ± 0.008 ops/ms | 10.852 ± 0.335 ops/ms (94.34x) |
| query-by-multi-tag | 1 | 0.135 ± 0.021 ops/ms | 0.382 ± 0.021 ops/ms (2.83x) |
| query-by-or-groups | 1 | 1.524 ± 0.035 ops/ms | 0.391 ± 0.014 ops/ms (0.26x) |
| query-last-event | 1 | 1.583 ± 0.008 ops/ms | 17.110 ± 0.697 ops/ms (10.81x) |
| query-cursor-walk | 1 | 0.464 ± 0.008 ops/ms | 0.187 ± 0.007 ops/ms (0.40x) |
| query-by-id | 1 | 1911.672 ± 47.586 ops/ms | 58.045 ± 1.703 ops/ms (0.03x) |
| query-wildcard | 1 | 4.827 ± 0.145 ops/ms | 0.052 ± 0.001 ops/ms (0.01x) |

Relative to **inmem/metrics=off**, higher is better. A ratio is only about the setting these targets differ in if it is larger than both error bars and survives running the profile with the targets in the opposite order: the first target is measured against a server the later ones then inherit warm, which is worth a few percent on its own.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. This run captured none of the store's own statements, so every plan here is a reconstruction.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.42..108.23 rows=500 width=312) (actual time=0.032..0.237 rows=500.00 loops=1)
  Buffers: shared hit=26
  ->  Index Scan using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..11954.98 rows=55443 width=312) (actual time=0.031..0.211 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=26
Planning:
  Buffers: shared hit=72
Planning Time: 0.367 ms
Execution Time: 0.274 ms
```

### tag needle (~10 matches)

```
Sort  (cost=57.71..57.72 rows=7 width=312) (actual time=0.360..0.361 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=55
  ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=30.24..57.61 rows=7 width=312) (actual time=0.319..0.343 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=47
        ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..30.24 rows=7 width=0) (actual time=0.307..0.307 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=37
Planning:
  Buffers: shared hit=12
Planning Time: 0.149 ms
Execution Time: 0.414 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=1771.78..1773.03 rows=500 width=312) (actual time=3.095..3.138 rows=500.00 loops=1)
  Buffers: shared hit=1037
  ->  Sort  (cost=1771.78..1773.34 rows=625 width=312) (actual time=3.094..3.110 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 442kB
        Buffers: shared hit=1037
        ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=33.48..1742.76 rows=625 width=312) (actual time=0.732..2.792 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=1000
              Buffers: shared hit=1037
              ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..33.33 rows=625 width=0) (actual time=0.639..0.640 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=37
Planning:
  Buffers: shared hit=3
Planning Time: 0.114 ms
Execution Time: 3.185 ms
```

### one entity's whole history (hot)

```
Sort  (cost=4829.08..4839.01 rows=3972 width=312) (actual time=5.613..5.788 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1910kB
  Buffers: shared hit=2029
  ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=55.18..4591.64 rows=3972 width=312) (actual time=1.375..3.931 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=1993
        Buffers: shared hit=2029
        ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..54.19 rows=3972 width=0) (actual time=1.226..1.227 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=36
Planning:
  Buffers: shared hit=3
Planning Time: 0.128 ms
Execution Time: 6.110 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.42..3.40 rows=1 width=312) (actual time=0.022..0.023 rows=1.00 loops=1)
  Buffers: shared hit=5
  ->  Index Scan Backward using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..11836.23 rows=3972 width=312) (actual time=0.021..0.022 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 20
        Index Searches: 1
        Buffers: shared hit=5
Planning:
  Buffers: shared hit=11
Planning Time: 0.163 ms
Execution Time: 0.034 ms
```

### cursor page from the midpoint (limit 500)

```
Limit  (cost=0.42..136.49 rows=500 width=312) (actual time=0.014..0.150 rows=500.00 loops=1)
  Buffers: shared hit=27
  ->  Index Scan using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..10816.15 rows=39745 width=312) (actual time=0.014..0.125 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('786'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=27
Planning:
  Buffers: shared hit=14
Planning Time: 0.114 ms
Execution Time: 0.181 ms
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| inmem/metrics=off | query-by-entity-cold | thrpt | 1 | 0.115 | ops/ms | 6.8% | 115 | 6,908 | 0 |
| inmem/metrics=off | query-by-entity-hot | thrpt | 1 | 0.062 | ops/ms | 9.4% | 62 | 3,698 | 0 |
| inmem/metrics=off | query-by-id | thrpt | 1 | 1911.672 | ops/ms | 2.5% | 1,911,672 | 114,703,840 | 0 |
| inmem/metrics=off | query-by-multi-tag | thrpt | 1 | 0.135 | ops/ms | 15.7% | 135 | 8,116 | 0 |
| inmem/metrics=off | query-by-or-groups | thrpt | 1 | 1.524 | ops/ms | 2.3% | 1,524 | 91,458 | 0 |
| inmem/metrics=off | query-by-tag-needle | thrpt | 1 | 0.140 | ops/ms | 5.8% | 140 | 8,419 | 0 |
| inmem/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.397 | ops/ms | 5.3% | 397 | 23,842 | 0 |
| inmem/metrics=off | query-by-type | thrpt | 1 | 2.725 | ops/ms | 2.2% | 2,725 | 163,495 | 0 |
| inmem/metrics=off | query-cursor-walk | thrpt | 1 | 0.464 | ops/ms | 1.7% | 464 | 27,859 | 0 |
| inmem/metrics=off | query-last-event | thrpt | 1 | 1.583 | ops/ms | 0.5% | 1,583 | 94,965 | 0 |
| inmem/metrics=off | query-stream-page | thrpt | 1 | 3.216 | ops/ms | 6.9% | 3,216 | 192,978 | 0 |
| inmem/metrics=off | query-wildcard | thrpt | 1 | 4.827 | ops/ms | 3.0% | 4,827 | 289,626 | 0 |
| postgres:external/metrics=off | query-by-entity-cold | thrpt | 1 | 10.852 | ops/ms | 3.1% | 10,852 | 651,163 | 0 |
| postgres:external/metrics=off | query-by-entity-hot | thrpt | 1 | 0.047 | ops/ms | 1.4% | 47 | 2,849 | 0 |
| postgres:external/metrics=off | query-by-id | thrpt | 1 | 58.045 | ops/ms | 2.9% | 58,045 | 3,482,795 | 0 |
| postgres:external/metrics=off | query-by-multi-tag | thrpt | 1 | 0.382 | ops/ms | 5.4% | 382 | 22,928 | 0 |
| postgres:external/metrics=off | query-by-or-groups | thrpt | 1 | 0.391 | ops/ms | 3.5% | 391 | 23,456 | 0 |
| postgres:external/metrics=off | query-by-tag-needle | thrpt | 1 | 4.081 | ops/ms | 2.3% | 4,081 | 244,883 | 0 |
| postgres:external/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.514 | ops/ms | 5.7% | 514 | 30,877 | 0 |
| postgres:external/metrics=off | query-by-type | thrpt | 1 | 0.996 | ops/ms | 2.2% | 996 | 59,794 | 0 |
| postgres:external/metrics=off | query-cursor-walk | thrpt | 1 | 0.187 | ops/ms | 3.6% | 187 | 11,200 | 0 |
| postgres:external/metrics=off | query-last-event | thrpt | 1 | 17.110 | ops/ms | 4.1% | 17,110 | 1,026,628 | 0 |
| postgres:external/metrics=off | query-stream-page | thrpt | 1 | 0.963 | ops/ms | 2.8% | 963 | 57,820 | 0 |
| postgres:external/metrics=off | query-wildcard | thrpt | 1 | 0.052 | ops/ms | 2.5% | 52 | 3,145 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
