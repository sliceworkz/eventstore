/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventstore.infra.inmem.fs;

import java.nio.file.Path;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * Factory interface for creating in-memory event storage instances with filesystem persistence.
 * <p>
 * This is an add-on to the standard in-memory event storage that persists events and bookmarks
 * as JSON files on the filesystem. Events are stored in memory for fast access and simultaneously
 * written to disk so they survive application restarts. On startup, previously persisted events
 * and bookmarks are reloaded into memory.
 * <p>
 * This implementation is ideal for local development where you want persistence across restarts
 * without needing a PostgreSQL database. Events are stored as human-readable JSON files that can
 * be inspected, edited, or deleted manually.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Simple creation with default settings (stores in ./eventstore-data)
 * EventStore eventStore = InMemoryFsEventStorage.newBuilder().buildStore();
 *
 * // With custom directory
 * EventStore eventStore = InMemoryFsEventStorage.newBuilder()
 *     .directory("my-events")
 *     .buildStore();
 *
 * // With all options
 * EventStore eventStore = InMemoryFsEventStorage.newBuilder()
 *     .directory(Path.of("/tmp/my-eventstore"))
 *     .name("dev-store")
 *     .resultLimit(1000)
 *     .buildStore();
 * }</pre>
 *
 * <h2>File Layout:</h2>
 * <pre>
 * eventstore-data/
 *   events/
 *     0000000001-00001-2026-04-02-10-30-00-0000.json
 *     0000000002-00002-2026-04-02-10-30-01-0000.json
 *     ...
 *   bookmarks/
 *     my-projection.json
 *     ...
 * </pre>
 *
 * @see EventStorage
 * @see EventStore
 * @see org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage
 */
public interface InMemoryFsEventStorage {

	static Builder newBuilder ( ) {
		return InMemoryFsEventStorage.Builder.newBuilder();
	}

	class Builder {

		private Path directory = Path.of("eventstore-data");
		private Limit limit = Limit.none();
		private MeterRegistry meterRegistry = Metrics.globalRegistry;
		private String name = "inmem-fs-%s".formatted(System.identityHashCode(this));

		private Builder ( ) {

		}

		/**
		 * Sets the base directory for persisting events and bookmarks.
		 * <p>
		 * Events are stored under {@code <directory>/events/} and bookmarks under
		 * {@code <directory>/bookmarks/}. Directories are created automatically if they
		 * don't exist.
		 *
		 * @param directory the base directory path
		 * @return this Builder instance for method chaining
		 */
		public Builder directory ( Path directory ) {
			this.directory = directory;
			return this;
		}

		/**
		 * Sets the base directory for persisting events and bookmarks.
		 *
		 * @param directory the base directory path as a string
		 * @return this Builder instance for method chaining
		 * @see #directory(Path)
		 */
		public Builder directory ( String directory ) {
			this.directory = Path.of(directory);
			return this;
		}

		/**
		 * Configures an absolute limit on the number of results that can be returned by any query.
		 *
		 * @param absoluteLimit the maximum number of events any query can return (must be positive)
		 * @return this Builder instance for method chaining
		 */
		public Builder resultLimit ( int absoluteLimit ) {
			this.limit = Limit.to(absoluteLimit);
			return this;
		}

		/**
		 * Configures a custom name for this event storage instance.
		 *
		 * @param name the name to assign to this storage instance; must not be null or blank
		 * @return this Builder instance for method chaining
		 */
		public Builder name ( String name ) {
			this.name = name;
			return this;
		}

		/**
		 * Configures the Micrometer meter registry for collecting observability metrics.
		 *
		 * @param meterRegistry the Micrometer meter registry to use for metrics collection
		 * @return this Builder instance for method chaining
		 */
		public Builder meterRegistry ( MeterRegistry meterRegistry ) {
			this.meterRegistry = meterRegistry;
			return this;
		}

		static Builder newBuilder ( ) {
			return new Builder();
		}

		/**
		 * Builds and returns the configured {@link EventStorage} implementation.
		 *
		 * @return a new InMemoryFsEventStorageImpl instance with the configured settings
		 */
		public EventStorage build ( ) {
			return new InMemoryFsEventStorageImpl(directory, name, limit);
		}

		/**
		 * Builds the storage and returns a fully configured {@link EventStore} instance.
		 *
		 * @return a new EventStore instance backed by file-persisted in-memory storage
		 */
		public EventStore buildStore ( ) {
			return EventStoreFactory.get().eventStore(build(), meterRegistry);
		}
	}

}
