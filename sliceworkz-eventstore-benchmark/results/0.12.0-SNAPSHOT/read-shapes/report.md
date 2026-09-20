# Benchmark run: read-shapes

What each query shape costs against a store holding nothing but the context under test. The control for the composition profiles: crowded-store and crowded-database run these exact workloads over the same volume and the same targets, so `compare` between them attributes the difference to what else is in the way and nothing else.
Selectivity is two workloads rather than one on purpose. A tag matching ten events and a tag matching one percent of the store are different plans, and a single "tag query" number would be an average of two regimes that never occur together.

| | |
|---|---|
| suite version | 0.12.0-SNAPSHOT |
| started | 2026-09-20T11:55:33.975620459Z |
| finished | 2026-09-20T12:33:57.867492931Z |
| targets | inmem/metrics=off, postgres:18/metrics=off |
| corpus restore | no restore needed: every workload in this run is read-only |

> **Not suitable as a published baseline.**
>
> - measured against a Testcontainers PostgreSQL running stock defaults; publish from an external server whose configuration is deliberate
> - 2 measurements are too noisy to compare against anything, past the 10% this report calls uncomparable: query-by-multi-tag (inmem/metrics=off, 1 thread) at 11%, query-cursor-walk (inmem/metrics=off, 1 thread) at 11%

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

| workload | threads | inmem/metrics=off | postgres:18/metrics=off |
|---|---|---|---|
| query-stream-page | 1 | 3.159 ± 0.067 ops/ms | 0.936 ± 0.054 ops/ms (0.30x) |
| query-by-type | 1 | 2.775 ± 0.139 ops/ms | 0.970 ± 0.028 ops/ms (0.35x) |
| query-by-tag-needle | 1 | 0.154 ± 0.013 ops/ms | 3.679 ± 0.045 ops/ms (23.90x) |
| query-by-tag-swathe | 1 | 0.415 ± 0.016 ops/ms | 0.485 ± 0.015 ops/ms (1.17x) |
| query-by-entity-hot | 1 | 0.068 ± 0.001 ops/ms | 0.048 ± 0.001 ops/ms (0.70x) |
| query-by-entity-cold | 1 | 0.146 ± 0.012 ops/ms | 8.674 ± 0.214 ops/ms (59.39x) |
| query-by-multi-tag | 1 | 0.155 ± 0.016 ops/ms | 0.392 ± 0.015 ops/ms (2.53x) |
| query-by-or-groups | 1 | 1.471 ± 0.021 ops/ms | 0.377 ± 0.012 ops/ms (0.26x) |
| query-last-event | 1 | 1.680 ± 0.100 ops/ms | 14.857 ± 0.449 ops/ms (8.84x) |
| query-cursor-walk | 1 | 0.495 ± 0.055 ops/ms | 0.177 ± 0.006 ops/ms (0.36x) |
| query-by-id | 1 | 2004.737 ± 59.799 ops/ms | 44.824 ± 3.646 ops/ms (0.02x) |
| query-wildcard | 1 | 22.074 ± 0.538 ops/ms | 1.081 ± 0.035 ops/ms (0.05x) |

Relative to **inmem/metrics=off**, higher is better. A ratio is only about the setting these targets differ in if it is larger than both error bars and survives running the profile with the targets in the opposite order: the first target is measured against a server the later ones then inherit warm, which is worth a few percent on its own.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

**The reconstructions are the weaker half of this section, including for the reads.** They inline as literals what the store binds as parameters, so a reconstruction can report an execution time the whole measured operation fits inside -- which is not a fast plan but a different one. Read the captured plans further down against the measurements, and these for the shape of the predicate.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.42..109.35 rows=500 width=314) (actual time=0.030..0.256 rows=500.00 loops=1)
  Buffers: shared hit=26
  ->  Index Scan using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..11879.93 rows=54530 width=314) (actual time=0.029..0.227 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=26
Planning:
  Buffers: shared hit=105
Planning Time: 0.707 ms
Execution Time: 0.296 ms
```

### tag needle (~10 matches)

```
Sort  (cost=57.70..57.72 rows=7 width=314) (actual time=0.441..0.443 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=55
  ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=30.24..57.61 rows=7 width=314) (actual time=0.364..0.402 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=47
        ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..30.24 rows=7 width=0) (actual time=0.350..0.351 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=37
Planning:
  Buffers: shared hit=12
Planning Time: 0.156 ms
Execution Time: 0.507 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=1519.48..1520.73 rows=500 width=314) (actual time=3.704..3.746 rows=500.00 loops=1)
  Buffers: shared hit=1037
  ->  Sort  (cost=1519.48..1520.76 rows=511 width=314) (actual time=3.703..3.719 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 442kB
        Buffers: shared hit=1037
        ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=32.88..1496.49 rows=511 width=314) (actual time=0.804..3.308 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=1000
              Buffers: shared hit=1037
              ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..32.76 rows=511 width=0) (actual time=0.706..0.707 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=37
Planning:
  Buffers: shared hit=3
Planning Time: 0.123 ms
Execution Time: 3.824 ms
```

### one entity's whole history (hot)

```
Sort  (cost=4760.52..4769.92 rows=3761 width=314) (actual time=5.863..6.052 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1910kB
  Buffers: shared hit=2029
  ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=54.07..4537.17 rows=3761 width=314) (actual time=1.552..4.182 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=1993
        Buffers: shared hit=2029
        ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..53.13 rows=3761 width=0) (actual time=1.390..1.391 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=36
Planning:
  Buffers: shared hit=3
Planning Time: 0.136 ms
Execution Time: 6.409 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.42..3.55 rows=1 width=314) (actual time=0.026..0.026 rows=1.00 loops=1)
  Buffers: shared hit=5
  ->  Index Scan Backward using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..11762.42 rows=3761 width=314) (actual time=0.025..0.025 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 20
        Index Searches: 1
        Buffers: shared hit=5
Planning:
  Buffers: shared hit=11
Planning Time: 0.181 ms
Execution Time: 0.040 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..137.67 rows=500 width=314) (actual time=0.016..0.162 rows=500.00 loops=1)
  Buffers: shared hit=27
  ->  Index Scan using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..10777.88 rows=39263 width=314) (actual time=0.015..0.137 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('771'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=27
Planning:
  Buffers: shared hit=14
Planning Time: 0.129 ms
Execution Time: 0.197 ms
```

> **These are the store's own statements, explained by the server.** Captured by running each 
> workload with `auto_explain` on, after the last measurement, so the SQL is the one the backend 
> built, the parameters are bound as it binds them, and the plan is the one PostgreSQL chose. 
> Where these and the reconstructed plans above disagree, these are the ones that describe what 
> was measured.
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

### read as issued: query-stream-page @ postgres:18/metrics=off (generic plan) — measured 1.07 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 ORDER BY event_tx::xid8, event_position  LIMIT $3 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = '500'
	Limit  (cost=0.42..1152.83 rows=5000 width=370) (actual time=0.054..0.304 rows=500.00 loops=1)
	  Buffers: shared hit=26
	  ->  Index Scan using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..11524.55 rows=50000 width=370) (actual time=0.050..0.256 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=26
```

### read as issued: query-by-type @ postgres:18/metrics=off (generic plan) — measured 1.03 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3))) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = '500'
	Limit  (cost=0.42..569.33 rows=454 width=370) (actual time=0.069..0.499 rows=500.00 loops=1)
	  Buffers: shared hit=48
	  ->  Index Scan using bm_628labzk3k7h_idx_events_stream_type_position on bm_628labzk3k7h_events  (cost=0.42..5695.77 rows=4545 width=370) (actual time=0.065..0.442 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=48
```

### read as issued: query-by-tag-needle @ postgres:18/metrics=off (generic plan) — measured 0.27 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:needle'
	Sort  (cost=848.35..848.98 rows=250 width=370) (actual time=0.514..0.516 rows=10.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 28kB
	  Buffers: shared hit=47
	  ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=31.52..838.40 rows=250 width=370) (actual time=0.481..0.498 rows=10.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=10
	        Buffers: shared hit=47
	        ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..31.45 rows=250 width=0) (actual time=0.454..0.455 rows=10.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=37
```

### read as issued: query-by-tag-swathe @ postgres:18/metrics=off (generic plan) — measured 2.06 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:swathe', $4 = '500'
	Limit  (cost=848.35..848.42 rows=25 width=370) (actual time=3.760..3.848 rows=500.00 loops=1)
	  Buffers: shared hit=1037
	  ->  Sort  (cost=848.35..848.98 rows=250 width=370) (actual time=3.756..3.793 rows=500.00 loops=1)
	        Sort Key: event_tx, event_position
	        Sort Method: quicksort  Memory: 532kB
	        Buffers: shared hit=1037
	        ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=31.52..838.40 rows=250 width=370) (actual time=1.434..3.039 rows=1000.00 loops=1)
	              Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=1000
	              Buffers: shared hit=1037
	              ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..31.45 rows=250 width=0) (actual time=1.257..1.257 rows=1000.00 loops=1)
	                    Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                    Index Searches: 1
	                    Buffers: shared hit=37
```

### read as issued: query-by-entity-hot @ postgres:18/metrics=off (generic plan) — measured 20.91 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-000000'
	Sort  (cost=848.35..848.98 rows=250 width=370) (actual time=6.017..6.206 rows=6876.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 2176kB
	  Buffers: shared hit=2029
	  ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=31.52..838.40 rows=250 width=370) (actual time=1.566..4.056 rows=6876.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=1993
	        Buffers: shared hit=2029
	        ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..31.45 rows=250 width=0) (actual time=1.401..1.402 rows=6876.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=36
```

### read as issued: query-by-entity-cold @ postgres:18/metrics=off (generic plan) — measured 0.12 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-001729'
	Sort  (cost=848.35..848.98 rows=250 width=370) (actual time=0.108..0.109 rows=1.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 25kB
	  Buffers: shared hit=16
	  ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=31.52..838.40 rows=250 width=370) (actual time=0.100..0.101 rows=1.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=1
	        Buffers: shared hit=16
	        ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..31.45 rows=250 width=0) (actual time=0.085..0.085 rows=1.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=15
```

### read as issued: query-by-multi-tag @ postgres:18/metrics=off (generic plan) — measured 2.55 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3, $4, $5]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $6 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'channel:web', $4 = 'sku:SKU-000000', $5 = 'country:BE', $6 = '500'
	Limit  (cost=848.35..848.42 rows=25 width=370) (actual time=3.854..3.925 rows=500.00 loops=1)
	  Buffers: shared hit=526
	  ->  Sort  (cost=848.35..848.98 rows=250 width=370) (actual time=3.851..3.877 rows=500.00 loops=1)
	        Sort Key: event_tx, event_position
	        Sort Method: quicksort  Memory: 292kB
	        Buffers: shared hit=526
	        ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=31.52..838.40 rows=250 width=370) (actual time=3.098..3.652 rows=526.00 loops=1)
	              Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=471
	              Buffers: shared hit=526
	              ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_tags  (cost=0.00..31.45 rows=250 width=0) (actual time=3.021..3.021 rows=526.00 loops=1)
	                    Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	                    Index Searches: 1
	                    Buffers: shared hit=55
```

### read as issued: query-by-or-groups @ postgres:18/metrics=off (generic plan) — measured 2.65 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=0.42..1269.50 rows=23 width=370) (actual time=0.094..3.693 rows=500.00 loops=1)
	  Buffers: shared hit=126
	  ->  Index Scan using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.42..12525.68 rows=227 width=370) (actual time=0.089..3.639 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (((event_type = ANY (ARRAY[($3)::text, ($4)::text])) AND (event_tags @> ARRAY[($5)::text])) OR ((event_type = ANY (ARRAY[($6)::text, ($7)::text])) AND (event_tags @> ARRAY[($8)::text])) OR ((event_type = ANY (ARRAY[($9)::text, ($10)::text])) AND (event_tags @> ARRAY[($11)::text])) OR ((event_type = ANY (ARRAY[($12)::text, ($13)::text])) AND (event_tags @> ARRAY[($14)::text])) OR ((event_type = ANY (ARRAY[($15)::text, ($16)::text])) AND (event_tags @> ARRAY[($17)::text])))
	        Rows Removed by Filter: 2196
	        Index Searches: 1
	        Buffers: shared hit=126
```

### read as issued: query-last-event @ postgres:18/metrics=off (generic plan) — measured 0.07 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=400.41..400.42 rows=2 width=370) (actual time=1.057..1.058 rows=1.00 loops=1)
	  Buffers: shared hit=230
	  ->  Sort  (cost=400.41..400.47 rows=23 width=370) (actual time=1.054..1.055 rows=1.00 loops=1)
	        Sort Key: event_tx DESC, event_position DESC
	        Sort Method: top-N heapsort  Memory: 26kB
	        Buffers: shared hit=230
	        ->  Bitmap Heap Scan on bm_628labzk3k7h_events  (cost=312.17..399.89 rows=23 width=370) (actual time=0.754..0.975 rows=208.00 loops=1)
	              Recheck Cond: ((event_tags @> ARRAY[($4)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Heap Blocks: exact=197
	              Buffers: shared hit=230
	              ->  BitmapAnd  (cost=312.17..312.17 rows=23 width=0) (actual time=0.718..0.719 rows=0.00 loops=1)
	                    Buffers: shared hit=33
	                    ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_tags  (cost=0.00..15.31 rows=500 width=0) (actual time=0.495..0.495 rows=6876.00 loops=1)
	                          Index Cond: (event_tags @> ARRAY[($4)::text])
	                          Index Searches: 1
	                          Buffers: shared hit=6
	                    ->  Bitmap Index Scan on bm_628labzk3k7h_idx_events_stream_type_position  (cost=0.00..296.60 rows=4545 width=0) (actual time=0.154..0.154 rows=1660.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                          Index Searches: 1
	                          Buffers: shared hit=27
```

### read as issued: query-last-event @ postgres:18/metrics=off (custom plan, first executions only) — measured 0.07 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=0.42..25.06 rows=1 width=370) (actual time=0.031..0.032 rows=1.00 loops=1)
	  Buffers: shared hit=6
	  ->  Index Scan Backward using bm_628labzk3k7h_idx_events_stream_type_position on bm_628labzk3k7h_events  (cost=0.42..1552.42 rows=63 width=370) (actual time=0.030..0.030 rows=1.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = 'StockCounted'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
	        Rows Removed by Filter: 4
	        Index Searches: 1
	        Buffers: shared hit=6
```

### read as issued: query-cursor-walk @ postgres:18/metrics=off (generic plan) — measured 5.65 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND (event_tx, event_position) > ($1::xid8, $2) AND stream_context = $3 AND stream_purpose = $4 ORDER BY event_tx::xid8, event_position  LIMIT $5 OFFSET 0
	Query Parameters: $1 = '772', $2 = '29500', $3 = 'inventory', $4 = 'default', $5 = '500'
	Limit  (cost=0.43..894.68 rows=1667 width=370) (actual time=0.011..0.210 rows=500.00 loops=1)
	  Buffers: shared hit=26
	  ->  Index Scan using bm_628labzk3k7h_idx_events_stream_position on bm_628labzk3k7h_events  (cost=0.43..8941.31 rows=16667 width=370) (actual time=0.011..0.184 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($3)::text) AND (stream_purpose = ($4)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW(($1)::xid8, $2)))
	        Index Searches: 1
	        Buffers: shared hit=26
```

### read as issued: query-by-id @ postgres:18/metrics=off (generic plan) — measured 0.02 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_id = $1::uuid

	Query Parameters: $1 = '018cc251-f400-74eb-8412-04a7d99e38f3'
	Index Scan using bm_628labzk3k7h_events_event_id_key on bm_628labzk3k7h_events  (cost=0.42..8.45 rows=1 width=362) (actual time=0.039..0.042 rows=1.00 loops=1)
	  Index Cond: (event_id = ($1)::uuid)
	  Index Searches: 1
	  Buffers: shared hit=4
```

### read as issued: query-wildcard @ postgres:18/metrics=off (generic plan) — measured 0.92 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_628labzk3k7h_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND event_position > 0 ORDER BY event_tx::xid8, event_position  LIMIT $1 OFFSET 0
	Query Parameters: $1 = '500'
	Limit  (cost=0.42..1383.03 rows=10000 width=370) (actual time=0.035..0.291 rows=500.00 loops=1)
	  Buffers: shared hit=24
	  ->  Index Scan using bm_628labzk3k7h_idx_events_global_order on bm_628labzk3k7h_events  (cost=0.42..13826.49 rows=100000 width=370) (actual time=0.031..0.256 rows=500.00 loops=1)
	        Index Cond: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Index Searches: 1
	        Buffers: shared hit=24
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| inmem/metrics=off | query-by-entity-cold | thrpt | 1 | 0.146 | ops/ms | 8.4% | 146 | 8,770 | 0 |
| inmem/metrics=off | query-by-entity-hot | thrpt | 1 | 0.068 | ops/ms | 1.4% | 68 | 4,114 | 0 |
| inmem/metrics=off | query-by-id | thrpt | 1 | 2004.737 | ops/ms | 3.0% | 2,004,737 | 120,287,517 | 0 |
| inmem/metrics=off | query-by-multi-tag | thrpt | 1 | 0.155 | ops/ms | 10.5% | 155 | 9,314 | 0 |
| inmem/metrics=off | query-by-or-groups | thrpt | 1 | 1.471 | ops/ms | 1.4% | 1,471 | 88,298 | 0 |
| inmem/metrics=off | query-by-tag-needle | thrpt | 1 | 0.154 | ops/ms | 8.6% | 154 | 9,241 | 0 |
| inmem/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.415 | ops/ms | 3.7% | 415 | 24,927 | 0 |
| inmem/metrics=off | query-by-type | thrpt | 1 | 2.775 | ops/ms | 5.0% | 2,775 | 166,481 | 0 |
| inmem/metrics=off | query-cursor-walk | thrpt | 1 | 0.495 | ops/ms | 11.1% | 495 | 29,722 | 0 |
| inmem/metrics=off | query-last-event | thrpt | 1 | 1.680 | ops/ms | 6.0% | 1,680 | 100,793 | 0 |
| inmem/metrics=off | query-stream-page | thrpt | 1 | 3.159 | ops/ms | 2.1% | 3,159 | 189,561 | 0 |
| inmem/metrics=off | query-wildcard | thrpt | 1 | 22.074 | ops/ms | 2.4% | 22,074 | 1,324,509 | 0 |
| postgres:18/metrics=off | query-by-entity-cold | thrpt | 1 | 8.674 | ops/ms | 2.5% | 8,674 | 520,449 | 0 |
| postgres:18/metrics=off | query-by-entity-hot | thrpt | 1 | 0.048 | ops/ms | 1.7% | 48 | 2,876 | 0 |
| postgres:18/metrics=off | query-by-id | thrpt | 1 | 44.824 | ops/ms | 8.1% | 44,824 | 2,689,527 | 0 |
| postgres:18/metrics=off | query-by-multi-tag | thrpt | 1 | 0.392 | ops/ms | 3.7% | 392 | 23,531 | 0 |
| postgres:18/metrics=off | query-by-or-groups | thrpt | 1 | 0.377 | ops/ms | 3.1% | 377 | 22,653 | 0 |
| postgres:18/metrics=off | query-by-tag-needle | thrpt | 1 | 3.679 | ops/ms | 1.2% | 3,679 | 220,748 | 0 |
| postgres:18/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.485 | ops/ms | 3.0% | 485 | 29,104 | 0 |
| postgres:18/metrics=off | query-by-type | thrpt | 1 | 0.970 | ops/ms | 2.9% | 970 | 58,235 | 0 |
| postgres:18/metrics=off | query-cursor-walk | thrpt | 1 | 0.177 | ops/ms | 3.6% | 177 | 10,626 | 0 |
| postgres:18/metrics=off | query-last-event | thrpt | 1 | 14.857 | ops/ms | 3.0% | 14,857 | 891,432 | 0 |
| postgres:18/metrics=off | query-stream-page | thrpt | 1 | 0.936 | ops/ms | 5.7% | 936 | 56,166 | 0 |
| postgres:18/metrics=off | query-wildcard | thrpt | 1 | 1.081 | ops/ms | 3.3% | 1,081 | 64,886 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
