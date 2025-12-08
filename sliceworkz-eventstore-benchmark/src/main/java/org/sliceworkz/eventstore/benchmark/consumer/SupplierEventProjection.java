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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.SupplierEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.BatchAwareProjection;
import org.sliceworkz.eventstore.query.EventQuery;

public class SupplierEventProjection implements BatchAwareProjection<SupplierEvent> {

	private AtomicLong eventsProcessed = new AtomicLong();
	private AtomicLong batchesProcessed = new AtomicLong();
	
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
		eventsProcessed.incrementAndGet();
	}

	@Override
	public void beforeBatch() {
	}

	@Override
	public void cancelBatch() {
		
	}

	@Override
	public void afterBatch(Optional<EventReference> lastEventReference) {
		batchesProcessed.incrementAndGet();
	}
	
}
