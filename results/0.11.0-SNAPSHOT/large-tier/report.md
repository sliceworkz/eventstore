# Benchmark run: large-tier

The read shapes at ten million events, against an external server. The tier the whole corpus-reuse machinery exists for: provisioning costs minutes and is then measured against for days, so run `provision` first and let the measurement runs find it already there.
What to look for is not the absolute numbers but their ratio to read-shapes at a hundred thousand. A needle tag query should cost about the same at both volumes -- the index does the work, and the store is a hundred times larger. If it scales with volume the query is not using the index, and the report's captured plan is where to look. The two shapes that legitimately do scale are the swathe (which returns ~1% of the store, so a hundred times the store is a hundred times the events) and the hot entity (a hundred thousand entities hold more history each); read those as returning more rows, not as losing an index.
Reads only, and its cadence is read-shapes' exactly -- the profile it is a ratio against, and a control measured differently is not one. Appends at this tier are large-tier-writes, which is a separate profile because it cannot share these settings: an append workload here grows the store for a whole trial, so its measurement budget is a fixed number of events rather than a length of time.
Deliberately external rather than containerised. A Testcontainers PostgreSQL runs stock defaults -- 128 MB of shared_buffers, untuned WAL -- and at this size those defaults are most of what would be measured. The publish step refuses a containerised run as a baseline for exactly that reason.
Set the schema mode to NONE if a DBA owns the schema on that server; ENSURE is right when the suite is allowed to create its own corpus tables, which is the usual arrangement for a benchmark host. The btree_gin extension has to exist -- creating it needs CREATE on the database, not on the schema.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-05T06:42:43.143592732Z |
| finished | 2026-09-05T07:00:50.671482341Z |
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
| effective_cache_size | 52428808kB |
| effective_io_concurrency | 200 |
| fsync | on |
| full_page_writes | on |
| jit | on |
| lc_messages | en_US.UTF-8 |
| maintenance_work_mem | 1048576kB |
| max_connections | 100 |
| max_parallel_workers | 8 |
| max_parallel_workers_per_gather | 2 |
| max_wal_size | 8192MB |
| max_worker_processes | 8 |
| min_wal_size | 1024MB |
| random_page_cost | 1.1 |
| seq_page_cost | 1 |
| server_version | 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1) |
| shared_buffers | 15728648kB |
| synchronous_commit | on |
| track_io_timing | off |
| version | PostgreSQL 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1) on x86_64-pc-linux-gnu, compiled by gcc (Ubuntu 15.2.0-16ubuntu1) 15.2.0, 64-bit |
| wal_compression | off |
| work_mem | 131072kB |

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

**The reconstructions are the weaker half of this section, including for the reads.** They inline as literals what the store binds as parameters, so a reconstruction can report an execution time the whole measured operation fits inside -- which is not a fast plan but a different one. Read the captured plans further down against the measurements, and these for the shape of the predicate.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.56..60.86 rows=500 width=313) (actual time=0.019..0.153 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..670823.80 rows=5562386 width=313) (actual time=0.019..0.130 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=70
Planning Time: 0.324 ms
Execution Time: 0.181 ms
```

### tag needle (~10 matches)

```
Sort  (cost=894.10..895.96 rows=742 width=313) (actual time=0.291..0.292 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=84
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=23.34..858.73 rows=742 width=313) (actual time=0.266..0.278 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=76
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..23.15 rows=742 width=0) (actual time=0.252..0.252 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=66
Planning:
  Buffers: shared hit=12
Planning Time: 0.115 ms
Execution Time: 0.328 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..5888.47 rows=500 width=313) (actual time=0.019..5.338 rows=500.00 loops=1)
  Buffers: shared hit=1444
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..657196.88 rows=55809 width=313) (actual time=0.018..5.315 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=1444
Planning:
  Buffers: shared hit=3
Planning Time: 0.077 ms
Execution Time: 5.360 ms
```

### one entity's whole history (hot) — **JIT 4ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
Sort  (cost=241744.90..242404.04 rows=263657 width=313) (actual time=487.314..498.360 rows=455092.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 126338kB
  Buffers: shared hit=188212
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1521.37..218004.82 rows=263657 width=313) (actual time=122.803..349.419 rows=455092.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=184596
        Buffers: shared hit=188212
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1455.45 rows=263682 width=0) (actual time=97.309..97.310 rows=455093.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=3616
Planning:
  Buffers: shared hit=3
Planning Time: 0.085 ms
JIT:
  Functions: 6
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.256 ms (Deform 0.120 ms), Inlining 0.000 ms, Optimization 0.244 ms, Emission 3.172 ms, Total 3.672 ms
Execution Time: 521.576 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..3.06 rows=1 width=313) (actual time=0.056..0.056 rows=1.00 loops=1)
  Buffers: shared hit=4 read=4
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..658236.12 rows=263657 width=313) (actual time=0.055..0.055 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared hit=4 read=4
Planning:
  Buffers: shared hit=11
Planning Time: 0.165 ms
Execution Time: 0.071 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..76.91 rows=500 width=313) (actual time=0.018..0.161 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..612016.42 rows=4008431 width=313) (actual time=0.018..0.139 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('3087567'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=14
Planning Time: 0.094 ms
Execution Time: 0.190 ms
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

### read as issued: query-stream-page @ postgres:external/metrics=off (generic plan) — measured 1.04 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 ORDER BY event_tx::xid8, event_position  LIMIT $3 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = '500'
	Limit  (cost=0.56..64582.84 rows=499795 width=401) (actual time=0.018..0.176 rows=500.00 loops=1)
	  Buffers: shared hit=32
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..645823.45 rows=4997951 width=401) (actual time=0.017..0.152 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=32
```

### read as issued: query-by-type @ postgres:external/metrics=off (generic plan) — measured 1.00 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3))) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = '500'
	Limit  (cost=0.56..31497.53 rows=45436 width=401) (actual time=0.056..0.468 rows=500.00 loops=1)
	  Buffers: shared hit=54
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..314969.53 rows=454359 width=401) (actual time=0.054..0.416 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=54
```

### read as issued: query-by-tag-needle @ postgres:external/metrics=off (generic plan) — measured 0.26 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:needle'
	Sort  (cost=28882.84..28945.31 rows=24990 width=401) (actual time=0.534..0.535 rows=10.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 28kB
	  Buffers: shared hit=76
	  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.68..27057.44 rows=24990 width=401) (actual time=0.507..0.520 rows=10.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=10
	        Buffers: shared hit=76
	        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.43 rows=24992 width=0) (actual time=0.480..0.480 rows=10.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=66
```

### read as issued: query-by-tag-swathe @ postgres:external/metrics=off (generic plan) — measured 5.02 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:swathe', $4 = '500'
	Limit  (cost=28314.89..28605.94 rows=2499 width=401) (actual time=113.475..147.951 rows=500.00 loops=1)
	  Buffers: shared hit=103857
	  ->  Gather Merge  (cost=28314.89..31225.28 rows=24989 width=401) (actual time=113.473..147.926 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=103857
	        ->  Sort  (cost=27314.87..27340.90 rows=10412 width=401) (actual time=111.535..111.546 rows=288.67 loops=3)
	              Sort Key: event_tx, event_position
	              Sort Method: top-N heapsort  Memory: 278kB
	              Buffers: shared hit=103857
	              Worker 0:  Sort Method: top-N heapsort  Memory: 446kB
	              Worker 1:  Sort Method: top-N heapsort  Memory: 398kB
	              ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.68..26620.08 rows=10412 width=401) (actual time=51.778..105.210 rows=33333.33 loops=3)
	                    Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                    Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	                    Heap Blocks: exact=54998
	                    Buffers: shared hit=103823
	                    Worker 0:  Heap Blocks: exact=23086
	                    Worker 1:  Heap Blocks: exact=21912
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.43 rows=24992 width=0) (actual time=43.179..43.179 rows=100000.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                          Index Searches: 1
	                          Buffers: shared hit=3787
```

### read as issued: query-by-tag-swathe @ postgres:external/metrics=off (custom plan, first executions only) — measured 5.02 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:swathe', $4 = '500'
	Limit  (cost=0.56..5888.47 rows=500 width=401) (actual time=0.017..5.047 rows=500.00 loops=1)
	  Buffers: shared hit=1444
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..657196.88 rows=55809 width=401) (actual time=0.016..5.024 rows=500.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{campaign:swathe}'::text[])
	        Rows Removed by Filter: 26973
	        Index Searches: 1
	        Buffers: shared hit=1444
```

### read as issued: query-by-entity-hot @ postgres:external/metrics=off (generic plan) — measured 1677.55 ms/op — **sorts on disk**

> the sort did not fit in work_mem and spilled to disk. Either the read returns more rows than it needs -- a limit or a savepoint -- or work_mem is too small for the size of result this query is meant to produce.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-000000'
	Sort  (cost=28882.84..28945.31 rows=24990 width=401) (actual time=505.971..543.785 rows=455092.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: external merge  Disk: 128952kB
	  Buffers: shared hit=188212, temp read=16119 written=16120
	  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.68..27057.44 rows=24990 width=401) (actual time=107.800..287.944 rows=455092.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=184596
	        Buffers: shared hit=188212
	        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.43 rows=24992 width=0) (actual time=88.654..88.655 rows=455093.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=3616
```

### read as issued: query-by-entity-cold @ postgres:external/metrics=off (generic plan) — measured 0.10 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-094269'
	Sort  (cost=28882.84..28945.31 rows=24990 width=401) (actual time=0.054..0.054 rows=1.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 25kB
	  Buffers: shared hit=22
	  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.68..27057.44 rows=24990 width=401) (actual time=0.053..0.053 rows=1.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=1
	        Buffers: shared hit=22
	        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.43 rows=24992 width=0) (actual time=0.051..0.051 rows=1.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=21
```

### read as issued: query-by-multi-tag @ postgres:external/metrics=off (generic plan) — measured 14.85 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3, $4, $5]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $6 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'channel:web', $4 = 'country:BE', $5 = 'sku:SKU-000000', $6 = '500'
	Limit  (cost=28314.89..28605.94 rows=2499 width=401) (actual time=120.004..134.024 rows=500.00 loops=1)
	  Buffers: shared hit=39818
	  ->  Gather Merge  (cost=28314.89..31225.28 rows=24989 width=401) (actual time=120.002..133.999 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=39818
	        ->  Sort  (cost=27314.87..27340.90 rows=10412 width=401) (actual time=118.112..118.121 rows=292.00 loops=3)
	              Sort Key: event_tx, event_position
	              Sort Method: top-N heapsort  Memory: 517kB
	              Buffers: shared hit=39818
	              Worker 0:  Sort Method: top-N heapsort  Memory: 313kB
	              Worker 1:  Sort Method: top-N heapsort  Memory: 309kB
	              ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.68..26620.08 rows=10412 width=401) (actual time=93.144..115.404 rows=12723.67 loops=3)
	                    Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	                    Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	                    Heap Blocks: exact=20456
	                    Buffers: shared hit=39784
	                    Worker 0:  Heap Blocks: exact=7629
	                    Worker 1:  Heap Blocks: exact=6863
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.43 rows=24992 width=0) (actual time=91.325..91.325 rows=38171.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	                          Index Searches: 1
	                          Buffers: shared hit=4796
```

### read as issued: query-by-multi-tag @ postgres:external/metrics=off (custom plan, first executions only) — measured 14.85 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3, $4, $5]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $6 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'channel:web', $4 = 'country:BE', $5 = 'sku:SKU-000000', $6 = '500'
	Limit  (cost=0.56..14839.94 rows=500 width=401) (actual time=0.017..12.505 rows=500.00 loops=1)
	  Buffers: shared hit=3959
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..657028.53 rows=22138 width=401) (actual time=0.017..12.481 rows=500.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{channel:web,country:BE,sku:SKU-000000}'::text[])
	        Rows Removed by Filter: 76022
	        Index Searches: 1
	        Buffers: shared hit=3959
```

### read as issued: query-by-or-groups @ postgres:external/metrics=off (generic plan) — measured 2.36 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=1000.59..65649.65 rows=2268 width=401) (actual time=3.548..6.252 rows=500.00 loops=1)
	  Buffers: shared hit=582
	  ->  Gather Merge  (cost=1000.59..647405.73 rows=22677 width=401) (actual time=3.547..6.228 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=582
	        ->  Parallel Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..643788.22 rows=9449 width=401) (actual time=0.133..1.599 rows=211.33 loops=3)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Filter: (((event_type = ANY (ARRAY[($3)::text, ($4)::text])) AND (event_tags @> ARRAY[($5)::text])) OR ((event_type = ANY (ARRAY[($6)::text, ($7)::text])) AND (event_tags @> ARRAY[($8)::text])) OR ((event_type = ANY (ARRAY[($9)::text, ($10)::text])) AND (event_tags @> ARRAY[($11)::text])) OR ((event_type = ANY (ARRAY[($12)::text, ($13)::text])) AND (event_tags @> ARRAY[($14)::text])) OR ((event_type = ANY (ARRAY[($15)::text, ($16)::text])) AND (event_tags @> ARRAY[($17)::text])))
	              Rows Removed by Filter: 1485
	              Index Searches: 1
	              Buffers: shared hit=582
```

### read as issued: query-by-or-groups @ postgres:external/metrics=off (custom plan, first executions only) — measured 2.36 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=0.56..1578.37 rows=500 width=401) (actual time=0.011..1.271 rows=500.00 loops=1)
	  Buffers: shared hit=219
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..727600.53 rows=230574 width=401) (actual time=0.010..1.247 rows=500.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: ((event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000001}'::text[]) OR (event_tags @> '{sku:SKU-000002}'::text[]) OR (event_tags @> '{sku:SKU-000003}'::text[]) OR (event_tags @> '{sku:SKU-000004}'::text[])))
	        Rows Removed by Filter: 3494
	        Index Searches: 1
	        Buffers: shared hit=219
```

### read as issued: query-last-event @ postgres:external/metrics=off (generic plan) — measured 0.06 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=16959.40..16959.97 rows=227 width=401) (actual time=54.712..54.714 rows=1.00 loops=1)
	  Buffers: shared hit=15616
	  ->  Sort  (cost=16959.40..16965.08 rows=2272 width=401) (actual time=54.709..54.711 rows=1.00 loops=1)
	        Sort Key: event_tx DESC, event_position DESC
	        Sort Method: top-N heapsort  Memory: 26kB
	        Buffers: shared hit=15616
	        ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=14281.60..16832.74 rows=2272 width=401) (actual time=42.057..52.704 rows=13529.00 loops=1)
	              Recheck Cond: ((event_tags @> ARRAY[($4)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Heap Blocks: exact=13069
	              Buffers: shared hit=15616
	              ->  BitmapAnd  (cost=14281.60..14281.60 rows=2272 width=0) (actual time=40.661..40.663 rows=0.00 loops=1)
	                    Buffers: shared hit=2547
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_tags  (cost=0.00..280.16 rows=49984 width=0) (actual time=25.481..25.481 rows=455093.00 loops=1)
	                          Index Cond: (event_tags @> ARRAY[($4)::text])
	                          Index Searches: 1
	                          Buffers: shared hit=112
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_type_position  (cost=0.00..14000.05 rows=454359 width=0) (actual time=11.349..11.349 rows=165193.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                          Index Searches: 1
	                          Buffers: shared hit=2435
```

### read as issued: query-last-event @ postgres:external/metrics=off (custom plan, first executions only) — measured 0.06 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=0.56..20.14 rows=1 width=401) (actual time=0.009..0.010 rows=1.00 loops=1)
	  Buffers: shared hit=8
	  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..91519.66 rows=4676 width=401) (actual time=0.009..0.009 rows=1.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = 'StockCounted'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
	        Rows Removed by Filter: 4
	        Index Searches: 1
	        Buffers: shared hit=8
```

### read as issued: query-cursor-walk @ postgres:external/metrics=off (generic plan) — measured 5.37 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND (event_tx, event_position) > ($1::xid8, $2) AND stream_context = $3 AND stream_purpose = $4 ORDER BY event_tx::xid8, event_position  LIMIT $5 OFFSET 0
	Query Parameters: $1 = '3087568', $2 = '2752000', $3 = 'inventory', $4 = 'default', $5 = '500'
	Limit  (cost=0.57..50241.16 rows=166598 width=401) (actual time=0.008..0.228 rows=500.00 loops=1)
	  Buffers: shared hit=30
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.57..502407.70 rows=1665984 width=401) (actual time=0.007..0.190 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($3)::text) AND (stream_purpose = ($4)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW(($1)::xid8, $2)))
	        Index Searches: 1
	        Buffers: shared hit=30
```

### read as issued: query-by-id @ postgres:external/metrics=off (generic plan) — measured 0.02 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_id = $1::uuid

	Query Parameters: $1 = '018cc251-f400-74eb-8412-04a7d99e38f3'
	Index Scan using bm_n3tx9gechuj9_events_event_id_key on bm_n3tx9gechuj9_events  (cost=0.56..2.79 rows=1 width=393) (actual time=0.005..0.005 rows=1.00 loops=1)
	  Index Cond: (event_id = ($1)::uuid)
	  Index Searches: 1
	  Buffers: shared hit=5
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | query-by-entity-cold | thrpt | 1 | 9.682 | ops/ms | 3.1% | 9,682 | 580,945 | 0 |
| postgres:external/metrics=off | query-by-entity-hot | thrpt | 1 | 0.001 | ops/ms | 5.8% | 1 | 43 | 0 |
| postgres:external/metrics=off | query-by-id | thrpt | 1 | 57.872 | ops/ms | 3.8% | 57,872 | 3,472,416 | 0 |
| postgres:external/metrics=off | query-by-multi-tag | thrpt | 1 | 0.067 | ops/ms | 3.1% | 67 | 4,047 | 0 |
| postgres:external/metrics=off | query-by-or-groups | thrpt | 1 | 0.425 | ops/ms | 5.3% | 425 | 25,481 | 0 |
| postgres:external/metrics=off | query-by-tag-needle | thrpt | 1 | 3.835 | ops/ms | 2.0% | 3,835 | 230,135 | 0 |
| postgres:external/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.199 | ops/ms | 2.6% | 199 | 11,950 | 0 |
| postgres:external/metrics=off | query-by-type | thrpt | 1 | 1.004 | ops/ms | 2.7% | 1,004 | 60,261 | 0 |
| postgres:external/metrics=off | query-cursor-walk | thrpt | 1 | 0.186 | ops/ms | 3.4% | 186 | 11,175 | 0 |
| postgres:external/metrics=off | query-last-event | thrpt | 1 | 17.431 | ops/ms | 6.4% | 17,431 | 1,045,906 | 0 |
| postgres:external/metrics=off | query-stream-page | thrpt | 1 | 0.959 | ops/ms | 6.6% | 959 | 57,547 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
