# Benchmark run: large-tier-writes

What an append costs at ten million events, against the same corpus large-tier reads. Split out from that profile rather than sharing it, because the two cannot be measured the same way: a read leaves the store alone and can be given as long as it needs, while an append grows it, and above a million events the corpus is restored once per trial rather than once per iteration -- a template copy of ten million rows between iterations would cost far more than the drift it prevents.
So an append workload here has a budget denominated in events, not seconds, and the budget does not grow with the tier. That is the whole reason this profile declares a drift cap of its own. At two percent, the suite-wide default, ten million events allow 200.000 appends per trial: about eighty seconds at one writer, and under ten at eight -- less than a single JMH iteration. No cadence fits that, and the run that discovered it died at 72% having thrown away forty minutes of clean reads.
Ten percent is a judgement, and it is about the label rather than the measurement. What a fraction of growth threatens is the claim "measured over ten million events"; it does not change a B-tree's depth or a GIN index's shape, so an append measured while the store went from 10.0M to 10.9M is measuring the same operation throughout. The cap is recorded in the manifest and printed in the report beside the drift, so a reader comparing this against a run measured under the default two percent can see that the allowance was widened deliberately. In the event the first captured run came in at 1.12%, so the allowance has never been needed: it was sized against an assumed ~25 ops/ms at eight writers where the real figure is 4.7. Left as it is rather than tightened to the default, because a server faster than that one should not fail a two-hour run over a label.
One caveat before reading any of these against the medium tier: at a hundred thousand entities the per-iteration boundary cache almost never gets a hit, so nearly every append-type-and-tag invocation here is a boundary probe plus the append, where the medium tier amortizes the probe over a whole iteration. Compare decide-then-append across tiers -- it always includes its read -- and treat this tier's append-type-and-tag as a different operation mix rather than the same workload at more volume.
Budget more wall clock than the estimate promises. The estimator counts iteration time only, and at this tier the restore dominates it: taking the template costs seconds, but handing the store back at the end of each trial is a truncate-and-copy of ten million rows, about two and a half minutes, once per trial.
This profile is also where the DCB check's shape was settled against its alternatives, and the figures stay quotable from here. One uniform NOT EXISTS check for every criteria -- the obvious spelling -- measures ~190-220x an unconditional append at this tier, with 50-150% error bars from the plan cache flipping between a 44ms custom plan and a 1.25s sequential scan; pinning the generic plan makes it 20x worse still. The check the library ships derives its shape from the criteria instead -- an ordered probe from the cursor when one is present, the custom-planned tag path when not -- and measures ~37x an unconditional append on this corpus's boundaries, at error bars that describe the store. This profile keeps characterising that check as the store and the workloads evolve.

| | |
|---|---|
| suite version | 0.11.0-SNAPSHOT |
| started | 2026-09-04T19:27:51.598905394Z |
| finished | 2026-09-04T20:40:43.580248911Z |
| targets | postgres:external/metrics=off |
| corpus restore | restored once per trial; intra-trial drift measured |
| store drift | 1.10% during the run, against the 10% this profile allows |

> **Not suitable as a published baseline.**
>
> - 4 measurements are too noisy to compare against anything, past the 10% this report calls uncomparable: append-type-and-tag (postgres:external/metrics=off, 1 thread) at 12%, decide-then-append (postgres:external/metrics=off, 1 thread) at 58%, append-type-and-tag (postgres:external/metrics=off, 8 threads) at 21%, decide-then-append (postgres:external/metrics=off, 8 threads) at 33%

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
| no criteria | 2.984 ± 0.105 ops/ms | 1.00x |
| one type set and one tag | 0.085 ± 0.011 ops/ms | 35.27x slower |

On PostgreSQL the unconditional append is also the only one that takes no advisory lock, so this gap is the whole DCB mechanism rather than just the extra predicate.

### What happens as threads are added

| workload | threads | throughput | useful ops/s | conflicts |
|---|---|---|---|---|
| append-none | 1 | 2.984 ± 0.105 ops/ms | 2,984 | 0.0% |
| append-none | 8 | 4.513 ± 0.113 ops/ms | 4,513 | 0.0% |
| append-type-and-tag | 1 | 0.085 ± 0.011 ops/ms | 85 | 0.0% |
| append-type-and-tag | 8 | 0.108 ± 0.022 ops/ms | 108 | 0.0% |
| decide-then-append | 1 | 0.027 ± 0.016 ops/ms | 27 | 0.0% |
| decide-then-append | 8 | 0.133 ± 0.043 ops/ms | 133 | 0.0% |

A rising throughput with a rising conflict rate is a store spending more of its capacity losing races, not doing more work. The useful column is the one to read.

## Query plans

Representative statements matching the shapes the store issues, not the statements themselves -- the backend builds its SQL internally and does not expose it. Enough to answer whether the planner used an index or scanned the table, and no substitute for the real thing if the query builder changes.

The reconstructed statements below describe the run's first PostgreSQL target. The captured ones name the target they came from, since a plan is a property of one store's configuration and a profile measuring a setting against itself explains both halves; the ms/op beside each is that same target's.

### stream page (unfiltered, limit 500)

```
Limit  (cost=0.56..224.46 rows=500 width=313) (actual time=0.041..0.311 rows=500.00 loops=1)
  Buffers: shared hit=2 read=30
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2490783.86 rows=5562360 width=313) (actual time=0.039..0.266 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Index Searches: 1
        Buffers: shared hit=2 read=30
Planning:
  Buffers: shared hit=58 read=12 dirtied=4
Planning Time: 0.432 ms
Execution Time: 0.358 ms
```

### tag needle (~10 matches)

```
Sort  (cost=2993.20..2995.06 rows=742 width=313) (actual time=0.751..0.751 rows=10.00 loops=1)
  Sort Key: event_tx, event_position
  Sort Method: quicksort  Memory: 27kB
  Buffers: shared hit=44 read=39
  ->  Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=59.69..2957.83 rows=742 width=313) (actual time=0.663..0.735 rows=10.00 loops=1)
        Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
        Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
        Heap Blocks: exact=10
        Buffers: shared hit=36 read=39
        ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..59.50 rows=742 width=0) (actual time=0.644..0.644 rows=10.00 loops=1)
              Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{campaign:needle}'::text[]))
              Index Searches: 1
              Buffers: shared hit=35 read=30
Planning:
  Buffers: shared hit=11 read=1
Planning Time: 0.127 ms
Execution Time: 0.802 ms
```

### tag swathe (~1% of the store)

```
Limit  (cost=0.56..22193.73 rows=500 width=313) (actual time=0.014..5.813 rows=500.00 loops=1)
  Buffers: shared hit=92 read=1352
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2477157.01 rows=55809 width=313) (actual time=0.014..5.789 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{campaign:swathe}'::text[])
        Rows Removed by Filter: 26973
        Index Searches: 1
        Buffers: shared hit=92 read=1352
Planning:
  Buffers: shared hit=3
Planning Time: 0.067 ms
Execution Time: 5.839 ms
```

### one entity's whole history (hot) — **lossy bitmap**, **sorts on disk**, **JIT 209ms**

> the bitmap outgrew work_mem, so whole pages were marked instead of rows and every row on them had to be re-checked. Raising work_mem for this statement removes the recheck entirely.
> the sort did not fit in work_mem and spilled to disk. Either the read returns more rows than it needs -- a limit or a savepoint -- or work_mem is too small for the size of result this query is meant to produce.
> PostgreSQL compiled this query before running it, which it does when the estimated cost is high. On a query that turns out to be short the compilation is most of the wait, and jit_above_cost is the knob.

```
Gather Merge  (cost=510811.01..541518.10 rows=263656 width=313) (actual time=451.230..507.505 rows=455092.00 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=3081 read=185295 written=11, temp read=14113 written=14144
  ->  Sort  (cost=509810.99..510085.63 rows=109857 width=313) (actual time=443.865..456.473 rows=151697.33 loops=3)
        Sort Key: event_tx, event_position
        Sort Method: external merge  Disk: 36496kB
        Buffers: shared hit=3081 read=185295 written=11, temp read=14113 written=14144
        Worker 0:  Sort Method: external merge  Disk: 36864kB
        Worker 1:  Sort Method: external merge  Disk: 39544kB
        ->  Parallel Bitmap Heap Scan on bm_n3tx9gechuj9_events  (cost=1836.11..484464.06 rows=109857 width=313) (actual time=174.783..396.250 rows=151697.33 loops=3)
              Recheck Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
              Rows Removed by Index Recheck: 1070154
              Filter: (event_tx < pg_snapshot_xmin(pg_current_snapshot()))
              Heap Blocks: exact=16637 lossy=42961
              Buffers: shared hit=3047 read=185295 written=11
              Worker 0:  Heap Blocks: exact=18124 lossy=42150
              Worker 1:  Heap Blocks: exact=18044 lossy=46679
              ->  Bitmap Index Scan on bm_n3tx9gechuj9_idx_events_stream_tags  (cost=0.00..1770.20 rows=263681 width=0) (actual time=106.447..106.447 rows=455092.00 loops=1)
                    Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tags @> '{sku:SKU-000000}'::text[]))
                    Index Searches: 1
                    Buffers: shared hit=1997 read=1618
Planning:
  Buffers: shared hit=3
Planning Time: 0.104 ms
JIT:
  Functions: 18
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 0.747 ms (Deform 0.356 ms), Inlining 95.465 ms, Optimization 67.920 ms, Emission 45.199 ms, Total 209.331 ms
Execution Time: 520.221 ms
```

### most recent event, backwards limit 1

```
Limit  (cost=0.56..9.96 rows=1 width=313) (actual time=0.029..0.029 rows=1.00 loops=1)
  Buffers: shared read=6
  ->  Index Scan Backward using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2478196.24 rows=263656 width=313) (actual time=0.028..0.028 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())))
        Filter: (event_tags @> '{sku:SKU-000000}'::text[])
        Rows Removed by Filter: 17
        Index Searches: 1
        Buffers: shared read=6
Planning:
  Buffers: shared hit=2 read=9
Planning Time: 0.196 ms
Execution Time: 0.042 ms
```

### cursor page from the midpoint (limit 500)

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..274.24 rows=500 width=313) (actual time=0.018..0.159 rows=500.00 loops=1)
  Buffers: shared hit=3 read=29
  ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events  (cost=0.56..2194012.38 rows=4008413 width=313) (actual time=0.017..0.137 rows=500.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_tx < pg_snapshot_xmin(pg_current_snapshot())) AND (ROW(event_tx, event_position) > ROW('3087567'::xid8, '2750000'::bigint)))
        Index Searches: 1
        Buffers: shared hit=3 read=29
Planning:
  Buffers: shared hit=7 read=7
Planning Time: 0.099 ms
Execution Time: 0.189 ms
```

> **The plans below do not describe the store's own execution.** They inline the tag arrays 
> and the cursor as literals, which is what PostgreSQL sees when it builds a *custom* plan; 
> the store binds them as JDBC parameters and re-uses the statement, so what it actually runs 
> is whichever of the custom and generic plans the server settled on -- and for several of 
> these shapes that is the generic one, which is a different plan entirely. Read these as the 
> shape of the predicate. The captured plans further down are the ones to read against the 
> measurements.

### DCB check: event types only, no tag (append-types) -- boundary 100,000 events back

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
Limit  (cost=0.56..0.64 rows=1 width=4) (actual time=0.037..0.037 rows=1.00 loops=1)
  Buffers: shared hit=1 read=5
  ->  Index Only Scan using bm_n3tx9gechuj9_idx_events_stream_type_position on bm_n3tx9gechuj9_events  (cost=0.56..81015.47 rows=1016254 width=4) (actual time=0.036..0.036 rows=1.00 loops=1)
        Index Cond: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReserved,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Heap Fetches: 0
        Index Searches: 1
        Buffers: shared hit=1 read=5
Planning:
  Buffers: shared read=2
Planning Time: 0.131 ms
Execution Time: 0.050 ms
```

### DCB check: four types scoped to one SKU (append-type-and-tag) -- boundary 100,000 events back — **sequential scan**

> no index served this, so it read the table from the beginning and discarded 14 rows on the way. A predicate the index can start from -- the cursor boundary alone does this -- turns the same question into a seek.

```
Limit  (cost=0.00..10.91 rows=1 width=4) (actual time=0.050..0.051 rows=1.00 loops=1)
  Buffers: shared read=3
  ->  Seq Scan on bm_n3tx9gechuj9_events  (cost=0.00..708191.03 rows=64888 width=4) (actual time=0.050..0.050 rows=1.00 loops=1)
        Filter: ((event_tags @> '{sku:SKU-000000}'::text[]) AND (stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Rows Removed by Filter: 14
        Buffers: shared read=3
Planning:
  Buffers: shared hit=2 read=1
Planning Time: 0.097 ms
Execution Time: 0.059 ms
```

### DCB check: one item carrying three AND-ed tags (append-multi-tag) -- boundary 100,000 events back — **sequential scan**

> no index served this, so it read the table from the beginning and discarded 194 rows on the way. A predicate the index can start from -- the cursor boundary alone does this -- turns the same question into a seek.

```
Limit  (cost=0.00..97.76 rows=1 width=4) (actual time=0.051..0.051 rows=1.00 loops=1)
  Buffers: shared hit=3 read=7
  ->  Seq Scan on bm_n3tx9gechuj9_events  (cost=0.00..708191.03 rows=7244 width=4) (actual time=0.050..0.050 rows=1.00 loops=1)
        Filter: ((event_tags @> '{sku:SKU-000000,channel:web,warehouse:WH-1}'::text[]) AND (stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Rows Removed by Filter: 194
        Buffers: shared hit=3 read=7
Planning:
  Buffers: shared hit=3
Planning Time: 0.080 ms
Execution Time: 0.074 ms
```

### DCB check: 2 OR-ed filter items (append-or-groups-2) -- boundary 100,000 events back — **sequential scan**

> no index served this, so it read the table from the beginning and discarded 14 rows on the way. A predicate the index can start from -- the cursor boundary alone does this -- turns the same question into a seek.

```
Limit  (cost=0.00..11.27 rows=1 width=4) (actual time=0.008..0.008 rows=1.00 loops=1)
  Buffers: shared hit=2
  ->  Seq Scan on bm_n3tx9gechuj9_events  (cost=0.00..733183.03 rows=65062 width=4) (actual time=0.007..0.007 rows=1.00 loops=1)
        Filter: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)))
        Rows Removed by Filter: 14
        Buffers: shared hit=2
Planning:
  Buffers: shared hit=6
Planning Time: 0.076 ms
Execution Time: 0.013 ms
```

### DCB check: 5 OR-ed filter items (append-or-groups-5) -- boundary 100,000 events back — **sequential scan**

> no index served this, so it read the table from the beginning and discarded 14 rows on the way. A predicate the index can start from -- the cursor boundary alone does this -- turns the same question into a seek.

```
Limit  (cost=0.00..12.32 rows=1 width=4) (actual time=0.009..0.009 rows=1.00 loops=1)
  Buffers: shared hit=2
  ->  Seq Scan on bm_n3tx9gechuj9_events  (cost=0.00..808159.04 rows=65584 width=4) (actual time=0.009..0.009 rows=1.00 loops=1)
        Filter: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[])))
        Rows Removed by Filter: 14
        Buffers: shared hit=2
Planning:
  Buffers: shared hit=12
Planning Time: 0.073 ms
Execution Time: 0.013 ms
```

### DCB check: 10 OR-ed filter items (append-or-groups-10) -- boundary 100,000 events back — **sequential scan**

> no index served this, so it read the table from the beginning and discarded 14 rows on the way. A predicate the index can start from -- the cursor boundary alone does this -- turns the same question into a seek.

```
Limit  (cost=0.00..14.04 rows=1 width=4) (actual time=0.012..0.012 rows=1.00 loops=1)
  Buffers: shared hit=2
  ->  Seq Scan on bm_n3tx9gechuj9_events  (cost=0.00..933119.05 rows=66452 width=4) (actual time=0.012..0.012 rows=1.00 loops=1)
        Filter: ((stream_context = 'inventory'::text) AND (stream_purpose = 'default'::text) AND (event_type = ANY ('{StockReceived,StockReserved,StockReleased,StockPicked}'::text[])) AND (ROW(event_tx, event_position) > ROW('3088894'::xid8, '5400000'::bigint)) AND ((event_tags @> '{sku:SKU-000000}'::text[]) OR (event_tags @> '{sku:SKU-012501}'::text[]) OR (event_tags @> '{sku:SKU-012502}'::text[]) OR (event_tags @> '{sku:SKU-012503}'::text[]) OR (event_tags @> '{sku:SKU-012504}'::text[]) OR (event_tags @> '{sku:SKU-012505}'::text[]) OR (event_tags @> '{sku:SKU-012506}'::text[]) OR (event_tags @> '{sku:SKU-012507}'::text[]) OR (event_tags @> '{sku:SKU-012508}'::text[]) OR (event_tags @> '{sku:SKU-012509}'::text[])))
        Rows Removed by Filter: 14
        Buffers: shared hit=2
Planning:
  Buffers: shared hit=22
Planning Time: 0.102 ms
Execution Time: 0.017 ms
```

> **These are the store's own statements, explained by the server.** Captured by running each 
> workload with `auto_explain` on, after the last measurement, so the SQL is the one the backend 
> built, the parameters are bound as it binds them, and the plan is the one PostgreSQL chose. 
> Where these and the reconstructed plans above disagree, these are the ones that describe what 
> was measured.
>
> **A plan says which collision mode it was captured under, and it is the profile's own.** So a 
> contention profile's plan is addressed at the stream and the boundary its measured appends 
> were. It runs on one thread, which is what it can be: contention between writers is not a 
> property of a plan and `auto_explain` would not attribute it, so these explain *where* a 
> profile's appends go and never what they wait for. A plan whose heading names no mode was 
> captured before the capture honoured the profile's -- it was addressed as `spread`, whatever 
> the profile ran, and describes that.
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
>
> That comparison is on estimates, and a DCB check is exactly the shape that defeats it: the 
> expected result is *no rows*, while the planner prices a `NOT EXISTS` by how soon it expects 
> to find one. A wider filter makes it expect a match sooner, so the generic plan's estimate 
> **falls** as facts are added while the custom plan's rises -- and once it drops below, the 
> server switches to a plan that scans the whole table for a row that is not there.

### DCB check as issued: append-type-and-tag @ postgres:external/metrics=off (collision=spread, generic plan) — measured 11.82 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-000008","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{warehouse:WH-1,channel:web,sku:SKU-000008}', $8 = 'inventory', $9 = 'default', $10 = '3088944', $11 = '5499837', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-000008'
	Insert on bm_n3tx9gechuj9_events  (cost=570.30..570.32 rows=1 width=264) (actual time=0.192..0.194 rows=1.00 loops=1)
	  Buffers: shared hit=5 read=27 dirtied=10
	  InitPlan 1
	    ->  Limit  (cost=0.56..570.30 rows=1 width=16) (actual time=0.086..0.086 rows=0.00 loops=1)
	          Buffers: shared read=14 dirtied=1
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..1725738.38 rows=3029 width=16) (actual time=0.085..0.085 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 171
	                Index Searches: 1
	                Buffers: shared read=14 dirtied=1
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.121..0.122 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared read=15 dirtied=2
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.025..0.025 rows=1.00 loops=1)
```

### DCB check as issued: decide-then-append @ postgres:external/metrics=off (collision=spread, generic plan) — measured 37.36 ms/op

> the cursor boundary is an Index Cond here, so the scan starts at the boundary rather than filtering its way to it.

```
	Query Text: INSERT INTO bm_n3tx9gechuj9_events (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES (uuidv7(), $1, $2, $3, $4, $5::jsonb, $6::jsonb, $7) ) AS new_events WHERE (
		SELECT event_position FROM bm_n3tx9gechuj9_events
		WHERE 1=1 AND stream_context = $8 AND stream_purpose = $9 AND (event_tx, event_position) > ($10::xid8, $11) AND ((event_type IN ($12, $13, $14, $15) AND event_tags @> ARRAY[$16]::text[])) ORDER BY event_tx, event_position LIMIT 1) IS NULL RETURNING event_position, event_timestamp, event_tx::text, event_id::text
	Query Parameters: $1 = NULL, $2 = 'inventory', $3 = 'default', $4 = 'StockReserved', $5 = '{"sku":"SKU-000017","quantity":1,"orderId":"ORD-benchmark"}', $6 = NULL, $7 = '{sku:SKU-000017,warehouse:WH-1,channel:web}', $8 = 'inventory', $9 = 'default', $10 = '3088944', $11 = '5499894', $12 = 'StockReleased', $13 = 'StockReserved', $14 = 'StockPicked', $15 = 'StockReceived', $16 = 'sku:SKU-000017'
	Insert on bm_n3tx9gechuj9_events  (cost=570.30..570.32 rows=1 width=264) (actual time=0.200..0.202 rows=1.00 loops=1)
	  Buffers: shared hit=5 read=24 dirtied=10
	  InitPlan 1
	    ->  Limit  (cost=0.56..570.30 rows=1 width=16) (actual time=0.091..0.091 rows=0.00 loops=1)
	          Buffers: shared read=11 dirtied=1
	          ->  Index Scan using bm_n3tx9gechuj9_idx_events_stream_position on bm_n3tx9gechuj9_events bm_n3tx9gechuj9_events_1  (cost=0.56..1725738.38 rows=3029 width=16) (actual time=0.090..0.090 rows=0.00 loops=1)
	                Index Cond: ((stream_context = ($8)::text) AND (stream_purpose = ($9)::text) AND (ROW(event_tx, event_position) > ROW(($10)::xid8, $11)))
	                Filter: ((event_tags @> ARRAY[($16)::text]) AND (event_type = ANY (ARRAY[($12)::text, ($13)::text, ($14)::text, ($15)::text])))
	                Rows Removed by Filter: 123
	                Index Searches: 1
	                Buffers: shared read=11 dirtied=1
	  ->  Result  (cost=0.00..0.02 rows=1 width=264) (actual time=0.123..0.124 rows=1.00 loops=1)
	        One-Time Filter: ((InitPlan 1).col1 IS NULL)
	        Buffers: shared read=12 dirtied=2
	        ->  Values Scan on "*VALUES*"  (cost=0.00..0.01 rows=1 width=240) (actual time=0.022..0.022 rows=1.00 loops=1)
```

## Every measurement

| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |
|---|---|---|---|---|---|---|---|---|---|
| postgres:external/metrics=off | append-none | thrpt | 1 | 2.984 | ops/ms | 3.5% | 2,984 | 143,971 | 0 |
| postgres:external/metrics=off | append-none | thrpt | 8 | 4.513 | ops/ms | 2.5% | 4,513 | 218,297 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 1 | 0.085 | ops/ms | 12.4% | 85 | 4,068 | 0 |
| postgres:external/metrics=off | append-type-and-tag | thrpt | 8 | 0.108 | ops/ms | 20.7% | 108 | 5,340 | 0 |
| postgres:external/metrics=off | decide-then-append | thrpt | 1 | 0.027 | ops/ms | 58.1% | 27 | 1,289 | 0 |
| postgres:external/metrics=off | decide-then-append | thrpt | 8 | 0.133 | ops/ms | 32.5% | 133 | 6,549 | 0 |

A relative error above about 10% means the measurement is too noisy to compare against anything; raise the iteration count or quieten the machine.
