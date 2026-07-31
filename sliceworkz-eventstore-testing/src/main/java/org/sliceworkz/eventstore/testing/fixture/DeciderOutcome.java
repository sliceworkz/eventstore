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

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * What a decider did: what it returned, what it appended, or how it failed.
 * <p>
 * Every expectation returns {@code this}, so they chain. Each throws {@link AssertionError} with the
 * full picture — expected against actual, and the captured failure if the decider threw — rather
 * than leaving the reader to work out which of several assertions fired.
 *
 * @param <DOMAIN_EVENT_TYPE> the stream's domain event type
 * @param <R>                 what the decider returned
 */
public final class DeciderOutcome<DOMAIN_EVENT_TYPE, R> {

	private final EventStoreFixture<DOMAIN_EVENT_TYPE> fixture;
	private final EventReference before;
	private final R result;
	private final RuntimeException failure;

	private List<Event<DOMAIN_EVENT_TYPE>> appended;

	private DeciderOutcome ( EventStoreFixture<DOMAIN_EVENT_TYPE> fixture, EventReference before, R result, RuntimeException failure ) {
		this.fixture = fixture;
		this.before = before;
		this.result = result;
		this.failure = failure;
	}

	static <E, R> DeciderOutcome<E, R> succeeded ( EventStoreFixture<E> fixture, EventReference before, R result ) {
		return new DeciderOutcome<>(fixture, before, result, null);
	}

	static <E, R> DeciderOutcome<E, R> failed ( EventStoreFixture<E> fixture, EventReference before, RuntimeException failure ) {
		return new DeciderOutcome<>(fixture, before, null, failure);
	}

	/**
	 * The events the decider appended, in order — everything written to the stream after the
	 * seeded history.
	 *
	 * @return the appended events; empty if it appended nothing
	 */
	public List<Event<DOMAIN_EVENT_TYPE>> appended ( ) {
		if ( appended == null ) {
			appended = fixture.stream().query(EventQuery.matchAll(), before).toList();
		}
		return appended;
	}

	/**
	 * What the decider returned.
	 *
	 * @return the decider's return value
	 * @throws AssertionError if the decider threw instead of returning
	 */
	public R result ( ) {
		if ( failure != null ) {
			throw new AssertionError("expected the decision to return a result, but it threw " + describe(failure), failure);
		}
		return result;
	}

	/**
	 * The exception the decider threw.
	 *
	 * @return the captured failure
	 * @throws AssertionError if the decider returned normally
	 */
	public RuntimeException failure ( ) {
		if ( failure == null ) {
			throw new AssertionError("expected the decision to fail, but it returned " + result);
		}
		return failure;
	}

	/**
	 * Asserts exactly these events were appended, in this order, each with exactly these tags.
	 * Stream, reference and timestamp are not compared — the store assigns them.
	 *
	 * @param expected the events expected, in order
	 * @return this
	 */
	public DeciderOutcome<DOMAIN_EVENT_TYPE, R> expectAppended ( ExpectedEvent... expected ) {
		failIfDeciderThrew("expected events to be appended");

		List<Event<DOMAIN_EVENT_TYPE>> actual = appended();
		boolean matches = expected.length == actual.size();
		for ( int i = 0; matches && i < expected.length; i++ ) {
			matches = expected[i].matches(actual.get(i));
		}
		if ( !matches ) {
			throw new AssertionError("""
					appended events do not match
					  expected: %s
					  actual  : %s""".formatted(List.of(expected), describe(actual)));
		}
		return this;
	}

	/**
	 * Asserts the decider appended nothing — it decided not to act.
	 *
	 * @return this
	 */
	public DeciderOutcome<DOMAIN_EVENT_TYPE, R> expectNoEventsAppended ( ) {
		failIfDeciderThrew("expected no events to be appended");

		if ( !appended().isEmpty() ) {
			throw new AssertionError("expected no events to be appended, but got " + describe(appended()));
		}
		return this;
	}

	/**
	 * Asserts what the decider returned.
	 *
	 * @param expected the expected return value
	 * @return this
	 */
	public DeciderOutcome<DOMAIN_EVENT_TYPE, R> expectResult ( R expected ) {
		failIfDeciderThrew("expected the decision to return " + expected);

		if ( !Objects.equals(expected, result) ) {
			throw new AssertionError("expected the decision to return %s, but it returned %s".formatted(expected, result));
		}
		return this;
	}

	/**
	 * Asserts what the decider returned, for results without a useful {@code equals}.
	 *
	 * @param condition   what the result must satisfy
	 * @param description what {@code condition} means, for the failure message
	 * @return this
	 */
	public DeciderOutcome<DOMAIN_EVENT_TYPE, R> expectResult ( Predicate<R> condition, String description ) {
		failIfDeciderThrew("expected the decision to return a result that is " + description);

		if ( !condition.test(result) ) {
			throw new AssertionError("expected the decision to return a result that is %s, but it returned %s"
					.formatted(description, result));
		}
		return this;
	}

	/**
	 * Asserts the decider completed without throwing.
	 *
	 * @return this
	 */
	public DeciderOutcome<DOMAIN_EVENT_TYPE, R> expectNoFailure ( ) {
		failIfDeciderThrew("expected the decision to complete");
		return this;
	}

	/**
	 * Asserts the append was rejected because the consistency boundary had moved, and hands back the
	 * exception for further assertions on <em>which</em> boundary fired.
	 *
	 * @return the failure, to assert on
	 */
	public OptimisticLockingFailure expectOptimisticLockingFailure ( ) {
		if ( failure == null ) {
			throw new AssertionError("""
					expected an OptimisticLockingException, but the decision completed
					  returned : %s
					  appended : %s""".formatted(result, describe(appended())));
		}
		if ( !(failure instanceof OptimisticLockingException optimisticLocking) ) {
			throw new AssertionError("expected an OptimisticLockingException, but the decision threw " + describe(failure), failure);
		}
		return new OptimisticLockingFailure(optimisticLocking);
	}

	/**
	 * Asserts the decider threw the given kind of exception, and hands it back.
	 *
	 * @param <X>  the exception type
	 * @param type the exception type expected
	 * @return the captured exception
	 */
	public <X extends RuntimeException> X expectFailure ( Class<X> type ) {
		if ( failure == null ) {
			throw new AssertionError("expected the decision to throw %s, but it returned %s".formatted(type.getName(), result));
		}
		if ( !type.isInstance(failure) ) {
			throw new AssertionError("expected the decision to throw %s, but it threw %s".formatted(type.getName(), describe(failure)), failure);
		}
		return type.cast(failure);
	}

	private void failIfDeciderThrew ( String what ) {
		if ( failure != null ) {
			throw new AssertionError("%s, but the decision threw %s".formatted(what, describe(failure)), failure);
		}
	}

	private static String describe ( Throwable throwable ) {
		return "%s: %s".formatted(throwable.getClass().getSimpleName(), throwable.getMessage());
	}

	private static String describe ( List<? extends Event<?>> events ) {
		return events.stream()
				.map(e -> e.tags().tags().isEmpty() ? e.data().toString() : "%s %s".formatted(e.data(), e.tags().tags()))
				.toList()
				.toString();
	}

}
