# Benchmark run: large-tier

The read shapes at ten million events, against an external server. The tier the whole corpus-reuse machinery exists for: provisioning costs minutes and is then measured against for days, so run `provision` first and let the measurement runs find it already there.
What to look for is not the absolute numbers but their ratio to read-shapes at a hundred thousand. A needle tag query should cost about the same at both volumes -- the index does the work, and the store is a hundred times larger. If it scales with volume the query is not using the index, and the report's captured plan is where to look. The two shapes that legitimately do scale are the swathe (which returns ~1% of the store, so a hundred times the store is a hundred times the events) and the hot entity (a hundred thousand entities hold more history each); read those as returning more rows, not as losing an index.
Reads only, and its cadence is read-shapes' exactly -- the profile it is a ratio against, and a control measured differently is not one. Appends at this tier are large-tier-writes, which is a separate profile because it cannot share these settings: an append workload here grows the store for a whole trial, so its measurement budget is a fixed number of events rather than a length of time.
Deliberately external rather than containerised. A Testcontainers PostgreSQL runs stock defaults -- 128 MB of shared_buffers, untuned WAL -- and at this size those defaults are most of what would be measured. The publish step refuses a containerised run as a baseline for exactly that reason.
Set the schema mode to NONE if a DBA owns the schema on that server; ENSURE is right when the suite is allowed to create its own corpus tables, which is the usual arrangement for a benchmark host. The btree_gin extension has to exist -- creating it needs CREATE on the database, not on the schema.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-08-30T17:45:42.983501094Z |
| finished | 2026-08-30T18:03:15.968755977Z |
| targets | postgres:external/metrics=off |
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
| fingerprint | `bm_n3tx9gechuj9_` |
| volume | 10,000,000 events under test |
| stream design | TAGGED |
| composition | CLEAN |
| payload | REALISTIC |
| entities | 100,000 |
| hot entity | `SKU-000000`, 455,092 events |
| cold entity | `SKU-094269`, 1 events |
| needle tag | 10 matches |
| swathe tag | 100,000 matches |
| mean payload | 114 bytes (sales) |

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. This run captured none of the store's own statements, so every plan here is a reconstruction.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.56..111.27 rows=500 width=313) (actual time=0.028..0.179 rows=500.00 loops=1)
  Buffers: shared hit=2 read=25
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..1217405.89 rows=5498323 width=313) (actual time=0.027..0.156 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=2 read=25
Planning:
  Buffers: shared hit=58 read=13
Planning Time: 0.319 ms
Execution Time: 0.206 ms
```

### tag needle (~10 matches)

```
Sort  (cost=2958.07..2959.90 rows=733 width=313) (actual time=0.434..0.435 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=42 read=36
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=59.64..2923.19 rows=733 width=313) (actual time=0.328..0.422 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=34 read=36
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..59.46 rows=733 width=0) (actual time=0.306..0.306 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=33 read=27
Planning:
  Buffers: shared hit=11 read=1
Planning Time: 0.077 ms
Execution Time: 0.467 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..11287.23 rows=500 width=313) (actual time=0.018..6.290 rows=500.00 loops=1)
  Buffers: shared hit=80 read=1199
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..1203926.75 rows=53334 width=313) (actual time=0.018..6.266 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=80 read=1199
Planning:
  Buffers: shared hit=3
Planning Time: 0.055 ms
Execution Time: 6.313 ms
```

### one entity's whole history (hot)

```
Gather Merge  (cost=499657.44..528580.72 rows=248340 width=313) (actual time=507.000..566.235 rows=455092.00 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=3093 read=185287 written=4, temp read=14106 written=14136
  ->  Sort  (cost=498657.41..498916.10 rows=103475 width=313) (actual time=497.902..511.853 rows=151697.33 loops=3)
        Sort Key: event_tx, event_position
        Sort Method: external merge  Disk: 38112kB
        Buffers: shared hit=3093 read=185287 written=4, temp read=14106 written=14136
        Worker 0:  Sort Method: external merge  Disk: 35904kB
        Worker 1:  Sort Method: external merge  Disk: 38832kB
        ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1730.95..474827.50 rows=103475 width=313) (actual time=173.166..443.502 rows=151697.33 loops=3)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Rows Removed by Index Recheck: 1070109
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=19090 lossy=43315
              Buffers: shared hit=3059 read=185287 written=4
              Worker 0:  Heap Blocks: exact=14416 lossy=44226
              Worker 1:  Heap Blocks: exact=19298 lossy=44246
              ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1668.86 rows=248364 width=0) (actual time=107.152..107.153 rows=455092.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=2005 read=1618
Planning:
  Buffers: shared hit=3
Planning Time: 0.072 ms
JIT:
  Functions: 18
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.787 ms (Deform 0.376 ms), Inlining 84.302 ms, Optimization 69.522 ms, Emission 50.316 ms, Total 204.927 ms
Execution Time: 579.688 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..5.42 rows=1 width=313) (actual time=0.033..0.034 rows=1.00 loops=1)
  Buffers: shared hit=1 read=5
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..1204901.78 rows=248341 width=313) (actual time=0.032..0.033 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared hit=1 read=5
Planning:
  Buffers: shared hit=2 read=9
Planning Time: 0.209 ms
Execution Time: 0.048 ms
```

### cursor page from the midpoint (limit 500)

```
Limit  (cost=0.56..138.08 rows=500 width=313) (actual time=0.019..0.159 rows=500.00 loops=1)
  Buffers: shared hit=3 read=26
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..1103300.89 rows=4011694 width=313) (actual time=0.019..0.136 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('1504560'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=3 read=26
Planning:
  Buffers: shared hit=7 read=7
Planning Time: 0.138 ms
Execution Time: 0.194 ms
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | query-by-entity-cold | thrpt | 1 | 10.540 | ops/ms | 3.4% | 10,540 | 632,418 | 0 |
| postgres:external/metrics=off | query-by-entity-hot | thrpt | 1 | 0.001 | ops/ms | 6.3% | 1 | 44 | 0 |
| postgres:external/metrics=off | query-by-id | thrpt | 1 | 57.075 | ops/ms | 4.0% | 57,075 | 3,424,552 | 0 |
| postgres:external/metrics=off | query-by-multi-tag | thrpt | 1 | 0.068 | ops/ms | 4.2% | 68 | 4,103 | 0 |
| postgres:external/metrics=off | query-by-or-groups | thrpt | 1 | 0.422 | ops/ms | 3.8% | 422 | 25,356 | 0 |
| postgres:external/metrics=off | query-by-tag-needle | thrpt | 1 | 3.609 | ops/ms | 2.0% | 3,609 | 216,575 | 0 |
| postgres:external/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.193 | ops/ms | 2.6% | 193 | 11,592 | 0 |
| postgres:external/metrics=off | query-by-type | thrpt | 1 | 0.979 | ops/ms | 3.5% | 979 | 58,727 | 0 |
| postgres:external/metrics=off | query-cursor-walk | thrpt | 1 | 0.182 | ops/ms | 3.0% | 182 | 10,908 | 0 |
| postgres:external/metrics=off | query-last-event | thrpt | 1 | 16.117 | ops/ms | 5.0% | 16,117 | 967,042 | 0 |
| postgres:external/metrics=off | query-stream-page | thrpt | 1 | 0.967 | ops/ms | 2.2% | 967 | 58,032 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
