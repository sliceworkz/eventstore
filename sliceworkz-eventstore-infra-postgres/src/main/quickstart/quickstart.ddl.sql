--
-- Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
-- Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
--
-- This program is free software: you can redistribute it and/or modify
-- it under the terms of the GNU Lesser General Public License as published by
-- the Free Software Foundation, either version 3 of the License, or
-- (at your option) any later version.
--
-- This program is distributed in the hope that it will be useful,
-- but WITHOUT ANY WARRANTY; without even the implied warranty of
-- MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
-- GNU Lesser General Public License for more details.
--
-- You should have received a copy of the GNU Lesser General Public License
-- along with this program.  If not, see <http://www.gnu.org/licenses/>.
--

----
---- !!! GENERATED FILE - DO NOT EDIT !!!
----
---- Eventstore database schema DDL
----
----
---- "PREFIX" can be removed or replaced to allow multiple eventstores next to each other in one database schema
----



---- EVENTS

CREATE TABLE IF NOT EXISTS events (
      -- Primary key and positioning
      event_position BIGSERIAL PRIMARY KEY,

      -- XID8 transaction id
      event_tx xid8 DEFAULT pg_current_xact_id()::xid8 NOT NULL,

      -- Event identification
      event_id UUID NOT NULL UNIQUE,

      -- Idempotency key (uniqueness is scoped per stream via idx_events_stream_idempotency below,
      -- not globally, so the same key on different streams does not collide)
      idempotency_key TEXT,

      -- Stream identification
      stream_context TEXT NOT NULL,
      stream_purpose TEXT NOT NULL DEFAULT 'default',

      -- Event metadata
      event_type TEXT NOT NULL,

      -- Transaction information
      event_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

      -- Event payload
      event_data JSONB NOT NULL,
      event_erasable_data JSONB,

      -- Tags as string array
      event_tags TEXT[] DEFAULT '{}'

  ) WITH (FILLFACTOR = 100);


	-- Compact BRIN index on event_position
	CREATE INDEX IF NOT EXISTS idx_events_position_brin ON events USING BRIN (event_position);

	-- Allows efficient filtering on multiple dimensions
	-- Primary index for your most common query pattern
	-- B-tree handles equality (=) and IN clauses efficiently
	CREATE INDEX IF NOT EXISTS idx_events_stream_type_position ON events (
	    stream_context,
	    stream_purpose,
	    event_type,
	    event_tx,
	    event_position  -- for ordering
	);

	-- Separate GIN index ONLY for tag filtering
	CREATE INDEX IF NOT EXISTS idx_events_tags ON events USING GIN (event_tags);

	-- Combined stream + tags index: serves DCB reads that scope by stream AND filter by
	-- tags in a single index (the B-tree indexes above cannot cover the tag containment,
	-- and the tags-only GIN index above cannot cover the stream scope). Requires the
	-- btree_gin extension to index the scalar stream columns alongside the tag array.
	-- NB: GIN cannot serve ORDER BY event_position, so the B-tree indexes are kept for
	-- ordered stream replay; this index is additive, for stream-scoped tag lookups.
	--
	-- Guarded rather than a bare CREATE EXTENSION IF NOT EXISTS. Both reasons concern only the very
	-- first start against a database that does not have the extension yet -- afterwards this is a
	-- catalog read that issues no DDL at all -- but on that first start the whole script is one
	-- transaction, so a failure here rolls the entire schema back and the store does not come up.
	--
	-- Privileges. btree_gin is trusted (since PostgreSQL 13), so no superuser is needed -- but
	-- installing it requires CREATE on the *database*, which is a different privilege from CREATE on
	-- the schema. The ordinary locked-down setup (GRANT CREATE ON SCHEMA app TO app_role, nothing on
	-- the database) is therefore a role that creates every other object in this script and fails on
	-- this one statement, with a bare "permission denied to create extension" as the only clue. The
	-- pre-check is what makes the DBA-installs-it-once split work: an unprivileged role runs this
	-- script forever after without touching the extension. The handler makes the remaining case name
	-- its two remedies instead of leaving them to be deduced.
	--
	-- Concurrency. An extension is database-scoped, while the schema advisory lock the caller holds is
	-- keyed on the table prefix -- so two stores with *different* prefixes starting together are not
	-- serialized against each other here, and race on pg_extension_name_index exactly as the tables
	-- used to race on pg_type_typname_nsp_index before that lock existed. duplicate_object (the
	-- extension appeared between the check and the CREATE) and unique_violation (the raw catalog
	-- conflict) both mean the same harmless thing: someone else has just installed it. By the time
	-- either surfaces the winner has committed -- a conflicting catalog insert blocks until it does --
	-- so the opclasses the index below needs are visible to this transaction.
	DO $ext$ BEGIN
	  IF NOT EXISTS ( SELECT 1 FROM pg_extension WHERE extname = 'btree_gin' ) THEN
	    BEGIN
	      CREATE EXTENSION btree_gin;
	    EXCEPTION
	      WHEN duplicate_object OR unique_violation THEN
	        NULL;
	      WHEN insufficient_privilege THEN
	        RAISE EXCEPTION 'The eventstore requires the btree_gin extension, and this role may not create it'
	          USING ERRCODE = 'insufficient_privilege',
	                DETAIL  = 'btree_gin is a trusted extension, so no superuser is involved, but creating it '
	                          'requires CREATE on the current database -- a different privilege from CREATE on '
	                          'the schema. That is why this role can create every other object in the event '
	                          'store schema and not this one. It is needed for the combined stream+tags GIN '
	                          'index, which schema validation requires.',
	                HINT    = 'Either have a DBA run "CREATE EXTENSION btree_gin;" in this database once, after '
	                          'which this schema script needs no extra privilege at any later start, or grant '
	                          'the privilege with "GRANT CREATE ON DATABASE <database> TO <role>;".';
	    END;
	  END IF;
	END $ext$;
	CREATE INDEX IF NOT EXISTS idx_events_stream_tags ON events USING GIN (
	    stream_context,
	    stream_purpose,
	    event_tags
	);

	-- Keep stream position index for stream reads
	CREATE INDEX IF NOT EXISTS idx_events_stream_position ON events (
	    stream_context,
	    stream_purpose,
	    event_tx,
	    event_position
	);

	-- Idempotency: uniqueness of the idempotency key is scoped to the logical event stream
	-- (context + purpose), NOT the whole table, so the same key used on two unrelated streams
	-- does not collide and dedup behaviour does not depend on how storage instances / prefixes
	-- are wired at runtime. Partial (keyless events - the majority - are not indexed) and named
	-- so it can be validated and so its name surfaces in the unique-violation error.
	CREATE UNIQUE INDEX IF NOT EXISTS idx_events_stream_idempotency ON events (
	    stream_context,
	    stream_purpose,
	    idempotency_key
	) WHERE idempotency_key IS NOT NULL;


---- EVENT APPEND NOTIFICATIONS

-- One notification per (stream_context, stream_purpose) touched by the statement, NOT one per row.
--
-- A row-level trigger here made every write amplify: a 1000-event append queued 1000 notifications,
-- and an import chunk queued 5000, of which the consumer uses exactly one per stream --
-- OptimizingApendListenerDecorator collapses a burst to the newest reference before the delegate is
-- called, so the rest were built as JSON, written to the cluster-wide async queue, sent over the wire,
-- parsed by Jackson and fanned out to every listener only to lose a comparison. This also aligns the
-- Postgres backend with the in-memory ones, which have always notified once per stream per append.
--
-- The reference carried is the maximum over the total (event_tx, event_position) order -- the order
-- EventReference.happenedAfter defines and reads are sorted by -- and NOT the maximum position. The
-- two genuinely disagree: event_position is a bigserial and event_tx is assigned independently, so a
-- row can hold the highest position while another holds the higher transaction. DISTINCT ON returns
-- the whole winning row, so event_id always belongs to the reference being reported rather than being
-- aggregated separately from it. event_tx is compared as xid8 (numeric, unsigned) and rendered by
-- jsonb_build_object as a JSON string, exactly as the row-level version did, so the payload shape the
-- Java side parses is unchanged.
--
-- CREATE OR REPLACE, not a create-if-absent guard: a function body changed by a later release has
-- to reach databases that already have the old one. A guard that skips an existing function leaves
-- the old body in place forever and reports success -- and because drop-schema.sql only drops the
-- tables, not even INITIALIZE would replace it.
CREATE OR REPLACE FUNCTION notify_event_appended()
RETURNS trigger AS $fn$
DECLARE
    latest record;
BEGIN
    FOR latest IN
        SELECT DISTINCT ON (stream_context, stream_purpose)
               stream_context, stream_purpose, event_position, event_tx, event_id
        FROM inserted
        ORDER BY stream_context, stream_purpose, event_tx DESC, event_position DESC
    LOOP
        PERFORM pg_notify('event_appended',
            jsonb_build_object(
                'streamContext', latest.stream_context,
                'streamPurpose', latest.stream_purpose,
                'eventPosition', latest.event_position,
                'eventTx',       latest.event_tx,
                'eventId',       latest.event_id
            )::text
        );
    END LOOP;
    RETURN NULL;
END;
$fn$ LANGUAGE plpgsql;

-- Recreate the trigger only when it is absent or does not have the shape this release wants.
-- PostgreSQL 14 added CREATE OR REPLACE TRIGGER, but it rewrites unconditionally and so takes an
-- ACCESS EXCLUSIVE lock on the events table on every single startup of every instance. Comparing
-- first makes the common case -- the trigger is already correct -- a catalog read that locks nothing,
-- and it also repairs a trigger whose timing, orientation or target function has drifted. A database
-- carrying the old row-level trigger is repaired here, which is what migrates it to per-statement
-- notifications with no operator action.
DO $$ BEGIN
  -- tgtype is a bitmask: ROW = 1, BEFORE = 2, INSERT = 4, DELETE = 8, UPDATE = 16, TRUNCATE = 32.
  -- AFTER INSERT ... FOR EACH STATEMENT leaves the ROW bit clear, so it is INSERT alone = 4. (The
  -- row-level version this replaced was ROW | INSERT = 5, so an un-migrated trigger fails the compare
  -- and is recreated.) tgnewtable is checked too: the function reads the "inserted" transition table,
  -- so a statement-level trigger declared without REFERENCING would fail at runtime, not at startup.
  IF NOT EXISTS (
      SELECT 1
      FROM pg_trigger t
      JOIN pg_class c ON t.tgrelid = c.oid
      JOIN pg_namespace n ON c.relnamespace = n.oid
      WHERE n.nspname = current_schema()
        AND c.relname = 'events'
        AND t.tgname = 'table_insert_trigger'
        AND NOT t.tgisinternal
        AND t.tgtype = 4
        AND t.tgnewtable = 'inserted'
        AND t.tgfoid = 'notify_event_appended'::regproc
  ) THEN
    DROP TRIGGER IF EXISTS table_insert_trigger ON events;
    CREATE TRIGGER table_insert_trigger
        AFTER INSERT ON events
        REFERENCING NEW TABLE AS inserted
        FOR EACH STATEMENT
        EXECUTE FUNCTION notify_event_appended();
  END IF;
END $$;



---- BOOKMARKING

CREATE TABLE IF NOT EXISTS bookmarks (
      reader TEXT PRIMARY KEY,
      event_position BIGINT NOT NULL,
      event_id UUID NOT NULL,
      event_tx xid8 NOT NULL,
      updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
      updated_tags TEXT[] DEFAULT '{}',
      CONSTRAINT fk_bookmarks_event_id
          FOREIGN KEY (event_id)
          REFERENCES events(event_id)
          ON DELETE CASCADE
  );

  CREATE INDEX IF NOT EXISTS idx_bookmarks_event_id ON bookmarks(event_id);


-- Deliberately still FOR EACH ROW, unlike the events trigger above. bookmark() is a single-row
-- upsert keyed on the reader, so one row per statement is all there ever is: per-row and
-- per-statement are the same count here, and a transition table would only add a tuplestore for one
-- row. The append amplification does not exist on this table.
--
-- CREATE OR REPLACE for the same reason as notify_event_appended above.
CREATE OR REPLACE FUNCTION notify_bookmark_placed()
RETURNS trigger AS $fn$
BEGIN
    PERFORM pg_notify('bookmark_placed',
        jsonb_build_object(
            'reader', NEW.reader,
            'eventTx', NEW.event_tx,
            'eventPosition', NEW.event_position,
            'eventId', NEW.event_id
        )::text
    );
    RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

DO $$ BEGIN
  -- tgtype bitmask as above: AFTER INSERT OR UPDATE ... FOR EACH ROW is ROW | INSERT | UPDATE = 21.
  IF NOT EXISTS (
      SELECT 1
      FROM pg_trigger t
      JOIN pg_class c ON t.tgrelid = c.oid
      JOIN pg_namespace n ON c.relnamespace = n.oid
      WHERE n.nspname = current_schema()
        AND c.relname = 'bookmarks'
        AND t.tgname = 'table_insert_or_update_trigger'
        AND NOT t.tgisinternal
        AND t.tgtype = 21
        AND t.tgfoid = 'notify_bookmark_placed'::regproc
  ) THEN
    DROP TRIGGER IF EXISTS table_insert_or_update_trigger ON bookmarks;
    CREATE TRIGGER table_insert_or_update_trigger
        AFTER INSERT OR UPDATE ON bookmarks
        FOR EACH ROW
        EXECUTE FUNCTION notify_bookmark_placed();
  END IF;
END $$;



---- LEASES (leader election)

-- Coordination state, deliberately outside the event log: lease reads and writes never touch the
-- events table, take no lock any event query or append takes, and are not subject to the
-- pg_snapshot_xmin visibility barrier — a stalled event reader must not look like an expired lease,
-- and lease traffic must never delay an event read. Every timestamp in these tables is written and
-- compared with the database server's clock only; no contender's clock ever enters a comparison.
--
-- An expired or released lease keeps its row (release just backdates heartbeat_at), because the
-- fencing token must survive: it increments on every change of ownership and may never be handed
-- out twice.

CREATE TABLE IF NOT EXISTS leases (
      lease_name TEXT PRIMARY KEY,
      lease_owner TEXT NOT NULL,
      priority BIGINT NOT NULL,
      fencing_token BIGINT NOT NULL,
      ttl_millis BIGINT NOT NULL,
      acquired_at TIMESTAMP WITH TIME ZONE NOT NULL,
      heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL
  );

-- One row per (lease, contender), refreshed on every requestLease call. A contender is live while
-- its heartbeat is younger than its own ttl; a live contender with a strictly higher priority than
-- the current owner turns the owner's renewals into step-down requests. Rows long dead are pruned
-- opportunistically during requests.
CREATE TABLE IF NOT EXISTS lease_contenders (
      lease_name TEXT NOT NULL,
      contender TEXT NOT NULL,
      priority BIGINT NOT NULL,
      ttl_millis BIGINT NOT NULL,
      heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
      PRIMARY KEY (lease_name, contender)
  );
