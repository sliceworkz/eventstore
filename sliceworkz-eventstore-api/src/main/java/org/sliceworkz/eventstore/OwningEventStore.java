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
package org.sliceworkz.eventstore;

import java.util.Optional;
import java.util.Set;

import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.ErasureReport;
import org.sliceworkz.eventstore.shredding.ShreddingAudit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * An {@link EventStore} that closes its {@link EventStorage} along with itself.
 * <p>
 * Created by {@link EventStore#owning(EventStore, EventStorage)}, which is where the reasoning for this
 * class lives. Everything but {@link #close()} is delegated unchanged.
 */
final class OwningEventStore implements EventStore {

	private final EventStore eventStore;
	private final EventStorage eventStorage;

	OwningEventStore ( EventStore eventStore, EventStorage eventStorage ) {
		if ( eventStore == null ) {
			throw new IllegalArgumentException("eventStore cannot be null");
		}
		if ( eventStorage == null ) {
			throw new IllegalArgumentException("eventStorage cannot be null");
		}
		this.eventStore = eventStore;
		this.eventStorage = eventStorage;
	}

	@Override
	public <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Set<Class<?>> eventRootClasses, Set<Class<?>> historicalEventRootClasses ) {
		return eventStore.getEventStream(eventStreamId, eventRootClasses, historicalEventRootClasses);
	}

	@Override
	public ErasureReport erase ( DataSubject subject, ErasureReason reason ) {
		return eventStore.erase(subject, reason);
	}

	@Override
	public Optional<ShreddingAudit> shreddingAudit ( ) {
		return eventStore.shreddingAudit();
	}

	/**
	 * Closes the store, then the storage. Both are idempotent, so closing this more than once — or
	 * closing the storage separately as well — is harmless.
	 */
	@Override
	public void close ( ) {
		eventStore.close();
		eventStorage.close();
	}

	@Override
	public String toString ( ) {
		return "%s owning storage '%s'".formatted(eventStore, eventStorage.name());
	}

}
