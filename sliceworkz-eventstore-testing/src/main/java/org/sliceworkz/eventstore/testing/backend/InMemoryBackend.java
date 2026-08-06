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
package org.sliceworkz.eventstore.testing.backend;

import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.testing.EventStoreBackend;
import org.sliceworkz.eventstore.testing.StorageOptions;

/**
 * The in-memory storage, as a backend for the shared scenarios.
 * <p>
 * Needs nothing: no container, no database, no cleanup. Every store is new, so isolation is free.
 * A prefix is meaningless here and is folded into the store name instead, which is enough to keep
 * two stores requested by the same scenario apart.
 */
public class InMemoryBackend implements EventStoreBackend {

	@Override
	public String name ( ) {
		return "inmem";
	}

	@Override
	public EventStorage createEventStorage ( StorageOptions options ) {
		InMemoryEventStorage.Builder builder = InMemoryEventStorage.newBuilder().name(storeName(options));
		if ( options.resultLimit() != null ) {
			builder.resultLimit(options.resultLimit());
		}
		return builder.build();
	}

	private String storeName ( StorageOptions options ) {
		return options.prefix() == null ? options.discriminator() : options.prefix() + options.discriminator();
	}

	@Override
	public boolean supports ( Capability capability ) {
		// exhaustive, so a new capability forces a deliberate decision here
		return switch (capability) {
			case IMPORT, TABLE_PREFIX, RESULT_LIMIT, LEASE -> true;
			case RAW_STORAGE_ACCESS -> false;
		};
	}

}
