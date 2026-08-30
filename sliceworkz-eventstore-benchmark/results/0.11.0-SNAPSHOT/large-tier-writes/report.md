# Benchmark run: large-tier-writes

What an append costs at ten million events, against the same corpus large-tier reads. Split out from that profile rather than sharing it, because the two cannot be measured the same way: a read leaves the store alone and can be given as long as it needs, while an append grows it, and above a million events the corpus is restored once per trial rather than once per iteration -- a template copy of ten million rows between iterations would cost far more than the drift it prevents.
So an append workload here has a budget denominated in events, not seconds, and the budget does not grow with the tier. That is the whole reason this profile declares a drift cap of its own. At two percent, the suite-wide default, ten million events allow 200.000 appends per trial: about eighty seconds at one writer, and under ten at eight -- less than a single JMH iteration. No cadence fits that, and the run that discovered it died at 72% having thrown away forty minutes of clean reads.
Ten percent is a judgement, and it is about the label rather than the measurement. What a fraction of growth threatens is the claim "measured over ten million events"; it does not change a B-tree's depth or a GIN index's shape, so an append measured while the store went from 10.0M to 10.9M is measuring the same operation throughout. The cap is recorded in the manifest and printed in the report beside the drift, so a reader comparing this against a run measured under the default two percent can see that the allowance was widened deliberately.
If a fast enough server breaches ten percent anyway, shorten the iterations -- do not widen the cap further. Past roughly a tenth the corpus stops being the one the manifest names.
One caveat before reading any of these against the medium tier: at a hundred thousand entities the per-iteration boundary cache almost never gets a hit, so nearly every append-type-and-tag invocation here is a boundary probe plus the append, where the medium tier amortizes the probe over a whole iteration. Compare decide-then-append across tiers -- it always includes its read -- and treat this tier's append-type-and-tag as a different operation mix rather than the same workload at more volume.
Budget more wall clock than the estimate promises. The estimator counts iteration time only, and at this tier the restore dominates it: taking the template costs seconds, but handing the store back at the end of each trial is a truncate-and-copy of ten million rows, about two and a half minutes, once per trial. Eighteen trials of that is most of the run.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-08-30T18:03:16.703937584Z |
| finished | 2026-08-30T19:16:01.434873844Z |
| targets | postgres:external/metrics=off |
| corpus restore | restored once per trial; intra-trial drift measured |
| store drift | 1.14% during the run, against the 10% this profile allows |

> **Not suitable as a published baseline.**
>
> - 3 measurements are too noisy to compare against anything, past the 10% this report calls uncomparable: append-type-and-tag (postgres:external/metrics=off, 1 thread) at 121%, append-type-and-tag (postgres:external/metrics=off, 8 threads) at 55%, decide-then-append (postgres:external/metrics=off, 8 threads) at 24%

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

## What this run says

### What the DCB check costs

| append | throughput | relative |
|---|---|---|
| no criteria | 3.131 ± 0.067 ops/ms | 1.00x |
| one type set and one tag | 0.026 ± 0.031 ops/ms | 122.13x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 3.131 ± 0.067 ops/ms | 3,131 | 0.0% |
| append-none | 8 | 4.689 ± 0.112 ops/ms | 4,689 | 0.0% |
| append-type-and-tag | 1 | 0.026 ± 0.031 ops/ms | 26 | 0.0% |
| append-type-and-tag | 8 | 0.165 ± 0.091 ops/ms | 165 | 0.0% |
| decide-then-append | 1 | 0.001 ± 0.000 ops/ms | 1 | 0.0% |
| decide-then-append | 8 | 0.049 ± 0.012 ops/ms | 49 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. This run captured none of the store's own statements, so every plan here is a reconstruction.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.56..112.61 rows=500 width=315) (actual time=0.043..0.286 rows=500.00 loops=1)
  Buffers: shared hit=1 read=26
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..1212069.07 rows=5408957 width=315) (actual time=0.042..0.249 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=1 read=26
Planning:
  Buffers: shared hit=61 read=9
Planning Time: 0.422 ms
Execution Time: 0.326 ms
```

### tag needle (~10 matches)

```
Sort  (cost=2911.20..2913.00 rows=721 width=315) (actual time=0.407..0.408 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=42 read=36
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=59.58..2876.97 rows=721 width=315) (actual time=0.286..0.395 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=34 read=36
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..59.40 rows=721 width=0) (actual time=0.269..0.269 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=33 read=27
Planning:
  Buffers: shared hit=11 read=1
Planning Time: 0.110 ms
Execution Time: 0.442 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..11624.77 rows=500 width=315) (actual time=0.016..5.949 rows=500.00 loops=1)
  Buffers: shared hit=80 read=1199
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..1198804.50 rows=51565 width=315) (actual time=0.015..5.925 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=80 read=1199
Planning:
  Buffers: shared hit=3
Planning Time: 0.057 ms
Execution Time: 5.974 ms
```

### one entity's whole history (hot)

```
Gather Merge  (cost=496929.12..525466.44 rows=245026 width=315) (actual time=469.035..523.779 rows=455092.00 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=3035 read=185291 written=31, temp read=14104 written=14135
  ->  Sort  (cost=495929.10..496184.33 rows=102094 width=315) (actual time=461.825..473.461 rows=151697.33 loops=3)
        Sort Key: event_tx, event_position
        Sort Method: external merge  Disk: 36856kB
        Buffers: shared hit=3035 read=185291 written=31, temp read=14104 written=14135
        Worker 0:  Sort Method: external merge  Disk: 35672kB
        Worker 1:  Sort Method: external merge  Disk: 40304kB
        ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1709.42..472427.11 rows=102094 width=315) (actual time=183.838..413.045 rows=151697.33 loops=3)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Rows Removed by Index Recheck: 1070179
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=18377 lossy=42003
              Buffers: shared hit=3001 read=185291 written=31
              Worker 0:  Heap Blocks: exact=16006 lossy=42419
              Worker 1:  Heap Blocks: exact=18425 lossy=47365
              ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1648.16 rows=245049 width=0) (actual time=104.152..104.153 rows=455092.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=1947 read=1618
Planning:
  Buffers: shared hit=3
Planning Time: 0.093 ms
JIT:
  Functions: 18
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.752 ms (Deform 0.360 ms), Inlining 105.564 ms, Optimization 87.525 ms, Emission 45.813 ms, Total 239.655 ms
Execution Time: 536.557 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..5.46 rows=1 width=315) (actual time=0.031..0.031 rows=1.00 loops=1)
  Buffers: shared hit=1 read=5
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..1199771.81 rows=245026 width=315) (actual time=0.030..0.030 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared hit=1 read=5
Planning:
  Buffers: shared hit=2 read=9
Planning Time: 0.195 ms
Execution Time: 0.045 ms
```

### cursor page from the midpoint (limit 500)

```
Limit  (cost=0.56..139.99 rows=500 width=315) (actual time=0.015..0.153 rows=500.00 loops=1)
  Buffers: shared hit=3 read=26
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..1098661.11 rows=3939910 width=315) (actual time=0.015..0.130 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('1504560'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=3 read=26
Planning:
  Buffers: shared hit=7 read=7
Planning Time: 0.083 ms
Execution Time: 0.178 ms
```

> **The plans below do not describe the store's own execution.** They inline the tag arrays 
> and the cursor as literals, which is what PostgreSQL sees when it builds a *custom* plan; 
> the store binds them as JDBC parameters and re-uses the statement, so what it actually runs 
> is whichever of the custom and generic plans the server settled on -- and for several of 
> these shapes that is the generic one, which is a different plan entirely. Read these as the 
> shape of the predicate. This run captured none of the store's own 
> *append* statements, so there is nothing here to check these against: read the shapes, and 
> take the plan a measurement actually ran on from a `jmh` run over the same corpus.

### DCB check: event types only, no tag (append-types) -- boundary 100,000 events back

```
Limit  (cost=0.56..0.63 rows=1 width=4) (actual time=0.033..0.033 rows=1.00 loops=1)
  Buffers: shared hit=1 read=4
  ->  Index Only Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..72243.32 rows=991448 width=4) (actual time=0.032..0.032 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('1505886'::xid8, '5400000'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=1 read=4
Planning:
  Buffers: shared read=2
Planning Time: 0.130 ms
Execution Time: 0.045 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 100,000 events back — **sequential scan**

```
Limit  (cost=0.00..11.68 rows=1 width=4) (actual time=583.678..583.680 rows=1.00 loops=1)
  Buffers: shared read=201360
  ->  Seq Scan on bm_n3tx9gechuj9_events  (cost=0.00..707907.58 rows=60586 width=4) (actual time=583.678..583.678 rows=1.00 loops=1)
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) AND (stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('1505886'::xid8, '5400000'::bigint)))
        Rows Removed by Filter: 5400011
        Buffers: shared read=201360
Planning:
  Buffers: shared hit=2 read=1
Planning Time: 0.052 ms
Execution Time: 583.718 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 100,000 events back — **sequential scan**

```
Limit  (cost=0.00..103.10 rows=1 width=4) (actual time=0.026..0.026 rows=1.00 loops=1)
  Buffers: shared hit=9
  ->  Seq Scan on bm_n3tx9gechuj9_events  (cost=0.00..707907.58 rows=6866 width=4) (actual time=0.025..0.025 rows=1.00 loops=1)
        Filter: ((event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]) AND (stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('1505886'::xid8, '5400000'::bigint)))
        Rows Removed by Filter: 194
        Buffers: shared hit=9
Planning:
  Buffers: shared hit=3
Planning Time: 0.129 ms
Execution Time: 0.035 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 100,000 events back — **sequential scan**

```
Limit  (cost=0.00..12.06 rows=1 width=4) (actual time=0.006..0.006 rows=1.00 loops=1)
  Buffers: shared hit=2
  ->  Seq Scan on bm_n3tx9gechuj9_events  (cost=0.00..732874.36 rows=60757 width=4) (actual time=0.006..0.006 rows=1.00 loops=1)
        Filter: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[])) AND (ROW(event_tx, event_position) > ROW('1505886'::xid8, '5400000'::bigint)))
        Rows Removed by Filter: 14
        Buffers: shared hit=2
Planning:
  Buffers: shared hit=6
Planning Time: 0.057 ms
Execution Time: 0.010 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 100,000 events back — **sequential scan**

```
Limit  (cost=0.00..13.18 rows=1 width=4) (actual time=0.008..0.008 rows=1.00 loops=1)
  Buffers: shared hit=2
  ->  Seq Scan on bm_n3tx9gechuj9_events  (cost=0.00..807774.70 rows=61267 width=4) (actual time=0.008..0.008 rows=1.00 loops=1)
        Filter: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('1505886'::xid8, '5400000'::bigint)) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[])))
        Rows Removed by Filter: 14
        Buffers: shared hit=2
Planning:
  Buffers: shared hit=12
Planning Time: 0.064 ms
Execution Time: 0.012 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 100,000 events back — **sequential scan**

```
Limit  (cost=0.00..15.01 rows=1 width=4) (actual time=0.011..0.011 rows=1.00 loops=1)
  Buffers: shared hit=2
  ->  Seq Scan on bm_n3tx9gechuj9_events  (cost=0.00..932608.60 rows=62118 width=4) (actual time=0.011..0.011 rows=1.00 loops=1)
        Filter: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('1505886'::xid8, '5400000'::bigint)) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]) OR (event_tags @> '{sku:SKU-012505}'::text[]) OR (event_tags @> '{sku:SKU-012506}'::text[]) OR (event_tags @> '{sku:SKU-012507}'::text[]) OR (event_tags @> '{sku:SKU-012508}'::text[]) OR (event_tags @> '{sku:SKU-012509}'::text[])))
        Rows Removed by Filter: 14
        Buffers: shared hit=2
Planning:
  Buffers: shared hit=22
Planning Time: 0.091 ms
Execution Time: 0.015 ms
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | append-none | thrpt | 1 | 3.131 | ops/ms | 2.1% | 3,131 | 150,519 | 0 |
| postgres:external/metrics=off | append-none | thrpt | 8 | 4.689 | ops/ms | 2.4% | 4,689 | 225,208 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 1 | 0.026 | ops/ms | 120.6% | 26 | 1,240 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 8 | 0.165 | ops/ms | 55.1% | 165 | 8,148 | 0 |
| postgres:external/metrics=off | decide-then-append | thrpt | 1 | 0.001 | ops/ms | 7.3% | 1 | 39 | 0 |
| postgres:external/metrics=off | decide-then-append | thrpt | 8 | 0.049 | ops/ms | 24.1% | 49 | 2,221 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
