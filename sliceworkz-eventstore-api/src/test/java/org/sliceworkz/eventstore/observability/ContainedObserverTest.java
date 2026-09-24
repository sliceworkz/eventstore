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
package org.sliceworkz.eventstore.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * {@link EventStoreObserver#contained(EventStoreObserver)}: nothing an observer throws reaches the
 * operation it observes, and wrapping is idempotent.
 */
class ContainedObserverTest {

	private static final StreamInfo STREAM = new StreamInfo("s", EventStreamId.forContext("c"), true);

	@Test
	void noopAndContainedObserversAreReturnedAsTheyAre ( ) {
		assertSame(EventStoreObserver.NOOP, EventStoreObserver.contained(EventStoreObserver.NOOP));
		EventStoreObserver contained = EventStoreObserver.contained(new Throwing());
		assertSame(contained, EventStoreObserver.contained(contained));
	}

	@Test
	void aNullObserverIsRefused ( ) {
		assertThrows(IllegalArgumentException.class, () -> EventStoreObserver.contained(null));
	}

	@Test
	void whatAnObserverThrowsIsContained ( ) {
		Throwing throwing = new Throwing();
		EventStoreObserver contained = EventStoreObserver.contained(throwing);
		assertDoesNotThrow(() -> {
			try ( Observation.Scope<Outcome.HeadRead> scope = contained.start(new Observation.Head(STREAM)) ) {
				scope.completed(new Outcome.HeadRead(java.time.Duration.ZERO, java.util.Optional.empty()));
				scope.failed(new IllegalStateException());
			}
			contained.storageStarted("s");
			contained.storageClosed("s");
			contained.subscriptionOpened(STREAM);
			contained.subscriptionClosed(STREAM);
			contained.streamOpened(STREAM);
			contained.notificationChannelChanged("s", NotificationChannel.EVENT_APPENDED, true);
		});
		assertTrue(throwing.calls.get() >= 7, "every call reached the observer");
	}

	@Test
	void aScopeWhoseStartThrewIsANoOp ( ) {
		EventStoreObserver contained = EventStoreObserver.contained(new EventStoreObserver() {
			@Override
			public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
				throw new NoClassDefFoundError("a binding whose library is missing");
			}
		});
		assertDoesNotThrow(() -> contained.start(new Observation.Head(STREAM)).close());
	}

	private static final class Throwing implements EventStoreObserver {

		private final AtomicInteger calls = new AtomicInteger();

		private RuntimeException fail ( ) {
			calls.incrementAndGet();
			return new IllegalStateException("broken observer");
		}

		@Override
		public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
			calls.incrementAndGet();
			return new Observation.Scope<>() {
				@Override public void completed ( O outcome ) { throw fail(); }
				@Override public void failed ( Throwable failure ) { throw fail(); }
				@Override public void close ( ) { throw fail(); }
			};
		}

		@Override public void storageStarted ( String storage ) { throw fail(); }
		@Override public void storageClosed ( String storage ) { throw fail(); }
		@Override public void subscriptionOpened ( StreamInfo stream ) { throw fail(); }
		@Override public void subscriptionClosed ( StreamInfo stream ) { throw fail(); }
		@Override public void streamOpened ( StreamInfo stream ) { throw fail(); }
		@Override public void notificationChannelChanged ( String storage, NotificationChannel channel, boolean listening ) { throw fail(); }

	}

}
