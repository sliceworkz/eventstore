# Benchmark run: replay-throughput

What a read-model rebuild costs. The projector reads in batches of 500 carrying a cursor, so this measures the per-batch cost -- the query, the deserialization of 500 payloads, and the handler call -- rather than one heroic unbounded read.
Beside it, the two paging primitives a rebuild is made of: a cursor-carried walk and a plain page. If replay-batches is much worse than five times query-cursor-walk, the cost is in the projector rather than in the store, and that is worth knowing before tuning the wrong thing.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-04T11:11:48.988647685Z |
| finished | 2026-09-04T11:25:34.434824012Z |
| targets | inmem/metrics=off, postgres:18/metrics=off |
| corpus restore | no restore needed: every workload in this run is read-only |

> **Not suitable as a published baseline.**
>
> - measured against a Testcontainers PostgreSQL running stock defaults; publish from an external server whose configuration is deliberate
> - 1 measurement is too noisy to compare against anything, past the 10% this report calls uncomparable: query-cursor-walk (inmem/metrics=off, 1 thread) at 14%

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
| replay-batches | 1 | 0.298 ± 0.022 ops/ms | 0.083 ± 0.005 ops/ms (0.28x) |
| query-cursor-walk | 1 | 0.450 ± 0.065 ops/ms | 0.183 ± 0.010 ops/ms (0.41x) |
| query-stream-page | 1 | 3.297 ± 0.137 ops/ms | 0.950 ± 0.084 ops/ms (0.29x) |

Relative to **inmem/metrics=off**, higher is better. A ratio is only about the setting these targets differ in if it is larger than both error bars and survives running the profile with the targets in the opposite order: the first target is measured against a server the later ones then inherit warm, which is worth a few percent on its own.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

**The reconstructions are the weaker half of this section, including for the reads.** They inline as literals what the store binds as parameters, so a reconstruction can report an execution time the whole measured operation fits inside -- which is not a fast plan but a different one. Read the captured plans further down against the measurements, and these for the shape of the predicate.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.43..755.42 rows=500 width=278) (actual time=0.023..0.143 rows=500.00 loops=1)
  Buffers: shared hit=27
  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..86092.40 rows=57015 width=278) (actual time=0.022..0.120 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=27
Planning:
  Buffers: shared hit=105
Planning Time: 0.452 ms
Execution Time: 0.170 ms
```

### tag needle (~10 matches)

```
Sort  (cost=62.19..62.21 rows=8 width=278) (actual time=0.402..0.403 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=34 read=24
  ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=30.27..62.07 rows=8 width=278) (actual time=0.308..0.389 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=26 read=24
        ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..30.27 rows=8 width=0) (actual time=0.288..0.288 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=24 read=16
Planning:
  Buffers: shared hit=12
Planning Time: 0.103 ms
Execution Time: 0.456 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=398.63..398.87 rows=95 width=278) (actual time=2.543..2.577 rows=500.00 loops=1)
  Buffers: shared hit=175 read=865
  ->  Sort  (cost=398.63..398.87 rows=95 width=278) (actual time=2.542..2.555 rows=500.00 loops=1)
        Sort Key: event_tx, event_position
        Sort Method: quicksort  Memory: 442kB
        Buffers: shared hit=175 read=865
        ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=30.73..395.51 rows=95 width=278) (actual time=0.531..2.322 rows=1000.00 loops=1)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=1000
              Buffers: shared hit=175 read=865
              ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..30.71 rows=95 width=0) (actual time=0.461..0.462 rows=1000.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:swathe}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=39 read=1
Planning:
  Buffers: shared hit=3
Planning Time: 0.079 ms
Execution Time: 2.635 ms
```

### one entity's whole history (hot)

```
Sort  (cost=10688.29..10698.15 rows=3945 width=278) (actual time=6.302..6.469 rows=6876.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 1910kB
  Buffers: shared hit=1154 read=878
  ->  Bitmap Heap Scan on bm_2syjmtlnanmm_events  (cost=55.07..10452.66 rows=3945 width=278) (actual time=1.384..4.817 rows=6876.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=1993
        Buffers: shared hit=1154 read=878
        ->  Bitmap Index Scan on bm_2syjmtlnanmm_idx_events_stream_tags  (cost=0.00..54.09 rows=3946 width=0) (actual time=1.239..1.240 rows=6876.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Index Searches: 1
              Buffers: shared hit=35 read=4
Planning:
  Buffers: shared hit=3
Planning Time: 0.135 ms
Execution Time: 6.785 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.43..22.22 rows=1 width=278) (actual time=0.066..0.067 rows=1.00 loops=1)
  Buffers: shared hit=3 read=2
  ->  Index Scan Backward using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..85969.59 rows=3945 width=278) (actual time=0.064..0.065 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 20
        Index Searches: 1
        Buffers: shared hit=3 read=2
Planning:
  Buffers: shared hit=11
Planning Time: 0.335 ms
Execution Time: 0.094 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.43..795.77 rows=500 width=278) (actual time=0.016..0.134 rows=500.00 loops=1)
  Buffers: shared hit=27
  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..86007.02 rows=54069 width=278) (actual time=0.015..0.111 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('771'::xid8, '27500'::bigint)))
        Index Searches: 1
        Buffers: shared hit=27
Planning:
  Buffers: shared hit=14
Planning Time: 0.119 ms
Execution Time: 0.166 ms
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

### read as issued: replay-batches @ postgres:18/metrics=off (generic plan) — measured 12.11 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND (event_tx, event_position) > ($1::xid8, $2) AND (event_tx, event_position) <= ($3::xid8, $4) AND stream_context = $5 AND stream_purpose = $6 ORDER BY event_tx::xid8, event_position  LIMIT $7 OFFSET 0
	Query Parameters: $1 = '760', $2 = '5000', $3 = '760', $4 = '5000', $5 = 'inventory', $6 = 'default', $7 = '500'
	Limit  (cost=0.44..3204.58 rows=1111 width=366) (actual time=0.003..0.003 rows=0.00 loops=1)
	  Buffers: shared hit=3
	  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.44..32041.83 rows=11110 width=366) (actual time=0.003..0.003 rows=0.00 loops=1)
	        Index Cond: ((stream_context = ($5)::text) AND (stream_purpose = ($6)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW(($1)::xid8, $2)) AND (ROW(event_tx, event_position) <= ROW(($3)::xid8, $4)))
	        Index Searches: 1
	        Buffers: shared hit=3
```

### read as issued: query-cursor-walk @ postgres:18/metrics=off (generic plan) — measured 5.47 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND (event_tx, event_position) > ($1::xid8, $2) AND stream_context = $3 AND stream_purpose = $4 ORDER BY event_tx::xid8, event_position  LIMIT $5 OFFSET 0
	Query Parameters: $1 = '772', $2 = '29500', $3 = 'inventory', $4 = 'default', $5 = '500'
	Limit  (cost=0.43..7026.57 rows=3333 width=366) (actual time=0.021..0.274 rows=500.00 loops=1)
	  Buffers: shared hit=26
	  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..70261.74 rows=33330 width=366) (actual time=0.020..0.233 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($3)::text) AND (stream_purpose = ($4)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW(($1)::xid8, $2)))
	        Index Searches: 1
	        Buffers: shared hit=26
```

### read as issued: query-stream-page @ postgres:18/metrics=off (generic plan) — measured 1.05 ms/op

```
	Query Text: 	SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
		FROM bm_2syjmtlnanmm_events
		WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
	 AND stream_context = $1 AND stream_purpose = $2 ORDER BY event_tx::xid8, event_position  LIMIT $3 OFFSET 0
	Query Parameters: $1 = 'inventory', $2 = 'default', $3 = '500'
	Limit  (cost=0.43..8926.08 rows=9999 width=366) (actual time=0.046..0.406 rows=500.00 loops=1)
	  Buffers: shared hit=27
	  ->  Index Scan using bm_2syjmtlnanmm_idx_events_stream_position on bm_2syjmtlnanmm_events  (cost=0.43..89256.93 rows=99990 width=366) (actual time=0.044..0.331 rows=500.00 loops=1)
	        Index Cond: ((stream_context = ($1)::text) AND (stream_purpose = ($2)::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
	        Index Searches: 1
	        Buffers: shared hit=27
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| inmem/metrics=off | query-cursor-walk | thrpt | 1 | 0.450 | ops/ms | 14.5% | 450 | 27,003 | 0 |
| inmem/metrics=off | query-stream-page | thrpt | 1 | 3.297 | ops/ms | 4.1% | 3,297 | 197,812 | 0 |
| inmem/metrics=off | replay-batches | thrpt | 1 | 0.298 | ops/ms | 7.4% | 298 | 17,863 | 0 |
| postgres:18/metrics=off | query-cursor-walk | thrpt | 1 | 0.183 | ops/ms | 5.5% | 183 | 10,966 | 0 |
| postgres:18/metrics=off | query-stream-page | thrpt | 1 | 0.950 | ops/ms | 8.9% | 950 | 57,023 | 0 |
| postgres:18/metrics=off | replay-batches | thrpt | 1 | 0.083 | ops/ms | 6.0% | 83 | 4,960 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
