
----
---- __NOTICE__
----
---- Eventstore database schema DDL
----
---- 
---- "PREFIX_" can be removed or replaced to allow multiple eventstores next to each other in one database schema
---- 



---- EVENTS

DROP TABLE IF EXISTS PREFIX_events CASCADE;
CREATE TABLE PREFIX_events (
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

      -- Tags as string array
      event_tags TEXT[] DEFAULT '{}'
  );


	-- Allows efficient filtering on multiple dimensions
	-- Primary index for your most common query pattern
	-- B-tree handles equality (=) and IN clauses efficiently
	CREATE INDEX PREFIX_idx_events_stream_type_position ON PREFIX_events (
	    stream_context, 
	    stream_purpose, 
	    event_type,
	    event_position  -- for ordering
	);
	
	-- Separate GIN index ONLY for tag filtering
	CREATE INDEX PREFIX_idx_events_tags ON PREFIX_events USING GIN (event_tags);
	
	-- Keep stream position index for stream reads
	CREATE INDEX PREFIX_idx_events_stream_position ON PREFIX_events (
	    stream_context, 
	    stream_purpose, 
	    event_position
	);


---- EVENT APPEND NOTIFICATIONS

CREATE OR REPLACE FUNCTION PREFIX_notify_event_appended()
RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('PREFIX_event_appended',
        jsonb_build_object(
            'streamContext', NEW.stream_context,
            'streamPurpose', NEW.stream_purpose,
            'eventPosition', NEW.event_position,
            'eventId', NEW.event_id,
            'eventType', NEW.event_type
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER table_insert_trigger
    AFTER INSERT ON PREFIX_events
    FOR EACH ROW
    EXECUTE FUNCTION PREFIX_notify_event_appended();
    


---- BOOKMARKING 
    
DROP TABLE IF EXISTS PREFIX_bookmarks CASCADE;  
CREATE TABLE IF NOT EXISTS PREFIX_bookmarks (
      reader VARCHAR(255) PRIMARY KEY,
      event_position BIGINT NOT NULL,
      event_id UUID NOT NULL,
      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      updated_tags TEXT[] DEFAULT '{}',
      CONSTRAINT fk_bookmarks_event_id
          FOREIGN KEY (event_id)
          REFERENCES PREFIX_events(event_id)
          ON DELETE CASCADE
  );

  CREATE INDEX IF NOT EXISTS PREFIX_idx_bookmarks_updated_at ON PREFIX_bookmarks(updated_at);


    
CREATE OR REPLACE FUNCTION PREFIX_notify_bookmark_placed()
RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('PREFIX_bookmark_placed',
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
    AFTER INSERT OR UPDATE ON PREFIX_bookmarks
    FOR EACH ROW
    EXECUTE FUNCTION PREFIX_notify_bookmark_placed();
    
