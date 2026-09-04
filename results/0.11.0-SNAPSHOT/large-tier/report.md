# Benchmark run: large-tier

The read shapes at ten million events, against an external server. The tier the whole corpus-reuse machinery exists for: provisioning costs minutes and is then measured against for days, so run `provision` first and let the measurement runs find it already there.
What to look for is not the absolute numbers but their ratio to read-shapes at a hundred thousand. A needle tag query should cost about the same at both volumes -- the index does the work, and the store is a hundred times larger. If it scales with volume the query is not using the index, and the report's captured plan is where to look. The two shapes that legitimately do scale are the swathe (which returns ~1% of the store, so a hundred times the store is a hundred times the events) and the hot entity (a hundred thousand entities hold more history each); read those as returning more rows, not as losing an index.
Reads only, and its cadence is read-shapes' exactly -- the profile it is a ratio against, and a control measured differently is not one. Appends at this tier are large-tier-writes, which is a separate profile because it cannot share these settings: an append workload here grows the store for a whole trial, so its measurement budget is a fixed number of events rather than a length of time.
Deliberately external rather than containerised. A Testcontainers PostgreSQL runs stock defaults -- 128 MB of shared_buffers, untuned WAL -- and at this size those defaults are most of what would be measured. The publish step refuses a containerised run as a baseline for exactly that reason.
Set the schema mode to NONE if a DBA owns the schema on that server; ENSURE is right when the suite is allowed to create its own corpus tables, which is the usual arrangement for a benchmark host. The btree_gin extension has to exist -- creating it needs CREATE on the database, not on the schema.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-04T11:25:35.768359535Z |
| finished | 2026-09-04T11:43:52.818313898Z |
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

**The reconstructions are the weaker half of this section, including for the reads.** They inline as literals what the store binds as parameters, so a reconstruction can report an execution time the whole measured operation fits inside -- which is not a fast plan but a different one. Read the captured plans further down against the measurements, and these for the shape of the predicate.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.56..225.08 rows=500 width=313) (actual time=0.036..0.296 rows=500.00 loops=1)
  Buffers: shared hit=3 read=29
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2465305.99 rows=5490276 width=313) (actual time=0.035..0.250 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=3 read=29
Planning:
  Buffers: shared hit=54 read=16
Planning Time: 0.580 ms
Execution Time: 0.345 ms
```

### tag needle (~10 matches)

```
Sort  (cost=2966.91..2968.74 rows=732 width=313) (actual time=0.687..0.688 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=44 read=42
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=72.39..2932.08 rows=732 width=313) (actual time=0.547..0.668 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=36 read=42
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..72.20 rows=732 width=0) (actual time=0.522..0.523 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=35 read=33
Planning:
  Buffers: shared hit=11 read=1
Planning Time: 0.153 ms
Execution Time: 0.745 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..19305.63 rows=500 width=313) (actual time=0.027..8.011 rows=500.00 loops=1)
  Buffers: shared hit=92 read=1352
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2451897.82 rows=63504 width=313) (actual time=0.026..7.975 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=92 read=1352
Planning:
  Buffers: shared hit=3
Planning Time: 0.102 ms
Execution Time: 8.047 ms
```

### one entity's whole history (hot) — **lossy bitmap**, **sorts on disk**, **JIT 206ms**

> the bitmap outgrew work_mem, so whole pages were marked instead of rows and every row on them had to be re-checked. Raising work_mem for this statement removes the recheck entirely.
> the sort did not fit in work_mem and spilled to disk. Either the read returns more rows than it needs -- a limit or a savepoint -- or work_mem is too small for the size of result this query is meant to produce.
> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
Gather Merge  (cost=500994.70..530131.58 rows=250174 width=313) (actual time=514.898..575.028 rows=455092.00 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=3084 read=185297, temp read=14111 written=14141
  ->  Sort  (cost=499994.67..500255.27 rows=104239 width=313) (actual time=505.469..519.664 rows=151697.33 loops=3)
        Sort Key: event_tx, event_position
        Sort Method: external merge  Disk: 36896kB
        Buffers: shared hit=3084 read=185297, temp read=14111 written=14141
        Worker 0:  Sort Method: external merge  Disk: 35616kB
        Worker 1:  Sort Method: external merge  Disk: 40376kB
        ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1757.45..475983.59 rows=104239 width=313) (actual time=172.562..449.599 rows=151697.33 loops=3)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Rows Removed by Index Recheck: 1070154
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=17593 lossy=42757
              Buffers: shared hit=3050 read=185297
              Worker 0:  Heap Blocks: exact=16177 lossy=42052
              Worker 1:  Heap Blocks: exact=19035 lossy=46983
              ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1694.90 rows=250197 width=0) (actual time=106.395..106.395 rows=455094.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=2000 read=1618
Planning:
  Buffers: shared hit=3
Planning Time: 0.102 ms
JIT:
  Functions: 18
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.769 ms (Deform 0.370 ms), Inlining 76.977 ms, Optimization 80.685 ms, Emission 47.399 ms, Total 205.830 ms
Execution Time: 588.791 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..10.37 rows=1 width=313) (actual time=0.037..0.037 rows=1.00 loops=1)
  Buffers: shared hit=1 read=7
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2452831.17 rows=250174 width=313) (actual time=0.036..0.037 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared hit=1 read=7
Planning:
  Buffers: shared hit=2 read=9
Planning Time: 0.203 ms
Execution Time: 0.057 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..274.27 rows=500 width=313) (actual time=0.020..0.168 rows=500.00 loops=1)
  Buffers: shared hit=3 read=29
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2177346.62 rows=3977591 width=313) (actual time=0.019..0.145 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('3087567'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=3 read=29
Planning:
  Buffers: shared hit=7 read=7
Planning Time: 0.134 ms
Execution Time: 0.203 ms
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

### read as issued: query-stream-page @ postgres:external/metrics=off (generic plan) — measured 1.04 ms/op — **JIT 4ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 ORDER BY event_tx::xid8, event_position  LIMIT $3 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = '500'
	Limit  (cost=0.56..236839.18 rows=499813 width=401) (actual time=3.988..4.221 rows=500.00 loops=1)
	  Buffers: shared hit=32
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2368385.31 rows=4998127 width=401) (actual time=0.046..0.235 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=32
	JIT:
	  Functions: 9
	  Options: Inlining false, Optimization false, Expressions true, Deforming true
	  Timing: Generation 0.534 ms (Deform 0.194 ms), Inlining 0.000 ms, Optimization 0.234 ms, Emission 3.706 ms, Total 4.474 ms
```

### read as issued: query-by-type @ postgres:external/metrics=off (generic plan) — measured 1.00 ms/op — **JIT 4ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3))) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = '500'
	Limit  (cost=0.56..109735.12 rows=45438 width=401) (actual time=3.645..3.935 rows=500.00 loops=1)
	  Buffers: shared hit=54
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..1097334.04 rows=454375 width=401) (actual time=0.052..0.311 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=54
	JIT:
	  Functions: 10
	  Options: Inlining false, Optimization false, Expressions true, Deforming true
	  Timing: Generation 0.490 ms (Deform 0.176 ms), Inlining 0.000 ms, Optimization 0.197 ms, Emission 3.393 ms, Total 4.080 ms
```

### read as issued: query-by-tag-needle @ postgres:external/metrics=off (generic plan) — measured 0.26 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:needle'
	Gather Merge  (cost=84084.48..86995.10 rows=24991 width=401) (actual time=13.657..15.760 rows=10.00 loops=1)
	  Workers Planned: 2
	  Workers Launched: 2
	  Buffers: shared hit=152
	  ->  Sort  (cost=83084.46..83110.49 rows=10413 width=401) (actual time=0.218..0.219 rows=3.33 loops=3)
	        Sort Key: event_tx, event_position
	        Sort Method: quicksort  Memory: 28kB
	        Buffers: shared hit=152
	        Worker 0:  Sort Method: quicksort  Memory: 25kB
	        Worker 1:  Sort Method: quicksort  Memory: 25kB
	        ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=236.88..80464.59 rows=10413 width=401) (actual time=0.189..0.193 rows=3.33 loops=3)
	              Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=10
	              Buffers: shared hit=118
	              ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..230.63 rows=24993 width=0) (actual time=0.445..0.445 rows=10.00 loops=1)
	                    Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                    Index Searches: 1
	                    Buffers: shared hit=68
```

### read as issued: query-by-tag-needle @ postgres:external/metrics=off (custom plan, first executions only) — measured 0.26 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:needle'
	Sort  (cost=2966.91..2968.74 rows=732 width=401) (actual time=0.199..0.199 rows=10.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 28kB
	  Buffers: shared hit=78
	  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=72.39..2932.08 rows=732 width=401) (actual time=0.193..0.196 rows=10.00 loops=1)
	        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=10
	        Buffers: shared hit=78
	        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..72.20 rows=732 width=0) (actual time=0.190..0.190 rows=10.00 loops=1)
	              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
	              Index Searches: 1
	              Buffers: shared hit=68
```

### read as issued: query-by-tag-swathe @ postgres:external/metrics=off (generic plan) — measured 5.10 ms/op — **lossy bitmap**

> the bitmap outgrew work_mem, so whole pages were marked instead of rows and every row on them had to be re-checked. Raising work_mem for this statement removes the recheck entirely.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:swathe', $4 = '500'
	Limit  (cost=84084.48..84375.53 rows=2499 width=401) (actual time=219.507..229.331 rows=500.00 loops=1)
	  Buffers: shared hit=2364 read=101587
	  ->  Gather Merge  (cost=84084.48..86995.10 rows=24991 width=401) (actual time=219.504..229.300 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=2364 read=101587
	        ->  Sort  (cost=83084.46..83110.49 rows=10413 width=401) (actual time=212.713..212.724 rows=301.00 loops=3)
	              Sort Key: event_tx, event_position
	              Sort Method: top-N heapsort  Memory: 365kB
	              Buffers: shared hit=2364 read=101587
	              Worker 0:  Sort Method: top-N heapsort  Memory: 468kB
	              Worker 1:  Sort Method: top-N heapsort  Memory: 496kB
	              ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=236.88..80464.59 rows=10413 width=401) (actual time=44.166..205.012 rows=33333.33 loops=3)
	                    Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                    Rows Removed by Index Recheck: 570493
	                    Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	                    Heap Blocks: exact=11927 lossy=22902
	                    Buffers: shared hit=2335 read=101582
	                    Worker 0:  Heap Blocks: exact=11929 lossy=22199
	                    Worker 1:  Heap Blocks: exact=9813 lossy=21226
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..230.63 rows=24993 width=0) (actual time=47.138..47.138 rows=100000.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                          Index Searches: 1
	                          Buffers: shared hit=2213 read=1576
```

### read as issued: query-by-tag-swathe @ postgres:external/metrics=off (custom plan, first executions only) — measured 5.10 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $4 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'campaign:swathe', $4 = '500'
	Limit  (cost=0.56..19305.63 rows=500 width=401) (actual time=0.032..6.894 rows=500.00 loops=1)
	  Buffers: shared hit=1444
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2451897.82 rows=63504 width=401) (actual time=0.031..6.865 rows=500.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{campaign:swathe}'::text[])
	        Rows Removed by Filter: 26973
	        Index Searches: 1
	        Buffers: shared hit=1444
```

### read as issued: query-by-entity-hot @ postgres:external/metrics=off (generic plan) — measured 1633.47 ms/op — **lossy bitmap**, **sorts on disk**

> the bitmap outgrew work_mem, so whole pages were marked instead of rows and every row on them had to be re-checked. Raising work_mem for this statement removes the recheck entirely.
> the sort did not fit in work_mem and spilled to disk. Either the read returns more rows than it needs -- a limit or a savepoint -- or work_mem is too small for the size of result this query is meant to produce.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-000000'
	Gather Merge  (cost=84084.48..86995.10 rows=24991 width=401) (actual time=462.586..534.928 rows=455092.00 loops=1)
	  Workers Planned: 2
	  Workers Launched: 2
	  Buffers: shared hit=2117 read=186264, temp read=16147 written=16180
	  ->  Sort  (cost=83084.46..83110.49 rows=10413 width=401) (actual time=452.315..469.189 rows=151697.33 loops=3)
	        Sort Key: event_tx, event_position
	        Sort Method: external merge  Disk: 42912kB
	        Buffers: shared hit=2117 read=186264, temp read=16147 written=16180
	        Worker 0:  Sort Method: external merge  Disk: 43160kB
	        Worker 1:  Sort Method: external merge  Disk: 43104kB
	        ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=236.88..80464.59 rows=10413 width=401) (actual time=95.656..389.626 rows=151697.33 loops=3)
	              Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Rows Removed by Index Recheck: 1070154
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=17892 lossy=43604
	              Buffers: shared hit=2088 read=186259
	              Worker 0:  Heap Blocks: exact=17557 lossy=44055
	              Worker 1:  Heap Blocks: exact=17356 lossy=44133
	              ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..230.63 rows=24993 width=0) (actual time=99.775..99.776 rows=455094.00 loops=1)
	                    Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                    Index Searches: 1
	                    Buffers: shared hit=1966 read=1652
```

### read as issued: query-by-entity-cold @ postgres:external/metrics=off (generic plan) — measured 0.12 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-094269'
	Gather Merge  (cost=84084.48..86995.10 rows=24991 width=401) (actual time=11.038..12.456 rows=1.00 loops=1)
	  Workers Planned: 2
	  Workers Launched: 2
	  Buffers: shared hit=98
	  ->  Sort  (cost=83084.46..83110.49 rows=10413 width=401) (actual time=0.121..0.122 rows=0.33 loops=3)
	        Sort Key: event_tx, event_position
	        Sort Method: quicksort  Memory: 25kB
	        Buffers: shared hit=98
	        Worker 0:  Sort Method: quicksort  Memory: 25kB
	        Worker 1:  Sort Method: quicksort  Memory: 25kB
	        ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=236.88..80464.59 rows=10413 width=401) (actual time=0.105..0.105 rows=0.33 loops=3)
	              Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	              Heap Blocks: exact=1
	              Buffers: shared hit=64
	              ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..230.63 rows=24993 width=0) (actual time=0.097..0.097 rows=1.00 loops=1)
	                    Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text]))
	                    Index Searches: 1
	                    Buffers: shared hit=23
```

### read as issued: query-by-entity-cold @ postgres:external/metrics=off (custom plan, first executions only) — measured 0.12 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3]::text[])) ORDER BY event_tx::xid8, event_position
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'sku:SKU-094269'
	Sort  (cost=2966.91..2968.74 rows=732 width=401) (actual time=0.068..0.068 rows=1.00 loops=1)
	  Sort Key: event_tx, event_position
	  Sort Method: quicksort  Memory: 25kB
	  Buffers: shared hit=24
	  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=72.39..2932.08 rows=732 width=401) (actual time=0.066..0.066 rows=1.00 loops=1)
	        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-094269}'::text[]))
	        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	        Heap Blocks: exact=1
	        Buffers: shared hit=24
	        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..72.20 rows=732 width=0) (actual time=0.063..0.063 rows=1.00 loops=1)
	              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-094269}'::text[]))
	              Index Searches: 1
	              Buffers: shared hit=23
```

### read as issued: query-by-multi-tag @ postgres:external/metrics=off (generic plan) — measured 14.97 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3, $4, $5]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $6 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'channel:web', $4 = 'country:BE', $5 = 'sku:SKU-000000', $6 = '500'
	Limit  (cost=84084.48..84375.53 rows=2499 width=401) (actual time=125.428..135.062 rows=500.00 loops=1)
	  Buffers: shared hit=2676 read=37144
	  ->  Gather Merge  (cost=84084.48..86995.10 rows=24991 width=401) (actual time=125.427..135.034 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=2676 read=37144
	        ->  Sort  (cost=83084.46..83110.49 rows=10413 width=401) (actual time=119.270..119.285 rows=299.00 loops=3)
	              Sort Key: event_tx, event_position
	              Sort Method: top-N heapsort  Memory: 509kB
	              Buffers: shared hit=2676 read=37144
	              Worker 0:  Sort Method: top-N heapsort  Memory: 318kB
	              Worker 1:  Sort Method: top-N heapsort  Memory: 343kB
	              ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=236.88..80464.59 rows=10413 width=401) (actual time=92.756..116.013 rows=12723.67 loops=3)
	                    Recheck Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	                    Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
	                    Heap Blocks: exact=15488
	                    Buffers: shared hit=2647 read=37139
	                    Worker 0:  Heap Blocks: exact=9167
	                    Worker 1:  Heap Blocks: exact=10293
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..230.63 rows=24993 width=0) (actual time=95.077..95.077 rows=38171.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tags @> ARRAY[($3)::text, ($4)::text, ($5)::text]))
	                          Index Searches: 1
	                          Buffers: shared hit=2610 read=2188
```

### read as issued: query-by-multi-tag @ postgres:external/metrics=off (custom plan, first executions only) — measured 14.97 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_tags @> ARRAY[$3, $4, $5]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $6 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'channel:web', $4 = 'country:BE', $5 = 'sku:SKU-000000', $6 = '500'
	Limit  (cost=0.56..58751.68 rows=500 width=401) (actual time=0.014..12.887 rows=500.00 loops=1)
	  Buffers: shared hit=3959
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2451684.62 rows=20865 width=401) (actual time=0.013..12.864 rows=500.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{channel:web,country:BE,sku:SKU-000000}'::text[])
	        Rows Removed by Filter: 76022
	        Index Searches: 1
	        Buffers: shared hit=3959
```

### read as issued: query-by-or-groups @ postgres:external/metrics=off (generic plan) — measured 2.38 ms/op — **JIT 12ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=1000.59..237928.62 rows=2268 width=401) (actual time=18.043..21.353 rows=500.00 loops=1)
	  Buffers: shared hit=582
	  ->  Gather Merge  (cost=1000.59..2369967.51 rows=22677 width=401) (actual time=14.830..18.113 rows=500.00 loops=1)
	        Workers Planned: 2
	        Workers Launched: 2
	        Buffers: shared hit=582
	        ->  Parallel Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2366350.00 rows=9449 width=401) (actual time=2.532..3.750 rows=202.00 loops=3)
	              Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Filter: (((event_type = ANY (ARRAY[($3)::text, ($4)::text])) AND (event_tags @> ARRAY[($5)::text])) OR ((event_type = ANY (ARRAY[($6)::text, ($7)::text])) AND (event_tags @> ARRAY[($8)::text])) OR ((event_type = ANY (ARRAY[($9)::text, ($10)::text])) AND (event_tags @> ARRAY[($11)::text])) OR ((event_type = ANY (ARRAY[($12)::text, ($13)::text])) AND (event_tags @> ARRAY[($14)::text])) OR ((event_type = ANY (ARRAY[($15)::text, ($16)::text])) AND (event_tags @> ARRAY[($17)::text])))
	              Rows Removed by Filter: 1425
	              Index Searches: 1
	              Buffers: shared hit=582
	JIT:
	  Functions: 29
	  Options: Inlining false, Optimization false, Expressions true, Deforming true
	  Timing: Generation 1.364 ms (Deform 0.457 ms), Inlining 0.000 ms, Optimization 0.578 ms, Emission 9.938 ms, Total 11.880 ms
```

### read as issued: query-by-or-groups @ postgres:external/metrics=off (custom plan, first executions only) — measured 2.38 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3, $4) AND event_tags @> ARRAY[$5]::text[]) OR (event_type IN ($6, $7) AND event_tags @> ARRAY[$8]::text[]) OR (event_type IN ($9, $10) AND event_tags @> ARRAY[$11]::text[]) OR (event_type IN ($12, $13) AND event_tags @> ARRAY[$14]::text[]) OR (event_type IN ($15, $16) AND event_tags @> ARRAY[$17]::text[])) ORDER BY event_tx::xid8, event_position  LIMIT $18 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockReserved', $4 = 'StockPicked', $5 = 'sku:SKU-000000', $6 = 'StockReserved', $7 = 'StockPicked', $8 = 'sku:SKU-000001', $9 = 'StockReserved', $10 = 'StockPicked', $11 = 'sku:SKU-000002', $12 = 'StockReserved', $13 = 'StockPicked', $14 = 'sku:SKU-000003', $15 = 'StockReserved', $16 = 'StockPicked', $17 = 'sku:SKU-000004', $18 = '500'
	Limit  (cost=0.56..5876.14 rows=500 width=401) (actual time=0.010..1.209 rows=500.00 loops=1)
	  Buffers: shared hit=219
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2521281.53 rows=214556 width=401) (actual time=0.010..1.186 rows=500.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: ((event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-000001}'::text[]) OR (event_tags @> '{sku:SKU-000002}'::text[]) OR (event_tags @> '{sku:SKU-000003}'::text[]) OR (event_tags @> '{sku:SKU-000004}'::text[])))
	        Rows Removed by Filter: 3494
	        Index Searches: 1
	        Buffers: shared hit=219
```

### read as issued: query-last-event @ postgres:external/metrics=off (generic plan) — measured 0.06 ms/op — **lossy bitmap**

> the bitmap outgrew work_mem, so whole pages were marked instead of rows and every row on them had to be re-checked. Raising work_mem for this statement removes the recheck entirely.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=42072.16..42072.73 rows=227 width=401) (actual time=409.364..409.367 rows=1.00 loops=1)
	  Buffers: shared read=88300
	  ->  Sort  (cost=42072.16..42077.84 rows=2272 width=401) (actual time=409.362..409.364 rows=1.00 loops=1)
	        Sort Key: event_tx DESC, event_position DESC
	        Sort Method: top-N heapsort  Memory: 26kB
	        Buffers: shared read=88300
	        ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=33295.60..41945.50 rows=2272 width=401) (actual time=49.398..406.928 rows=13529.00 loops=1)
	              Recheck Cond: ((event_tags @> ARRAY[($4)::text]) AND (stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	              Rows Removed by Index Recheck: 1988036
	              Heap Blocks: exact=12261 lossy=73491
	              Buffers: shared read=88300
	              ->  BitmapAnd  (cost=33295.60..33295.60 rows=2272 width=0) (actual time=47.930..47.931 rows=0.00 loops=1)
	                    Buffers: shared read=2548
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_tags  (cost=0.00..354.02 rows=49986 width=0) (actual time=27.233..27.233 rows=455094.00 loops=1)
	                          Index Cond: (event_tags @> ARRAY[($4)::text])
	                          Index Searches: 1
	                          Buffers: shared read=113
	                    ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_type_position  (cost=0.00..32940.19 rows=454375 width=0) (actual time=15.440..15.440 rows=165193.00 loops=1)
	                          Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_type = ($3)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	                          Index Searches: 1
	                          Buffers: shared read=2435
```

### read as issued: query-last-event @ postgres:external/metrics=off (custom plan, first executions only) — measured 0.06 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 AND ((event_type IN ($3) AND event_tags @> ARRAY[$4]::text[])) ORDER BY event_tx::xid8 DESC, event_position DESC LIMIT $5 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = 'StockCounted', $4 = 'sku:SKU-000000', $5 = '1'
	Limit  (cost=0.56..72.32 rows=1 width=401) (actual time=0.007..0.007 rows=1.00 loops=1)
	  Buffers: shared hit=8
	  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..321904.11 rows=4486 width=401) (actual time=0.007..0.007 rows=1.00 loops=1)
	        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = 'StockCounted'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
	        Rows Removed by Filter: 4
	        Index Searches: 1
	        Buffers: shared hit=8
```

### read as issued: query-cursor-walk @ postgres:external/metrics=off (generic plan) — measured 5.26 ms/op — **JIT 2ms**

> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.
> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND (event_tx, event_position) > ($1::xid8, $2) AND stream_context = $3 AND stream_purpose = $4 ORDER BY event_tx::xid8, event_position  LIMIT $5 OFFSET 0
	Query Parameters: $1 = '3087568', $2 = '2752000', $3 = 'inventory', $4 = 'default', $5 = '500'
	Limit  (cost=0.57..171633.26 rows=166604 width=401) (actual time=1.876..2.013 rows=500.00 loops=1)
	  Buffers: shared hit=30
	  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.57..1716329.50 rows=1666042 width=401) (actual time=0.017..0.132 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($3)::text) AND (stream_purpose = ($4)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW(($1)::xid8, $2)))
	        Index Searches: 1
	        Buffers: shared hit=30
	JIT:
	  Functions: 11
	  Options: Inlining false, Optimization false, Expressions true, Deforming true
	  Timing: Generation 0.217 ms (Deform 0.067 ms), Inlining 0.000 ms, Optimization 0.110 ms, Emission 1.750 ms, Total 2.077 ms
```

### read as issued: query-by-id @ postgres:external/metrics=off (generic plan) — measured 0.02 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_n3tx9gechuj9_events
		WHERE event_id = $1::uuid

	Query Parameters: $1 = '018cc251-f400-74eb-8412-04a7d99e38f3'
	Index Scan using bm_n3tx9gechuj9_events_event_id_key on bm_n3tx9gechuj9_events  (cost=0.56..8.59 rows=1 width=393) (actual time=0.002..0.002 rows=1.00 loops=1)
	  Index Cond: (event_id = ($1)::uuid)
	  Index Searches: 1
	  Buffers: shared hit=5
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | query-by-entity-cold | thrpt | 1 | 8.520 | ops/ms | 3.9% | 8,520 | 511,242 | 0 |
| postgres:external/metrics=off | query-by-entity-hot | thrpt | 1 | 0.001 | ops/ms | 5.1% | 1 | 45 | 0 |
| postgres:external/metrics=off | query-by-id | thrpt | 1 | 57.647 | ops/ms | 2.9% | 57,647 | 3,458,898 | 0 |
| postgres:external/metrics=off | query-by-multi-tag | thrpt | 1 | 0.067 | ops/ms | 3.8% | 67 | 4,015 | 0 |
| postgres:external/metrics=off | query-by-or-groups | thrpt | 1 | 0.421 | ops/ms | 4.9% | 421 | 25,261 | 0 |
| postgres:external/metrics=off | query-by-tag-needle | thrpt | 1 | 3.846 | ops/ms | 2.3% | 3,846 | 230,763 | 0 |
| postgres:external/metrics=off | query-by-tag-swathe | thrpt | 1 | 0.196 | ops/ms | 1.8% | 196 | 11,778 | 0 |
| postgres:external/metrics=off | query-by-type | thrpt | 1 | 0.999 | ops/ms | 1.6% | 999 | 59,952 | 0 |
| postgres:external/metrics=off | query-cursor-walk | thrpt | 1 | 0.190 | ops/ms | 2.2% | 190 | 11,422 | 0 |
| postgres:external/metrics=off | query-last-event | thrpt | 1 | 17.708 | ops/ms | 5.4% | 17,708 | 1,062,541 | 0 |
| postgres:external/metrics=off | query-stream-page | thrpt | 1 | 0.965 | ops/ms | 3.5% | 965 | 57,920 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
