
# Postgres Storage for Eventstore

Create a database schema with the DDL scripts found in 'ensure-schema.sql',
removing "PREFIX_" or replacing it to manage different stores next to each other.
Use 'drop-schema.sql' to drop existing schema objects before recreating.
 


## Database privileges

What the application role needs depends on the `DatabaseInitMode` it starts with, and on one thing
that is not a table: the **`btree_gin` extension**, which the combined stream+tags GIN index
(`idx_events_stream_tags`) is built on and which schema validation requires.

| mode | what the role must be allowed to do |
|---|---|
| `NONE`, `VALIDATE` | `CONNECT`, `USAGE` on the schema, and no DDL at all — see the runtime grants below |
| `ENSURE` (default) | the above, plus `CREATE` on the **schema** — and, *only if `btree_gin` is not installed yet*, `CREATE` on the **database** |
| `INITIALIZE` | the above, plus ownership of the store's tables and functions (it drops them) |

Every mode needs these at runtime. They come for free when the role created the tables itself; when a
DBA created them, they have to be granted:

```sql
GRANT SELECT, INSERT                 ON <prefix>events           TO <role>;
GRANT SELECT, INSERT, UPDATE, DELETE ON <prefix>bookmarks        TO <role>;
GRANT SELECT, INSERT, UPDATE         ON <prefix>leases           TO <role>;
GRANT SELECT, INSERT, UPDATE, DELETE ON <prefix>lease_contenders TO <role>;
GRANT USAGE                          ON SEQUENCE <prefix>events_event_position_seq TO <role>;
```

Events are never updated or deleted — the store only ever appends to that table. Lease rows are
never deleted either (a release backdates the heartbeat so the fencing token survives), so the
leases table needs no `DELETE`; contender rows are pruned, so that table does.

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