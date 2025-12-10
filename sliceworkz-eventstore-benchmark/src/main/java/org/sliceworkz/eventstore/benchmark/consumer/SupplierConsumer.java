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

import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.SupplierEvent;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;

public class SupplierConsumer implements EventStreamEventuallyConsistentAppendListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(SupplierConsumer.class);
	
	private SupplierEventProjection projection;
	private Projector<SupplierEvent> projector;
	private AtomicLong recievedAppendNotifications = new AtomicLong();
	
	public SupplierConsumer ( EventStream<SupplierEvent> stream, DataSource dataSource ) {
		this.projection = new SupplierEventProjection(dataSource);
		this.projector = Projector.<SupplierEvent>newBuilder()
			.from(stream)
			.towards(projection)
			.bookmarkProgress()
				.withReader("supplier-projector")
				.readBeforeFirstExecution()
				.done()
			.build();
		stream.subscribe(this);
	}
	
	public SupplierEventProjection getProjection ( ) {
		return projection;
	}

	@Override
	public EventReference eventsAppended(EventReference atLeastUntil) {
		recievedAppendNotifications.incrementAndGet();
		return runProjector();
	}
	
	public EventReference runProjector ( ) {
		EventReference from = projector.accumulatedMetrics().lastEventReference();
		EventReference to = projector.run().lastEventReference();
//		LOGGER.info("S\t" + (from==null?"-":from.position()) + "\t" + (to==null?"-":to.position()));
		
		return to;
	}
	
	public long recievedAppendNotifications ( ) {
		return recievedAppendNotifications.get();
	}

}
