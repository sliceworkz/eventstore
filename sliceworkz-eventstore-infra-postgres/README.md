
# Postgres Storage for Eventstore

Create a database schema with the DDL scripts found in 'ensure-schema.sql',
removing "PREFIX_" or replacing it to manage different stores next to each other.
Use 'drop-schema.sql' to drop existing schema objects before recreating.

## Connecting

The builder needs two connections: a pooled one for reads and appends, and a direct one for the
LISTEN/NOTIFY monitors, which do not work through a transaction pooler such as PgBouncer. Either build
the pools yourself and pass them in, or describe them in a `db.properties` file
(template: `src/main/quickstart/db.properties`):

```properties
db.pooled.url=jdbc:postgresql://host/db
db.pooled.username=...
db.pooled.password=...
db.pooled.maximumPoolSize=25

db.nonpooled.url=jdbc:postgresql://host/db
db.nonpooled.username=...
db.nonpooled.password=...
db.nonpooled.maximumPoolSize=2
```

Keys inside a section are HikariCP properties; keys under `datasource.` (`db.pooled.datasource.sslmode`)
go to the JDBC driver. The builder takes the first of these that is present:

1. a `DataSource` passed to `.dataSource(...)` (and `.monitoringDataSource(...)`)
2. `Properties` or a file passed to `.configuration(...)`
3. the file named by the system property `eventstore.db.config`
4. the file named by the environment variable `EVENTSTORE_DB_CONFIG`
5. `./db.properties` in the working directory of the process
6. `db.properties` at the root of the classpath (`src/main/resources` in a Maven project)

The lookup never walks into parent directories. When nothing is found, `build()` throws an
`EventStorageException` naming every location it tried. Several stores in one process are configured
by giving each builder its own `.configuration(...)`, or by sharing one pool between them through
`.dataSource(...)` when they live in one database under different prefixes.

## Timeouts: a stalled lock holder, and a socket that dies silently

Two waits in this backend have no natural end, and the builder bounds both:

```java
EventStorage storage = PostgresEventStorage.newBuilder()
    .lockTimeout(Duration.ofSeconds(10))                // default; Duration.ZERO waits without bound
    .notificationProbeInterval(Duration.ofSeconds(30))  // default
    .build();
```

**`lockTimeout`** bounds how long a conditional append waits for its stream's advisory lock (and a
lease request for its lease's lock). A healthy holder releases it within one INSERT, so the bound is
never hit by ordinary contention; it is hit by a holder that has stalled — a paused process, a session
the server still believes in after its client has gone. Without it, every conditional append to that
stream parks behind the holder inside a checked-out pool connection until the pool is empty, and from
then on every operation of the store fails on the pool's connection timeout, reads included, on every
stream. With it, the parked appends fail one at a time with an `EventStorageException` naming the
stream and the bound (the cause carries SQLSTATE `55P03`), nothing is written, and the store stays up
for everything else. Find the holder with:

```sql
SELECT l.objid, a.pid, a.state, a.xact_start, a.application_name, a.client_addr, a.query
FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
WHERE l.locktype = 'advisory' AND l.granted;
```

The bound is set with `SET LOCAL`, so it lives and dies with the append's transaction and never
follows the connection back into the pool; the schema scripts' own lock is not under it.

**`notificationProbeInterval`** bounds how long a LISTEN/NOTIFY monitoring connection may stay silent
before its monitor asks the server whether it is still there. While waiting for notifications the
driver sends nothing, so a socket whose peer has vanished without closing it — a NAT or firewall that
dropped its state, a network partition, a crashed host — looks exactly like a quiet channel, and would
otherwise be read forever with `sliceworkz.eventstore.notifications.up` reading 1. After the interval
without traffic the monitor sends one round trip (bounded at 5 seconds, which is also the network
timeout every monitoring connection runs under) and replaces a connection that does not answer. A busy
channel is never probed.

Both are library-level bounds and independent of the driver's. The driver's own settings are worth
knowing about, and are yours to set in `db.properties` under `datasource.`: `tcpKeepAlive=true` detects
the same dead socket, but only after the operating system's keepalive time (two hours by default on
Linux); `socketTimeout=<seconds>` bounds *every* read on the pooled connections, so set it only above
the longest statement the store legitimately runs — a large `importEvents` batch, a `CREATE INDEX`
under `ENSURE` — or it becomes the failure it was meant to catch.



## Database privileges

What the application role needs depends on the `DatabaseInitMode` it starts with, and on one thing
that is not a table: the **`btree_gin` extension**, which the combined stream+tags GIN index
(`idx_events_stream_tags`) is built on and which schema validation requires.

| mode | what the role must be allowed to do |
|---|---|
| `NONE`, `VALIDATE` | `CONNECT`, `USAGE` on the schema, and no DDL at all — see the runtime grants below |
| `ENSURE` (default) | the above, plus `CREATE` on the **schema** — and, *only if `btree_gin` is not installed yet*, `CREATE` on the **database** |
| `RECREATE` | the above, plus ownership of the store's tables and functions (it drops them) |

Every mode needs these at runtime. They come for free when the role created the tables itself; when a
DBA created them, they have to be granted:

```sql
GRANT SELECT, INSERT                 ON <prefix>events           TO <role>;
GRANT SELECT, INSERT, UPDATE, DELETE ON <prefix>bookmarks        TO <role>;
GRANT SELECT, INSERT, UPDATE         ON <prefix>leases           TO <role>;
GRANT SELECT, INSERT, UPDATE, DELETE ON <prefix>lease_contenders TO <role>;
GRANT SELECT, INSERT, UPDATE         ON <prefix>shredding_keys   TO <role>;
GRANT USAGE                          ON SEQUENCE <prefix>events_event_position_seq TO <role>;
```

Events are never updated or deleted — the store only ever appends to that table. Lease rows are
never deleted either (a release backdates the heartbeat so the fencing token survives), so the
leases table needs no `DELETE`; contender rows are pruned, so that table does.

The shredding key table needs no `DELETE` either, and deliberately: erasing a data subject *updates*
the row, nulling `key_material` and stamping `shredded_at` and `shredded_reason`. Keeping the row is
what leaves the erasure an audit trail — the events themselves record nothing about it — and what
lets a key id keep resolving to "erased" rather than to "unknown" — and an unknown key id fails the
read, since it means the events were sealed against another store. Granting `DELETE` here would let
an erasure be made untraceable, and turn the deleted subject's events unreadable.

A role that must read the events but never the personal data in them is granted every column of the
key table *except* `key_material`:

```sql
GRANT SELECT (key_id, subject_type, subject_id, subject_category, created_at, shredded_at, shredded_reason)
    ON <prefix>shredding_keys TO <reporting_role>;
```

Resolving a key under that role fails with `insufficient_privilege`, which the key store reports as a
denial rather than an outage: every protected value reads as `Shreddable.Withheld`, projections
advance, and the audit still works. That last part rests on a detail of PostgreSQL worth knowing when
writing anything else against this table: `SELECT` privilege is checked on *every* column a statement
references, in a `WHERE` or `FILTER` clause as much as in the select list. The audit therefore never
mentions `key_material` at all and judges "shredded" by `shredded_at`, which an erasure stamps in the
same statement; a predicate on `key_material IS NULL` would fail the whole audit for exactly this role.
`PostgresShreddingReportingRoleTest` pins both halves — every key denied, every audit statement
answered — per supported version. One consequence for how such a role *starts* its store: the
`information_schema` shows a role only the columns it has a privilege on, so schema validation would
report `key_material` as missing. A store opened by the reporting role therefore uses
`DatabaseInitMode.NONE`, which is the production recommendation anyway. This is the database-enforced
boundary, and it is all-or-nothing per role. Per-category entitlement — a service that reads names
and never addresses — is declared on the codec with `ShreddingCodec.restrictedTo(...)`, which decides
on the category in the envelope and never reaches the table for a denied one. Row-level security on
the key table does *not* produce a denial: a hidden row is indistinguishable from an absent one, and
an absent row is a key the store never held, which fails the read rather than reading as withheld.

### Migrating the bookmarks table to store the event id only

A bookmark names a stored event by id, and its position and transaction are read from that event
(`<prefix>bookmarks` joined to `<prefix>events` on `event_id`, one probe on the unique index). A
database created while the bookmarks table still carried its own `event_position` and `event_tx`
columns has to drop them — nothing binds them any more, and they are `NOT NULL`, so the first
placement would fail on them. `ENSURE` never drops a column, so this is a hand-applied migration
under every mode:

```sql
ALTER TABLE <prefix>bookmarks DROP COLUMN event_position, DROP COLUMN event_tx;
```

No data migration: the columns were the caller's copy of what the events row says, and every read now
answers from that row. `ENSURE` replaces the `notify_bookmark_placed` function itself; a `VALIDATE` or
`NONE` deployment applies the current body from `ensure-schema.sql` by hand, since the function now looks
the event up for the notification payload. `checkDatabase()` reports an un-migrated table under
`VALIDATE` and `ENSURE` with this statement, and under `NONE` the first `placeBookmark` names it rather
than failing on a bare not-null violation (`PostgresSchemaDriftTest` pins both). The grants are
unchanged: the role already needs `SELECT` on the events table.

### Migrating a database created before the order indexes carried their admission predicates

Two B-tree indexes on the `(event_tx, event_position)` order serve the reads that do not bind both
stream columns — the stream indexes all lead with `(stream_context, stream_purpose)` and offer such a
read neither a start condition nor an order, so without them each page is a scan feeding a sort,
whatever its limit:

- `idx_events_global_order` on `(event_tx, event_position)` `WHERE event_position > 0`: a wildcard
  stream (`EventStreamId.anyContext()`) paged by a store-wide projection or an export, `head()` of
  the whole store, an unscoped `EventStoreImporter` run — and the restored-history check every start
  runs (see "Backup and restore"), which without it is a scan of the table on every boot.
- `idx_events_context_order` on `(stream_context, event_tx, event_position)` `WHERE event_tx >
  '0'::xid8`: a read that binds the context and leaves the purpose open — a whole-context replay or
  export over a per-entity layout, where every entity is its own purpose.

**Both predicates are tautologies, and they are the point.** `event_position` is a `bigserial` the
store never writes itself, so 1 is the lowest value there is; `event_tx` comes from
`pg_current_xact_id()`, which never returns 0. Each index therefore covers every row and is the index
it would be without its predicate. What the predicate decides is who may *enter* it: PostgreSQL
admits a partial index only to a statement whose own predicates imply the index's, and a tautology
over a column with no `CHECK` constraint is one the planner cannot prove for itself — so only a
statement that spells the same predicate out is admitted, and the store spells each one out for
exactly the reads whose scope binds no more than that index leads with.

Without that, a read of one stream or one context can be served by a wider order index: walk it in
order and filter on the stream columns it does not lead with. The planner takes that whenever the
stream is a large share of the table, because the wider index is smaller and it prices the filter as
though the stream's events were spread evenly through the order. They are not — a stream that has
been quiet while other streams wrote sits behind everything written since, so the walk covers all of
it one row at a time and grows with everything those streams write. Every answer stays correct and
nothing is logged. Measured on a 500.000-event table whose quiet stream sits 300.000 events back:
`head()` of that stream 24ms against 0.08ms off its own index, and the consistency check of a
conditional append 56ms against 0.02ms — the check worst of all, because it is server-prepared and
the cached generic plan *is* that walk. `PostgresOrderIndexAdmissionTest` pins it on 16, 17 and 18.

Schema validation requires both indexes, with their predicates, and requires the two they replaced —
`idx_events_tx_position` and `idx_events_context_tx_position`, which indexed every row — to be gone:
left in place, those are exactly what a stream or context read walks instead of its own index.
`ENSURE` does all four on the next start of an existing database; the creates are plain `CREATE
INDEX`, which blocks appends for the duration of the build, and the drops take a brief `ACCESS
EXCLUSIVE` on the events table. So on a large table a `VALIDATE` or `NONE` deployment — or an
`ENSURE` one that would rather not build indexes during a rolling start — applies the migration by
hand first, without either lock (each statement on its own, outside a transaction block, which
`CONCURRENTLY` requires):

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS <prefix>idx_events_global_order ON <prefix>events (event_tx, event_position) WHERE event_position > 0;
CREATE INDEX CONCURRENTLY IF NOT EXISTS <prefix>idx_events_context_order ON <prefix>events (stream_context, event_tx, event_position) WHERE event_tx > '0'::xid8;
DROP INDEX CONCURRENTLY IF EXISTS <prefix>idx_events_tx_position;
DROP INDEX CONCURRENTLY IF EXISTS <prefix>idx_events_context_tx_position;
```

Run the creates before the drops, so no start between the two is left without an order index at all.
`checkDatabase()` reports each of the four under `VALIDATE` — a replacement missing, a replacement
created without its predicate, a superseded index still present — and carries this migration in the
message.

A database that has no order indexes at all, from before either existed, needs the same statements:
the creates apply and the drops find nothing.

### Migrating a database created before shredding existed

`ENSURE` only ever creates tables, so it adds this one on the next start of an existing database and
nothing else is needed. A `VALIDATE` or `NONE` deployment, where a DBA applies DDL by hand, needs:

```sql
CREATE TABLE IF NOT EXISTS <prefix>shredding_keys (
      key_id TEXT PRIMARY KEY,
      subject_type TEXT NOT NULL,
      subject_id TEXT NOT NULL,
      subject_category TEXT NOT NULL,
      key_material BYTEA,
      created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
      shredded_at TIMESTAMP WITH TIME ZONE,
      shredded_reason TEXT
  );

CREATE UNIQUE INDEX IF NOT EXISTS <prefix>idx_shredding_keys_active
    ON <prefix>shredding_keys (subject_type, subject_id, subject_category)
    WHERE key_material IS NOT NULL;

CREATE INDEX IF NOT EXISTS <prefix>idx_shredding_keys_subject
    ON <prefix>shredding_keys (subject_type, subject_id, subject_category);
```

No data migration: the table starts empty, and events written before shredding existed carry no
sealed values. `checkDatabase()` validates the table, so an un-migrated database is reported at
startup rather than at the first erasure request — which is the failure worth catching early, since
an erasure with nowhere to destroy anything is a compliance problem rather than an outage.

There is deliberately **no foreign key** between this table and `<prefix>events`, in either
direction. Events name their keys through ordinary `dek:` tags; a constraint would either block
pruning events or cascade keys away with them, and a cascade here would erase data nobody asked to
erase.

**`CREATE` on the schema and `CREATE` on the database are different privileges**, and this is the
one place the difference shows. `btree_gin` is a *trusted* extension (PostgreSQL 13+), so installing
it needs no superuser — but it does need `CREATE` on the current database. The common locked-down
setup grants `CREATE` on the schema only, which is a role that can create every table, index,
function and trigger in this schema and *cannot* create the extension.

Two ways to run `ENSURE` under such a role, both fine:

```sql
-- either: a DBA installs it once, and the application role never needs the privilege
CREATE EXTENSION btree_gin;

-- or: grant it, for the first start at least
GRANT CREATE ON DATABASE <database> TO <role>;
```

The first is the recommended split. The schema script *pre-checks* whether the extension is
installed, so once it is, the extension statement never runs again — an unprivileged role starts
against it indefinitely, and there is no `NOTICE` in the log either. A store that hits neither
option fails to start (the script is a single transaction, so nothing is half-created) with an
error that names both remedies.

Extension placement does not matter: `CREATE EXTENSION btree_gin SCHEMA extensions`, the convention
on several managed offerings, serves the index just as well, and the application role needs no
`USAGE` on that schema — resolving the default GIN operator class for `text` is not filtered by
`search_path`.

Check the privileges of a role before deploying it:

```sql
SELECT has_database_privilege('<role>', current_database(), 'CREATE') AS can_create_extension,
       has_schema_privilege('<role>', current_schema(), 'CREATE')     AS can_create_tables,
       EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gin') AS extension_installed;
```

`can_create_extension` only has to be true when `extension_installed` is false.


## Backup and restore

**Back the cluster up physically, never with `pg_dump`.** `pg_basebackup` with WAL archiving,
pgBackRest, Barman, a filesystem or managed-service snapshot, or a promoted replica all restore
`pg_control` with the transaction counter and epoch intact, so the next transaction id the restored
cluster assigns is above every stored `event_tx`. A restore of that kind needs nothing from this
library and is the right routine backup for an eventstore. (The visibility notes say `pg_dump` is
harmless, and it is — *as a reader*. The problem below is with restoring from one.)

**What a logical dump does to this store.** `pg_dump` copies `event_tx` as plain data, so a
`pg_restore` into a fresh cluster keeps the source cluster's transaction ids. `xid8` carries the
epoch, so on a cluster that has been running for a while those are in the billions, while a fresh
cluster hands out ids from a few hundred. Two things then fail, both silently — nothing throws and
nothing is logged:

- **The history reads as absent.** Every query, `head()`, every projection and every DCB check sits
  behind `event_tx < pg_snapshot_xmin(pg_current_snapshot())`. Restored events with an id above the
  new cluster's counter fail that test until the counter passes them, which is years away; meanwhile
  `SELECT count(*)` in psql shows every row.
- **New events sort before all of history.** An append gets a low id, and in the
  `(event_tx, event_position)` order it lands before every restored event. A bookmark restored at the
  old head is then ahead of every new event, so projectors never advance; a lock check whose
  reference is in history sees nothing after it and admits every conflict.

Measured on a source cluster at epoch 1 (`event_tx` ≈ 4.56 billion) dumped into a fresh PostgreSQL 16
whose next id was 757: 2001 events restored, 0 visible to a read, 51 of 51 stream heads above the
counter, and the first event appended afterwards ordered *first* in its stream with nothing after the
restored bookmark. The 32-bit wraparound itself is not the issue — `xid8` never wraps in practice,
and freezing does not touch stored values — the counter is simply younger than the data.

**The store refuses to start in that state.** `build()` checks, whatever the `DatabaseInitMode`,
that the newest stored event in the `(event_tx, event_position)` order does not carry a transaction
id at or above the next id the cluster will assign (`pg_snapshot_xmax(pg_current_snapshot())` —
never `pg_current_xact_id()`, which would assign an id to the checking connection and make startup
the kind of writing transaction the visibility notes warn about). No append can produce such a row,
so a hit is unambiguous, and it is fatal: the storage is closed and `build()` throws an
`EventStorageException` naming the highest stored id, the cluster's next id, how many events and
streams are affected and the three remedies below. The check is one probe off
`idx_events_global_order`, the global order, walked backwards from its last leaf — no scan of the
events table, no sort, and no walk of the streams, which on a per-entity layout would be a probe per
entity on every start — so it costs well under a millisecond whatever the store holds; the count of
affected events and streams is a second statement, a range walk over the same index, run only once
the probe has found the store ahead of the cluster. A database not yet carrying that index (see
"Migrating a database created before the order indexes carried their admission predicates") answers
the check with a scan of the table under `NONE`. `PostgresRestoredIntoYoungerClusterTest` pins the
refusal, the closed storage, and the plan shape.
What the check cannot catch is a store already appended to after such a restore *and* since had its
counter moved past the history: those low-id events stay mis-ordered, and nothing distinguishes them
from ordinary ones any more — which is why it is worth catching on the first start.

**Three ways out, in the order to prefer them:**

1. **Restore physically instead.** If a physical backup exists, use it; nothing else is needed.
2. **Move the counter, if nothing has been appended yet.** On the *stopped* restored cluster,
   `pg_resetwal -e <epoch> -x <xid>` sets the next transaction id; choose a value above the highest
   stored `event_tx` (the error message carries it) and follow the `pg_resetwal` documentation for
   choosing a safe one. The error reports the id as one `xid8` number, and `pg_resetwal` takes its
   two halves as separate flags — `xid8` = epoch × 2³² + xid, so the epoch is the high 32 bits and
   the xid the low 32. Worked through, for an error reading
   `transaction id (up to 4564383641, ...) is at or above the next id this PostgreSQL cluster will assign (757)`:

   ```
   4564383641 = 1 × 2³² + 269416345         -- epoch 1, xid 269416345 = 0x100EF799
   ```

   The xid then has to be rounded up the way the `pg_resetwal` documentation prescribes for a safe
   value — to the next multiple of 0x100000 above it, since `pg_xact` is kept in segments of that
   many ids and the counter must start on a segment boundary: `0x100EF799` lies in segment `0x100`,
   so the next boundary is `(0x100 + 1) × 0x100000 = 0x10100000`. Both flags are needed: a fresh
   cluster is at epoch 0, and `-x` alone would leave the counter at `0 × 2³² + 0x10100000`, still
   below every stored id.

   ```
   pg_ctl stop -D <datadir>
   pg_resetwal -e 1 -x 0x10100000 -D <datadir>
   pg_ctl start -D <datadir>
   ```

   The counter is now `1 × 2³² + 0x10100000 = 4564451328`, above the reported 4564383641, which
   `SELECT pg_snapshot_xmax(pg_current_snapshot())` confirms once the cluster is up; `pg_controldata`
   shows the same value as `Latest checkpoint's NextXID: 1:269484032` (`epoch:xid`) while it is
   stopped. Verified: after exactly that `pg_resetwal -e 1 -x 0x10100000` on the cluster above, every
   event was visible again and the check passed. The one event appended before the reset stayed
   ordered first — so this is only a fix while the restored store has not been written to. Managed
   services do not expose `pg_resetwal`, which is one more reason the next option is the portable one.
3. **Copy the events into a fresh store with `EventStoreImporter`.** This is the supported way to
   move a store between clusters — a major-version upgrade by dump, a cloud migration, a change of
   hosting. `importEvents` binds no `event_tx` and no `event_position`: the target assigns both, in
   source order, so the ordering is the new cluster's own and nothing is hidden. What the importer does
   *not* carry, and the runbook therefore has to:
   - **The `btree_gin` extension** on the target database (a `pg_dump -t` of the tables does not
     include it, and the restore fails creating `idx_events_stream_tags` without it). Let `ENSURE`
     create the target schema, or install the extension first.
   - **Bookmarks.** Copy `<prefix>bookmarks` across after the events: a bookmark stores only the
     `event_id`, the import preserves ids, and the store answers a bookmark's position and transaction
     from the target's own events row — so the copied table is valid as it stands and the foreign key
     holds.
   - **Shredding keys.** Copy `<prefix>shredding_keys` alongside — shredded rows included, so erased
     values still read as erased. A key the target store never held fails the read rather than reading
     as erased, so a store whose keys were left behind cannot read any protected value at all.
   - **Leases** are deliberately not migrated; they expire.
   - **Anything outside the store holding event references** — an SQL read model's own bookmark and
     freshness columns, say — holds the source's coordinates too. Rebuild such read models on the
     target rather than resuming them.

   Import in batches with `SKIP_EXISTING_ID` so a failed run resumes, and feed `ImportReport.sourceTo()`
   into a later run's `.after(...)` to pick up events appended to the source during the cutover.

## Example queries 

Specific syntax is used on on GIN-indexed Tags:

```
SELECT * FROM events WHERE 'tagName:123' = ANY(event_tags);
```

```
SELECT * FROM events WHERE event_tags && ARRAY['tagName:123', 'otherTagName:456'];
```

```
SELECT * FROM events WHERE event_tags @> ARRAY['tagName:123', 'active'];
```



## Performance analysis

```
EXPLAIN (ANALYZE, BUFFERS) 
SELECT * FROM events 
WHERE stream_context='value1' 
  AND stream_purpose='value2' 
  AND event_type IN ('one', 'two', 'three') 
  AND event_tags @> ARRAY['tag1', 'tag2'];
```