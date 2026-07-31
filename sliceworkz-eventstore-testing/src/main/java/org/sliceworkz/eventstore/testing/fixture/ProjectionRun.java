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
package org.sliceworkz.eventstore.testing.fixture;

import java.util.function.Consumer;

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;

/**
 * Drives a projection over seeded history and asserts on the result.
 * <p>
 * The point is determinism. A {@link Projector} normally runs to whatever is currently the head of
 * the store, which is fine in production and unhelpful in a test. {@link #upTo(EventReference)}
 * pins the end, so the assertion is about a known set of events rather than about timing.
 * <p>
 * {@link #expectEventsProcessed(long)} is what makes savepoint projections testable: the whole
 * point of a savepoint is that the projection <em>does not</em> replay everything before it, and
 * asserting only on the final state cannot tell a working savepoint from an ignored one.
 *
 * @param <DOMAIN_EVENT_TYPE> the stream's domain event type
 * @param <P>                 the projection type
 */
public final class ProjectionRun<DOMAIN_EVENT_TYPE, P extends Projection<DOMAIN_EVENT_TYPE>> {

	private final EventStoreFixture<DOMAIN_EVENT_TYPE> fixture;
	private final P projection;

	private EventReference upTo;
	private Integer batchSize;
	private ProjectorMetrics metrics;

	ProjectionRun ( EventStoreFixture<DOMAIN_EVENT_TYPE> fixture, P projection ) {
		this.fixture = fixture;
		this.projection = projection;
	}

	/**
	 * Stops the projection at this event instead of at the head of the store.
	 *
	 * @param upTo the last event to process, typically from {@code given(...).lastReference()}
	 * @return this
	 */
	public ProjectionRun<DOMAIN_EVENT_TYPE, P> upTo ( EventReference upTo ) {
		this.upTo = upTo;
		return this;
	}

	/**
	 * Processes events in batches of this size, exercising the projector's multi-query path.
	 *
	 * @param batchSize maximum events per query
	 * @return this
	 */
	public ProjectionRun<DOMAIN_EVENT_TYPE, P> inBatchesOf ( int batchSize ) {
		this.batchSize = batchSize;
		return this;
	}

	/**
	 * Runs the projection. Called automatically by the expectations, so only needed when asserting
	 * on the projection directly.
	 *
	 * @return this
	 */
	public ProjectionRun<DOMAIN_EVENT_TYPE, P> run ( ) {
		if ( metrics != null ) {
			return this;
		}
		Projector.Builder<DOMAIN_EVENT_TYPE> builder = Projector.<DOMAIN_EVENT_TYPE>from(fixture.stream()).towards(projection);
		if ( batchSize != null ) {
			builder.inBatchesOf(batchSize);
		}
		Projector<DOMAIN_EVENT_TYPE> projector = builder.build();
		metrics = upTo == null ? projector.run() : projector.runUntil(upTo);
		return this;
	}

	/**
	 * Asserts how many events the projection handled.
	 * <p>
	 * This counts events passed to {@code when(...)}, which is what distinguishes a savepoint that
	 * short-circuited the replay from one that was ignored.
	 *
	 * @param expected the expected number of handled events
	 * @return this
	 */
	public ProjectionRun<DOMAIN_EVENT_TYPE, P> expectEventsProcessed ( long expected ) {
		run();
		if ( metrics.eventsHandled() != expected ) {
			throw new AssertionError("expected the projection to handle %d event(s), but it handled %d"
					.formatted(expected, metrics.eventsHandled()));
		}
		return this;
	}

	/**
	 * Asserts on the projection's state once the run is over.
	 *
	 * @param assertions assertions against the built-up projection
	 * @return this
	 */
	public ProjectionRun<DOMAIN_EVENT_TYPE, P> expectState ( Consumer<P> assertions ) {
		run();
		assertions.accept(projection);
		return this;
	}

	/**
	 * @return the projection, run if it has not been already
	 */
	public P projection ( ) {
		run();
		return projection;
	}

	/**
	 * @return the metrics of the run, run if it has not been already
	 */
	public ProjectorMetrics metrics ( ) {
		run();
		return metrics;
	}

}
