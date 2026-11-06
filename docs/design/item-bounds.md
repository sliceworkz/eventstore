# Item bounds and the read bound

The rules are in the root `CLAUDE.md`, under EventFilter, EventQuery and AppendCriteria. This file holds
the reasons, the rejected alternatives and the measurements behind them.

## Two kinds of bound, kept on two types

A decision is bounded twice, for two different reasons:

- **Which part of the history is relevant** — "the stock movements after the last count", "the invoices
  recorded up to the close of the year". That is a property of the decision, so it lives on the
  `EventFilter`, per item: `after` (exclusive) and `until` (inclusive). Each item carries its own, so one
  filter unites facts bounded at different points of the history.
- **Where a read stops** — the head a decider pins all its reads at, so several reads see one moment.
  That is a property of the read, so it lives on the `EventQuery`: `notAfter`.

Only the first is ever part of a consistency boundary. `AppendCriteria.of(query, reference)` takes the
query's filter and nothing else, so the criteria built from the query a decider read with is the right
one.

**The alternative — one `until` on the filter, shared by its items — loses** on both counts. It could not
express items bounded differently: `or` had to refuse two filters with different `until`s, and the
framework could not merge a savepoint model's read (bounded *after* its savepoint) with any other. And it
put the read bound where the consistency check reads: `AppendCriteria.of(relevant.until(head), head)` —
which is what reusing "the query I read with" produces — deemed nothing after the head relevant and
admitted every append with nothing raised. Every place a caller met it had to warn about it. With the read
bound off the filter, that mistake cannot be written.

**The alternative — `EventQuery` re-exposing the filter's builders — loses** because the two halves then
share a vocabulary they mean different things by. A query has `of(filter)`, `backwards()`, `limit(n)` and
`notAfter(ref)`, and no shortcut; everything about what is relevant is spelled on the filter.

The read bound is named for what it excludes. `asOf` and `upTo` were considered: `asOf` reads as "from
this point on", the opposite of what it does, and both read in a backwards query as "walk back until you
reach it", while a backwards read *starts* at the bound and walks into history.

## What the consistency check does with item bounds

The check asks for matching events after the expected reference. Against that reference, at the moment
the criteria is made (`AppendCriteria`'s canonical constructor):

- an item `until` a reference at or before the expected one can match nothing appended later, and is
  removed;
- an item `after` a reference at or before the expected one loses that bound, which adds nothing;
- every other bound is kept: an `until` later than the reference still admits events between the two,
  and an `after` later than the reference still excludes the events before it.

With no expected reference ("I decided on an empty boundary") nothing is pruned: every matching event
counts. When every item is removed the filter matches nothing, so the append is unconditional — correct,
since the decision depends only on history no later append can change. That outcome needs no guard: the
read bound, which is what used to produce it by accident, cannot reach a filter.

## PostgreSQL: a branch per item

When every item carries the same bounds — every filter without any — the statement is the single one the
store always issued: the shared bounds, the cursor and the read bound conjoined at the top level, where
each is an index start condition, and the items' types and tags as one disjunction. The cursor and an
item's `after` are both exclusive lower bounds, so only the later is written; the read bound and an
item's `until` are both inclusive upper bounds, so only the earlier is.

When the items' bounds differ, the read is one ordered branch per item, merged in order:

```sql
SELECT e.… FROM (
    SELECT DISTINCT event_tx, event_position FROM (
        (SELECT event_tx, event_position FROM events WHERE <item 1's range> AND <item 1> ORDER BY … LIMIT n)
        UNION ALL
        (SELECT event_tx, event_position FROM events WHERE <item 2's range> AND <item 2> ORDER BY … LIMIT n)
    ) u ORDER BY … LIMIT n
) p JOIN events e ON e.event_position = p.event_position ORDER BY …
```

Each branch starts at its own bound and stops at the limit; an event matching two items comes out of two
branches and is kept once; the payloads are fetched for the page alone. A branch whose range is empty is
left out, and a read left with no branch is not issued.

### Measured

PostgreSQL 16.15, 17.11 and 18.6, Docker, the store's own schema. 1.300.016 events: 998.910 in one stream,
301.090 in another context, interleaved, about ten events per real transaction. Each event tagged with
one of 10.000 `sku:` values (about 100 events each) and one of 10 `warehouse:` values (about 100.000
each). A page of 500, `EXPLAIN (ANALYZE, BUFFERS)`, single runs: the buffers and rows discarded are the
reliable figures, the times are indicative. The three versions chose the same plan for every shape.

| shape | plan | rows discarded | ms (16 / 17 / 18) |
|---|---|---|---|
| two items, `sku` tags, no bounds (the shape before item bounds) | `BitmapOr` over the tag index, sort | 125 | 9.3 / 8.7 / 9.9 |
| one statement, each item's range in its OR group: `sku` item up to 5%, `warehouse` item after 90% | ordered walk of the stream, every range checked row by row | **905.792** | **526 / 514 / 530** |
| same, generic plan | the `warehouse` item's every row collected, sorted | 91.685 | 177 / 188 / 157 |
| one statement, both items broad, ranges (…5%] and (90%…] | walk from the 5% point | 855.791 | 427 / 451 / 442 |
| **a branch per item**, same filter as the 905.792 row | `Merge Append`, each branch at its own bound | 5.791 | **5.1 / 4.6 / 4.6** |
| a branch per item, generic plan | the broad branch collects and sorts | 91.685 | 137 / 105 / 97 |
| a branch per item, both items broad, cursor at 5% | the item behind the cursor costs ~0.02 ms | 5.791 | 3.8 / 3.5 / 3.8 |
| append check, cursor at 99.8%, one item with an `until` | the bound in the index condition | 0 | 0.17 / 0.15 / 0.22 |
| same, generic plan | the walk forward from the reference; the bound a filter | 909 | 0.62 / 0.39 / 0.43 |

Planning the branch statement costs about half a millisecond, so it is kept off the plan cache
(`prepareThreshold` 0, the same mechanism as the append check without a reference). Only a filter whose
items differ in their bounds pays it.

**The rejected alternative — each item's range inside its own OR group, one statement — loses** because a
range inside a disjunction is no index start condition. The planner can still put each range in a bitmap
index condition when it reads every item through a bitmap (the `sku` shapes above), but that plan
collects every matching row and sorts them; for a broad item it chooses the ordered walk instead, from
the widest range's start, filtering every row. Correct, unlogged, and linear in the stream.

**The second rejected alternative — the store splitting the read into one query per item and merging in
Java** — would work on any backend unchanged, at one round trip per item, but on PostgreSQL the separate
statements see different snapshots: the visibility barrier can move between them, so an event one
sub-query could not see yet can land before the next page's cursor and be skipped for good. It is safe
only when every sub-query is bounded at a head taken first. The single statement runs on one snapshot.

## In memory

The in-memory backends judge item bounds per event in `EventFilter.matches`; the read bound keeps its
shortcut (`takeWhile` forward, `dropWhile` backward). They are a linear scan either way.

## Item bounds are part of the storage contract

A backend answers them, and the TCK holds every backend to it (`ItemBoundsTest`): every read is compared
with the filter's own `matches` over the whole stream, in both directions, with and without a read bound,
whole and paged. There is no capability flag: a backend that ignored item bounds would have its reads
corrected by the re-check above the SPI, but a page cut short by events discarded after the limit was
spent reads as the last page, and ends a replay early, silently.
