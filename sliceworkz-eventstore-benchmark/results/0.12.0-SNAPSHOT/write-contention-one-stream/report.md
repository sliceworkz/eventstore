# Benchmark run: write-contention-one-stream

Every writer conditionally appending to one stream, so on PostgreSQL they serialise on that stream's pg_advisory_xact_lock while writing to distinct SKUs -- lock contention with no logical conflict. Diff it against write-contention-spread and the difference is the lock; against write-contention-one-boundary and the difference is conflict-and-retry on top of the lock.
Threads draw exactly the rotation of SKUs that write-contention-spread draws, and every append is then written into the hot SKU's stream instead of its own. That puts one entity's events in another's stream, which no application would do -- and it is the only arrangement that holds the boundaries, the filters and the tags fixed while varying nothing but the lock.
It therefore requires PER_ENTITY, and the profile was previously TAGGED, where a single stream per context means one advisory lock for every writer regardless of mode. This profile and one-boundary both aimed every thread at the hot SKU, so they were one measurement under two names and reported identical throughput and identical conflict counts.
Note that append-none is in the workload list deliberately even though it takes no lock: it is the line that should stay flat while the other two bend, and a run where it bends too is measuring something else.

| | |
|---|---|
| suite version | 0.12.0-SNAPSHOT |
| started | 2026-09-20T09:56:32.093714460Z |
| finished | 2026-09-20T10:58:09.149471708Z |
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
| append-none | 1 | 36.946 ± 0.148 ops/ms | 11.719 ± 0.363 ops/ms (0.32x) |
| append-type-and-tag | 1 | 0.592 ± 0.021 ops/ms | 2.659 ± 0.028 ops/ms (4.49x) |
| decide-then-append | 1 | 0.088 ± 0.003 ops/ms | 0.197 ± 0.008 ops/ms (2.23x) |

Relative to **inmem/metrics=off**, higher is better. A ratio is only about the setting these targets differ in if it is larger than both error bars and survives running the profile with the targets in the opposite order: the first target is measured against a server the later ones then inherit warm, which is worth a few percent on its own.

### What the DCB check costs — inmem/metrics=off

| append | throughput | relative |
|---|---|---|
| no criteria | 36.946 ± 0.148 ops/ms | 1.00x |
| one type set and one tag | 0.592 ± 0.021 ops/ms | 62.41x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added — inmem/metrics=off

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 36.946 ± 0.148 ops/ms | 36,946 | 0.0% |
| append-none | 4 | 35.511 ± 0.300 ops/ms | 35,511 | 0.0% |
| append-none | 8 | 35.018 ± 0.367 ops/ms | 35,018 | 0.0% |
| append-none | 16 | 34.010 ± 0.266 ops/ms | 34,010 | 0.0% |
| append-type-and-tag | 1 | 0.592 ± 0.021 ops/ms | 592 | 0.0% |
| append-type-and-tag | 4 | 0.592 ± 0.045 ops/ms | 592 | 0.0% |
| append-type-and-tag | 8 | 0.593 ± 0.049 ops/ms | 593 | 0.0% |
| append-type-and-tag | 16 | 0.570 ± 0.053 ops/ms | 570 | 0.0% |
| decide-then-append | 1 | 0.088 ± 0.003 ops/ms | 88 | 0.0% |
| decide-then-append | 4 | 0.082 ± 0.003 ops/ms | 82 | 0.0% |
| decide-then-append | 8 | 0.086 ± 0.002 ops/ms | 86 | 0.0% |
| decide-then-append | 16 | 0.085 ± 0.002 ops/ms | 85 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

### What the DCB check costs — postgres:18/metrics=off

| append | throughput | relative |
|---|---|---|
| no criteria | 11.719 ± 0.363 ops/ms | 1.00x |
| one type set and one tag | 2.659 ± 0.028 ops/ms | 4.41x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added — postgres:18/metrics=off

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 11.719 ± 0.363 ops/ms | 11,719 | 0.0% |
| append-none | 4 | 25.392 ± 0.364 ops/ms | 25,392 | 0.0% |
| append-none | 8 | 33.721 ± 0.169 ops/ms | 33,721 | 0.0% |
| append-none | 16 | 32.344 ± 0.366 ops/ms | 32,344 | 0.0% |
| append-type-and-tag | 1 | 2.659 ± 0.028 ops/ms | 2,659 | 0.0% |
| append-type-and-tag | 4 | 2.465 ± 0.039 ops/ms | 2,465 | 0.0% |
| append-type-and-tag | 8 | 2.501 ± 0.018 ops/ms | 2,501 | 0.0% |
| append-type-and-tag | 16 | 2.413 ± 0.055 ops/ms | 2,413 | 0.0% |
| decide-then-append | 1 | 0.197 ± 0.008 ops/ms | 197 | 0.0% |
| decide-then-append | 4 | 0.726 ± 0.019 ops/ms | 726 | 0.0% |
| decide-then-append | 8 | 1.228 ± 0.013 ops/ms | 1,228 | 0.0% |
| decide-then-append | 16 | 1.434 ± 0.027 ops/ms | 1,434 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.42..105.82 rows=500 width=312) (actual time=0.021..0.162 rows=500.00 loops=1)
  Buffers: shared hit=26
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_context_order on bm_63j2j1n30jm3_events  (cost=0.42..11737.04 rows=55677 width=312) (actual time=0.020..0.138 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=26
Planning:
  Buffers: shared hit=103
Planning Time: 0.509 ms
Execution Time: 0.188 ms
```

### tag needle (~10 matches)

```
Sort  (cost=49.03..49.04 rows=7 width=312) (actual time=0.179..0.180 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=39
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=21.55..48.93 rows=7 width=312) (actual time=0.147..0.165 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: ((event_tx > '0'::xid8) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Heap Blocks: exact=10
        Buffers: shared hit=31
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..21.55 rows=7 width=0) (actual time=0.139..0.139 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=21
Planning:
  Buffers: shared hit=12
Planning Time: 0.101 ms
Execution Time: 0.217 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=1671.30..1672.55 rows=500 width=312) (actual time=2.144..2.178 rows=500.00 loops=1)
  Buffers: shared hit=1021
  ->  Sort  (cost=1671.30..1672.75 rows=579 width=312) (actual time=2.144..2.156 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 442kB
        Buffers: shared hit=1021
        ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=24.55..1644.73 rows=579 width=312) (actual time=0.340..1.927 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: ((event_tx > '0'::xid8) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
              Heap Blocks: exact=1000
              Buffers: shared hit=1021
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..24.41 rows=579 width=0) (actual time=0.274..0.274 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=21
Planning:
  Buffers: shared hit=3
Planning Time: 0.074 ms
Execution Time: 2.225 ms
```

### one entity's whole history (hot)

```
Sort  (cost=936.82..937.52 rows=280 width=312) (actual time=4.569..4.735 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1910kB
  Buffers: shared hit=2066
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=31.69..925.43 rows=280 width=312) (actual time=1.134..3.233 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=2037
        Buffers: shared hit=2066
        ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..31.62 rows=280 width=0) (actual time=0.998..0.998 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=29
Planning:
  Buffers: shared hit=3
Planning Time: 0.060 ms
Execution Time: 5.024 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.42..18.82 rows=1 width=312) (actual time=0.015..0.015 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan Backward using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events  (cost=0.42..5150.67 rows=280 width=312) (actual time=0.014..0.015 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=11
Planning Time: 0.103 ms
Execution Time: 0.023 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..134.23 rows=500 width=312) (actual time=0.012..0.127 rows=500.00 loops=1)
  Buffers: shared hit=25
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_context_order on bm_63j2j1n30jm3_events  (cost=0.42..10708.78 rows=40013 width=312) (actual time=0.012..0.105 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('771'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=25
Planning:
  Buffers: shared hit=14
Planning Time: 0.073 ms
Execution Time: 0.152 ms
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
Limit  (cost=0.42..0.50 rows=1 width=4) (actual time=0.011..0.012 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Only Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..57.83 rows=679 width=4) (actual time=0.011..0.011 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=2
Planning Time: 0.037 ms
Execution Time: 0.016 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..24.70 rows=1 width=4) (actual time=0.019..0.019 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1578.82 rows=65 width=4) (actual time=0.018..0.019 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=3
Planning Time: 0.068 ms
Execution Time: 0.026 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=113.87..117.78 rows=1 width=4) (actual time=0.622..0.623 rows=0.00 loops=1)
  Buffers: shared hit=34
  ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events  (cost=113.87..141.23 rows=7 width=4) (actual time=0.622..0.622 rows=0.00 loops=1)
        Recheck Cond: ((event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]) AND (stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Buffers: shared hit=34
        ->  BitmapAnd  (cost=113.87..113.87 rows=7 width=0) (actual time=0.619..0.619 rows=0.00 loops=1)
              Buffers: shared hit=34
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_tags  (cost=0.00..34.09 rows=781 width=0) (actual time=0.591..0.591 rows=705.00 loops=1)
                    Index Cond: (event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[])
                    Index Searches: 1
                    Buffers: shared hit=22
              ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_type_position  (cost=0.00..79.53 rows=923 width=0) (actual time=0.020..0.020 rows=1.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
                    Index Searches: 4
                    Buffers: shared hit=12
Planning:
  Buffers: shared hit=3
Planning Time: 0.061 ms
Execution Time: 0.631 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..24.37 rows=1 width=4) (actual time=0.008..0.008 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1581.12 rows=66 width=4) (actual time=0.008..0.008 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=6
Planning Time: 0.070 ms
Execution Time: 0.013 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..24.47 rows=1 width=4) (actual time=0.010..0.011 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1588.05 rows=66 width=4) (actual time=0.010..0.010 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=12
Planning Time: 0.123 ms
Execution Time: 0.018 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 1 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..24.29 rows=1 width=4) (actual time=0.007..0.007 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_type_position on bm_63j2j1n30jm3_events  (cost=0.42..1599.59 rows=67 width=4) (actual time=0.007..0.007 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('785'::xid8, '54979'::bigint)))
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000251}'::text[]) OR (event_tags @> '{sku:SKU-000252}'::text[]) OR (event_tags @> '{sku:SKU-000253}'::text[]) OR (event_tags @> '{sku:SKU-000254}'::text[]) OR (event_tags @> '{sku:SKU-000255}'::text[]) OR (event_tags @> '{sku:SKU-000256}'::text[]) OR (event_tags @> '{sku:SKU-000257}'::text[]) OR (event_tags @> '{sku:SKU-000258}'::text[]) OR (event_tags @> '{sku:SKU-000259}'::text[]))
        Index Searches: 1
        Buffers: shared hit=4
Planning:
  Buffers: shared hit=22
Planning Time: 0.128 ms
Execution Time: 0.012 ms
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

### DCB check as issued: append-type-and-tag @ postgres:18/metrics=off (collision=one-stream, generic plan) — measured 0.38 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-001183","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-001183}', $7 = 'inventory', $8 = 'SKU-000000', $9 = '779', $10 = '42148', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-001183'
	Insert on bm_63j2j1n30jm3_events  (cost=15.57..15.59 rows=1 width=232) (actual time=0.844..0.846 rows=1.00 loops=1)
	  Buffers: shared hit=531
	  InitPlan 1
	    ->  Limit  (cost=0.42..15.57 rows=1 width=16) (actual time=0.748..0.748 rows=0.00 loops=1)
	          Buffers: shared hit=512
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..15.57 rows=1 width=16) (actual time=0.748..0.748 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Rows Removed by Filter: 1713
	                Index Searches: 1
	                Buffers: shared hit=512
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.787..0.787 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=513
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.031..0.031 rows=1.00 loops=1)
```

### DCB check as issued: append-type-and-tag @ postgres:18/metrics=off (collision=one-stream, custom plan, first executions only) — measured 0.38 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-000024","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-000024}', $7 = 'inventory', $8 = 'SKU-000000', $9 = '785', $10 = '54812', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-000024'
	Insert on bm_63j2j1n30jm3_events  (cost=73.38..73.40 rows=1 width=232) (actual time=0.353..0.355 rows=1.00 loops=1)
	  Buffers: shared hit=47
	  InitPlan 1
	    ->  Limit  (cost=73.38..73.38 rows=1 width=16) (actual time=0.246..0.247 rows=0.00 loops=1)
	          Buffers: shared hit=28
	          ->  Sort  (cost=73.38..73.38 rows=2 width=16) (actual time=0.246..0.246 rows=0.00 loops=1)
	                Sort Key: bm_63j2j1n30jm3_events_1.event_tx, bm_63j2j1n30jm3_events_1.event_position
	                Sort Method: quicksort  Memory: 25kB
	                Buffers: shared hit=28
	                ->  Bitmap Heap Scan on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=34.52..73.37 rows=2 width=16) (actual time=0.242..0.243 rows=0.00 loops=1)
	                      Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000024}'::text[]))
	                      Filter: ((ROW(event_tx, event_position) > ROW('785'::xid8, '54812'::bigint)) AND (event_type = ANY ('{StockReleased,StockReserved,StockPicked,StockReceived}'::text[])))
	                      Buffers: shared hit=28
	                      ->  Bitmap Index Scan on bm_63j2j1n30jm3_idx_events_stream_tags  (cost=0.00..34.52 rows=10 width=0) (actual time=0.238..0.238 rows=0.00 loops=1)
	                            Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'SKU-000000'::text) AND (event_tags @> '{sku:SKU-000024}'::text[]))
	                            Index Searches: 1
	                            Buffers: shared hit=28
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.286..0.287 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=29
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.026..0.026 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:18/metrics=off (collision=one-stream, generic plan) — measured 5.08 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_63j2j1n30jm3_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6) ) AS new_events WHERE (
		SELECT event_position FROM bm_63j2j1n30jm3_events
		WHERE 1=1 AND stream_context = $7 AND stream_purpose = $8 AND (event_tx, event_position) > ($9::xid8, $10) AND ((event_type IN ($11, $12, $13, $14) AND event_tags @> ARRAY[$15]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'SKU-000000', $4 = 'StockReserved', $5 = '{"sku":"SKU-000096","quantity":1,"orderId":"ORD-benchmark"}', $6 = '{warehouse:WH-1,channel:web,sku:SKU-000096}', $7 = 'inventory', $8 = 'SKU-000000', $9 = '785', $10 = '54934', $11 = 'StockReleased', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'StockReceived', $15 = 'sku:SKU-000096'
	Insert on bm_63j2j1n30jm3_events  (cost=15.57..15.59 rows=1 width=232) (actual time=0.257..0.259 rows=1.00 loops=1)
	  Buffers: shared hit=32
	  InitPlan 1
	    ->  Limit  (cost=0.42..15.57 rows=1 width=16) (actual time=0.074..0.074 rows=0.00 loops=1)
	          Buffers: shared hit=13
	          ->  Index Scan using bm_63j2j1n30jm3_idx_events_stream_position on bm_63j2j1n30jm3_events bm_63j2j1n30jm3_events_1  (cost=0.42..15.57 rows=1 width=16) (actual time=0.073..0.073 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($7)::text) AND (stream_purpose = ($8)::text) AND (ROW(event_tx, event_position) > ROW(($9)::xid8, $10)))
	                Filter: ((event_tags @> ARRAY[($15)::text]) AND (event_type = ANY (ARRAY[($11)::text, ($12)::text, ($13)::text, ($14)::text])))
	                Rows Removed by Filter: 26
	                Index Searches: 1
	                Buffers: shared hit=13
	  ->  Result  (cost=0.00..0.02 rows=1 width=232) (actual time=0.133..0.133 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared hit=14
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=208) (actual time=0.039..0.039 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| inmem/metrics=off | append-none | thrpt | 1 | 36.946 | ops/ms | 0.4% | 36,946 | 1,478,474 | 0 |
| inmem/metrics=off | append-none | thrpt | 4 | 35.511 | ops/ms | 0.8% | 35,511 | 1,421,231 | 0 |
| inmem/metrics=off | append-none | thrpt | 8 | 35.018 | ops/ms | 1.0% | 35,018 | 1,401,012 | 0 |
| inmem/metrics=off | append-none | thrpt | 16 | 34.010 | ops/ms | 0.8% | 34,010 | 1,359,741 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 1 | 0.592 | ops/ms | 3.6% | 592 | 23,698 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 4 | 0.592 | ops/ms | 7.7% | 592 | 25,093 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 8 | 0.593 | ops/ms | 8.3% | 593 | 28,954 | 0 |
| inmem/metrics=off | append-type-and-tag | thrpt | 16 | 0.570 | ops/ms | 9.3% | 570 | 34,954 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 1 | 0.088 | ops/ms | 3.2% | 88 | 3,548 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 4 | 0.082 | ops/ms | 3.7% | 82 | 3,381 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 8 | 0.086 | ops/ms | 2.4% | 86 | 3,715 | 0 |
| inmem/metrics=off | decide-then-append | thrpt | 16 | 0.085 | ops/ms | 2.3% | 85 | 3,956 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 1 | 11.719 | ops/ms | 3.1% | 11,719 | 468,805 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 4 | 25.392 | ops/ms | 1.4% | 25,392 | 1,016,758 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 8 | 33.721 | ops/ms | 0.5% | 33,721 | 1,348,521 | 0 |
| postgres:18/metrics=off | append-none | thrpt | 16 | 32.344 | ops/ms | 1.1% | 32,344 | 1,293,781 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 1 | 2.659 | ops/ms | 1.1% | 2,659 | 106,409 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 4 | 2.465 | ops/ms | 1.6% | 2,465 | 98,674 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 8 | 2.501 | ops/ms | 0.7% | 2,501 | 100,187 | 0 |
| postgres:18/metrics=off | append-type-and-tag | thrpt | 16 | 2.413 | ops/ms | 2.3% | 2,413 | 97,028 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 1 | 0.197 | ops/ms | 4.0% | 197 | 7,904 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 4 | 0.726 | ops/ms | 2.6% | 726 | 29,314 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 8 | 1.228 | ops/ms | 1.1% | 1,228 | 49,706 | 0 |
| postgres:18/metrics=off | decide-then-append | thrpt | 16 | 1.434 | ops/ms | 1.9% | 1,434 | 58,176 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
