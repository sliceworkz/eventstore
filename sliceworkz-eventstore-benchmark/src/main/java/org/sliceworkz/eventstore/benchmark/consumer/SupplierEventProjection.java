/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sliceworkz.eventstore.benchmark.consumer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.SupplierEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.BatchAwareProjection;
import org.sliceworkz.eventstore.query.EventQuery;

public class SupplierEventProjection implements BatchAwareProjection<SupplierEvent> {

	private static final Logger LOGGER = LoggerFactory.getLogger(SupplierEventProjection.class);
	private static final String INSERT_SQL = "INSERT INTO benchmark_readmodel (event_id, event_position) VALUES (?, ?)";

	private final DataSource dataSource;
	private Connection connection;
	private PreparedStatement insertStatement;

	private AtomicLong eventsProcessed = new AtomicLong();
	private AtomicLong batchesProcessed = new AtomicLong();

	public SupplierEventProjection(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	public long eventsProcessed ( ) {
		return eventsProcessed.get();
	}

	public long batchesProcessed ( ) {
		return batchesProcessed.get();
	}

	@Override
	public EventQuery eventQuery() {
		return EventQuery.matchAll();
	}

	@Override
	public void when(Event<SupplierEvent> eventWithMeta) {
		try {
			insertStatement.setString(1, eventWithMeta.reference().id().value());
			insertStatement.setLong(2, eventWithMeta.reference().position());
			insertStatement.addBatch();
			long idx = eventsProcessed.incrementAndGet();
		} catch (SQLException e) {
			LOGGER.error("Failed to insert event", e);
			throw new RuntimeException("Failed to insert event", e);
		}
	}

	@Override
	public void beforeBatch() {
		try {
			connection = dataSource.getConnection();
			connection.setAutoCommit(false);
			insertStatement = connection.prepareStatement(INSERT_SQL);
		} catch (SQLException e) {
			LOGGER.error("Failed to start transaction", e);
			throw new RuntimeException("Failed to start transaction", e);
		}
	}

	@Override
	public void cancelBatch() {
		try {
			if (insertStatement != null) {
				insertStatement.close();
			}
			if (connection != null) {
				connection.rollback();
				connection.close();
			}
		} catch (SQLException e) {
			LOGGER.error("Failed to rollback transaction", e);
			throw new RuntimeException("Failed to rollback transaction", e);
		} finally {
			insertStatement = null;
			connection = null;
		}
	}

	@Override
	public void afterBatch(Optional<EventReference> lastEventReference) {
		try {
			if (insertStatement != null) {
				insertStatement.executeBatch();
				insertStatement.close();
			}
			if (connection != null) {
				connection.commit();
				connection.close();
			}
			batchesProcessed.incrementAndGet();
		} catch (SQLException e) {
			LOGGER.error("Failed to commit transaction", e);
			throw new RuntimeException("Failed to commit transaction", e);
		} finally {
			insertStatement = null;
			connection = null;
		}
	}

}
