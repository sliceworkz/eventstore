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

-- DDL for benchmark_readmodel table
-- Conditional drop and create

DROP TABLE IF EXISTS benchmark_readmodel;

CREATE TABLE benchmark_readmodel (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    event_position BIGINT NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    readmodel_tx xid8 DEFAULT pg_current_xact_id()::xid8,
    readmodel_thread VARCHAR(255) NOT NULL
);

-- Index on event_position for potential queries
CREATE INDEX idx_benchmark_readmodel_position ON benchmark_readmodel(event_position);
