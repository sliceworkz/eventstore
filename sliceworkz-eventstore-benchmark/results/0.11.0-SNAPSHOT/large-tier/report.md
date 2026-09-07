# Benchmark run: large-tier

The read shapes at ten million events, against an external server. The tier the whole corpus-reuse machinery exists for: provisioning costs minutes and is then measured against for days, so run `provision` first and let the measurement runs find it already there.
What to look for is not the absolute numbers but their ratio to read-shapes at a hundred thousand. A needle tag query should cost about the same at both volumes -- the index does the work, and the store is a hundred times larger. If it scales with volume the query is not using the index, and the report's captured plan is where to look. The two shapes that legitimately do scale are the swathe (which returns ~1% of the store, so a hundred times the store is a hundred times the events) and the hot entity (a hundred thousand entities hold more history each); read those as returning more rows, not as losing an index.
Reads only, and its cadence is read-shapes' exactly -- the profile it is a ratio against, and a control measured differently is not one. Appends at this tier are large-tier-writes, which is a separate profile because it cannot share these settings: an append workload here grows the store for a whole trial, so its measurement budget is a fixed number of events rather than a length of time.
Deliberately external rather than containerised. A Testcontainers PostgreSQL runs stock defaults -- 128 MB of shared_buffers, untuned WAL -- and at this size those defaults are most of what would be measured. The publish step refuses a containerised run as a baseline for exactly that reason.
Set the schema mode to NONE if a DBA owns the schema on that server; ENSURE is right when the suite is allowed to create its own corpus tables, which is the usual arrangement for a benchmark host. The btree_gin extension has to exist -- creating it needs CREATE on the database, not on the schema.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-05T22:09:59.856252431Z |
| finished | 2026-09-05T22:28:06.825395153Z |
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
| effective_cache_size | 41943040kB |
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
| shared_buffers | 12582912kB |
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
Limit  (cost=0.56..61.34 rows=500 width=312) (actual time=0.032..0.187 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..667613.49 rows=5492080 width=312) (actual time=0.031..0.162 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=70
Planning Time: 0.322 ms
Execution Time: 0.218 ms
```

### tag needle (~10 matches)

```
Sort  (cost=882.27..884.10 rows=732 width=312) (actual time=0.397..0.397 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=84
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=23.29..847.44 rows=732 width=312) (actual time=0.353..0.383 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=76
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..23.10 rows=732 width=0) (actual time=0.339..0.340 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=66
Planning:
  Buffers: shared hit=12
Planning Time: 0.111 ms
Execution Time: 0.437 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..7061.84 rows=500 width=312) (actual time=0.025..5.614 rows=500.00 loops=1)
  Buffers: shared hit=1444
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..654114.88 rows=46317 width=312) (actual time=0.024..5.589 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=1444
Planning:
  Buffers: shared hit=3
Planning Time: 0.082 ms
Execution Time: 5.638 ms
```

### one entity's whole history (hot) — **JIT 3ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
Sort  (cost=231466.38..232090.18 rows=249523 width=312) (actual time=544.367..555.417 rows=455092.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 126338kB
  Buffers: shared hit=188220
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1441.03..209098.12 rows=249523 width=312) (actual time=121.030..410.059 rows=455092.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=184596
        Buffers: shared hit=188220
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1378.65 rows=249547 width=0) (actual time=98.065..98.065 rows=455093.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=3624
Planning:
  Buffers: shared hit=3
Planning Time: 0.104 ms
JIT:
  Functions: 6
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.270 ms (Deform 0.127 ms), Inlining 0.000 ms, Optimization 0.242 ms, Emission 2.434 ms, Total 2.946 ms
Execution Time: 577.743 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..3.19 rows=1 width=312) (actual time=0.033..0.033 rows=1.00 loops=1)
  Buffers: shared hit=8
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..655130.91 rows=249523 width=312) (actual time=0.032..0.032 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=11
Planning Time: 0.157 ms
Execution Time: 0.060 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..77.54 rows=500 width=312) (actual time=0.016..0.155 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..609653.80 rows=3959895 width=312) (actual time=0.015..0.133 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('3087567'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=14
Planning Time: 0.122 ms
Execution Time: 0.188 ms
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

### read as issued: query-stream-page @ postgres:external/metrics=off (generic plan) — measured 1.05 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 ORDER BY event_tx::xid8, event_position  LIMIT $3 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = '500'
	Limit  (cost=0.56..64586.80 rows=500098 width=400) (actual time=0.040..0.295 rows=500.00 loops=1)
	  Buffers: shared hit=32
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..645863.28 rows=5000983 width=400) (actual time=0.038..0.248 rows=500.00 loops=1)
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
	Limit  (cost=0.56..31505.13 rows=45464 width=400) (actual time=0.038..0.290 rows=500.00 loops=1)
	  Buffers: shared hit=54
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..315042.74 rows=454635 width=400) (actual time=0.037..0.260 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=54
```

### read as issued: query-by-tag-needle @ postgres:external/metrics=off (generic plan) — measured 0.27 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:needle'
	Sort  (cost=28899.48..28961.99 rows=25005 width=400) (actual time=0.799..0.801 rows=10.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 28kB
	  Buffers: shared hit=76
	  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.76..27072.87 rows=25005 width=400) (actual time=0.764..0.784 rows=10.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=10
	        Buffers: shared hit=76
	        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.50 rows=25007 width=0) (actual time=0.731..0.731 rows=10.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=66
```

### read as issued: query-by-tag-swathe @ postgres:external/metrics=off (generic plan) — measured 5.23 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:swathe', $4 = '500'
	Limit  (cost=28330.59..28621.75 rows=2500 width=400) (actual time=127.692..168.228 rows=500.00 loops=1)
	  Buffers: shared hit=103844
	  ->  Gather Merge  (cost=28330.59..31242.83 rows=25005 width=400) (actual time=127.690..168.203 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=103844
	        ->  Sort  (cost=27330.56..27356.61 rows=10419 width=400) (actual time=125.268..125.278 rows=286.67 loops=3)
	              Sort Key: event_tx, event_position
	              Sort Method: top-N heapsort  Memory: 283kB
	              Buffers: shared hit=103844
	              Worker 0:  Sort Method: top-N heapsort  Memory: 474kB
	              Worker 1:  Sort Method: top-N heapsort  Memory: 365kB
	              ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.76..26635.25 rows=10419 width=400) (actual time=52.049..118.697 rows=33333.33 loops=3)
	                    Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                    Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	                    Heap Blocks: exact=56653
	                    Buffers: shared hit=103810
	                    Worker 0:  Heap Blocks: exact=23208
	                    Worker 1:  Heap Blocks: exact=20135
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.50 rows=25007 width=0) (actual time=43.852..43.853 rows=100000.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                          Index Searches: 1
	                          Buffers: shared hit=3774
```

### read as issued: query-by-tag-swathe @ postgres:external/metrics=off (custom plan, first executions only) — measured 5.23 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:swathe', $4 = '500'
	Limit  (cost=0.56..7061.84 rows=500 width=400) (actual time=0.022..4.766 rows=500.00 loops=1)
	  Buffers: shared hit=1444
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..654114.88 rows=46317 width=400) (actual time=0.022..4.743 rows=500.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{campaign:swathe}'::text[])
	        Rows Removed by Filter: 26973
	        Index Searches: 1
	        Buffers: shared hit=1444
```

### read as issued: query-by-entity-hot @ postgres:external/metrics=off (generic plan) — measured 1713.17 ms/op — **sorts on disk**

> the sort did not fit in work_mem and spilled to disk. Either the read returns more rows than it needs -- a limit or a savepoint -- or work_mem is too small for the size of result this query is meant to produce.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-000000'
	Sort  (cost=28899.48..28961.99 rows=25005 width=400) (actual time=534.485..573.946 rows=455092.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: external merge  Disk: 128952kB
	  Buffers: shared hit=188220, temp read=16119 written=16120
	  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.76..27072.87 rows=25005 width=400) (actual time=107.328..316.478 rows=455092.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=184596
	        Buffers: shared hit=188220
	        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.50 rows=25007 width=0) (actual time=87.911..87.911 rows=455093.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=3624
```

### read as issued: query-by-entity-cold @ postgres:external/metrics=off (generic plan) — measured 0.10 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-094269'
	Sort  (cost=28899.48..28961.99 rows=25005 width=400) (actual time=0.203..0.203 rows=1.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 25kB
	  Buffers: shared hit=22
	  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.76..27072.87 rows=25005 width=400) (actual time=0.196..0.197 rows=1.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=1
	        Buffers: shared hit=22
	        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.50 rows=25007 width=0) (actual time=0.185..0.185 rows=1.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=21
```

### read as issued: query-by-multi-tag @ postgres:external/metrics=off (generic plan) — measured 15.27 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3, $4, $5]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $6 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'channel:web', $4 = 'country:BE', $5 = 'sku:SKU-000000', $6 = '500'
	Limit  (cost=28330.59..28621.75 rows=2500 width=400) (actual time=124.614..142.434 rows=500.00 loops=1)
	  Buffers: shared hit=39838
	  ->  Gather Merge  (cost=28330.59..31242.83 rows=25005 width=400) (actual time=124.613..142.408 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=39838
	        ->  Sort  (cost=27330.56..27356.61 rows=10419 width=400) (actual time=122.576..122.585 rows=303.33 loops=3)
	              Sort Key: event_tx, event_position
	              Sort Method: top-N heapsort  Memory: 518kB
	              Buffers: shared hit=39838
	              Worker 0:  Sort Method: top-N heapsort  Memory: 305kB
	              Worker 1:  Sort Method: top-N heapsort  Memory: 319kB
	              ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.76..26635.25 rows=10419 width=400) (actual time=94.151..120.015 rows=12723.67 loops=3)
	                    Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	                    Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	                    Heap Blocks: exact=20285
	                    Buffers: shared hit=39804
	                    Worker 0:  Heap Blocks: exact=7423
	                    Worker 1:  Heap Blocks: exact=7240
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.50 rows=25007 width=0) (actual time=92.448..92.448 rows=38171.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	                          Index Searches: 1
	                          Buffers: shared hit=4816
```

### read as issued: query-by-multi-tag @ postgres:external/metrics=off (custom plan, first executions only) — measured 15.27 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3, $4, $5]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $6 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'channel:web', $4 = 'country:BE', $5 = 'sku:SKU-000000', $6 = '500'
	Limit  (cost=0.56..15397.96 rows=500 width=400) (actual time=0.038..14.294 rows=500.00 loops=1)
	  Buffers: shared hit=3959
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..653989.48 rows=21237 width=400) (actual time=0.037..14.270 rows=500.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{channel:web,country:BE,sku:SKU-000000}'::text[])
	        Rows Removed by Filter: 76022
	        Index Searches: 1
	        Buffers: shared hit=3959
```

### read as issued: query-by-or-groups @ postgres:external/metrics=off (generic plan) — measured 2.31 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=1000.59..65645.11 rows=2269 width=400) (actual time=3.908..7.465 rows=500.00 loops=1)
	  Buffers: shared hit=601
	  ->  Gather Merge  (cost=1000.59..647445.82 rows=22690 width=400) (actual time=3.907..7.441 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=601
	        ->  Parallel Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..643826.81 rows=9454 width=400) (actual time=0.164..2.052 rows=215.67 loops=3)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Filter: (((event_type = ANY (ARRAY[($3)::text, ($4)::text])) AND (event_tags @> ARRAY[($5)::text])) OR ((event_type = ANY (ARRAY[($6)::text, ($7)::text])) AND (event_tags @> ARRAY[($8)::text])) OR ((event_type = ANY (ARRAY[($9)::text, ($10)::text])) AND (event_tags @> ARRAY[($11)::text])) OR ((event_type = ANY (ARRAY[($12)::text, ($13)::text])) AND (event_tags @> ARRAY[($14)::text])) OR ((event_type = ANY (ARRAY[($15)::text, ($16)::text])) AND (event_tags @> ARRAY[($17)::text])))
	              Rows Removed by Filter: 1520
	              Index Searches: 1
	              Buffers: shared hit=601
```

### read as issued: query-by-or-groups @ postgres:external/metrics=off (custom plan, first executions only) — measured 2.31 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=0.56..1643.55 rows=500 width=400) (actual time=0.016..1.943 rows=500.00 loops=1)
	  Buffers: shared hit=219
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..723635.39 rows=220219 width=400) (actual time=0.016..1.916 rows=500.00 loops=1)
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
	Limit  (cost=16964.88..16965.45 rows=227 width=400) (actual time=56.665..56.667 rows=1.00 loops=1)
	  Buffers: shared hit=15616
	  ->  Sort  (cost=16964.88..16970.56 rows=2273 width=400) (actual time=56.664..56.665 rows=1.00 loops=1)
	        Sort Key: event_tx DESC, event_position DESC
	        Sort Method: top-N heapsort  Memory: 26kB
	        Buffers: shared hit=15616
	        ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=14285.89..16838.16 rows=2273 width=400) (actual time=42.518..54.668 rows=13529.00 loops=1)
	              Recheck Cond: ((event_tags @> ARRAY[($4)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Heap Blocks: exact=13069
	              Buffers: shared hit=15616
	              ->  BitmapAnd  (cost=14285.89..14285.89 rows=2273 width=0) (actual time=41.088..41.090 rows=0.00 loops=1)
	                    Buffers: shared hit=2547
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_tags  (cost=0.00..280.32 rows=50015 width=0) (actual time=24.858..24.858 rows=455093.00 loops=1)
	                          Index Cond: (event_tags @> ARRAY[($4)::text])
	                          Index Searches: 1
	                          Buffers: shared hit=112
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_type_position  (cost=0.00..14004.19 rows=454635 width=0) (actual time=12.366..12.366 rows=165193.00 loops=1)
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
	Limit  (cost=0.56..21.30 rows=1 width=400) (actual time=0.007..0.007 rows=1.00 loops=1)
	  Buffers: shared hit=8
	  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..78135.73 rows=3768 width=400) (actual time=0.006..0.006 rows=1.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = 'StockCounted'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
	        Rows Removed by Filter: 4
	        Index Searches: 1
	        Buffers: shared hit=8
```

### read as issued: query-cursor-walk @ postgres:external/metrics=off (generic plan) — measured 5.35 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND (event_tx, event_position) > ($1::xid8, $2) AND stream_context = $3 AND stream_purpose = $4 ORDER BY event_tx::xid8, event_position  LIMIT $5 OFFSET 0
	Query Parameters: $1 = '3087568', $2 = '2752000', $3 = 'inventory', $4 = 'default', $5 = '500'
	Limit  (cost=0.57..50237.42 rows=166699 width=400) (actual time=0.021..0.343 rows=500.00 loops=1)
	  Buffers: shared hit=30
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.57..502370.26 rows=1666994 width=400) (actual time=0.020..0.284 rows=500.00 loops=1)
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
	Index Scan using bm_n3tx9gechuj9_events_event_id_key on bm_n3tx9gechuj9_events  (cost=0.56..2.79 rows=1 width=392) (actual time=0.002..0.003 rows=1.00 loops=1)
	  Index Cond: (event_id = ($1)::uuid)
	  Index Searches: 1
	  Buffers: shared hit=5
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | query-by-entity-cold | thrpt | 1 | 9.863 | ops/ms | 4.6% | 9,863 | 591,788 | 0 |
| postgres:external/metrics=off | query-by-entity-hot | thrpt | 1 | 0.001 | ops/ms | 3.9% | 1 | 39 | 0 |
| postgres:external/metrics=off | query-by-id | thrpt | 1 | 57.041 | ops/ms | 3.3% | 57,041 | 3,422,528 | 0 |
| postgres:external/metrics=off | query-by-multi-tag | thrpt | 1 | 0.065 | ops/ms | 2.9% | 65 | 3,935 | 0 |
| postgres:external/metrics=off | query-by-or-groups | thrpt | 1 | 0.432 | ops/ms | 3.7% | 432 | 25,938 | 0 |
| postgres:external/metrics=off | query-by-tag-needle | thrpt | 1 | 3.655 | ops/ms | 1.9% | 3,655 | 219,294 | 0 |
| postgres:external/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.191 | ops/ms | 2.6% | 191 | 11,473 | 0 |
| postgres:external/metrics=off | query-by-type | thrpt | 1 | 0.997 | ops/ms | 1.6% | 997 | 59,856 | 0 |
| postgres:external/metrics=off | query-cursor-walk | thrpt | 1 | 0.187 | ops/ms | 3.5% | 187 | 11,229 | 0 |
| postgres:external/metrics=off | query-last-event | thrpt | 1 | 16.992 | ops/ms | 4.6% | 16,992 | 1,019,554 | 0 |
| postgres:external/metrics=off | query-stream-page | thrpt | 1 | 0.955 | ops/ms | 3.9% | 955 | 57,339 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
