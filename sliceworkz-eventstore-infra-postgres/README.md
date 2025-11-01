
# Postgres Storage for Eventstore

Create a database schema with the DDL scripts found in 'initialisation.sql', 
removing "PREFIX_" or replacing it to manage different stores next to each other.
 


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