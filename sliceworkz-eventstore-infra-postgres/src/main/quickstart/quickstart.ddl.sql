--
-- Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
-- Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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

DROP TABLE IF EXISTS events CASCADE;
CREATE TABLE events (
      -- Primary key and positioning
      event_position BIGSERIAL PRIMARY KEY,

      -- Event identification
      event_id UUID NOT NULL UNIQUE,

      -- Stream identification  
      stream_context TEXT NOT NULL,
      stream_purpose TEXT NOT NULL DEFAULT '',

      -- Event metadata
      event_type TEXT NOT NULL,

      -- Transaction information
      event_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

      -- Event payload
      event_data JSONB NOT NULL,
      event_erasable_data JSONB,

      -- Tags as string array
      event_tags TEXT[] DEFAULT '{}'
  );


	-- Allows efficient filtering on multiple dimensions
	-- Primary index for your most common query pattern
	-- B-tree handles equality (=) and IN clauses efficiently
	DROP INDEX IF EXISTS idx_events_stream_type_position;
	CREATE INDEX idx_events_stream_type_position ON events (
	    stream_context, 
	    stream_purpose, 
	    event_type,
	    event_position  -- for ordering
	);
	
	-- Separate GIN index ONLY for tag filtering
	DROP INDEX IF EXISTS idx_events_tags;
	CREATE INDEX idx_events_tags ON events USING GIN (event_tags);
	
	-- Keep stream position index for stream reads
	DROP INDEX IF EXISTS idx_events_stream_position;
	CREATE INDEX idx_events_stream_position ON events (
	    stream_context, 
	    stream_purpose, 
	    event_position
	);


---- LOCK BEFORE INSERT TO ENSURE POSITION AND COMMIT SEQUENCES ARE ALIGNED

CREATE OR REPLACE FUNCTION lock_before_insert()
RETURNS TRIGGER AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(TG_RELID::int);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER lock_trigger
  BEFORE INSERT ON events
  FOR EACH ROW
  EXECUTE FUNCTION lock_before_insert();


---- EVENT APPEND NOTIFICATIONS

CREATE OR REPLACE FUNCTION notify_event_appended()
RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('event_appended',
        jsonb_build_object(
            'streamContext', NEW.stream_context,
            'streamPurpose', NEW.stream_purpose,
            'eventPosition', NEW.event_position,
            'eventId', NEW.event_id
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER table_insert_trigger
    AFTER INSERT ON events
    FOR EACH ROW
    EXECUTE FUNCTION notify_event_appended();
    


---- BOOKMARKING 
    
DROP TABLE IF EXISTS bookmarks CASCADE;  
CREATE TABLE IF NOT EXISTS bookmarks (
      reader VARCHAR(255) PRIMARY KEY,
      event_position BIGINT NOT NULL,
      event_id UUID NOT NULL,
      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      updated_tags TEXT[] DEFAULT '{}',
      CONSTRAINT fk_bookmarks_event_id
          FOREIGN KEY (event_id)
          REFERENCES events(event_id)
          ON DELETE CASCADE
  );

  CREATE INDEX IF NOT EXISTS idx_bookmarks_updated_at ON bookmarks(updated_at);


    
CREATE OR REPLACE FUNCTION notify_bookmark_placed()
RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('bookmark_placed',
        jsonb_build_object(
            'reader', NEW.reader,
            'eventPosition', NEW.event_position,
            'eventId', NEW.event_id
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER table_insert_or_update_trigger
    AFTER INSERT OR UPDATE ON bookmarks
    FOR EACH ROW
    EXECUTE FUNCTION notify_bookmark_placed();
    
