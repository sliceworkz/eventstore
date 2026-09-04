# Benchmark run: write-contention-spread

How append throughput scales with writers when they do not collide -- each thread on its own SKU, so no two conditional appends take the same advisory lock and no boundary is shared. The control for the other two contention profiles: whatever this curve does is the store scaling, and the gap between it and one-stream or one-boundary is what contention costs.
PER_ENTITY is load-bearing here and the three profiles were previously TAGGED, which quietly made the set unable to ask its own question. The append lock is keyed on (prefix, context, purpose), so with one stream per context every writer takes the same lock whatever the collision mode: "spread" spread the boundaries and not the locks, and its gap to one-stream was zero by construction. Under PER_ENTITY a SKU is a purpose, so distinct SKUs really are distinct locks.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-04T06:55:18.889128471Z |
| finished | 2026-09-04T07:55:59.064888103Z |
| targets | inmem/metrics=off, postgres:18/metrics=off |
| corpus restore | restored before every iteration |

> **Not suitable as a published baseline.**
>
> - measured against a Testcontainers PostgreSQL running stock defaults; publish from an external server whose configuration is deliberate
> - 4 measurements are too noisy to compare against anything, past the 10% this report calls uncomparable: append-type-and-tag (inmem/metrics=off, 1 thread) at 15%, append-type-and-tag (inmem/metrics=off, 4 threads) at 11%, append-type-and-tag (inmem/metrics=off, 8 threads) at 11%, append-type-and-tag (inmem/metrics=off, 16 threads) at 11%

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
| append-none | 1 | 37.069 ± 0.247 ops/ms | 11.708 ± 0.213 ops/ms (0.32x) |
| append-type-and-tag | 1 | 0.310 ± 0.046 ops/ms | 5.779 ± 0.150 ops/ms (18.66x) |
| decide-then-append | 1 | 0.086 ± 0.006 ops/ms | 1.595 ± 0.038 ops/ms (18.53x) |

Relative to **inmem/metrics=off**, higher is better. A ratio is only about the setting these targets differ in if it is larger than both error bars and survives running the profile with the targets in the opposite order: the first target is measured against a server the later ones then inherit warm, which is worth a few percent on its own.

### What the DCB check costs — inmem/metrics=off

| append | throughput | relative |
|---|---|---|
| no criteria | 37.069 ± 0.247 ops/ms | 1.00x |
| one type set and one tag | 0.310 ± 0.046 ops/ms | 119.72x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added — inmem/metrics=off

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 37.069 ± 0.247 ops/ms | 37,069 | 0.0% |
| append-none | 4 | 35.301 ± 0.286 ops/ms | 35,301 | 0.0% |
| append-none | 8 | 35.054 ± 0.311 ops/ms | 35,054 | 0.0% |
| append-none | 16 | 33.883 ± 0.210 ops/ms | 33,883 | 0.0% |
| append-type-and-tag | 1 | 0.310 ± 0.046 ops/ms | 310 | 0.0% |
| append-type-and-tag | 4 | 0.242 ± 0.026 ops/ms | 242 | 0.0% |
| append-type-and-tag | 8 | 0.254 ± 0.027 ops/ms | 254 | 0.0% |
| append-type-and-tag | 16 | 0.262 ± 0.029 ops/ms | 262 | 0.0% |
| decide-then-append | 1 | 0.086 ± 0.006 ops/ms | 86 | 0.0% |
| decide-then-append | 4 | 0.075 ± 0.002 ops/ms | 75 | 0.0% |
| decide-then-append | 8 | 0.075 ± 0.002 ops/ms | 75 | 0.0% |
| decide-then-append | 16 | 0.075 ± 0.003 ops/ms | 75 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

### What the DCB check costs — postgres:18/metrics=off

| append | throughput | relative |
|---|---|---|
| no criteria | 11.708 ± 0.213 ops/ms | 1.00x |
| one type set and one tag | 5.779 ± 0.150 ops/ms | 2.03x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added — postgres:18/metrics=off

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 11.708 ± 0.213 ops/ms | 11,708 | 0.0% |
| append-none | 4 | 25.081 ± 0.324 ops/ms | 25,081 | 0.0% |
| append-none | 8 | 33.553 ± 0.321 ops/ms | 33,553 | 0.0% |
| append-none | 16 | 32.433 ± 0.308 ops/ms | 32,433 | 0.0% |
| append-type-and-tag | 1 | 5.779 ± 0.150 ops/ms | 5,779 | 0.0% |
| append-type-and-tag | 4 | 17.014 ± 0.222 ops/ms | 17,014 | 0.0% |
| append-type-and-tag | 8 | 23.908 ± 0.154 ops/ms | 23,908 | 0.0% |
| append-type-and-tag | 16 | 23.444 ± 0.409 ops/ms | 23,444 | 0.0% |
| decide-then-append | 1 | 1.595 ± 0.038 ops/ms | 1,595 | 0.0% |
| decide-then-append | 4 | 3.472 ± 0.195 ops/ms | 3,472 | 0.0% |
| decide-then-append | 8 | 4.356 ± 0.020 ops/ms | 4,356 | 0.0% |
| decide-then-append | 16 | 4.325 ± 0.086 ops/ms | 4,325 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500) — **sequential scan**

> no index served this, so it read the table from the beginning and discarded 15,000 rows on the way. A predicate the index can start from -- the cursor boundary alone does this -- turns the same question into a seek.

```
Limit  (cost=7501.52..7559.75 rows=500 width=313) (actual time=14.385..16.936 rows=500.00 loops=1)
  Buffers: shared hit=4504
  ->  Gather Merge  (cost=7501.52..13915.67 rows=55073 width=313) (actual time=14.384..16.910 rows=500.00 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        Buffers: shared hit=4504
        ->  Sort  (cost=6501.49..6558.86 rows=22947 width=313) (actual time=12.055..12.068 rows=332.67 loops=3)
              Sort Key: event_tx, event_position
              Sort Method: top-N heapsort  Memory: 214kB
              Buffers: shared hit=4504
              Worker 0:  Sort Method: top-N heapsort  Memory: 214kB
              Worker 1:  Sort Method: top-N heapsort  Memory: 210kB
              ->  Parallel Seq Scan on bm_63j2j1n30jm3_events  (cost=0.00..5358.07 rows=22947 width=313) (actual time=0.011..8.487 rows=18333.33 loops=3)
                    Filter: ((stream_context = 'inventory'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
                    Rows Removed by Filter: 15000
                    Buffers: shared hit=4410
Planning:
  Buffers: shared hit=100
Planning Time: 0.472 ms
Execution Time: 17.006 ms
```

### tag needle (~10 matches)

```
Sort  (cost=49.01..49.03 rows=7 width=313) (actual time=0.151..0.152 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=31
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=21.55..48.91 rows=7 width=313) (actual time=0.135..0.145 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=31
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..21.55 rows=7 width=0) (actual time=0.127..0.127 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=21
Planning:
  Buffers: shared hit=12
Planning Time: 0.146 ms
Execution Time: 0.203 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=1606.51..1607.76 rows=500 width=313) (actual time=1.366..1.403 rows=500.00 loops=1)
  Buffers: shared hit=1021
  ->  Sort  (cost=1606.51..1607.89 rows=551 width=313) (actual time=1.365..1.379 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 442kB
        Buffers: shared hit=1021
        ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=24.41..1581.42 rows=551 width=313) (actual time=0.370..1.164 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=1000
              Buffers: shared hit=1021
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..24.27 rows=551 width=0) (actual time=0.297..0.297 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=21
Planning:
  Buffers: shared hit=3
Planning Time: 0.061 ms
Execution Time: 1.447 ms
```

### one entity's whole history (hot)

```
Sort  (cost=877.01..877.66 rows=259 width=313) (actual time=4.868..5.048 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1910kB
  Buffers: shared hit=2066
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=31.58..866.63 rows=259 width=313) (actual time=1.227..3.474 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=2037
        Buffers: shared hit=2066
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..31.51 rows=259 width=0) (actual time=1.071..1.071 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=29
Planning:
  Buffers: shared hit=3
Planning Time: 0.069 ms
Execution Time: 5.360 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.42..19.69 rows=1 width=313) (actual time=0.016..0.016 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan Backward using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events  (cost=0.42..4990.29 rows=259 width=313) (actual time=0.015..0.015 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=11
Planning Time: 0.098 ms
Execution Time: 0.023 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=7267.08..7325.31 rows=500 width=313) (actual time=9.007..10.558 rows=500.00 loops=1)
  Buffers: shared hit=2191
  ->  Gather Merge  (cost=7267.08..11869.60 rows=39518 width=313) (actual time=9.006..10.535 rows=500.00 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        Buffers: shared hit=2191
        ->  Sort  (cost=6267.05..6308.22 rows=16466 width=313) (actual time=6.361..6.373 rows=287.00 loops=3)
              Sort Key: event_tx, event_position
              Sort Method: top-N heapsort  Memory: 213kB
              Buffers: shared hit=2191
              Worker 0:  Sort Method: top-N heapsort  Memory: 213kB
              Worker 1:  Sort Method: top-N heapsort  Memory: 211kB
              ->  Parallel Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=380.56..5446.57 rows=16466 width=313) (actual time=1.695..4.658 rows=9166.67 loops=3)
                    Recheck Cond: (stream_context = 'inventory'::text)
                    Filter: ((ROW(event_tx, event_position) > ROW('771'::xid8, '27500'::bigint)) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
                    Rows Removed by Filter: 9167
                    Heap Blocks: exact=1435
                    Buffers: shared hit=2163
                    Worker 0:  Heap Blocks: exact=332
                    Worker 1:  Heap Blocks: exact=344
                    ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..370.68 rows=55073 width=0) (actual time=2.026..2.026 rows=55000.00 loops=1)
                          Index Cond: (stream_context = 'inventory'::text)
                          Index Searches: 1
                          Buffers: shared hit=12
Planning:
  Buffers: shared hit=14
Planning Time: 0.084 ms
Execution Time: 10.595 ms
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
Limit  (cost=0.42..0.50 rows=1 width=4) (actual time=0.012..0.013 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Only Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..57.14 rows=652 width=4) (actual time=0.012..0.012 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=2
Planning Time: 0.058 ms
Execution Time: 0.020 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..25.14 rows=1 width=4) (actual time=0.016..0.016 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1508.77 rows=61 width=4) (actual time=0.016..0.016 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=3
Planning Time: 0.053 ms
Execution Time: 0.021 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=97.25..101.16 rows=1 width=4) (actual time=0.631..0.631 rows=0.00 loops=1)
  Buffers: shared hit=34
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=97.25..124.60 rows=7 width=4) (actual time=0.631..0.631 rows=0.00 loops=1)
        Recheck Cond: ((event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]) AND (stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Buffers: shared hit=34
        ->  BitmapAnd  (cost=97.25..97.25 rows=7 width=0) (actual time=0.628..0.628 rows=0.00 loops=1)
              Buffers: shared hit=34
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_tags  (cost=0.00..34.00 rows=763 width=0) (actual time=0.599..0.599 rows=705.00 loops=1)
                    Index Cond: (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[])
                    Index Searches: 1
                    Buffers: shared hit=22
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_type_position  (cost=0.00..62.99 rows=887 width=0) (actual time=0.020..0.020 rows=1.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
                    Index Searches: 4
                    Buffers: shared hit=12
Planning:
  Buffers: shared hit=3
Planning Time: 0.054 ms
Execution Time: 0.640 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..25.18 rows=1 width=4) (actual time=0.008..0.008 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1510.98 rows=61 width=4) (actual time=0.007..0.007 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=6
Planning Time: 0.066 ms
Execution Time: 0.013 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..24.89 rows=1 width=4) (actual time=0.008..0.009 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1517.63 rows=62 width=4) (actual time=0.008..0.008 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=12
Planning Time: 0.099 ms
Execution Time: 0.015 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..25.07 rows=1 width=4) (actual time=0.010..0.010 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1528.72 rows=62 width=4) (actual time=0.009..0.010 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]) OR (event_tags @> '{sku:SKU-000255}'::text[]) OR (event_tags @> '{sku:SKU-000256}'::text[]) OR (event_tags @> '{sku:SKU-000257}'::text[]) OR (event_tags @> '{sku:SKU-000258}'::text[]) OR (event_tags @> '{sku:SKU-000259}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=22
Planning Time: 0.143 ms
Execution Time: 0.017 ms
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

### DCB check as issued: append-type-and-tag @ postgres:18/metrics=off (collision=spread, generic plan) — measured 0.17 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000008', $4 = 'StockReserved', $5 = '{"sku":"SKU-000008","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-000008}', $8 = 'inventory', $9 = 'SKU-000008', $10 = '785', $11 = '54959', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-000008'
	Insert on bm_63j2j1n30jm3_events  (cost=15.57..15.59 rows=1 width=264) (actual time=0.113..0.114 rows=1.00 loops=1)
	  Buffers: shared hit=20
	  InitPlan 1
	    ->  Limit  (cost=0.42..15.57 rows=1 width=16) (actual time=0.018..0.018 rows=0.00 loops=1)
	          Buffers: shared hit=3
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..15.57 rows=1 width=16) (actual time=0.018..0.018 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Index Searches: 1
	                Buffers: shared hit=3
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.060..0.060 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=4
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.030..0.030 rows=1.00 loops=1)
```

### DCB check as issued: append-type-and-tag @ postgres:18/metrics=off (collision=spread, custom plan, first executions only) — measured 0.17 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000026', $4 = 'StockReserved', $5 = '{"sku":"SKU-000026","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{sku:SKU-000026,warehouse:WH-1,channel:web}', $8 = 'inventory', $9 = 'SKU-000026', $10 = '785', $11 = '54630', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-000026'
	Insert on bm_63j2j1n30jm3_events  (cost=27.99..28.01 rows=1 width=264) (actual time=0.079..0.081 rows=1.00 loops=1)
	  Buffers: shared hit=20
	  InitPlan 1
	    ->  Limit  (cost=27.99..27.99 rows=1 width=16) (actual time=0.015..0.015 rows=0.00 loops=1)
	          Buffers: shared hit=3
	          ->  Sort  (cost=27.99..27.99 rows=1 width=16) (actual time=0.015..0.015 rows=0.00 loops=1)
	                Sort Key: bm_63j2j1n30jm3_events_1.event_tx, bm_63j2j1n30jm3_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=3
	                ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=23.95..27.98 rows=1 width=16) (actual time=0.013..0.013 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000026'::text) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54630'::bigint)) AND (event_tags @> '{sku:SKU-000026}'::text[]))
	                      Filter: (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[]))
	                      Buffers: shared hit=3
	                      ->  BitmapAnd  (cost=23.95..23.95 rows=1 width=0) (actual time=0.012..0.012 rows=0.00 loops=1)
	                            Buffers: shared hit=3
	                            ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_position  (cost=0.00..5.26 rows=67 width=0) (actual time=0.012..0.012 rows=0.00 loops=1)
	                                  Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000026'::text) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54630'::bigint)))
	                                  Index Searches: 1
	                                  Buffers: shared hit=3
	                            ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_tags  (cost=0.00..18.45 rows=277 width=0) (never executed)
	                                  Index Cond: (event_tags @> '{sku:SKU-000026}'::text[])
	                                  Index Searches: 0
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.041..0.042 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=4
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.021..0.021 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:18/metrics=off (collision=spread, generic plan) — measured 0.63 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000017', $4 = 'StockReserved', $5 = '{"sku":"SKU-000017","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{sku:SKU-000017,warehouse:WH-1,channel:web}', $8 = 'inventory', $9 = 'SKU-000017', $10 = '785', $11 = '54567', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-000017'
	Insert on bm_63j2j1n30jm3_events  (cost=15.57..15.59 rows=1 width=264) (actual time=0.153..0.155 rows=1.00 loops=1)
	  Buffers: shared hit=20
	  InitPlan 1
	    ->  Limit  (cost=0.42..15.57 rows=1 width=16) (actual time=0.028..0.029 rows=0.00 loops=1)
	          Buffers: shared hit=3
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..15.57 rows=1 width=16) (actual time=0.027..0.028 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Index Searches: 1
	                Buffers: shared hit=3
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.077..0.078 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=4
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.039..0.040 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:18/metrics=off (collision=spread, custom plan, first executions only) — measured 0.63 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000035', $4 = 'StockReserved', $5 = '{"sku":"SKU-000035","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-000035}', $8 = 'inventory', $9 = 'SKU-000035', $10 = '785', $11 = '54673', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-000035'
	Insert on bm_63j2j1n30jm3_events  (cost=27.27..27.29 rows=1 width=264) (actual time=0.140..0.143 rows=1.00 loops=1)
	  Buffers: shared hit=20
	  InitPlan 1
	    ->  Limit  (cost=27.26..27.27 rows=1 width=16) (actual time=0.030..0.031 rows=0.00 loops=1)
	          Buffers: shared hit=3
	          ->  Sort  (cost=27.26..27.27 rows=1 width=16) (actual time=0.029..0.030 rows=0.00 loops=1)
	                Sort Key: bm_63j2j1n30jm3_events_1.event_tx, bm_63j2j1n30jm3_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=3
	                ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=23.23..27.25 rows=1 width=16) (actual time=0.025..0.026 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000035'::text) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54673'::bigint)) AND (event_tags @> '{sku:SKU-000035}'::text[]))
	                      Filter: (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[]))
	                      Buffers: shared hit=3
	                      ->  BitmapAnd  (cost=23.23..23.23 rows=1 width=0) (actual time=0.020..0.021 rows=0.00 loops=1)
	                            Buffers: shared hit=3
	                            ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_position  (cost=0.00..4.98 rows=45 width=0) (actual time=0.020..0.020 rows=0.00 loops=1)
	                                  Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000035'::text) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54673'::bigint)))
	                                  Index Searches: 1
	                                  Buffers: shared hit=3
	                            ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_tags  (cost=0.00..18.00 rows=187 width=0) (never executed)
	                                  Index Cond: (event_tags @> '{sku:SKU-000035}'::text[])
	                                  Index Searches: 0
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.070..0.071 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=4
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.031..0.031 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| inmem/metrics=off | append-none | thrpt | 1 | 37.069 | ops/ms | 0.7% | 37,069 | 1,483,702 | 0 |
| inmem/metrics=off | append-none | thrpt | 4 | 35.301 | ops/ms | 0.8% | 35,301 | 1,411,987 | 0 |
| inmem/metrics=off | append-none | thrpt | 8 | 35.054 | ops/ms | 0.9% | 35,054 | 1,402,394 | 0 |
| inmem/metrics=off | append-none | thrpt | 16 | 33.883 | ops/ms | 0.6% | 33,883 | 1,354,942 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 1 | 0.310 | ops/ms | 14.8% | 310 | 12,404 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 4 | 0.242 | ops/ms | 10.9% | 242 | 9,931 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 8 | 0.254 | ops/ms | 10.6% | 254 | 10,682 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 16 | 0.262 | ops/ms | 10.9% | 262 | 11,378 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 1 | 0.086 | ops/ms | 6.6% | 86 | 3,451 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 4 | 0.075 | ops/ms | 2.5% | 75 | 3,115 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 8 | 0.075 | ops/ms | 2.5% | 75 | 3,279 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 16 | 0.075 | ops/ms | 3.5% | 75 | 3,576 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 1 | 11.708 | ops/ms | 1.8% | 11,708 | 468,376 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 4 | 25.081 | ops/ms | 1.3% | 25,081 | 1,006,993 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 8 | 33.553 | ops/ms | 1.0% | 33,553 | 1,341,550 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 16 | 32.433 | ops/ms | 0.9% | 32,433 | 1,297,243 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 1 | 5.779 | ops/ms | 2.6% | 5,779 | 231,172 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 4 | 17.014 | ops/ms | 1.3% | 17,014 | 680,214 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 8 | 23.908 | ops/ms | 0.6% | 23,908 | 971,250 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 16 | 23.444 | ops/ms | 1.7% | 23,444 | 939,260 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 1 | 1.595 | ops/ms | 2.4% | 1,595 | 63,811 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 4 | 3.472 | ops/ms | 5.6% | 3,472 | 138,944 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 8 | 4.356 | ops/ms | 0.5% | 4,356 | 174,793 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 16 | 4.325 | ops/ms | 2.0% | 4,325 | 173,683 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
