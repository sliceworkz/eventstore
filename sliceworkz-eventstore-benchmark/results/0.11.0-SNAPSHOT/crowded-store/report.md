# Benchmark run: crowded-store

read-shapes over a store that also holds five other bounded contexts, in the same table, at five times the volume. This is the composition that actually slows a query down: more rows behind every index, lower selectivity for a tag that appears in several contexts, a bigger heap to correlate.
Compare against read-shapes -- same workloads, same targets, same volume under test -- and the difference is the cost of sharing a table with other domains.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-04T09:34:54.148142374Z |
| finished | 2026-09-04T10:32:28.113654943Z |
| targets | inmem/metrics=off, postgres:18/metrics=off |
| corpus restore | no restore needed: every workload in this run is read-only |

> **Not suitable as a published baseline.**
>
> - measured against a Testcontainers PostgreSQL running stock defaults; publish from an external server whose configuration is deliberate
> - 1 measurement is too noisy to compare against anything, past the 10% this report calls uncomparable: query-by-multi-tag (inmem/metrics=off, 1 thread) at 18%

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
| query-stream-page | 1 | 3.178 ± 0.080 ops/ms | 0.945 ± 0.037 ops/ms (0.30x) |
| query-by-type | 1 | 2.791 ± 0.118 ops/ms | 1.008 ± 0.025 ops/ms (0.36x) |
| query-by-tag-needle | 1 | 0.046 ± 0.001 ops/ms | 3.708 ± 0.074 ops/ms (80.91x) |
| query-by-tag-swathe | 1 | 0.400 ± 0.028 ops/ms | 0.471 ± 0.016 ops/ms (1.18x) |
| query-by-entity-hot | 1 | 0.033 ± 0.001 ops/ms | 0.047 ± 0.001 ops/ms (1.44x) |
| query-by-entity-cold | 1 | 0.044 ± 0.000 ops/ms | 8.128 ± 0.245 ops/ms (186.62x) |
| query-by-multi-tag | 1 | 0.127 ± 0.023 ops/ms | 0.376 ± 0.019 ops/ms (2.97x) |
| query-by-or-groups | 1 | 1.448 ± 0.042 ops/ms | 0.064 ± 0.001 ops/ms (0.04x) |
| query-last-event | 1 | 0.025 ± 0.001 ops/ms | 14.556 ± 0.303 ops/ms (585.47x) |
| query-cursor-walk | 1 | 0.460 ± 0.033 ops/ms | 0.182 ± 0.007 ops/ms (0.40x) |
| query-by-id | 1 | 1981.901 ± 161.603 ops/ms | 47.181 ± 1.065 ops/ms (0.02x) |
| query-wildcard | 1 | 4.705 ± 0.349 ops/ms | 0.013 ± 0.000 ops/ms (0.00x) |

Relative to **inmem/metrics=off**, higher is better. A ratio is only about the setting these targets differ in if it is larger than both error bars and survives running the profile with the targets in the opposite order: the first target is measured against a server the later ones then inherit warm, which is worth a few percent on its own.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

**The reconstructions are the weaker half of this section, including for the reads.** They inline as literals what the store binds as parameters, so a reconstruction can report an execution time the whole measured operation fits inside -- which is not a fast plan but a different one. Read the captured plans further down against the measurements, and these for the shape of the predicate.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.43..776.83 rows=500 width=279) (actual time=0.030..0.179 rows=500.00 loops=1)
  Buffers: shared hit=1 read=26
  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..85458.27 rows=55035 width=279) (actual time=0.029..0.156 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=1 read=26
Planning:
  Buffers: shared hit=100 read=5
Planning Time: 0.464 ms
Execution Time: 0.207 ms
```

### tag needle (~10 matches)

```
Sort  (cost=58.22..58.24 rows=7 width=279) (actual time=0.484..0.485 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=29 read=29
  ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=30.27..58.12 rows=7 width=279) (actual time=0.318..0.466 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=21 read=29
        ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..30.27 rows=7 width=0) (actual time=0.299..0.299 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=20 read=20
Planning:
  Buffers: shared hit=12
Planning Time: 0.101 ms
Execution Time: 0.537 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=421.35..421.60 rows=101 width=279) (actual time=4.019..4.087 rows=500.00 loops=1)
  Buffers: shared hit=48 read=992
  ->  Sort  (cost=421.35..421.60 rows=101 width=279) (actual time=4.017..4.043 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 442kB
        Buffers: shared hit=48 read=992
        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=30.76..417.99 rows=101 width=279) (actual time=0.529..3.611 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=1000
              Buffers: shared hit=48 read=992
              ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..30.74 rows=101 width=0) (actual time=0.461..0.461 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=39 read=1
Planning:
  Buffers: shared hit=3
Planning Time: 0.094 ms
Execution Time: 4.171 ms
```

### one entity's whole history (hot)

```
Sort  (cost=10358.90..10368.36 rows=3783 width=279) (actual time=6.649..6.817 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1910kB
  Buffers: shared hit=1021 read=1011
  ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=54.22..10134.09 rows=3783 width=279) (actual time=1.397..5.188 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=1993
        Buffers: shared hit=1021 read=1011
        ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..53.27 rows=3783 width=0) (actual time=1.254..1.255 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=34 read=5
Planning:
  Buffers: shared hit=3
Planning Time: 0.131 ms
Execution Time: 7.153 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.43..22.99 rows=1 width=279) (actual time=0.038..0.038 rows=1.00 loops=1)
  Buffers: shared hit=2 read=3
  ->  Index Scan Backward using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..85339.59 rows=3783 width=279) (actual time=0.037..0.037 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 20
        Index Searches: 1
        Buffers: shared hit=2 read=3
Planning:
  Buffers: shared hit=11
Planning Time: 0.158 ms
Execution Time: 0.050 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.43..812.63 rows=500 width=279) (actual time=0.024..0.168 rows=500.00 loops=1)
  Buffers: shared hit=22 read=5
  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..85408.64 rows=52578 width=279) (actual time=0.023..0.145 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('771'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=22 read=5
Planning:
  Buffers: shared hit=14
Planning Time: 0.137 ms
Execution Time: 0.202 ms
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

### read as issued: query-stream-page @ postgres:18/metrics=off (generic plan) — measured 1.06 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 ORDER BY event_tx::xid8, event_position  LIMIT $3 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = '500'
	Limit  (cost=0.43..8878.49 rows=9999 width=367) (actual time=0.051..0.307 rows=500.00 loops=1)
	  Buffers: shared hit=27
	  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..88780.99 rows=99990 width=367) (actual time=0.048..0.259 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=27
```

### read as issued: query-by-type @ postgres:18/metrics=off (generic plan) — measured 0.99 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3))) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = '500'
	Limit  (cost=0.43..1413.67 rows=435 width=367) (actual time=0.075..0.500 rows=500.00 loops=1)
	  Buffers: shared hit=48
	  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_type_position on bm_2syjmtlnanmm_events  (cost=0.43..14123.11 rows=4347 width=367) (actual time=0.071..0.443 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=48
```

### read as issued: query-by-tag-needle @ postgres:18/metrics=off (generic plan) — measured 0.27 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:needle'
	Sort  (cost=1835.37..1836.62 rows=500 width=367) (actual time=0.347..0.348 rows=10.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 28kB
	  Buffers: shared hit=50
	  ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=32.86..1812.96 rows=500 width=367) (actual time=0.323..0.334 rows=10.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=10
	        Buffers: shared hit=50
	        ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..32.73 rows=500 width=0) (actual time=0.302..0.302 rows=10.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=40
```

### read as issued: query-by-tag-swathe @ postgres:18/metrics=off (generic plan) — measured 2.12 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:swathe', $4 = '500'
	Limit  (cost=1835.37..1835.50 rows=50 width=367) (actual time=2.814..2.871 rows=500.00 loops=1)
	  Buffers: shared hit=1040
	  ->  Sort  (cost=1835.37..1836.62 rows=500 width=367) (actual time=2.810..2.835 rows=500.00 loops=1)
	        Sort Key: event_tx, event_position
	        Sort Method: quicksort  Memory: 532kB
	        Buffers: shared hit=1040
	        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=32.86..1812.96 rows=500 width=367) (actual time=0.927..2.273 rows=1000.00 loops=1)
	              Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=1000
	              Buffers: shared hit=1040
	              ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..32.73 rows=500 width=0) (actual time=0.803..0.804 rows=1000.00 loops=1)
	                    Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                    Index Searches: 1
	                    Buffers: shared hit=40
```

### read as issued: query-by-entity-hot @ postgres:18/metrics=off (generic plan) — measured 21.31 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-000000'
	Sort  (cost=1835.37..1836.62 rows=500 width=367) (actual time=6.782..6.963 rows=6876.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 2176kB
	  Buffers: shared hit=2032
	  ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=32.86..1812.96 rows=500 width=367) (actual time=2.039..4.672 rows=6876.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=1993
	        Buffers: shared hit=2032
	        ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..32.73 rows=500 width=0) (actual time=1.712..1.713 rows=6876.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=39
```

### read as issued: query-by-entity-cold @ postgres:18/metrics=off (generic plan) — measured 0.12 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-001729'
	Sort  (cost=1835.37..1836.62 rows=500 width=367) (actual time=0.137..0.137 rows=1.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 25kB
	  Buffers: shared hit=21
	  ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=32.86..1812.96 rows=500 width=367) (actual time=0.126..0.127 rows=1.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=1
	        Buffers: shared hit=21
	        ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..32.73 rows=500 width=0) (actual time=0.108..0.108 rows=1.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=20
```

### read as issued: query-by-multi-tag @ postgres:18/metrics=off (generic plan) — measured 2.66 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3, $4, $5]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $6 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-000000', $4 = 'country:BE', $5 = 'channel:web', $6 = '500'
	Limit  (cost=1835.37..1835.50 rows=50 width=367) (actual time=1.961..2.002 rows=500.00 loops=1)
	  Buffers: shared hit=535
	  ->  Sort  (cost=1835.37..1836.62 rows=500 width=367) (actual time=1.957..1.973 rows=500.00 loops=1)
	        Sort Key: event_tx, event_position
	        Sort Method: quicksort  Memory: 292kB
	        Buffers: shared hit=535
	        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=32.86..1812.96 rows=500 width=367) (actual time=1.441..1.844 rows=526.00 loops=1)
	              Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=471
	              Buffers: shared hit=535
	              ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..32.73 rows=500 width=0) (actual time=1.394..1.395 rows=526.00 loops=1)
	                    Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	                    Index Searches: 1
	                    Buffers: shared hit=64
```

### read as issued: query-by-or-groups @ postgres:18/metrics=off (generic plan) — measured 15.66 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=4244.29..4244.35 rows=22 width=367) (actual time=15.491..15.529 rows=500.00 loops=1)
	  Buffers: shared hit=4144
	  ->  Sort  (cost=4244.29..4244.84 rows=217 width=367) (actual time=15.487..15.503 rows=500.00 loops=1)
	        Sort Key: event_tx, event_position
	        Sort Method: top-N heapsort  Memory: 278kB
	        Buffers: shared hit=4144
	        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=3418.86..4235.87 rows=217 width=367) (actual time=10.474..13.584 rows=11122.00 loops=1)
	              Recheck Cond: (((event_tags @> ARRAY[($5)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($3)::text, ($4)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot()))) OR ((event_tags @> ARRAY[($8)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($6)::text, ($7)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot()))) OR ((event_tags @> ARRAY[($11)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($9)::text, ($10)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot()))) OR ((event_tags @> ARRAY[($14)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot()))) OR ((event_tags @> ARRAY[($17)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($15)::text, ($16)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot()))))
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=2050
	              Buffers: shared hit=4144
	              ->  BitmapOr  (cost=3418.86..3418.86 rows=217 width=0) (actual time=10.314..10.317 rows=0.00 loops=1)
	                    Buffers: shared hit=2094
	                    ->  BitmapAnd  (cost=683.57..683.57 rows=43 width=0) (actual time=3.715..3.715 rows=0.00 loops=1)
	                          Buffers: shared hit=423
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_tags  (cost=0.00..31.95 rows=3000 width=0) (actual time=2.523..2.523 rows=40227.00 loops=1)
	                                Index Cond: (event_tags @> ARRAY[($5)::text])
	                                Index Searches: 1
	                                Buffers: shared hit=14
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..651.26 rows=8695 width=0) (actual time=0.938..0.938 rows=39331.00 loops=1)
	                                Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($3)::text, ($4)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                                Index Searches: 2
	                                Buffers: shared hit=409
	                    ->  BitmapAnd  (cost=683.57..683.57 rows=43 width=0) (actual time=2.134..2.134 rows=0.00 loops=1)
	                          Buffers: shared hit=419
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_tags  (cost=0.00..31.95 rows=3000 width=0) (actual time=1.127..1.128 rows=20300.00 loops=1)
	                                Index Cond: (event_tags @> ARRAY[($8)::text])
	                                Index Searches: 1
	                                Buffers: shared hit=10
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..651.26 rows=8695 width=0) (actual time=0.795..0.795 rows=39331.00 loops=1)
	                                Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($6)::text, ($7)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                                Index Searches: 2
	                                Buffers: shared hit=409
	                    ->  BitmapAnd  (cost=683.57..683.57 rows=43 width=0) (actual time=1.654..1.655 rows=0.00 loops=1)
	                          Buffers: shared hit=418
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_tags  (cost=0.00..31.95 rows=3000 width=0) (actual time=0.744..0.744 rows=13494.00 loops=1)
	                                Index Cond: (event_tags @> ARRAY[($11)::text])
	                                Index Searches: 1
	                                Buffers: shared hit=9
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..651.26 rows=8695 width=0) (actual time=0.732..0.732 rows=39331.00 loops=1)
	                                Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($9)::text, ($10)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                                Index Searches: 2
	                                Buffers: shared hit=409
	                    ->  BitmapAnd  (cost=683.57..683.57 rows=43 width=0) (actual time=1.429..1.429 rows=0.00 loops=1)
	                          Buffers: shared hit=417
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_tags  (cost=0.00..31.95 rows=3000 width=0) (actual time=0.528..0.528 rows=10185.00 loops=1)
	                                Index Cond: (event_tags @> ARRAY[($14)::text])
	                                Index Searches: 1
	                                Buffers: shared hit=8
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..651.26 rows=8695 width=0) (actual time=0.739..0.739 rows=39331.00 loops=1)
	                                Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                                Index Searches: 2
	                                Buffers: shared hit=409
	                    ->  BitmapAnd  (cost=683.57..683.57 rows=43 width=0) (actual time=1.277..1.277 rows=0.00 loops=1)
	                          Buffers: shared hit=417
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_tags  (cost=0.00..31.95 rows=3000 width=0) (actual time=0.438..0.438 rows=8202.00 loops=1)
	                                Index Cond: (event_tags @> ARRAY[($17)::text])
	                                Index Searches: 1
	                                Buffers: shared hit=8
	                          ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..651.26 rows=8695 width=0) (actual time=0.710..0.710 rows=39331.00 loops=1)
	                                Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ANY (ARRAY[($15)::text, ($16)::text])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                                Index Searches: 2
	                                Buffers: shared hit=409
```

### read as issued: query-by-or-groups @ postgres:18/metrics=off (custom plan, first executions only) — measured 15.66 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=9954.35..9955.60 rows=500 width=367) (actual time=21.022..21.058 rows=500.00 loops=1)
	  Buffers: shared hit=2461
	  ->  Sort  (cost=9954.35..9955.67 rows=527 width=367) (actual time=21.021..21.035 rows=500.00 loops=1)
	        Sort Key: event_tx, event_position
	        Sort Method: top-N heapsort  Memory: 278kB
	        Buffers: shared hit=2461
	        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=270.36..9930.53 rows=527 width=367) (actual time=1.073..19.021 rows=11122.00 loops=1)
	              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000001}'::text[]) OR (event_tags @> '{sku:SKU-000002}'::text[]) OR (event_tags @> '{sku:SKU-000003}'::text[]) OR (event_tags @> '{sku:SKU-000004}'::text[]))
	              Rows Removed by Filter: 28209
	              Heap Blocks: exact=2052
	              Buffers: shared hit=2461
	              ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..270.23 rows=3559 width=0) (actual time=0.924..0.924 rows=39331.00 loops=1)
	                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                    Index Searches: 2
	                    Buffers: shared hit=409
```

### read as issued: query-last-event @ postgres:18/metrics=off (generic plan) — measured 0.07 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=445.04..445.04 rows=2 width=367) (actual time=4.222..4.223 rows=1.00 loops=1)
	  Buffers: shared hit=238
	  ->  Sort  (cost=445.04..445.09 rows=22 width=367) (actual time=4.219..4.220 rows=1.00 loops=1)
	        Sort Key: event_tx DESC, event_position DESC
	        Sort Method: top-N heapsort  Memory: 26kB
	        Buffers: shared hit=238
	        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=357.84..444.54 rows=22 width=367) (actual time=3.918..4.135 rows=208.00 loops=1)
	              Recheck Cond: ((event_tags @> ARRAY[($4)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Heap Blocks: exact=197
	              Buffers: shared hit=238
	              ->  BitmapAnd  (cost=357.84..357.84 rows=22 width=0) (actual time=3.857..3.858 rows=0.00 loops=1)
	                    Buffers: shared hit=41
	                    ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_tags  (cost=0.00..31.95 rows=3000 width=0) (actual time=3.188..3.188 rows=40227.00 loops=1)
	                          Index Cond: (event_tags @> ARRAY[($4)::text])
	                          Index Searches: 1
	                          Buffers: shared hit=14
	                    ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_type_position  (cost=0.00..325.63 rows=4347 width=0) (actual time=0.183..0.183 rows=1660.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                          Index Searches: 1
	                          Buffers: shared hit=27
```

### read as issued: query-last-event @ postgres:18/metrics=off (custom plan, first executions only) — measured 0.07 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=0.43..51.39 rows=1 width=367) (actual time=0.030..0.030 rows=1.00 loops=1)
	  Buffers: shared hit=6
	  ->  Index Scan Backward using bm_2syjmtlnanmm_idx_events_stream_type_position on bm_2syjmtlnanmm_events  (cost=0.43..510.06 rows=10 width=367) (actual time=0.029..0.029 rows=1.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = 'StockCounted'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
	        Rows Removed by Filter: 4
	        Index Searches: 1
	        Buffers: shared hit=6
```

### read as issued: query-cursor-walk @ postgres:18/metrics=off (generic plan) — measured 5.48 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND (event_tx, event_position) > ($1::xid8, $2) AND stream_context = $3 AND stream_purpose = $4 ORDER BY event_tx::xid8, event_position  LIMIT $5 OFFSET 0
	Query Parameters: $1 = '772', $2 = '29500', $3 = 'inventory', $4 = 'default', $5 = '500'
	Limit  (cost=0.43..6986.18 rows=3333 width=367) (actual time=0.006..0.192 rows=500.00 loops=1)
	  Buffers: shared hit=26
	  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..69857.92 rows=33330 width=367) (actual time=0.006..0.168 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($3)::text) AND (stream_purpose = ($4)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW(($1)::xid8, $2)))
	        Index Searches: 1
	        Buffers: shared hit=26
```

### read as issued: query-by-id @ postgres:18/metrics=off (generic plan) — measured 0.02 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_id = $1::uuid

	Query Parameters: $1 = '018cc251-f400-74eb-8412-04a7d99e38f3'
	Index Scan using bm_2syjmtlnanmm_events_event_id_key on bm_2syjmtlnanmm_events  (cost=0.43..8.45 rows=1 width=359) (actual time=0.038..0.040 rows=1.00 loops=1)
	  Index Cond: (event_id = ($1)::uuid)
	  Index Searches: 1
	  Buffers: shared hit=4
```

### read as issued: query-wildcard @ postgres:18/metrics=off (generic plan) — measured 77.56 ms/op — **sequential scan**, **JIT 8ms**

> no index served this, so it read the table from the beginning.
> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 ORDER BY event_tx::xid8, event_position  LIMIT $1 OFFSET 0
	Query Parameters: $1 = '500'
	Limit  (cost=136407.94..143395.23 rows=59994 width=367) (actual time=84.136..89.040 rows=500.00 loops=1)
	  Buffers: shared hit=15399 read=8332
	  ->  Gather Merge  (cost=136407.94..206280.84 rows=599940 width=367) (actual time=82.053..86.932 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=15399 read=8332
	        ->  Sort  (cost=135407.91..136032.85 rows=249975 width=367) (actual time=71.616..71.625 rows=311.33 loops=3)
	              Sort Key: event_tx, event_position
	              Sort Method: top-N heapsort  Memory: 278kB
	              Buffers: shared hit=15399 read=8332
	              Worker 0:  Sort Method: top-N heapsort  Memory: 287kB
	              Worker 1:  Sort Method: top-N heapsort  Memory: 288kB
	              ->  Parallel Seq Scan on bm_2syjmtlnanmm_events  (cost=0.00..29261.88 rows=249975 width=367) (actual time=1.564..38.158 rows=200000.00 loops=3)
	                    Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	                    Buffers: shared hit=15305 read=8332
	JIT:
	  Functions: 14
	  Options: Inlining false, Optimization false, Expressions true, Deforming true
	  Timing: Generation 0.906 ms (Deform 0.348 ms), Inlining 0.000 ms, Optimization 0.468 ms, Emission 6.270 ms, Total 7.644 ms
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| inmem/metrics=off | query-by-entity-cold | thrpt | 1 | 0.044 | ops/ms | 1.1% | 44 | 2,619 | 0 |
| inmem/metrics=off | query-by-entity-hot | thrpt | 1 | 0.033 | ops/ms | 2.5% | 33 | 1,963 | 0 |
| inmem/metrics=off | query-by-id | thrpt | 1 | 1981.901 | ops/ms | 8.2% | 1,981,901 | 118,917,520 | 0 |
| inmem/metrics=off | query-by-multi-tag | thrpt | 1 | 0.127 | ops/ms | 18.1% | 127 | 7,600 | 0 |
| inmem/metrics=off | query-by-or-groups | thrpt | 1 | 1.448 | ops/ms | 2.9% | 1,448 | 86,865 | 0 |
| inmem/metrics=off | query-by-tag-needle | thrpt | 1 | 0.046 | ops/ms | 2.5% | 46 | 2,756 | 0 |
| inmem/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.400 | ops/ms | 7.0% | 400 | 23,989 | 0 |
| inmem/metrics=off | query-by-type | thrpt | 1 | 2.791 | ops/ms | 4.2% | 2,791 | 167,495 | 0 |
| inmem/metrics=off | query-cursor-walk | thrpt | 1 | 0.460 | ops/ms | 7.1% | 460 | 27,614 | 0 |
| inmem/metrics=off | query-last-event | thrpt | 1 | 0.025 | ops/ms | 2.8% | 25 | 1,498 | 0 |
| inmem/metrics=off | query-stream-page | thrpt | 1 | 3.178 | ops/ms | 2.5% | 3,178 | 190,709 | 0 |
| inmem/metrics=off | query-wildcard | thrpt | 1 | 4.705 | ops/ms | 7.4% | 4,705 | 282,329 | 0 |
| postgres:18/metrics=off | query-by-entity-cold | thrpt | 1 | 8.128 | ops/ms | 3.0% | 8,128 | 487,695 | 0 |
| postgres:18/metrics=off | query-by-entity-hot | thrpt | 1 | 0.047 | ops/ms | 2.3% | 47 | 2,822 | 0 |
| postgres:18/metrics=off | query-by-id | thrpt | 1 | 47.181 | ops/ms | 2.3% | 47,181 | 2,830,955 | 0 |
| postgres:18/metrics=off | query-by-multi-tag | thrpt | 1 | 0.376 | ops/ms | 5.0% | 376 | 22,576 | 0 |
| postgres:18/metrics=off | query-by-or-groups | thrpt | 1 | 0.064 | ops/ms | 1.9% | 64 | 3,836 | 0 |
| postgres:18/metrics=off | query-by-tag-needle | thrpt | 1 | 3.708 | ops/ms | 2.0% | 3,708 | 222,510 | 0 |
| postgres:18/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.471 | ops/ms | 3.5% | 471 | 28,282 | 0 |
| postgres:18/metrics=off | query-by-type | thrpt | 1 | 1.008 | ops/ms | 2.5% | 1,008 | 60,491 | 0 |
| postgres:18/metrics=off | query-cursor-walk | thrpt | 1 | 0.182 | ops/ms | 3.6% | 182 | 10,954 | 0 |
| postgres:18/metrics=off | query-last-event | thrpt | 1 | 14.556 | ops/ms | 2.1% | 14,556 | 873,356 | 0 |
| postgres:18/metrics=off | query-stream-page | thrpt | 1 | 0.945 | ops/ms | 3.9% | 945 | 56,725 | 0 |
| postgres:18/metrics=off | query-wildcard | thrpt | 1 | 0.013 | ops/ms | 0.7% | 13 | 780 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
