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
---- __NOTICE__
----
---- Eventstore database schema DDL
----
----
---- "PREFIX" can be removed or replaced to allow multiple eventstores next to each other in one database schema
----



---- EVENTS

CREATE TABLE IF NOT EXISTS PREFIX_events (
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
	CREATE INDEX IF NOT EXISTS PREFIX_idx_events_position_brin ON PREFIX_events USING BRIN (event_position);

	-- Allows efficient filtering on multiple dimensions
	-- Primary index for your most common query pattern
	-- B-tree handles equality (=) and IN clauses efficiently
	CREATE INDEX IF NOT EXISTS PREFIX_idx_events_stream_type_position ON PREFIX_events (
	    stream_context,
	    stream_purpose,
	    event_type,
	    event_tx,
	    event_position  -- for ordering
	);

	-- Separate GIN index ONLY for tag filtering
	CREATE INDEX IF NOT EXISTS PREFIX_idx_events_tags ON PREFIX_events USING GIN (event_tags);

	-- Combined stream + tags index: serves DCB reads that scope by stream AND filter by
	-- tags in a single index (the B-tree indexes above cannot cover the tag containment,
	-- and the tags-only GIN index above cannot cover the stream scope). Requires the
	-- btree_gin extension to index the scalar stream columns alongside the tag array.
	-- NB: GIN cannot serve ORDER BY event_position, so the B-tree indexes are kept for
	-- ordered stream replay; this index is additive, for stream-scoped tag lookups.
	CREATE EXTENSION IF NOT EXISTS btree_gin;
	CREATE INDEX IF NOT EXISTS PREFIX_idx_events_stream_tags ON PREFIX_events USING GIN (
	    stream_context,
	    stream_purpose,
	    event_tags
	);

	-- Keep stream position index for stream reads
	CREATE INDEX IF NOT EXISTS PREFIX_idx_events_stream_position ON PREFIX_events (
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
	CREATE UNIQUE INDEX IF NOT EXISTS PREFIX_idx_events_stream_idempotency ON PREFIX_events (
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
CREATE OR REPLACE FUNCTION PREFIX_notify_event_appended()
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
        PERFORM pg_notify('PREFIX_event_appended',
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
        AND c.relname = 'PREFIX_events'
        AND t.tgname = 'table_insert_trigger'
        AND NOT t.tgisinternal
        AND t.tgtype = 4
        AND t.tgnewtable = 'inserted'
        AND t.tgfoid = 'PREFIX_notify_event_appended'::regproc
  ) THEN
    DROP TRIGGER IF EXISTS table_insert_trigger ON PREFIX_events;
    CREATE TRIGGER table_insert_trigger
        AFTER INSERT ON PREFIX_events
        REFERENCING NEW TABLE AS inserted
        FOR EACH STATEMENT
        EXECUTE FUNCTION PREFIX_notify_event_appended();
  END IF;
END $$;



---- BOOKMARKING

CREATE TABLE IF NOT EXISTS PREFIX_bookmarks (
      reader TEXT PRIMARY KEY,
      event_position BIGINT NOT NULL,
      event_id UUID NOT NULL,
      event_tx xid8 NOT NULL,
      updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
      updated_tags TEXT[] DEFAULT '{}',
      CONSTRAINT fk_bookmarks_event_id
          FOREIGN KEY (event_id)
          REFERENCES PREFIX_events(event_id)
          ON DELETE CASCADE
  );

  CREATE INDEX IF NOT EXISTS PREFIX_idx_bookmarks_event_id ON PREFIX_bookmarks(event_id);


-- Deliberately still FOR EACH ROW, unlike the events trigger above. bookmark() is a single-row
-- upsert keyed on the reader, so one row per statement is all there ever is: per-row and
-- per-statement are the same count here, and a transition table would only add a tuplestore for one
-- row. The append amplification does not exist on this table.
--
-- CREATE OR REPLACE for the same reason as PREFIX_notify_event_appended above.
CREATE OR REPLACE FUNCTION PREFIX_notify_bookmark_placed()
RETURNS trigger AS $fn$
BEGIN
    PERFORM pg_notify('PREFIX_bookmark_placed',
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
        AND c.relname = 'PREFIX_bookmarks'
        AND t.tgname = 'table_insert_or_update_trigger'
        AND NOT t.tgisinternal
        AND t.tgtype = 21
        AND t.tgfoid = 'PREFIX_notify_bookmark_placed'::regproc
  ) THEN
    DROP TRIGGER IF EXISTS table_insert_or_update_trigger ON PREFIX_bookmarks;
    CREATE TRIGGER table_insert_or_update_trigger
        AFTER INSERT OR UPDATE ON PREFIX_bookmarks
        FOR EACH ROW
        EXECUTE FUNCTION PREFIX_notify_bookmark_placed();
  END IF;
END $$;
