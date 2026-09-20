# Benchmark run: crowded-store

read-shapes over a store that also holds five other bounded contexts, in the same table, at five times the volume. This is the composition that actually slows a query down: more rows behind every index, lower selectivity for a tag that appears in several contexts, a bigger heap to correlate.
Compare against read-shapes -- same workloads, same targets, same volume under test -- and the difference is the cost of sharing a table with other domains.

| | |
|---|---|
| suite version | 0.12.0-SNAPSHOT |
| started | 2026-09-20T10:58:10.097126465Z |
| finished | 2026-09-20T11:55:33.280616754Z |
| targets | inmem/metrics=off, postgres:18/metrics=off |
| corpus restore | no restore needed: every workload in this run is read-only |

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
| fingerprint | `bm_2syjmtlnanmm_` |
| volume | 100,000 events under test |
| stream design | TAGGED |
| composition | MULTI_DOMAIN |
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
| query-stream-page | 1 | 3.178 ± 0.052 ops/ms | 0.904 ± 0.040 ops/ms (0.28x) |
| query-by-type | 1 | 2.749 ± 0.021 ops/ms | 0.988 ± 0.031 ops/ms (0.36x) |
| query-by-tag-needle | 1 | 0.048 ± 0.002 ops/ms | 3.661 ± 0.072 ops/ms (75.63x) |
| query-by-tag-swathe | 1 | 0.420 ± 0.033 ops/ms | 0.472 ± 0.010 ops/ms (1.13x) |
| query-by-entity-hot | 1 | 0.035 ± 0.001 ops/ms | 0.050 ± 0.000 ops/ms (1.44x) |
| query-by-entity-cold | 1 | 0.046 ± 0.001 ops/ms | 8.299 ± 0.159 ops/ms (180.22x) |
| query-by-multi-tag | 1 | 0.136 ± 0.013 ops/ms | 0.376 ± 0.009 ops/ms (2.77x) |
| query-by-or-groups | 1 | 1.455 ± 0.041 ops/ms | 0.066 ± 0.001 ops/ms (0.05x) |
| query-last-event | 1 | 0.025 ± 0.001 ops/ms | 14.892 ± 0.210 ops/ms (596.26x) |
| query-cursor-walk | 1 | 0.474 ± 0.041 ops/ms | 0.181 ± 0.011 ops/ms (0.38x) |
| query-by-id | 1 | 1809.270 ± 76.686 ops/ms | 47.152 ± 0.849 ops/ms (0.03x) |
| query-wildcard | 1 | 20.883 ± 0.276 ops/ms | 1.246 ± 0.031 ops/ms (0.06x) |

Relative to **inmem/metrics=off**, higher is better. A ratio is only about the setting these targets differ in if it is larger than both error bars and survives running the profile with the targets in the opposite order: the first target is measured against a server the later ones then inherit warm, which is worth a few percent on its own.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

**The reconstructions are the weaker half of this section, including for the reads.** They inline as literals what the store binds as parameters, so a reconstruction can report an execution time the whole measured operation fits inside -- which is not a fast plan but a different one. Read the captured plans further down against the measurements, and these for the shape of the predicate.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.43..783.06 rows=500 width=279) (actual time=0.038..0.257 rows=500.00 loops=1)
  Buffers: shared hit=27
  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..85581.06 rows=54675 width=279) (actual time=0.037..0.211 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=27
Planning:
  Buffers: shared hit=105
Planning Time: 0.656 ms
Execution Time: 0.303 ms
```

### tag needle (~10 matches)

```
Sort  (cost=58.22..58.24 rows=7 width=279) (actual time=0.531..0.533 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=58
  ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=30.27..58.12 rows=7 width=279) (actual time=0.467..0.511 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=50
        ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..30.27 rows=7 width=0) (actual time=0.454..0.454 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=40
Planning:
  Buffers: shared hit=12
Planning Time: 0.182 ms
Execution Time: 0.603 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=383.46..383.69 rows=91 width=279) (actual time=2.239..2.273 rows=500.00 loops=1)
  Buffers: shared hit=1040
  ->  Sort  (cost=383.46..383.69 rows=91 width=279) (actual time=2.237..2.250 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 442kB
        Buffers: shared hit=1040
        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=30.71..380.50 rows=91 width=279) (actual time=0.892..2.001 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=1000
              Buffers: shared hit=1040
              ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..30.69 rows=91 width=0) (actual time=0.817..0.817 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=40
Planning:
  Buffers: shared hit=3
Planning Time: 0.211 ms
Execution Time: 2.338 ms
```

### one entity's whole history (hot)

```
Sort  (cost=10046.46..10055.54 rows=3630 width=279) (actual time=4.949..5.117 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1910kB
  Buffers: shared hit=2032
  ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=53.42..9831.83 rows=3630 width=279) (actual time=1.363..3.487 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=1993
        Buffers: shared hit=2032
        ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..52.51 rows=3631 width=0) (actual time=1.222..1.223 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=39
Planning:
  Buffers: shared hit=3
Planning Time: 0.125 ms
Execution Time: 5.431 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.43..23.97 rows=1 width=279) (actual time=0.042..0.042 rows=1.00 loops=1)
  Buffers: shared hit=3 read=2
  ->  Index Scan Backward using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..85462.53 rows=3630 width=279) (actual time=0.041..0.041 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 20
        Index Searches: 1
        Buffers: shared hit=3 read=2
Planning:
  Buffers: shared hit=11
Planning Time: 0.139 ms
Execution Time: 0.052 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.43..822.30 rows=500 width=279) (actual time=0.016..0.144 rows=500.00 loops=1)
  Buffers: shared hit=27
  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..85515.94 rows=52025 width=279) (actual time=0.016..0.122 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('771'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=27
Planning:
  Buffers: shared hit=14
Planning Time: 0.083 ms
Execution Time: 0.172 ms
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

### read as issued: query-stream-page @ postgres:18/metrics=off (generic plan) — measured 1.11 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 ORDER BY event_tx::xid8, event_position  LIMIT $3 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = '500'
	Limit  (cost=0.43..8892.88 rows=9999 width=335) (actual time=0.059..0.448 rows=500.00 loops=1)
	  Buffers: shared hit=27
	  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..88924.94 rows=99990 width=335) (actual time=0.056..0.392 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=27
```

### read as issued: query-by-type @ postgres:18/metrics=off (generic plan) — measured 1.01 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3))) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = '500'
	Limit  (cost=0.43..1416.18 rows=435 width=335) (actual time=0.055..0.385 rows=500.00 loops=1)
	  Buffers: shared hit=48
	  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_type_position on bm_2syjmtlnanmm_events  (cost=0.43..14148.13 rows=4347 width=335) (actual time=0.052..0.342 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=48
```

### read as issued: query-by-tag-needle @ postgres:18/metrics=off (generic plan) — measured 0.27 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:needle'
	Sort  (cost=1835.37..1836.62 rows=500 width=335) (actual time=0.469..0.471 rows=10.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 28kB
	  Buffers: shared hit=50
	  ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=32.86..1812.96 rows=500 width=335) (actual time=0.435..0.453 rows=10.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=10
	        Buffers: shared hit=50
	        ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..32.73 rows=500 width=0) (actual time=0.408..0.409 rows=10.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=40
```

### read as issued: query-by-tag-swathe @ postgres:18/metrics=off (generic plan) — measured 2.12 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:swathe', $4 = '500'
	Limit  (cost=1835.37..1835.50 rows=50 width=335) (actual time=2.648..2.721 rows=500.00 loops=1)
	  Buffers: shared hit=1040
	  ->  Sort  (cost=1835.37..1836.62 rows=500 width=335) (actual time=2.643..2.670 rows=500.00 loops=1)
	        Sort Key: event_tx, event_position
	        Sort Method: quicksort  Memory: 532kB
	        Buffers: shared hit=1040
	        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=32.86..1812.96 rows=500 width=335) (actual time=1.076..2.137 rows=1000.00 loops=1)
	              Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=1000
	              Buffers: shared hit=1040
	              ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..32.73 rows=500 width=0) (actual time=0.919..0.920 rows=1000.00 loops=1)
	                    Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                    Index Searches: 1
	                    Buffers: shared hit=40
```

### read as issued: query-by-entity-hot @ postgres:18/metrics=off (generic plan) — measured 20.14 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-000000'
	Sort  (cost=1835.37..1836.62 rows=500 width=335) (actual time=7.118..7.300 rows=6876.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 2176kB
	  Buffers: shared hit=2032
	  ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=32.86..1812.96 rows=500 width=335) (actual time=2.515..5.018 rows=6876.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=1993
	        Buffers: shared hit=2032
	        ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..32.73 rows=500 width=0) (actual time=2.229..2.229 rows=6876.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=39
```

### read as issued: query-by-entity-cold @ postgres:18/metrics=off (generic plan) — measured 0.12 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-001729'
	Sort  (cost=1835.37..1836.62 rows=500 width=335) (actual time=0.160..0.161 rows=1.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 25kB
	  Buffers: shared hit=21
	  ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=32.86..1812.96 rows=500 width=335) (actual time=0.149..0.150 rows=1.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=1
	        Buffers: shared hit=21
	        ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..32.73 rows=500 width=0) (actual time=0.130..0.131 rows=1.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=20
```

### read as issued: query-by-multi-tag @ postgres:18/metrics=off (generic plan) — measured 2.66 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3, $4, $5]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $6 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-000000', $4 = 'country:BE', $5 = 'channel:web', $6 = '500'
	Limit  (cost=1835.37..1835.50 rows=50 width=335) (actual time=3.221..3.274 rows=500.00 loops=1)
	  Buffers: shared hit=535
	  ->  Sort  (cost=1835.37..1836.62 rows=500 width=335) (actual time=3.218..3.235 rows=500.00 loops=1)
	        Sort Key: event_tx, event_position
	        Sort Method: quicksort  Memory: 292kB
	        Buffers: shared hit=535
	        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=32.86..1812.96 rows=500 width=335) (actual time=2.286..2.953 rows=526.00 loops=1)
	              Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=471
	              Buffers: shared hit=535
	              ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..32.73 rows=500 width=0) (actual time=2.240..2.240 rows=526.00 loops=1)
	                    Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	                    Index Searches: 1
	                    Buffers: shared hit=64
```

### read as issued: query-by-or-groups @ postgres:18/metrics=off (generic plan) — measured 15.23 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=4244.29..4244.35 rows=22 width=335) (actual time=14.413..14.450 rows=500.00 loops=1)
	  Buffers: shared hit=4144
	  ->  Sort  (cost=4244.29..4244.84 rows=217 width=335) (actual time=14.411..14.426 rows=500.00 loops=1)
	        Sort Key: event_tx, event_position
	        Sort Method: top-N heapsort  Memory: 278kB
	        Buffers: shared hit=4144
	        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=3418.86..4235.87 rows=217 width=335) (actual time=9.572..12.541 rows=11122.00 loops=1)
	              Recheck Cond: (((event_tags @> ARRAY[($5)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($3)::text, ($4)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot()))) OR ((event_tags @> ARRAY[($8)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($6)::text, ($7)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot()))) OR ((event_tags @> ARRAY[($11)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($9)::text, ($10)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot()))) OR ((event_tags @> ARRAY[($14)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot()))) OR ((event_tags @> ARRAY[($17)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($15)::text, ($16)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot()))))
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=2050
	              Buffers: shared hit=4144
	              ->  BitmapOr  (cost=3418.86..3418.86 rows=217 width=0) (actual time=9.418..9.420 rows=0.00 loops=1)
	                    Buffers: shared hit=2094
	                    ->  BitmapAnd  (cost=683.57..683.57 rows=43 width=0) (actual time=2.918..2.918 rows=0.00 loops=1)
	                          Buffers: shared hit=423
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_tags  (cost=0.00..31.95 rows=3000 width=0) (actual time=1.725..1.725 rows=40227.00 loops=1)
	                                Index Cond: (event_tags @> ARRAY[($5)::text])
	                                Index Searches: 1
	                                Buffers: shared hit=14
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..651.26 rows=8695 width=0) (actual time=0.940..0.940 rows=39331.00 loops=1)
	                                Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($3)::text, ($4)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                                Index Searches: 2
	                                Buffers: shared hit=409
	                    ->  BitmapAnd  (cost=683.57..683.57 rows=43 width=0) (actual time=2.080..2.081 rows=0.00 loops=1)
	                          Buffers: shared hit=419
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_tags  (cost=0.00..31.95 rows=3000 width=0) (actual time=1.106..1.106 rows=20300.00 loops=1)
	                                Index Cond: (event_tags @> ARRAY[($8)::text])
	                                Index Searches: 1
	                                Buffers: shared hit=10
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..651.26 rows=8695 width=0) (actual time=0.764..0.765 rows=39331.00 loops=1)
	                                Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($6)::text, ($7)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                                Index Searches: 2
	                                Buffers: shared hit=409
	                    ->  BitmapAnd  (cost=683.57..683.57 rows=43 width=0) (actual time=1.602..1.602 rows=0.00 loops=1)
	                          Buffers: shared hit=418
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_tags  (cost=0.00..31.95 rows=3000 width=0) (actual time=0.704..0.704 rows=13494.00 loops=1)
	                                Index Cond: (event_tags @> ARRAY[($11)::text])
	                                Index Searches: 1
	                                Buffers: shared hit=9
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..651.26 rows=8695 width=0) (actual time=0.720..0.720 rows=39331.00 loops=1)
	                                Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($9)::text, ($10)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                                Index Searches: 2
	                                Buffers: shared hit=409
	                    ->  BitmapAnd  (cost=683.57..683.57 rows=43 width=0) (actual time=1.406..1.406 rows=0.00 loops=1)
	                          Buffers: shared hit=417
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_tags  (cost=0.00..31.95 rows=3000 width=0) (actual time=0.524..0.524 rows=10185.00 loops=1)
	                                Index Cond: (event_tags @> ARRAY[($14)::text])
	                                Index Searches: 1
	                                Buffers: shared hit=8
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..651.26 rows=8695 width=0) (actual time=0.719..0.719 rows=39331.00 loops=1)
	                                Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                                Index Searches: 2
	                                Buffers: shared hit=409
	                    ->  BitmapAnd  (cost=683.57..683.57 rows=43 width=0) (actual time=1.312..1.312 rows=0.00 loops=1)
	                          Buffers: shared hit=417
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_tags  (cost=0.00..31.95 rows=3000 width=0) (actual time=0.436..0.436 rows=8202.00 loops=1)
	                                Index Cond: (event_tags @> ARRAY[($17)::text])
	                                Index Searches: 1
	                                Buffers: shared hit=8
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..651.26 rows=8695 width=0) (actual time=0.746..0.746 rows=39331.00 loops=1)
	                                Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($15)::text, ($16)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                                Index Searches: 2
	                                Buffers: shared hit=409
```

### read as issued: query-by-or-groups @ postgres:18/metrics=off (custom plan, first executions only) — measured 15.23 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=9963.54..9964.79 rows=500 width=335) (actual time=20.512..20.547 rows=500.00 loops=1)
	  Buffers: shared hit=2461
	  ->  Sort  (cost=9963.54..9964.84 rows=522 width=335) (actual time=20.511..20.524 rows=500.00 loops=1)
	        Sort Key: event_tx, event_position
	        Sort Method: top-N heapsort  Memory: 278kB
	        Buffers: shared hit=2461
	        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=270.42..9939.97 rows=522 width=335) (actual time=1.076..18.581 rows=11122.00 loops=1)
	              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000001}'::text[]) OR (event_tags @> '{sku:SKU-000002}'::text[]) OR (event_tags @> '{sku:SKU-000003}'::text[]) OR (event_tags @> '{sku:SKU-000004}'::text[]))
	              Rows Removed by Filter: 28209
	              Heap Blocks: exact=2052
	              Buffers: shared hit=2461
	              ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..270.29 rows=3563 width=0) (actual time=0.930..0.930 rows=39331.00 loops=1)
	                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                    Index Searches: 2
	                    Buffers: shared hit=409
```

### read as issued: query-last-event @ postgres:18/metrics=off (generic plan) — measured 0.07 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=445.04..445.04 rows=2 width=335) (actual time=4.492..4.494 rows=1.00 loops=1)
	  Buffers: shared hit=238
	  ->  Sort  (cost=445.04..445.09 rows=22 width=335) (actual time=4.490..4.491 rows=1.00 loops=1)
	        Sort Key: event_tx DESC, event_position DESC
	        Sort Method: top-N heapsort  Memory: 26kB
	        Buffers: shared hit=238
	        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=357.84..444.54 rows=22 width=335) (actual time=4.197..4.407 rows=208.00 loops=1)
	              Recheck Cond: ((event_tags @> ARRAY[($4)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Heap Blocks: exact=197
	              Buffers: shared hit=238
	              ->  BitmapAnd  (cost=357.84..357.84 rows=22 width=0) (actual time=4.141..4.141 rows=0.00 loops=1)
	                    Buffers: shared hit=41
	                    ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_tags  (cost=0.00..31.95 rows=3000 width=0) (actual time=3.458..3.458 rows=40227.00 loops=1)
	                          Index Cond: (event_tags @> ARRAY[($4)::text])
	                          Index Searches: 1
	                          Buffers: shared hit=14
	                    ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..325.63 rows=4347 width=0) (actual time=0.195..0.195 rows=1660.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                          Index Searches: 1
	                          Buffers: shared hit=27
```

### read as issued: query-last-event @ postgres:18/metrics=off (custom plan, first executions only) — measured 0.07 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=0.43..52.07 rows=1 width=335) (actual time=0.034..0.035 rows=1.00 loops=1)
	  Buffers: shared hit=6
	  ->  Index Scan Backward using bm_2syjmtlnanmm_idx_events_stream_type_position on bm_2syjmtlnanmm_events  (cost=0.43..465.23 rows=9 width=335) (actual time=0.033..0.034 rows=1.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = 'StockCounted'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
	        Rows Removed by Filter: 4
	        Index Searches: 1
	        Buffers: shared hit=6
```

### read as issued: query-cursor-walk @ postgres:18/metrics=off (generic plan) — measured 5.54 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND (event_tx, event_position) > ($1::xid8, $2) AND stream_context = $3 AND stream_purpose = $4 ORDER BY event_tx::xid8, event_position  LIMIT $5 OFFSET 0
	Query Parameters: $1 = '772', $2 = '29500', $3 = 'inventory', $4 = 'default', $5 = '500'
	Limit  (cost=0.43..6998.40 rows=3333 width=335) (actual time=0.008..0.163 rows=500.00 loops=1)
	  Buffers: shared hit=26
	  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..69980.06 rows=33330 width=335) (actual time=0.008..0.140 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($3)::text) AND (stream_purpose = ($4)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW(($1)::xid8, $2)))
	        Index Searches: 1
	        Buffers: shared hit=26
```

### read as issued: query-by-id @ postgres:18/metrics=off (generic plan) — measured 0.02 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_id = $1::uuid

	Query Parameters: $1 = '018cc251-f400-74eb-8412-04a7d99e38f3'
	Index Scan using bm_2syjmtlnanmm_events_event_id_key on bm_2syjmtlnanmm_events  (cost=0.43..8.45 rows=1 width=327) (actual time=0.028..0.029 rows=1.00 loops=1)
	  Index Cond: (event_id = ($1)::uuid)
	  Index Searches: 1
	  Buffers: shared hit=4
```

### read as issued: query-wildcard @ postgres:18/metrics=off (generic plan) — measured 0.80 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND event_position > 0 ORDER BY event_tx::xid8, event_position  LIMIT $1 OFFSET 0
	Query Parameters: $1 = '500'
	Limit  (cost=0.43..7741.67 rows=59994 width=335) (actual time=0.027..0.291 rows=500.00 loops=1)
	  Buffers: shared hit=24
	  ->  Index Scan using bm_2syjmtlnanmm_idx_events_global_order on bm_2syjmtlnanmm_events  (cost=0.43..77412.95 rows=599941 width=335) (actual time=0.025..0.260 rows=500.00 loops=1)
	        Index Cond: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Index Searches: 1
	        Buffers: shared hit=24
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| inmem/metrics=off | query-by-entity-cold | thrpt | 1 | 0.046 | ops/ms | 2.3% | 46 | 2,770 | 0 |
| inmem/metrics=off | query-by-entity-hot | thrpt | 1 | 0.035 | ops/ms | 1.7% | 35 | 2,081 | 0 |
| inmem/metrics=off | query-by-id | thrpt | 1 | 1809.270 | ops/ms | 4.2% | 1,809,270 | 108,559,771 | 0 |
| inmem/metrics=off | query-by-multi-tag | thrpt | 1 | 0.136 | ops/ms | 9.4% | 136 | 8,140 | 0 |
| inmem/metrics=off | query-by-or-groups | thrpt | 1 | 1.455 | ops/ms | 2.8% | 1,455 | 87,339 | 0 |
| inmem/metrics=off | query-by-tag-needle | thrpt | 1 | 0.048 | ops/ms | 3.2% | 48 | 2,911 | 0 |
| inmem/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.420 | ops/ms | 8.0% | 420 | 25,181 | 0 |
| inmem/metrics=off | query-by-type | thrpt | 1 | 2.749 | ops/ms | 0.8% | 2,749 | 164,980 | 0 |
| inmem/metrics=off | query-cursor-walk | thrpt | 1 | 0.474 | ops/ms | 8.6% | 474 | 28,439 | 0 |
| inmem/metrics=off | query-last-event | thrpt | 1 | 0.025 | ops/ms | 3.0% | 25 | 1,505 | 0 |
| inmem/metrics=off | query-stream-page | thrpt | 1 | 3.178 | ops/ms | 1.6% | 3,178 | 190,677 | 0 |
| inmem/metrics=off | query-wildcard | thrpt | 1 | 20.883 | ops/ms | 1.3% | 20,883 | 1,253,030 | 0 |
| postgres:18/metrics=off | query-by-entity-cold | thrpt | 1 | 8.299 | ops/ms | 1.9% | 8,299 | 497,960 | 0 |
| postgres:18/metrics=off | query-by-entity-hot | thrpt | 1 | 0.050 | ops/ms | 0.8% | 50 | 2,985 | 0 |
| postgres:18/metrics=off | query-by-id | thrpt | 1 | 47.152 | ops/ms | 1.8% | 47,152 | 2,829,179 | 0 |
| postgres:18/metrics=off | query-by-multi-tag | thrpt | 1 | 0.376 | ops/ms | 2.5% | 376 | 22,565 | 0 |
| postgres:18/metrics=off | query-by-or-groups | thrpt | 1 | 0.066 | ops/ms | 1.0% | 66 | 3,946 | 0 |
| postgres:18/metrics=off | query-by-tag-needle | thrpt | 1 | 3.661 | ops/ms | 2.0% | 3,661 | 219,671 | 0 |
| postgres:18/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.472 | ops/ms | 2.2% | 472 | 28,344 | 0 |
| postgres:18/metrics=off | query-by-type | thrpt | 1 | 0.988 | ops/ms | 3.1% | 988 | 59,302 | 0 |
| postgres:18/metrics=off | query-cursor-walk | thrpt | 1 | 0.181 | ops/ms | 5.9% | 181 | 10,845 | 0 |
| postgres:18/metrics=off | query-last-event | thrpt | 1 | 14.892 | ops/ms | 1.4% | 14,892 | 893,550 | 0 |
| postgres:18/metrics=off | query-stream-page | thrpt | 1 | 0.904 | ops/ms | 4.4% | 904 | 54,268 | 0 |
| postgres:18/metrics=off | query-wildcard | thrpt | 1 | 1.246 | ops/ms | 2.5% | 1,246 | 74,783 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
