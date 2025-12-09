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


-- BASIC INFO : NUMBER OF EVENTS, TIMESTAMP OF FIRST AND LAST

SELECT 
	count(*), MIN(event_timestamp), MAX(event_timestamp)
FROM benchmark_events

-- COUNT THE NUMBER OF EVENTS PRODUCED PER SECOND

SELECT 
    date_bin('1 second', event_timestamp, TIMESTAMP '2000-01-01') AS time_bucket,
    COUNT(*) AS event_count
FROM benchmark_events
GROUP BY time_bucket
ORDER BY time_bucket;

-- EACH EVENT WITH PROCESSING DELAY

  SELECT
      e.event_id as event_id,
      e.event_position,
      e.event_timestamp as event_timestamp,
      e.event_type as event_type,
      r.processed_at as processed_timestamp,
      EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000 as latency_ms
  FROM benchmark_events e
  INNER JOIN benchmark_readmodel r ON e.event_position = r.event_position
  ORDER BY e.event_position;

-- MIN, MAX, AVG, MEG, P90 AND P95 VALUES
  
  SELECT
      COUNT(*) as total_events,
      AVG(EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as avg_latency_ms,
      MIN(EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as min_latency_ms,
      MAX(EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as max_latency_ms,
      PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as median_latency_ms,
      PERCENTILE_CONT(0.90) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as p90_latency_ms,
      PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as p95_latency_ms

  FROM benchmark_events e
  INNER JOIN benchmark_readmodel r ON e.event_position = r.event_position;

