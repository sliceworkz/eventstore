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

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An observer that contains what its delegate throws: see {@link EventStoreObserver#contained(EventStoreObserver)}.
 * <p>
 * Catches {@link RuntimeException} and {@link LinkageError} — the second because an observer binding a
 * library that is missing or of the wrong version fails with one, and that is a broken observer, not a
 * broken store. Any other {@code Error} is left alone: an out-of-memory is not the observer's to swallow.
 */
final class ContainedObserver implements EventStoreObserver {

	private static final Logger LOGGER = LoggerFactory.getLogger(ContainedObserver.class);

	private final EventStoreObserver delegate;

	/**
	 * Whether this observer has already reported a failure at ERROR. Logged loudly once, since a broken
	 * observer is something to fix; quietly afterwards, since it fails on every operation of the store.
	 */
	private final AtomicBoolean failureReported = new AtomicBoolean();

	ContainedObserver ( EventStoreObserver delegate ) {
		this.delegate = delegate;
	}

	@Override
	public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
		try {
			Observation.Scope<O> scope = delegate.start(observation);
			return scope == null ? NoopObserver.scope() : new ContainedScope<>(scope);
		} catch ( RuntimeException | LinkageError e ) {
			report("start", e);
			return NoopObserver.scope();
		}
	}

	@Override
	public void storageStarted ( String storage ) {
		contain("storageStarted", () -> delegate.storageStarted(storage));
	}

	@Override
	public void storageClosed ( String storage ) {
		contain("storageClosed", () -> delegate.storageClosed(storage));
	}

	@Override
	public void subscriptionOpened ( StreamInfo stream ) {
		contain("subscriptionOpened", () -> delegate.subscriptionOpened(stream));
	}

	@Override
	public void subscriptionClosed ( StreamInfo stream ) {
		contain("subscriptionClosed", () -> delegate.subscriptionClosed(stream));
	}

	@Override
	public void notificationChannelChanged ( String storage, NotificationChannel channel, boolean listening ) {
		contain("notificationChannelChanged", () -> delegate.notificationChannelChanged(storage, channel, listening));
	}

	@Override
	public void streamOpened ( StreamInfo stream ) {
		contain("streamOpened", () -> delegate.streamOpened(stream));
	}

	private void contain ( String method, Runnable call ) {
		try {
			call.run();
		} catch ( RuntimeException | LinkageError e ) {
			report(method, e);
		}
	}

	private void report ( String method, Throwable e ) {
		if ( failureReported.compareAndSet(false, true) ) {
			LOGGER.error("event store observer {} threw from {}; the operation it observed is unaffected, and further failures of this observer are logged at DEBUG: {}",
					delegate.getClass().getName(), method, e.getMessage(), e);
		} else {
			LOGGER.debug("event store observer {} threw from {}: {}", delegate.getClass().getName(), method, e.getMessage(), e);
		}
	}

	@Override
	public String toString ( ) {
		return "contained " + delegate;
	}

	private final class ContainedScope<O extends Outcome> implements Observation.Scope<O> {

		private final Observation.Scope<O> scope;

		private ContainedScope ( Observation.Scope<O> scope ) {
			this.scope = scope;
		}

		@Override
		public void completed ( O outcome ) {
			contain("completed", () -> scope.completed(outcome));
		}

		@Override
		public void failed ( Throwable failure ) {
			contain("failed", () -> scope.failed(failure));
		}

		@Override
		public void close ( ) {
			contain("close", scope::close);
		}

	}

}
