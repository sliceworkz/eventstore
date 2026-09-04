# Benchmark run: crowded-database

read-shapes over a store sharing its database with three million-event stores under other table prefixes. A different mechanism from crowded-store and reported separately for exactly that reason: other tables add no row to any query this store issues, so anything that moves here moved through shared buffers, WAL, autovacuum or the cluster-wide notification queue.
Expect a smaller effect than crowded-store, and treat a large one as a finding rather than as confirmation -- it would mean the store is losing to its neighbours somewhere it does not read.
What this measures is coexistence, not contention: the neighbours are written once while the corpus is provisioned and are then idle for the whole run, so none of the four mechanisms named above is actually exercised by them. A null result here says a store does not pay for large neighbours sitting next to it; it says nothing about a neighbour under load, and in particular nothing about the pg_snapshot_xmin stall a long-running writing transaction anywhere in the cluster causes.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-04T10:32:28.867808405Z |
| finished | 2026-09-04T11:11:48.314513947Z |
| targets | inmem/metrics=off, postgres:18/metrics=off |
| corpus restore | no restore needed: every workload in this run is read-only |

> **Not suitable as a published baseline.**
>
> - measured against a Testcontainers PostgreSQL running stock defaults; publish from an external server whose configuration is deliberate
> - 2 measurements are too noisy to compare against anything, past the 10% this report calls uncomparable: query-by-multi-tag (inmem/metrics=off, 1 thread) at 13%, query-cursor-walk (inmem/metrics=off, 1 thread) at 13%

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
| fingerprint | `bm_3icbu1uum6b8_` |
| volume | 100,000 events under test |
| stream design | TAGGED |
| composition | MULTI_STORE |
| payload | REALISTIC |
| entities | 2,000 |
| neighbour stores | 1,000,000 events, 1,000,000 events, 1,000,000 events |
| hot entity | `SKU-000000`, 6,876 events |
| cold entity | `SKU-001729`, 1 events |
| needle tag | 10 matches |
| swathe tag | 1,000 matches |
| mean payload | 141 bytes (sales) |

## What this run says

### The targets side by side

| workload | threads | inmem/metrics=off | postgres:18/metrics=off |
|---|---|---|---|
| query-stream-page | 1 | 3.438 ± 0.163 ops/ms | 0.950 ± 0.037 ops/ms (0.28x) |
| query-by-type | 1 | 2.747 ± 0.020 ops/ms | 0.992 ± 0.024 ops/ms (0.36x) |
| query-by-tag-needle | 1 | 0.135 ± 0.003 ops/ms | 3.673 ± 0.056 ops/ms (27.24x) |
| query-by-tag-swathe | 1 | 0.404 ± 0.012 ops/ms | 0.492 ± 0.015 ops/ms (1.22x) |
| query-by-entity-hot | 1 | 0.065 ± 0.001 ops/ms | 0.047 ± 0.001 ops/ms (0.71x) |
| query-by-entity-cold | 1 | 0.131 ± 0.004 ops/ms | 8.668 ± 0.221 ops/ms (66.37x) |
| query-by-multi-tag | 1 | 0.145 ± 0.018 ops/ms | 0.391 ± 0.017 ops/ms (2.69x) |
| query-by-or-groups | 1 | 1.476 ± 0.027 ops/ms | 0.378 ± 0.014 ops/ms (0.26x) |
| query-last-event | 1 | 1.587 ± 0.011 ops/ms | 14.964 ± 0.379 ops/ms (9.43x) |
| query-cursor-walk | 1 | 0.480 ± 0.063 ops/ms | 0.183 ± 0.005 ops/ms (0.38x) |
| query-by-id | 1 | 1956.922 ± 88.429 ops/ms | 47.769 ± 1.049 ops/ms (0.02x) |
| query-wildcard | 1 | 4.784 ± 0.317 ops/ms | 0.053 ± 0.001 ops/ms (0.01x) |

Relative to **inmem/metrics=off**, higher is better. A ratio is only about the setting these targets differ in if it is larger than both error bars and survives running the profile with the targets in the opposite order: the first target is measured against a server the later ones then inherit warm, which is worth a few percent on its own.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

**The reconstructions are the weaker half of this section, including for the reads.** They inline as literals what the store binds as parameters, so a reconstruction can report an execution time the whole measured operation fits inside -- which is not a fast plan but a different one. Read the captured plans further down against the measurements, and these for the shape of the predicate.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.42..109.05 rows=500 width=313) (actual time=0.025..0.151 rows=500.00 loops=1)
  Buffers: shared hit=26
  ->  Index Scan using bm_3icbu1uum6b8_idx_events_stream_position on bm_3icbu1uum6b8_events  (cost=0.42..11900.24 rows=54773 width=313) (actual time=0.024..0.128 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=26
Planning:
  Buffers: shared hit=105
Planning Time: 0.474 ms
Execution Time: 0.177 ms
```

### tag needle (~10 matches)

```
Sort  (cost=57.71..57.72 rows=7 width=313) (actual time=0.262..0.263 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=55
  ->  Bitmap Heap Scan on bm_3icbu1uum6b8_events  (cost=30.24..57.61 rows=7 width=313) (actual time=0.240..0.250 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=47
        ->  Bitmap Index Scan on bm_3icbu1uum6b8_idx_events_stream_tags  (cost=0.00..30.24 rows=7 width=0) (actual time=0.232..0.232 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=37
Planning:
  Buffers: shared hit=12
Planning Time: 0.112 ms
Execution Time: 0.301 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=1578.80..1580.05 rows=500 width=313) (actual time=1.578..1.612 rows=500.00 loops=1)
  Buffers: shared hit=1037
  ->  Sort  (cost=1578.80..1580.14 rows=537 width=313) (actual time=1.577..1.589 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 524kB
        Buffers: shared hit=1037
        ->  Bitmap Heap Scan on bm_3icbu1uum6b8_events  (cost=33.02..1554.45 rows=537 width=313) (actual time=0.529..1.358 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=1000
              Buffers: shared hit=1037
              ->  Bitmap Index Scan on bm_3icbu1uum6b8_idx_events_stream_tags  (cost=0.00..32.89 rows=537 width=0) (actual time=0.459..0.459 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=37
Planning:
  Buffers: shared hit=3
Planning Time: 0.075 ms
Execution Time: 1.670 ms
```

### one entity's whole history (hot)

```
Sort  (cost=4787.53..4797.13 rows=3840 width=313) (actual time=5.442..5.610 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1925kB
  Buffers: shared hit=2029
  ->  Bitmap Heap Scan on bm_3icbu1uum6b8_events  (cost=54.49..4558.92 rows=3840 width=313) (actual time=1.411..4.012 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=1993
        Buffers: shared hit=2029
        ->  Bitmap Index Scan on bm_3icbu1uum6b8_idx_events_stream_tags  (cost=0.00..53.53 rows=3840 width=0) (actual time=1.261..1.262 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=36
Planning:
  Buffers: shared hit=3
Planning Time: 0.105 ms
Execution Time: 5.928 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.42..3.49 rows=1 width=313) (actual time=0.042..0.043 rows=1.00 loops=1)
  Buffers: shared hit=3 read=2
  ->  Index Scan Backward using bm_3icbu1uum6b8_idx_events_stream_position on bm_3icbu1uum6b8_events  (cost=0.42..11782.51 rows=3840 width=313) (actual time=0.042..0.042 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 20
        Index Searches: 1
        Buffers: shared hit=3 read=2
Planning:
  Buffers: shared hit=11
Planning Time: 0.224 ms
Execution Time: 0.054 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.42..137.48 rows=500 width=313) (actual time=0.013..0.141 rows=500.00 loops=1)
  Buffers: shared hit=27
  ->  Index Scan using bm_3icbu1uum6b8_idx_events_stream_position on bm_3icbu1uum6b8_events  (cost=0.42..10786.71 rows=39349 width=313) (actual time=0.013..0.118 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('2274'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=27
Planning:
  Buffers: shared hit=14
Planning Time: 0.073 ms
Execution Time: 0.168 ms
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

### read as issued: query-stream-page @ postgres:18/metrics=off (generic plan) — measured 1.05 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 ORDER BY event_tx::xid8, event_position  LIMIT $3 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = '500'
	Limit  (cost=0.42..1152.83 rows=5000 width=401) (actual time=0.072..0.274 rows=500.00 loops=1)
	  Buffers: shared hit=26
	  ->  Index Scan using bm_3icbu1uum6b8_idx_events_stream_position on bm_3icbu1uum6b8_events  (cost=0.42..11524.55 rows=50000 width=401) (actual time=0.068..0.237 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=26
```

### read as issued: query-by-type @ postgres:18/metrics=off (generic plan) — measured 1.01 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3))) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = '500'
	Limit  (cost=0.42..569.33 rows=454 width=401) (actual time=0.055..0.345 rows=500.00 loops=1)
	  Buffers: shared hit=48
	  ->  Index Scan using bm_3icbu1uum6b8_idx_events_stream_type_position on bm_3icbu1uum6b8_events  (cost=0.42..5695.77 rows=4545 width=401) (actual time=0.051..0.296 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=48
```

### read as issued: query-by-tag-needle @ postgres:18/metrics=off (generic plan) — measured 0.27 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:needle'
	Sort  (cost=848.35..848.98 rows=250 width=401) (actual time=0.484..0.486 rows=10.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 28kB
	  Buffers: shared hit=47
	  ->  Bitmap Heap Scan on bm_3icbu1uum6b8_events  (cost=31.52..838.40 rows=250 width=401) (actual time=0.456..0.469 rows=10.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=10
	        Buffers: shared hit=47
	        ->  Bitmap Index Scan on bm_3icbu1uum6b8_idx_events_stream_tags  (cost=0.00..31.45 rows=250 width=0) (actual time=0.434..0.434 rows=10.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=37
```

### read as issued: query-by-tag-swathe @ postgres:18/metrics=off (generic plan) — measured 2.03 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:swathe', $4 = '500'
	Limit  (cost=848.35..848.42 rows=25 width=401) (actual time=2.582..2.661 rows=500.00 loops=1)
	  Buffers: shared hit=1037
	  ->  Sort  (cost=848.35..848.98 rows=250 width=401) (actual time=2.578..2.613 rows=500.00 loops=1)
	        Sort Key: event_tx, event_position
	        Sort Method: quicksort  Memory: 532kB
	        Buffers: shared hit=1037
	        ->  Bitmap Heap Scan on bm_3icbu1uum6b8_events  (cost=31.52..838.40 rows=250 width=401) (actual time=1.048..2.096 rows=1000.00 loops=1)
	              Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=1000
	              Buffers: shared hit=1037
	              ->  Bitmap Index Scan on bm_3icbu1uum6b8_idx_events_stream_tags  (cost=0.00..31.45 rows=250 width=0) (actual time=0.900..0.900 rows=1000.00 loops=1)
	                    Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                    Index Searches: 1
	                    Buffers: shared hit=37
```

### read as issued: query-by-entity-hot @ postgres:18/metrics=off (generic plan) — measured 21.44 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-000000'
	Sort  (cost=848.35..848.98 rows=250 width=401) (actual time=6.226..6.399 rows=6876.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 2176kB
	  Buffers: shared hit=2029
	  ->  Bitmap Heap Scan on bm_3icbu1uum6b8_events  (cost=31.52..838.40 rows=250 width=401) (actual time=1.974..4.402 rows=6876.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=1993
	        Buffers: shared hit=2029
	        ->  Bitmap Index Scan on bm_3icbu1uum6b8_idx_events_stream_tags  (cost=0.00..31.45 rows=250 width=0) (actual time=1.824..1.824 rows=6876.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=36
```

### read as issued: query-by-entity-cold @ postgres:18/metrics=off (generic plan) — measured 0.12 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-001729'
	Sort  (cost=848.35..848.98 rows=250 width=401) (actual time=0.133..0.134 rows=1.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 25kB
	  Buffers: shared hit=16
	  ->  Bitmap Heap Scan on bm_3icbu1uum6b8_events  (cost=31.52..838.40 rows=250 width=401) (actual time=0.124..0.125 rows=1.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=1
	        Buffers: shared hit=16
	        ->  Bitmap Index Scan on bm_3icbu1uum6b8_idx_events_stream_tags  (cost=0.00..31.45 rows=250 width=0) (actual time=0.106..0.106 rows=1.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=15
```

### read as issued: query-by-multi-tag @ postgres:18/metrics=off (generic plan) — measured 2.56 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3, $4, $5]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $6 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'channel:web', $4 = 'country:BE', $5 = 'sku:SKU-000000', $6 = '500'
	Limit  (cost=848.35..848.42 rows=25 width=401) (actual time=1.930..1.970 rows=500.00 loops=1)
	  Buffers: shared hit=526
	  ->  Sort  (cost=848.35..848.98 rows=250 width=401) (actual time=1.927..1.943 rows=500.00 loops=1)
	        Sort Key: event_tx, event_position
	        Sort Method: quicksort  Memory: 292kB
	        Buffers: shared hit=526
	        ->  Bitmap Heap Scan on bm_3icbu1uum6b8_events  (cost=31.52..838.40 rows=250 width=401) (actual time=1.376..1.808 rows=526.00 loops=1)
	              Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=471
	              Buffers: shared hit=526
	              ->  Bitmap Index Scan on bm_3icbu1uum6b8_idx_events_stream_tags  (cost=0.00..31.45 rows=250 width=0) (actual time=1.330..1.330 rows=526.00 loops=1)
	                    Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	                    Index Searches: 1
	                    Buffers: shared hit=55
```

### read as issued: query-by-or-groups @ postgres:18/metrics=off (generic plan) — measured 2.64 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=0.42..1269.50 rows=23 width=401) (actual time=0.069..1.990 rows=500.00 loops=1)
	  Buffers: shared hit=126
	  ->  Index Scan using bm_3icbu1uum6b8_idx_events_stream_position on bm_3icbu1uum6b8_events  (cost=0.42..12525.68 rows=227 width=401) (actual time=0.066..1.961 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (((event_type = ANY (ARRAY[($3)::text, ($4)::text])) AND (event_tags @> ARRAY[($5)::text])) OR ((event_type = ANY (ARRAY[($6)::text, ($7)::text])) AND (event_tags @> ARRAY[($8)::text])) OR ((event_type = ANY (ARRAY[($9)::text, ($10)::text])) AND (event_tags @> ARRAY[($11)::text])) OR ((event_type = ANY (ARRAY[($12)::text, ($13)::text])) AND (event_tags @> ARRAY[($14)::text])) OR ((event_type = ANY (ARRAY[($15)::text, ($16)::text])) AND (event_tags @> ARRAY[($17)::text])))
	        Rows Removed by Filter: 2196
	        Index Searches: 1
	        Buffers: shared hit=126
```

### read as issued: query-last-event @ postgres:18/metrics=off (generic plan) — measured 0.07 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=400.41..400.42 rows=2 width=401) (actual time=0.631..0.631 rows=1.00 loops=1)
	  Buffers: shared hit=230
	  ->  Sort  (cost=400.41..400.47 rows=23 width=401) (actual time=0.629..0.629 rows=1.00 loops=1)
	        Sort Key: event_tx DESC, event_position DESC
	        Sort Method: top-N heapsort  Memory: 26kB
	        Buffers: shared hit=230
	        ->  Bitmap Heap Scan on bm_3icbu1uum6b8_events  (cost=312.17..399.89 rows=23 width=401) (actual time=0.441..0.583 rows=208.00 loops=1)
	              Recheck Cond: ((event_tags @> ARRAY[($4)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Heap Blocks: exact=197
	              Buffers: shared hit=230
	              ->  BitmapAnd  (cost=312.17..312.17 rows=23 width=0) (actual time=0.424..0.424 rows=0.00 loops=1)
	                    Buffers: shared hit=33
	                    ->  Bitmap Index Scan on bm_3icbu1uum6b8_idx_events_tags  (cost=0.00..15.31 rows=500 width=0) (actual time=0.300..0.300 rows=6876.00 loops=1)
	                          Index Cond: (event_tags @> ARRAY[($4)::text])
	                          Index Searches: 1
	                          Buffers: shared hit=6
	                    ->  Bitmap Index Scan on bm_3icbu1uum6b8_idx_events_stream_type_position  (cost=0.00..296.60 rows=4545 width=0) (actual time=0.086..0.086 rows=1660.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                          Index Searches: 1
	                          Buffers: shared hit=27
```

### read as issued: query-last-event @ postgres:18/metrics=off (custom plan, first executions only) — measured 0.07 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=0.42..24.64 rows=1 width=401) (actual time=0.038..0.039 rows=1.00 loops=1)
	  Buffers: shared hit=6
	  ->  Index Scan Backward using bm_3icbu1uum6b8_idx_events_stream_type_position on bm_3icbu1uum6b8_events  (cost=0.42..1550.65 rows=64 width=401) (actual time=0.037..0.037 rows=1.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = 'StockCounted'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
	        Rows Removed by Filter: 4
	        Index Searches: 1
	        Buffers: shared hit=6
```

### read as issued: query-cursor-walk @ postgres:18/metrics=off (generic plan) — measured 5.47 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND (event_tx, event_position) > ($1::xid8, $2) AND stream_context = $3 AND stream_purpose = $4 ORDER BY event_tx::xid8, event_position  LIMIT $5 OFFSET 0
	Query Parameters: $1 = '2275', $2 = '29500', $3 = 'inventory', $4 = 'default', $5 = '500'
	Limit  (cost=0.43..894.68 rows=1667 width=401) (actual time=0.005..0.154 rows=500.00 loops=1)
	  Buffers: shared hit=26
	  ->  Index Scan using bm_3icbu1uum6b8_idx_events_stream_position on bm_3icbu1uum6b8_events  (cost=0.43..8941.31 rows=16667 width=401) (actual time=0.005..0.130 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($3)::text) AND (stream_purpose = ($4)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW(($1)::xid8, $2)))
	        Index Searches: 1
	        Buffers: shared hit=26
```

### read as issued: query-by-id @ postgres:18/metrics=off (generic plan) — measured 0.02 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_id = $1::uuid

	Query Parameters: $1 = '018cc251-f400-74eb-8412-04a7d99e38f3'
	Index Scan using bm_3icbu1uum6b8_events_event_id_key on bm_3icbu1uum6b8_events  (cost=0.42..8.45 rows=1 width=393) (actual time=0.026..0.028 rows=1.00 loops=1)
	  Index Cond: (event_id = ($1)::uuid)
	  Index Searches: 1
	  Buffers: shared hit=4
```

### read as issued: query-wildcard @ postgres:18/metrics=off (generic plan) — measured 18.96 ms/op — **sequential scan**

> no index served this, so it read the table from the beginning.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_3icbu1uum6b8_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 ORDER BY event_tx::xid8, event_position  LIMIT $1 OFFSET 0
	Query Parameters: $1 = '500'
	Limit  (cost=17161.76..18326.43 rows=10000 width=401) (actual time=20.928..23.751 rows=500.00 loops=1)
	  Buffers: shared hit=4428
	  ->  Gather Merge  (cost=17161.76..28808.41 rows=100000 width=401) (actual time=20.925..23.723 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=4428
	        ->  Sort  (cost=16161.74..16265.91 rows=41667 width=401) (actual time=18.500..18.511 rows=310.67 loops=3)
	              Sort Key: event_tx, event_position
	              Sort Method: top-N heapsort  Memory: 278kB
	              Buffers: shared hit=4428
	              Worker 0:  Sort Method: top-N heapsort  Memory: 278kB
	              Worker 1:  Sort Method: top-N heapsort  Memory: 278kB
	              ->  Parallel Seq Scan on bm_3icbu1uum6b8_events  (cost=0.00..5271.50 rows=41667 width=401) (actual time=0.018..10.159 rows=33333.33 loops=3)
	                    Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	                    Buffers: shared hit=4334
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| inmem/metrics=off | query-by-entity-cold | thrpt | 1 | 0.131 | ops/ms | 3.3% | 131 | 7,843 | 0 |
| inmem/metrics=off | query-by-entity-hot | thrpt | 1 | 0.065 | ops/ms | 1.2% | 65 | 3,927 | 0 |
| inmem/metrics=off | query-by-id | thrpt | 1 | 1956.922 | ops/ms | 4.5% | 1,956,922 | 117,419,263 | 0 |
| inmem/metrics=off | query-by-multi-tag | thrpt | 1 | 0.145 | ops/ms | 12.6% | 145 | 8,713 | 0 |
| inmem/metrics=off | query-by-or-groups | thrpt | 1 | 1.476 | ops/ms | 1.8% | 1,476 | 88,584 | 0 |
| inmem/metrics=off | query-by-tag-needle | thrpt | 1 | 0.135 | ops/ms | 2.3% | 135 | 8,097 | 0 |
| inmem/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.404 | ops/ms | 3.0% | 404 | 24,236 | 0 |
| inmem/metrics=off | query-by-type | thrpt | 1 | 2.747 | ops/ms | 0.7% | 2,747 | 164,836 | 0 |
| inmem/metrics=off | query-cursor-walk | thrpt | 1 | 0.480 | ops/ms | 13.1% | 480 | 28,824 | 0 |
| inmem/metrics=off | query-last-event | thrpt | 1 | 1.587 | ops/ms | 0.7% | 1,587 | 95,231 | 0 |
| inmem/metrics=off | query-stream-page | thrpt | 1 | 3.438 | ops/ms | 4.8% | 3,438 | 206,296 | 0 |
| inmem/metrics=off | query-wildcard | thrpt | 1 | 4.784 | ops/ms | 6.6% | 4,784 | 287,074 | 0 |
| postgres:18/metrics=off | query-by-entity-cold | thrpt | 1 | 8.668 | ops/ms | 2.5% | 8,668 | 520,107 | 0 |
| postgres:18/metrics=off | query-by-entity-hot | thrpt | 1 | 0.047 | ops/ms | 1.3% | 47 | 2,805 | 0 |
| postgres:18/metrics=off | query-by-id | thrpt | 1 | 47.769 | ops/ms | 2.2% | 47,769 | 2,866,217 | 0 |
| postgres:18/metrics=off | query-by-multi-tag | thrpt | 1 | 0.391 | ops/ms | 4.3% | 391 | 23,468 | 0 |
| postgres:18/metrics=off | query-by-or-groups | thrpt | 1 | 0.378 | ops/ms | 3.6% | 378 | 22,701 | 0 |
| postgres:18/metrics=off | query-by-tag-needle | thrpt | 1 | 3.673 | ops/ms | 1.5% | 3,673 | 220,413 | 0 |
| postgres:18/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.492 | ops/ms | 3.1% | 492 | 29,520 | 0 |
| postgres:18/metrics=off | query-by-type | thrpt | 1 | 0.992 | ops/ms | 2.4% | 992 | 59,504 | 0 |
| postgres:18/metrics=off | query-cursor-walk | thrpt | 1 | 0.183 | ops/ms | 2.8% | 183 | 10,979 | 0 |
| postgres:18/metrics=off | query-last-event | thrpt | 1 | 14.964 | ops/ms | 2.5% | 14,964 | 897,895 | 0 |
| postgres:18/metrics=off | query-stream-page | thrpt | 1 | 0.950 | ops/ms | 3.9% | 950 | 57,016 | 0 |
| postgres:18/metrics=off | query-wildcard | thrpt | 1 | 0.053 | ops/ms | 1.4% | 53 | 3,171 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
