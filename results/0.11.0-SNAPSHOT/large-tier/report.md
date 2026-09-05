# Benchmark run: large-tier

The read shapes at ten million events, against an external server. The tier the whole corpus-reuse machinery exists for: provisioning costs minutes and is then measured against for days, so run `provision` first and let the measurement runs find it already there.
What to look for is not the absolute numbers but their ratio to read-shapes at a hundred thousand. A needle tag query should cost about the same at both volumes -- the index does the work, and the store is a hundred times larger. If it scales with volume the query is not using the index, and the report's captured plan is where to look. The two shapes that legitimately do scale are the swathe (which returns ~1% of the store, so a hundred times the store is a hundred times the events) and the hot entity (a hundred thousand entities hold more history each); read those as returning more rows, not as losing an index.
Reads only, and its cadence is read-shapes' exactly -- the profile it is a ratio against, and a control measured differently is not one. Appends at this tier are large-tier-writes, which is a separate profile because it cannot share these settings: an append workload here grows the store for a whole trial, so its measurement budget is a fixed number of events rather than a length of time.
Deliberately external rather than containerised. A Testcontainers PostgreSQL runs stock defaults -- 128 MB of shared_buffers, untuned WAL -- and at this size those defaults are most of what would be measured. The publish step refuses a containerised run as a baseline for exactly that reason.
Set the schema mode to NONE if a DBA owns the schema on that server; ENSURE is right when the suite is allowed to create its own corpus tables, which is the usual arrangement for a benchmark host. The btree_gin extension has to exist -- creating it needs CREATE on the database, not on the schema.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-05T15:32:35.656112939Z |
| finished | 2026-09-05T15:50:43.501020570Z |
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
Limit  (cost=0.56..61.22 rows=500 width=313) (actual time=0.035..0.231 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..667868.50 rows=5505379 width=313) (actual time=0.034..0.202 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=70
Planning Time: 0.402 ms
Execution Time: 0.270 ms
```

### tag needle (~10 matches)

```
Sort  (cost=884.63..886.47 rows=734 width=313) (actual time=0.520..0.521 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=84
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=23.30..849.70 rows=734 width=313) (actual time=0.470..0.501 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=76
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..23.11 rows=734 width=0) (actual time=0.450..0.450 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=66
Planning:
  Buffers: shared hit=12
Planning Time: 0.143 ms
Execution Time: 0.569 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..5923.96 rows=500 width=313) (actual time=0.029..8.630 rows=500.00 loops=1)
  Buffers: shared hit=1444
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..654381.24 rows=55237 width=313) (actual time=0.028..8.590 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=1444
Planning:
  Buffers: shared hit=3
Planning Time: 0.085 ms
Execution Time: 8.667 ms
```

### one entity's whole history (hot) — **JIT 3ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
Sort  (cost=238070.86..238717.29 rows=258569 width=313) (actual time=603.833..618.044 rows=455092.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 126338kB
  Buffers: shared hit=188212
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1492.20..214825.27 rows=258569 width=313) (actual time=127.514..450.780 rows=455092.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=184596
        Buffers: shared hit=188212
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1427.56 rows=258594 width=0) (actual time=102.720..102.720 rows=455093.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=3616
Planning:
  Buffers: shared hit=3
Planning Time: 0.266 ms
JIT:
  Functions: 6
  Options: Inlining false, Optimization false, Expressions true, Deforming true
  Timing: Generation 0.348 ms (Deform 0.162 ms), Inlining 0.000 ms, Optimization 0.240 ms, Emission 2.346 ms, Total 2.934 ms
Execution Time: 646.587 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..3.10 rows=1 width=313) (actual time=0.034..0.035 rows=1.00 loops=1)
  Buffers: shared hit=8
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..655397.90 rows=258569 width=313) (actual time=0.034..0.034 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared hit=8
Planning:
  Buffers: shared hit=11
Planning Time: 0.190 ms
Execution Time: 0.051 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..76.69 rows=500 width=313) (actual time=0.015..0.161 rows=500.00 loops=1)
  Buffers: shared hit=32
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..612111.25 rows=4020596 width=313) (actual time=0.015..0.138 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('3087567'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=32
Planning:
  Buffers: shared hit=14
Planning Time: 0.095 ms
Execution Time: 0.191 ms
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
	Limit  (cost=0.56..64570.42 rows=500519 width=401) (actual time=0.028..0.312 rows=500.00 loops=1)
	  Buffers: shared hit=32
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..645699.46 rows=5005193 width=401) (actual time=0.026..0.273 rows=500.00 loops=1)
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
	Limit  (cost=0.56..31488.96 rows=45502 width=401) (actual time=0.059..0.498 rows=500.00 loops=1)
	  Buffers: shared hit=54
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..314883.09 rows=455018 width=401) (actual time=0.057..0.440 rows=500.00 loops=1)
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
	Sort  (cost=28923.19..28985.76 rows=25026 width=401) (actual time=0.746..0.747 rows=10.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 28kB
	  Buffers: shared hit=76
	  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.87..27094.90 rows=25026 width=401) (actual time=0.714..0.732 rows=10.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=10
	        Buffers: shared hit=76
	        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.61 rows=25028 width=0) (actual time=0.684..0.684 rows=10.00 loops=1)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Index Searches: 1
	              Buffers: shared hit=66
```

### read as issued: query-by-tag-swathe @ postgres:external/metrics=off (generic plan) — measured 5.25 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:swathe', $4 = '500'
	Limit  (cost=28352.92..28644.43 rows=2503 width=401) (actual time=146.009..191.574 rows=500.00 loops=1)
	  Buffers: shared hit=103857
	  ->  Gather Merge  (cost=28352.92..31267.61 rows=25026 width=401) (actual time=146.007..191.546 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=103857
	        ->  Sort  (cost=27352.89..27378.96 rows=10428 width=401) (actual time=143.874..143.884 rows=294.67 loops=3)
	              Sort Key: event_tx, event_position
	              Sort Method: top-N heapsort  Memory: 440kB
	              Buffers: shared hit=103857
	              Worker 0:  Sort Method: top-N heapsort  Memory: 347kB
	              Worker 1:  Sort Method: top-N heapsort  Memory: 300kB
	              ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.87..26656.92 rows=10428 width=401) (actual time=65.091..136.661 rows=33333.33 loops=3)
	                    Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                    Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	                    Heap Blocks: exact=56687
	                    Buffers: shared hit=103823
	                    Worker 0:  Heap Blocks: exact=22684
	                    Worker 1:  Heap Blocks: exact=20625
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.61 rows=25028 width=0) (actual time=55.640..55.641 rows=100000.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                          Index Searches: 1
	                          Buffers: shared hit=3787
```

### read as issued: query-by-tag-swathe @ postgres:external/metrics=off (custom plan, first executions only) — measured 5.25 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:swathe', $4 = '500'
	Limit  (cost=0.56..5923.96 rows=500 width=401) (actual time=0.022..4.870 rows=500.00 loops=1)
	  Buffers: shared hit=1444
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..654381.24 rows=55237 width=401) (actual time=0.021..4.846 rows=500.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{campaign:swathe}'::text[])
	        Rows Removed by Filter: 26973
	        Index Searches: 1
	        Buffers: shared hit=1444
```

### read as issued: query-by-entity-hot @ postgres:external/metrics=off (generic plan) — measured 1714.21 ms/op — **sorts on disk**

> the sort did not fit in work_mem and spilled to disk. Either the read returns more rows than it needs -- a limit or a savepoint -- or work_mem is too small for the size of result this query is meant to produce.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-000000'
	Sort  (cost=28923.19..28985.76 rows=25026 width=401) (actual time=535.293..574.265 rows=455092.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: external merge  Disk: 128952kB
	  Buffers: shared hit=188212, temp read=16119 written=16120
	  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.87..27094.90 rows=25026 width=401) (actual time=108.407..315.280 rows=455092.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=184596
	        Buffers: shared hit=188212
	        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.61 rows=25028 width=0) (actual time=88.591..88.591 rows=455093.00 loops=1)
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
	Sort  (cost=28923.19..28985.76 rows=25026 width=401) (actual time=0.058..0.058 rows=1.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 25kB
	  Buffers: shared hit=22
	  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.87..27094.90 rows=25026 width=401) (actual time=0.056..0.056 rows=1.00 loops=1)
	        Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=1
	        Buffers: shared hit=22
	        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.61 rows=25028 width=0) (actual time=0.052..0.052 rows=1.00 loops=1)
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
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-000000', $4 = 'country:BE', $5 = 'channel:web', $6 = '500'
	Limit  (cost=28352.92..28644.43 rows=2503 width=401) (actual time=123.965..140.111 rows=500.00 loops=1)
	  Buffers: shared hit=39818
	  ->  Gather Merge  (cost=28352.92..31267.61 rows=25026 width=401) (actual time=123.963..140.086 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=39818
	        ->  Sort  (cost=27352.89..27378.96 rows=10428 width=401) (actual time=121.587..121.597 rows=300.67 loops=3)
	              Sort Key: event_tx, event_position
	              Sort Method: top-N heapsort  Memory: 518kB
	              Buffers: shared hit=39818
	              Worker 0:  Sort Method: top-N heapsort  Memory: 309kB
	              Worker 1:  Sort Method: top-N heapsort  Memory: 314kB
	              ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=161.87..26656.92 rows=10428 width=401) (actual time=93.286..118.883 rows=12723.67 loops=3)
	                    Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	                    Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	                    Heap Blocks: exact=21027
	                    Buffers: shared hit=39784
	                    Worker 0:  Heap Blocks: exact=6343
	                    Worker 1:  Heap Blocks: exact=7578
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..155.61 rows=25028 width=0) (actual time=91.977..91.977 rows=38171.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	                          Index Searches: 1
	                          Buffers: shared hit=4796
```

### read as issued: query-by-multi-tag @ postgres:external/metrics=off (custom plan, first executions only) — measured 15.27 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3, $4, $5]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $6 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-000000', $4 = 'country:BE', $5 = 'channel:web', $6 = '500'
	Limit  (cost=0.56..15366.29 rows=500 width=401) (actual time=0.022..13.676 rows=500.00 loops=1)
	  Buffers: shared hit=3959
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..654211.49 rows=21288 width=401) (actual time=0.021..13.652 rows=500.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{sku:SKU-000000,country:BE,channel:web}'::text[])
	        Rows Removed by Filter: 76022
	        Index Searches: 1
	        Buffers: shared hit=3959
```

### read as issued: query-by-or-groups @ postgres:external/metrics=off (generic plan) — measured 2.67 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=1000.59..65628.79 rows=2271 width=401) (actual time=3.509..6.280 rows=500.00 loops=1)
	  Buffers: shared hit=588
	  ->  Gather Merge  (cost=1000.59..647282.59 rows=22710 width=401) (actual time=3.508..6.256 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=588
	        ->  Parallel Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..643661.27 rows=9462 width=401) (actual time=0.119..1.715 rows=214.67 loops=3)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Filter: (((event_type = ANY (ARRAY[($3)::text, ($4)::text])) AND (event_tags @> ARRAY[($5)::text])) OR ((event_type = ANY (ARRAY[($6)::text, ($7)::text])) AND (event_tags @> ARRAY[($8)::text])) OR ((event_type = ANY (ARRAY[($9)::text, ($10)::text])) AND (event_tags @> ARRAY[($11)::text])) OR ((event_type = ANY (ARRAY[($12)::text, ($13)::text])) AND (event_tags @> ARRAY[($14)::text])) OR ((event_type = ANY (ARRAY[($15)::text, ($16)::text])) AND (event_tags @> ARRAY[($17)::text])))
	              Rows Removed by Filter: 1509
	              Index Searches: 1
	              Buffers: shared hit=588
```

### read as issued: query-by-or-groups @ postgres:external/metrics=off (custom plan, first executions only) — measured 2.67 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=0.56..1581.65 rows=500 width=401) (actual time=0.015..2.068 rows=500.00 loops=1)
	  Buffers: shared hit=219
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..724067.18 rows=228978 width=401) (actual time=0.015..2.042 rows=500.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: ((event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000001}'::text[]) OR (event_tags @> '{sku:SKU-000002}'::text[]) OR (event_tags @> '{sku:SKU-000003}'::text[]) OR (event_tags @> '{sku:SKU-000004}'::text[])))
	        Rows Removed by Filter: 3494
	        Index Searches: 1
	        Buffers: shared hit=219
```

### read as issued: query-last-event @ postgres:external/metrics=off (generic plan) — measured 0.07 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=16973.21..16973.78 rows=228 width=401) (actual time=56.553..56.555 rows=1.00 loops=1)
	  Buffers: shared hit=15616
	  ->  Sort  (cost=16973.21..16978.89 rows=2275 width=401) (actual time=56.552..56.553 rows=1.00 loops=1)
	        Sort Key: event_tx DESC, event_position DESC
	        Sort Method: top-N heapsort  Memory: 26kB
	        Buffers: shared hit=15616
	        ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=14291.85..16846.36 rows=2275 width=401) (actual time=42.652..54.555 rows=13529.00 loops=1)
	              Recheck Cond: ((event_tags @> ARRAY[($4)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Heap Blocks: exact=13069
	              Buffers: shared hit=15616
	              ->  BitmapAnd  (cost=14291.85..14291.85 rows=2275 width=0) (actual time=41.225..41.226 rows=0.00 loops=1)
	                    Buffers: shared hit=2547
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_tags  (cost=0.00..280.53 rows=50057 width=0) (actual time=25.243..25.243 rows=455093.00 loops=1)
	                          Index Cond: (event_tags @> ARRAY[($4)::text])
	                          Index Searches: 1
	                          Buffers: shared hit=112
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_type_position  (cost=0.00..14009.93 rows=455018 width=0) (actual time=12.145..12.146 rows=165193.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                          Index Searches: 1
	                          Buffers: shared hit=2435
```

### read as issued: query-last-event @ postgres:external/metrics=off (custom plan, first executions only) — measured 0.07 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=0.56..20.51 rows=1 width=401) (actual time=0.007..0.007 rows=1.00 loops=1)
	  Buffers: shared hit=8
	  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..82320.47 rows=4128 width=401) (actual time=0.007..0.007 rows=1.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = 'StockCounted'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
	        Rows Removed by Filter: 4
	        Index Searches: 1
	        Buffers: shared hit=8
```

### read as issued: query-cursor-walk @ postgres:external/metrics=off (generic plan) — measured 6.67 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND (event_tx, event_position) > ($1::xid8, $2) AND stream_context = $3 AND stream_purpose = $4 ORDER BY event_tx::xid8, event_position  LIMIT $5 OFFSET 0
	Query Parameters: $1 = '3087568', $2 = '2752000', $3 = 'inventory', $4 = 'default', $5 = '500'
	Limit  (cost=0.57..50198.32 rows=166840 width=401) (actual time=0.018..0.166 rows=500.00 loops=1)
	  Buffers: shared hit=30
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.57..501977.48 rows=1668398 width=401) (actual time=0.016..0.142 rows=500.00 loops=1)
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
	Index Scan using bm_n3tx9gechuj9_events_event_id_key on bm_n3tx9gechuj9_events  (cost=0.56..2.79 rows=1 width=393) (actual time=0.002..0.003 rows=1.00 loops=1)
	  Index Cond: (event_id = ($1)::uuid)
	  Index Searches: 1
	  Buffers: shared hit=5
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | query-by-entity-cold | thrpt | 1 | 9.630 | ops/ms | 2.6% | 9,630 | 577,822 | 0 |
| postgres:external/metrics=off | query-by-entity-hot | thrpt | 1 | 0.001 | ops/ms | 5.1% | 1 | 38 | 0 |
| postgres:external/metrics=off | query-by-id | thrpt | 1 | 50.268 | ops/ms | 2.0% | 50,268 | 3,016,188 | 0 |
| postgres:external/metrics=off | query-by-multi-tag | thrpt | 1 | 0.066 | ops/ms | 2.4% | 66 | 3,937 | 0 |
| postgres:external/metrics=off | query-by-or-groups | thrpt | 1 | 0.375 | ops/ms | 7.2% | 375 | 22,486 | 0 |
| postgres:external/metrics=off | query-by-tag-needle | thrpt | 1 | 3.867 | ops/ms | 1.6% | 3,867 | 232,058 | 0 |
| postgres:external/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.190 | ops/ms | 2.3% | 190 | 11,436 | 0 |
| postgres:external/metrics=off | query-by-type | thrpt | 1 | 0.997 | ops/ms | 2.6% | 997 | 59,856 | 0 |
| postgres:external/metrics=off | query-cursor-walk | thrpt | 1 | 0.150 | ops/ms | 6.6% | 150 | 8,998 | 0 |
| postgres:external/metrics=off | query-last-event | thrpt | 1 | 14.713 | ops/ms | 4.3% | 14,713 | 882,821 | 0 |
| postgres:external/metrics=off | query-stream-page | thrpt | 1 | 0.962 | ops/ms | 3.4% | 962 | 57,719 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
