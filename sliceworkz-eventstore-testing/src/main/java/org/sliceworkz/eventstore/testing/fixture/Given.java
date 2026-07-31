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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;

/**
 * A fixture with its history seeded, ready to run something against.
 *
 * @param <DOMAIN_EVENT_TYPE> the stream's domain event type
 */
public final class Given<DOMAIN_EVENT_TYPE> {

	private final EventStoreFixture<DOMAIN_EVENT_TYPE> fixture;

	Given ( EventStoreFixture<DOMAIN_EVENT_TYPE> fixture ) {
		this.fixture = fixture;
	}

	/**
	 * Appends more history.
	 *
	 * @param more further events that already happened
	 * @return this
	 */
	public Given<DOMAIN_EVENT_TYPE> and ( ExpectedEvent... more ) {
		return and(List.of(more));
	}

	/**
	 * Appends more history.
	 *
	 * @param more further events that already happened
	 * @return this
	 */
	public Given<DOMAIN_EVENT_TYPE> and ( List<ExpectedEvent> more ) {
		if ( !more.isEmpty() ) {
			fixture.stream().append(AppendCriteria.none(), toEphemeralEvents(more));
		}
		return this;
	}

	/**
	 * The reference of the last event in the store, useful as a boundary for
	 * {@link ProjectionRun#upTo(EventReference)}.
	 *
	 * @return the last event's reference, or {@code null} if the store is empty
	 */
	public EventReference lastReference ( ) {
		return fixture.stream().query(EventQuery.matchAll().backwards().limit(1))
				.findFirst().map(Event::reference).orElse(null);
	}

	/**
	 * Runs the decider and captures what it returned and what it appended.
	 * <p>
	 * The decider gets the real {@link EventStream}, so the code under test is production code,
	 * unmodified. An exception it throws is captured rather than propagated — assert on it with
	 * {@link DeciderOutcome#expectOptimisticLockingFailure()} or
	 * {@link DeciderOutcome#expectFailure(Class)}.
	 *
	 * @param <R>     what the decider returns
	 * @param decider the code under test
	 * @return the outcome, to assert on
	 */
	public <R> DeciderOutcome<DOMAIN_EVENT_TYPE, R> when ( Decider<DOMAIN_EVENT_TYPE, R> decider ) {
		return run(decider, fixture.stream());
	}

	/**
	 * Runs a decider that returns nothing.
	 *
	 * @param decider the code under test
	 * @return the outcome, to assert on
	 */
	public DeciderOutcome<DOMAIN_EVENT_TYPE, Void> whenRunning ( Consumer<EventStream<DOMAIN_EVENT_TYPE>> decider ) {
		return when(stream -> {
			decider.accept(stream);
			return null;
		});
	}

	/**
	 * Runs the decider against a stream that has {@code interleaved} appended to it in the window
	 * between the decider's query and its own append.
	 * <p>
	 * This reproduces the race a DCB consistency boundary exists to catch: two decisions taken from
	 * the same history, the second appending against a reference that is no longer current. It does
	 * so deterministically and without threads — the interleaved events are appended immediately
	 * before the decider's first append reaches the store, which is the exact moment a real
	 * concurrent writer would win the race.
	 * <pre>{@code
	 * given(event(new CourseDefined("Java basics", 12)).tagged("course", "abc001"))
	 *     .whenConcurrently(
	 *         stream -> new Registrations(stream).subscribe("123", "abc001"),
	 *         event(new StudentSubscribed("123", "abc001"))
	 *             .tagged("student", "123").tagged("course", "abc001"))
	 *     .expectOptimisticLockingFailure();
	 * }</pre>
	 * If the decider never appends, the interleaved events are never written either — the decision
	 * did not reach the point where the race matters.
	 *
	 * @param <R>         what the decider returns
	 * @param decider     the code under test
	 * @param interleaved events a competing writer gets in first
	 * @return the outcome, to assert on
	 */
	public <R> DeciderOutcome<DOMAIN_EVENT_TYPE, R> whenConcurrently ( Decider<DOMAIN_EVENT_TYPE, R> decider, ExpectedEvent... interleaved ) {
		return run(decider, interleavingStream(List.of(interleaved)));
	}

	/**
	 * Drives a projection over the seeded history.
	 *
	 * @param <P>        the projection type
	 * @param projection the projection to build up
	 * @return the run, to configure and assert on
	 */
	public <P extends Projection<DOMAIN_EVENT_TYPE>> ProjectionRun<DOMAIN_EVENT_TYPE, P> project ( P projection ) {
		return new ProjectionRun<>(fixture, projection);
	}

	private <R> DeciderOutcome<DOMAIN_EVENT_TYPE, R> run ( Decider<DOMAIN_EVENT_TYPE, R> decider, EventStream<DOMAIN_EVENT_TYPE> stream ) {
		EventReference before = lastReference();
		try {
			R result = decider.decide(stream);
			return DeciderOutcome.succeeded(fixture, before, result);
		} catch (RuntimeException failure) {
			return DeciderOutcome.failed(fixture, before, failure);
		}
	}

	/**
	 * A stream that writes {@code interleaved} to the store just before the first append the
	 * decider makes.
	 * <p>
	 * A dynamic proxy rather than a hand-written delegate: {@link EventStream} is a wide interface
	 * and only {@code append} needs intercepting, so a proxy keeps this from breaking every time
	 * the interface gains a method.
	 */
	@SuppressWarnings("unchecked")
	private EventStream<DOMAIN_EVENT_TYPE> interleavingStream ( List<ExpectedEvent> interleaved ) {
		EventStream<DOMAIN_EVENT_TYPE> real = fixture.stream();
		boolean[] alreadyInterleaved = { false };

		InvocationHandler handler = ( proxy, method, args ) -> {
			if ( "append".equals(method.getName()) && !alreadyInterleaved[0] ) {
				alreadyInterleaved[0] = true;
				if ( !interleaved.isEmpty() ) {
					real.append(AppendCriteria.none(), toEphemeralEvents(interleaved));
				}
			}
			try {
				return method.invoke(real, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		};

		return (EventStream<DOMAIN_EVENT_TYPE>) Proxy.newProxyInstance(
				EventStream.class.getClassLoader(), new Class<?>[] { EventStream.class }, handler);
	}

	private List<EphemeralEvent<? extends DOMAIN_EVENT_TYPE>> toEphemeralEvents ( List<ExpectedEvent> events ) {
		List<EphemeralEvent<? extends DOMAIN_EVENT_TYPE>> ephemeral = new ArrayList<>(events.size());
		for ( ExpectedEvent event : events ) {
			ephemeral.add(event.<DOMAIN_EVENT_TYPE>toEphemeralEvent());
		}
		return ephemeral;
	}

	/**
	 * The code under test: reads what it needs from the stream, decides, appends.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the stream's domain event type
	 * @param <R>                 what the decision returns
	 */
	@FunctionalInterface
	public interface Decider<DOMAIN_EVENT_TYPE, R> {

		/**
		 * @param stream the stream under test
		 * @return whatever the decision produces
		 */
		R decide ( EventStream<DOMAIN_EVENT_TYPE> stream );

	}

}
