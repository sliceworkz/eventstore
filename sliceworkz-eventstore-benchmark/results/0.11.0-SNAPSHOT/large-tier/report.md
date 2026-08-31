# Benchmark run: large-tier

The read shapes at ten million events, against an external server. The tier the whole corpus-reuse machinery exists for: provisioning costs minutes and is then measured against for days, so run `provision` first and let the measurement runs find it already there.
What to look for is not the absolute numbers but their ratio to read-shapes at a hundred thousand. A needle tag query should cost about the same at both volumes -- the index does the work, and the store is a hundred times larger. If it scales with volume the query is not using the index, and the report's captured plan is where to look. The two shapes that legitimately do scale are the swathe (which returns ~1% of the store, so a hundred times the store is a hundred times the events) and the hot entity (a hundred thousand entities hold more history each); read those as returning more rows, not as losing an index.
Reads only, and its cadence is read-shapes' exactly -- the profile it is a ratio against, and a control measured differently is not one. Appends at this tier are large-tier-writes, which is a separate profile because it cannot share these settings: an append workload here grows the store for a whole trial, so its measurement budget is a fixed number of events rather than a length of time.
Deliberately external rather than containerised. A Testcontainers PostgreSQL runs stock defaults -- 128 MB of shared_buffers, untuned WAL -- and at this size those defaults are most of what would be measured. The publish step refuses a containerised run as a baseline for exactly that reason.
Set the schema mode to NONE if a DBA owns the schema on that server; ENSURE is right when the suite is allowed to create its own corpus tables, which is the usual arrangement for a benchmark host. The btree_gin extension has to exist -- creating it needs CREATE on the database, not on the schema.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-08-31T08:11:29.441049345Z |
| finished | 2026-08-31T08:28:55.980890982Z |
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
Limit  (cost=0.56..230.62 rows=500 width=314) (actual time=0.026..0.182 rows=500.00 loops=1)
  Buffers: shared hit=4 read=33
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2509560.35 rows=5454321 width=314) (actual time=0.025..0.158 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=4 read=33
Planning:
  Buffers: shared hit=58 read=13
Planning Time: 0.299 ms
Execution Time: 0.208 ms
```

### tag needle (~10 matches)

```
Sort  (cost=2934.64..2936.45 rows=727 width=314) (actual time=0.443..0.444 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=44 read=39
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=59.61..2900.08 rows=727 width=314) (actual time=0.329..0.431 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=36 read=39
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..59.43 rows=727 width=0) (actual time=0.312..0.312 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=35 read=30
Planning:
  Buffers: shared hit=11 read=1
Planning Time: 0.085 ms
Execution Time: 0.479 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..25425.50 rows=500 width=314) (actual time=0.017..6.486 rows=500.00 loops=1)
  Buffers: shared hit=179 read=1352 written=1
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2496169.99 rows=49089 width=314) (actual time=0.017..6.460 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=179 read=1352 written=1
Planning:
  Buffers: shared hit=3
Planning Time: 0.059 ms
Execution Time: 6.515 ms
```

### one entity's whole history (hot)

```
Gather Merge  (cost=500565.94..529660.20 rows=249808 width=314) (actual time=529.798..595.087 rows=455092.00 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=3044 read=185284, temp read=14120 written=14150
  ->  Sort  (cost=499565.92..499826.13 rows=104087 width=314) (actual time=521.433..538.421 rows=151697.33 loops=3)
        Sort Key: event_tx, event_position
        Sort Method: external merge  Disk: 38464kB
        Buffers: shared hit=3044 read=185284, temp read=14120 written=14150
        Worker 0:  Sort Method: external merge  Disk: 36504kB
        Worker 1:  Sort Method: external merge  Disk: 37992kB
        ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1742.78..475593.10 rows=104087 width=314) (actual time=179.372..460.322 rows=151697.33 loops=3)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Rows Removed by Index Recheck: 1070133
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=18309 lossy=44537
              Buffers: shared hit=3010 read=185284
              Worker 0:  Heap Blocks: exact=16986 lossy=42652
              Worker 1:  Heap Blocks: exact=17481 lossy=44600
              ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1680.33 rows=249832 width=0) (actual time=109.569..109.569 rows=455092.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=1981 read=1616
Planning:
  Buffers: shared hit=3
Planning Time: 0.135 ms
JIT:
  Functions: 18
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.800 ms (Deform 0.389 ms), Inlining 93.854 ms, Optimization 73.081 ms, Emission 46.382 ms, Total 214.117 ms
Execution Time: 609.735 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..10.56 rows=1 width=314) (actual time=0.031..0.031 rows=1.00 loops=1)
  Buffers: shared read=6
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2497173.58 rows=249808 width=314) (actual time=0.030..0.030 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared read=6
Planning:
  Buffers: shared hit=2 read=9
Planning Time: 0.207 ms
Execution Time: 0.046 ms
```

### cursor page from the midpoint (limit 500)

```
Limit  (cost=0.56..280.33 rows=500 width=314) (actual time=0.017..0.165 rows=500.00 loops=1)
  Buffers: shared hit=3 read=29
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2203976.99 rows=3939029 width=314) (actual time=0.016..0.142 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('1504560'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=3 read=29
Planning:
  Buffers: shared hit=7 read=7
Planning Time: 0.094 ms
Execution Time: 0.196 ms
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | query-by-entity-cold | thrpt | 1 | 10.076 | ops/ms | 3.0% | 10,076 | 604,609 | 0 |
| postgres:external/metrics=off | query-by-entity-hot | thrpt | 1 | 0.001 | ops/ms | 4.7% | 1 | 37 | 0 |
| postgres:external/metrics=off | query-by-id | thrpt | 1 | 55.620 | ops/ms | 2.4% | 55,620 | 3,337,283 | 0 |
| postgres:external/metrics=off | query-by-multi-tag | thrpt | 1 | 0.063 | ops/ms | 2.7% | 63 | 3,804 | 0 |
| postgres:external/metrics=off | query-by-or-groups | thrpt | 1 | 0.415 | ops/ms | 3.7% | 415 | 24,925 | 0 |
| postgres:external/metrics=off | query-by-tag-needle | thrpt | 1 | 3.439 | ops/ms | 1.4% | 3,439 | 206,343 | 0 |
| postgres:external/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.182 | ops/ms | 2.5% | 182 | 10,925 | 0 |
| postgres:external/metrics=off | query-by-type | thrpt | 1 | 0.962 | ops/ms | 2.8% | 962 | 57,739 | 0 |
| postgres:external/metrics=off | query-cursor-walk | thrpt | 1 | 0.176 | ops/ms | 3.8% | 176 | 10,573 | 0 |
| postgres:external/metrics=off | query-last-event | thrpt | 1 | 16.350 | ops/ms | 5.6% | 16,350 | 981,019 | 0 |
| postgres:external/metrics=off | query-stream-page | thrpt | 1 | 0.929 | ops/ms | 2.9% | 929 | 55,724 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
