# Benchmark run: write-contention-one-stream

Every writer conditionally appending to one stream, so on PostgreSQL they serialise on that stream's pg_advisory_xact_lock while writing to distinct SKUs -- lock contention with no logical conflict. Diff it against write-contention-spread and the difference is the lock; against write-contention-one-boundary and the difference is conflict-and-retry on top of the lock.
Threads draw exactly the rotation of SKUs that write-contention-spread draws, and every append is then written into the hot SKU's stream instead of its own. That puts one entity's events in another's stream, which no application would do -- and it is the only arrangement that holds the boundaries, the filters and the tags fixed while varying nothing but the lock.
It therefore requires PER_ENTITY, and the profile was previously TAGGED, where a single stream per context means one advisory lock for every writer regardless of mode. This profile and one-boundary both aimed every thread at the hot SKU, so they were one measurement under two names and reported identical throughput and identical conflict counts.
Note that append-none is in the workload list deliberately even though it takes no lock: it is the line that should stay flat while the other two bend, and a run where it bends too is measuring something else.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-04T07:55:59.770932362Z |
| finished | 2026-09-04T08:56:24.891101876Z |
| targets | inmem/metrics=off, postgres:18/metrics=off |
| corpus restore | restored before every iteration |

> **Not suitable as a published baseline.**
>
> - measured against a Testcontainers PostgreSQL running stock defaults; publish from an external server whose configuration is deliberate
> - 4 measurements are too noisy to compare against anything, past the 10% this report calls uncomparable: append-type-and-tag (inmem/metrics=off, 1 thread) at 19%, append-type-and-tag (inmem/metrics=off, 4 threads) at 13%, append-type-and-tag (inmem/metrics=off, 8 threads) at 12%, append-type-and-tag (inmem/metrics=off, 16 threads) at 14%

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
| current_database | integration-tests-db |
| effective_cache_size | 5242888kB |
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
| shared_buffers | 163848kB |
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
| append-none | 1 | 37.124 ± 0.197 ops/ms | 11.900 ± 0.314 ops/ms (0.32x) |
| append-type-and-tag | 1 | 0.291 ± 0.054 ops/ms | 1.370 ± 0.055 ops/ms (4.71x) |
| decide-then-append | 1 | 0.087 ± 0.004 ops/ms | 1.079 ± 0.027 ops/ms (12.41x) |

Relative to **inmem/metrics=off**, higher is better. A ratio is only about the setting these targets differ in if it is larger than both error bars and survives running the profile with the targets in the opposite order: the first target is measured against a server the later ones then inherit warm, which is worth a few percent on its own.

### What the DCB check costs — inmem/metrics=off

| append | throughput | relative |
|---|---|---|
| no criteria | 37.124 ± 0.197 ops/ms | 1.00x |
| one type set and one tag | 0.291 ± 0.054 ops/ms | 127.68x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added — inmem/metrics=off

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 37.124 ± 0.197 ops/ms | 37,124 | 0.0% |
| append-none | 4 | 35.546 ± 0.333 ops/ms | 35,546 | 0.0% |
| append-none | 8 | 35.129 ± 0.306 ops/ms | 35,129 | 0.0% |
| append-none | 16 | 33.887 ± 0.186 ops/ms | 33,887 | 0.0% |
| append-type-and-tag | 1 | 0.291 ± 0.054 ops/ms | 291 | 0.0% |
| append-type-and-tag | 4 | 0.227 ± 0.029 ops/ms | 227 | 0.0% |
| append-type-and-tag | 8 | 0.241 ± 0.029 ops/ms | 241 | 0.0% |
| append-type-and-tag | 16 | 0.233 ± 0.032 ops/ms | 233 | 0.0% |
| decide-then-append | 1 | 0.087 ± 0.004 ops/ms | 87 | 0.0% |
| decide-then-append | 4 | 0.073 ± 0.002 ops/ms | 73 | 0.0% |
| decide-then-append | 8 | 0.073 ± 0.002 ops/ms | 73 | 0.0% |
| decide-then-append | 16 | 0.074 ± 0.002 ops/ms | 74 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

### What the DCB check costs — postgres:18/metrics=off

| append | throughput | relative |
|---|---|---|
| no criteria | 11.900 ± 0.314 ops/ms | 1.00x |
| one type set and one tag | 1.370 ± 0.055 ops/ms | 8.68x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added — postgres:18/metrics=off

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 11.900 ± 0.314 ops/ms | 11,900 | 0.0% |
| append-none | 4 | 25.432 ± 0.234 ops/ms | 25,432 | 0.0% |
| append-none | 8 | 34.328 ± 0.193 ops/ms | 34,328 | 0.0% |
| append-none | 16 | 33.155 ± 0.389 ops/ms | 33,155 | 0.0% |
| append-type-and-tag | 1 | 1.370 ± 0.055 ops/ms | 1,370 | 0.0% |
| append-type-and-tag | 4 | 1.461 ± 0.034 ops/ms | 1,461 | 0.0% |
| append-type-and-tag | 8 | 1.498 ± 0.021 ops/ms | 1,498 | 0.0% |
| append-type-and-tag | 16 | 1.418 ± 0.033 ops/ms | 1,418 | 0.0% |
| decide-then-append | 1 | 1.079 ± 0.027 ops/ms | 1,079 | 0.0% |
| decide-then-append | 4 | 1.368 ± 0.043 ops/ms | 1,368 | 0.0% |
| decide-then-append | 8 | 1.395 ± 0.032 ops/ms | 1,395 | 0.0% |
| decide-then-append | 16 | 1.355 ± 0.026 ops/ms | 1,355 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500) — **sequential scan**

> no index served this, so it read the table from the beginning and discarded 15,000 rows on the way. A predicate the index can start from -- the cursor boundary alone does this -- turns the same question into a seek.

```
Limit  (cost=7493.29..7551.53 rows=500 width=313) (actual time=13.767..17.102 rows=500.00 loops=1)
  Buffers: shared hit=4504
  ->  Gather Merge  (cost=7493.29..13865.52 rows=54713 width=313) (actual time=13.765..17.058 rows=500.00 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        Buffers: shared hit=4504
        ->  Sort  (cost=6493.27..6550.26 rows=22797 width=313) (actual time=11.541..11.555 rows=332.00 loops=3)
              Sort Key: event_tx, event_position
              Sort Method: top-N heapsort  Memory: 214kB
              Buffers: shared hit=4504
              Worker 0:  Sort Method: top-N heapsort  Memory: 210kB
              Worker 1:  Sort Method: top-N heapsort  Memory: 213kB
              ->  Parallel Seq Scan on bm_63j2j1n30jm3_events  (cost=0.00..5357.32 rows=22797 width=313) (actual time=0.012..8.206 rows=18333.33 loops=3)
                    Filter: ((stream_context = 'inventory'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
                    Rows Removed by Filter: 15000
                    Buffers: shared hit=4410
Planning:
  Buffers: shared hit=100
Planning Time: 0.505 ms
Execution Time: 17.213 ms
```

### tag needle (~10 matches)

```
Sort  (cost=49.01..49.02 rows=7 width=313) (actual time=0.253..0.255 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=31
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=21.55..48.91 rows=7 width=313) (actual time=0.224..0.241 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=31
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..21.55 rows=7 width=0) (actual time=0.211..0.211 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=21
Planning:
  Buffers: shared hit=12
Planning Time: 0.210 ms
Execution Time: 0.336 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=1707.45..1708.70 rows=500 width=313) (actual time=2.285..2.351 rows=500.00 loops=1)
  Buffers: shared hit=1021
  ->  Sort  (cost=1707.45..1708.94 rows=596 width=313) (actual time=2.284..2.308 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 442kB
        Buffers: shared hit=1021
        ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=24.65..1679.97 rows=596 width=313) (actual time=0.640..1.945 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=1000
              Buffers: shared hit=1021
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..24.50 rows=596 width=0) (actual time=0.506..0.506 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=21
Planning:
  Buffers: shared hit=3
Planning Time: 0.093 ms
Execution Time: 2.429 ms
```

### one entity's whole history (hot)

```
Sort  (cost=889.02..889.68 rows=263 width=313) (actual time=8.187..8.518 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1910kB
  Buffers: shared hit=2066
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=31.60..878.45 rows=263 width=313) (actual time=2.231..5.736 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=2037
        Buffers: shared hit=2066
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..31.53 rows=263 width=0) (actual time=1.951..1.952 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=29
Planning:
  Buffers: shared hit=3
Planning Time: 0.132 ms
Execution Time: 9.124 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.42..19.43 rows=1 width=313) (actual time=0.038..0.039 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan Backward using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events  (cost=0.42..4999.92 rows=263 width=313) (actual time=0.037..0.037 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=11
Planning Time: 0.276 ms
Execution Time: 0.061 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=7262.58..7320.81 rows=500 width=313) (actual time=12.792..15.509 rows=500.00 loops=1)
  Buffers: shared hit=2191
  ->  Gather Merge  (cost=7262.58..11870.46 rows=39564 width=313) (actual time=12.791..15.458 rows=500.00 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        Buffers: shared hit=2191
        ->  Sort  (cost=6262.56..6303.77 rows=16485 width=313) (actual time=10.596..10.608 rows=330.67 loops=3)
              Sort Key: event_tx, event_position
              Sort Method: top-N heapsort  Memory: 214kB
              Buffers: shared hit=2191
              Worker 0:  Sort Method: top-N heapsort  Memory: 212kB
              Worker 1:  Sort Method: top-N heapsort  Memory: 215kB
              ->  Parallel Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=378.77..5441.13 rows=16485 width=313) (actual time=5.773..8.820 rows=9166.67 loops=3)
                    Recheck Cond: (stream_context = 'inventory'::text)
                    Filter: ((ROW(event_tx, event_position) > ROW('771'::xid8, '27500'::bigint)) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
                    Rows Removed by Filter: 9167
                    Heap Blocks: exact=537
                    Buffers: shared hit=2163
                    Worker 0:  Heap Blocks: exact=891
                    Worker 1:  Heap Blocks: exact=683
                    ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..368.88 rows=54713 width=0) (actual time=5.686..5.687 rows=55000.00 loops=1)
                          Index Cond: (stream_context = 'inventory'::text)
                          Index Searches: 1
                          Buffers: shared hit=12
Planning:
  Buffers: shared hit=14
Planning Time: 0.226 ms
Execution Time: 15.604 ms
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
Limit  (cost=0.42..0.50 rows=1 width=4) (actual time=0.018..0.018 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Only Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..57.29 rows=659 width=4) (actual time=0.017..0.017 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=2
Planning Time: 0.084 ms
Execution Time: 0.028 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..24.83 rows=1 width=4) (actual time=0.040..0.040 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1514.11 rows=62 width=4) (actual time=0.040..0.040 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=3
Planning Time: 0.086 ms
Execution Time: 0.048 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=97.26..101.17 rows=1 width=4) (actual time=1.207..1.208 rows=0.00 loops=1)
  Buffers: shared hit=34
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=97.26..124.62 rows=7 width=4) (actual time=1.207..1.208 rows=0.00 loops=1)
        Recheck Cond: ((event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]) AND (stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Buffers: shared hit=34
        ->  BitmapAnd  (cost=97.26..97.26 rows=7 width=0) (actual time=1.202..1.202 rows=0.00 loops=1)
              Buffers: shared hit=34
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_tags  (cost=0.00..33.96 rows=754 width=0) (actual time=1.156..1.156 rows=705.00 loops=1)
                    Index Cond: (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[])
                    Index Searches: 1
                    Buffers: shared hit=22
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_type_position  (cost=0.00..63.05 rows=890 width=0) (actual time=0.029..0.029 rows=1.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
                    Index Searches: 4
                    Buffers: shared hit=12
Planning:
  Buffers: shared hit=3
Planning Time: 0.074 ms
Execution Time: 1.220 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..24.87 rows=1 width=4) (actual time=0.013..0.014 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1516.34 rows=62 width=4) (actual time=0.013..0.013 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=6
Planning Time: 0.123 ms
Execution Time: 0.022 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..24.98 rows=1 width=4) (actual time=0.011..0.011 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1523.01 rows=62 width=4) (actual time=0.011..0.011 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=12
Planning Time: 0.153 ms
Execution Time: 0.018 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..24.76 rows=1 width=4) (actual time=0.020..0.020 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1534.14 rows=63 width=4) (actual time=0.019..0.019 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]) OR (event_tags @> '{sku:SKU-000255}'::text[]) OR (event_tags @> '{sku:SKU-000256}'::text[]) OR (event_tags @> '{sku:SKU-000257}'::text[]) OR (event_tags @> '{sku:SKU-000258}'::text[]) OR (event_tags @> '{sku:SKU-000259}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=22
Planning Time: 0.225 ms
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

### DCB check as issued: append-type-and-tag @ postgres:18/metrics=off (collision=one-stream, generic plan) — measured 0.73 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-000008","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-000008}', $8 = 'inventory', $9 = 'SKU-000000', $10 = '785', $11 = '54959', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-000008'
	Insert on bm_63j2j1n30jm3_events  (cost=15.57..15.59 rows=1 width=264) (actual time=0.120..0.121 rows=1.00 loops=1)
	  Buffers: shared hit=29
	  InitPlan 1
	    ->  Limit  (cost=0.42..15.57 rows=1 width=16) (actual time=0.035..0.036 rows=0.00 loops=1)
	          Buffers: shared hit=12
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..15.57 rows=1 width=16) (actual time=0.035..0.035 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 13
	                Index Searches: 1
	                Buffers: shared hit=12
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.072..0.072 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=13
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.030..0.030 rows=1.00 loops=1)
```

### DCB check as issued: append-type-and-tag @ postgres:18/metrics=off (collision=one-stream, custom plan, first executions only) — measured 0.73 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-000026","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,sku:SKU-000026,channel:web}', $8 = 'inventory', $9 = 'SKU-000000', $10 = '785', $11 = '54630', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-000026'
	Insert on bm_63j2j1n30jm3_events  (cost=77.19..77.21 rows=1 width=264) (actual time=0.273..0.274 rows=1.00 loops=1)
	  Buffers: shared hit=45
	  InitPlan 1
	    ->  Limit  (cost=77.19..77.19 rows=1 width=16) (actual time=0.200..0.200 rows=0.00 loops=1)
	          Buffers: shared hit=28
	          ->  Sort  (cost=77.19..77.20 rows=3 width=16) (actual time=0.199..0.200 rows=0.00 loops=1)
	                Sort Key: bm_63j2j1n30jm3_events_1.event_tx, bm_63j2j1n30jm3_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=28
	                ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=34.52..77.18 rows=3 width=16) (actual time=0.198..0.198 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000026}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('785'::xid8, '54630'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Buffers: shared hit=28
	                      ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..34.52 rows=11 width=0) (actual time=0.196..0.196 rows=0.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000026}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=28
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.228..0.228 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=29
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.022..0.022 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:18/metrics=off (collision=one-stream, generic plan) — measured 0.93 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-000017","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{sku:SKU-000017,warehouse:WH-1,channel:web}', $8 = 'inventory', $9 = 'SKU-000000', $10 = '785', $11 = '54567', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-000017'
	Insert on bm_63j2j1n30jm3_events  (cost=15.57..15.59 rows=1 width=264) (actual time=0.182..0.184 rows=1.00 loops=1)
	  Buffers: shared hit=45
	  InitPlan 1
	    ->  Limit  (cost=0.42..15.57 rows=1 width=16) (actual time=0.082..0.083 rows=0.00 loops=1)
	          Buffers: shared hit=28
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..15.57 rows=1 width=16) (actual time=0.081..0.082 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 70
	                Index Searches: 1
	                Buffers: shared hit=28
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.123..0.123 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=29
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.033..0.033 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:18/metrics=off (collision=one-stream, custom plan, first executions only) — measured 0.93 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-000035","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-000035}', $8 = 'inventory', $9 = 'SKU-000000', $10 = '785', $11 = '54673', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-000035'
	Insert on bm_63j2j1n30jm3_events  (cost=54.14..54.16 rows=1 width=264) (actual time=0.674..0.677 rows=1.00 loops=1)
	  Buffers: shared hit=45
	  InitPlan 1
	    ->  Limit  (cost=54.14..54.14 rows=1 width=16) (actual time=0.531..0.532 rows=0.00 loops=1)
	          Buffers: shared hit=28
	          ->  Sort  (cost=54.14..54.14 rows=1 width=16) (actual time=0.530..0.530 rows=0.00 loops=1)
	                Sort Key: bm_63j2j1n30jm3_events_1.event_tx, bm_63j2j1n30jm3_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=28
	                ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=34.49..54.13 rows=1 width=16) (actual time=0.524..0.525 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000035}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('785'::xid8, '54673'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Buffers: shared hit=28
	                      ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..34.49 rows=5 width=0) (actual time=0.516..0.516 rows=0.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000035}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=28
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.580..0.580 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=29
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.035..0.035 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| inmem/metrics=off | append-none | thrpt | 1 | 37.124 | ops/ms | 0.5% | 37,124 | 1,485,740 | 0 |
| inmem/metrics=off | append-none | thrpt | 4 | 35.546 | ops/ms | 0.9% | 35,546 | 1,422,312 | 0 |
| inmem/metrics=off | append-none | thrpt | 8 | 35.129 | ops/ms | 0.9% | 35,129 | 1,405,930 | 0 |
| inmem/metrics=off | append-none | thrpt | 16 | 33.887 | ops/ms | 0.5% | 33,887 | 1,356,184 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 1 | 0.291 | ops/ms | 18.6% | 291 | 11,641 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 4 | 0.227 | ops/ms | 12.8% | 227 | 9,259 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 8 | 0.241 | ops/ms | 11.9% | 241 | 10,119 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 16 | 0.233 | ops/ms | 13.5% | 233 | 10,284 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 1 | 0.087 | ops/ms | 4.1% | 87 | 3,488 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 4 | 0.073 | ops/ms | 2.4% | 73 | 3,042 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 8 | 0.073 | ops/ms | 3.2% | 73 | 3,186 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 16 | 0.074 | ops/ms | 3.1% | 74 | 3,468 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 1 | 11.900 | ops/ms | 2.6% | 11,900 | 476,065 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 4 | 25.432 | ops/ms | 0.9% | 25,432 | 1,026,301 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 8 | 34.328 | ops/ms | 0.6% | 34,328 | 1,372,802 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 16 | 33.155 | ops/ms | 1.2% | 33,155 | 1,325,416 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 1 | 1.370 | ops/ms | 4.0% | 1,370 | 54,825 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 4 | 1.461 | ops/ms | 2.3% | 1,461 | 58,486 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 8 | 1.498 | ops/ms | 1.4% | 1,498 | 60,102 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 16 | 1.418 | ops/ms | 2.3% | 1,418 | 57,174 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 1 | 1.079 | ops/ms | 2.5% | 1,079 | 43,190 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 4 | 1.368 | ops/ms | 3.1% | 1,368 | 54,794 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 8 | 1.395 | ops/ms | 2.3% | 1,395 | 56,022 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 16 | 1.355 | ops/ms | 2.0% | 1,355 | 54,705 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
