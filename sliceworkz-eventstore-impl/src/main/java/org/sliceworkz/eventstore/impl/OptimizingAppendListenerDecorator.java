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
package org.sliceworkz.eventstore.impl;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;

/**
 * Decorator that optimizes event append notifications by batching and deduplicating them.
 * <p>
 * This decorator wraps an {@link EventStreamEventuallyConsistentAppendListener} and optimizes
 * notification delivery by:
 * <ul>
 *   <li>Batching multiple rapid notifications into a single call to the delegate listener</li>
 *   <li>Skipping redundant notifications when events have already been processed</li>
 *   <li>Ensuring only one notification is in progress at a time</li>
 *   <li>Tracking the latest event reference seen (ordered by transaction, then position) and notifying with the most recent reference</li>
 *   <li>Respecting the listener's reported actual processing position to avoid redundant work</li>
 * </ul>
 * <p>
 * The decorator maintains thread-safe state tracking to handle concurrent append operations
 * efficiently. When multiple threads trigger notifications simultaneously, only one proceeds
 * while others register their target event references for batch processing.
 * <p>
 * The optimization leverages the return value of {@link EventStreamEventuallyConsistentAppendListener#eventsAppended(EventReference)}
 * to track what the delegate listener has actually processed, allowing it to skip notifications
 * for event references already handled.
 * <p>
 * <strong>Internal.</strong> Every listener passed to {@code subscribe(...)} is wrapped in one of these
 * by the store itself, so there is no reason for a caller to name this class — wrapping a listener
 * before subscribing it only gets it wrapped twice. It lives in the implementation package the
 * ServiceLoader exists to hide, and carries no compatibility promise.
 *
 * @see EventStreamEventuallyConsistentAppendListener
 */
public class OptimizingAppendListenerDecorator implements EventStreamEventuallyConsistentAppendListener {
    private final EventStreamEventuallyConsistentAppendListener delegate;
    private final ReentrantLock lock;
    private final AtomicReference<EventReference> lastNotifiedReference;
    private final AtomicReference<EventReference> nextEventReference;
    private volatile boolean updateInProgress;
    
    /**
     * Creates a new optimizing decorator for the given delegate listener.
     *
     * @param delegate the listener to decorate with optimization logic; must not be null
     */
    public OptimizingAppendListenerDecorator(EventStreamEventuallyConsistentAppendListener delegate) {
        this.delegate = delegate;
        this.lock = new ReentrantLock();
        this.lastNotifiedReference = new AtomicReference<>();
        this.nextEventReference = new AtomicReference<>();
        this.updateInProgress = false;
    }

    /**
     * The listener this decorator delivers to — the one the caller actually subscribed.
     * <p>
     * Every eventually consistent subscriber is wrapped in one of these, so anything reporting on a
     * subscriber (a log line naming the one that failed, say) has to look through the decorator to say
     * something the caller recognises.
     *
     * @return the decorated listener, never null
     */
    public EventStreamEventuallyConsistentAppendListener delegate ( ) {
        return delegate;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation optimizes notification delivery by:
     * <ul>
     *   <li>Skipping notifications for event references already processed by the delegate</li>
     *   <li>Batching concurrent notifications into a single delegate call with the latest event reference</li>
     *   <li>Ensuring only one notification to the delegate is in progress at any time</li>
     * </ul>
     * <p>
     * The method returns the {@code atLeastUntil} parameter, as the actual processing reference
     * is tracked from the delegate's return value and used internally for optimization.
     *
     * @param atLeastUntil reference to at least the last appended event
     * @return the {@code atLeastUntil} parameter
     */
    @Override
    public EventReference eventsAppended ( EventReference atLeastUntil ) {
        if ((lastNotifiedReference.get() != null) && !atLeastUntil.happenedAfter(lastNotifiedReference.get())) {
            return atLeastUntil;
        }

        // Update target to the latest event reference seen. Registering it before taking the lock is
        // what makes handing the work off safe: a thread that is about to decide it has nothing left
        // to do makes that decision while holding the lock, so it either sees this target, or has
        // already released the lock by the time we take it below.
        nextEventReference.updateAndGet(current -> (current == null || current.happenedBefore(atLeastUntil))? atLeastUntil:current);

        // Wait for the lock rather than giving up on it. Returning early instead -- assuming whoever
        // holds it will pick up the target just registered -- loses notifications: the holder may
        // already have read nextEventReference and decided to stop, in which case nobody delivers this
        // one and the listener never hears about the append. The wait is short, since the lock is
        // released around the delegate call.
        lock.lock();

        try {
            if (updateInProgress) {
                return atLeastUntil; // a delivery is running; it re-reads the target and will pick this up
            }

            notifyDecoratedListener();

        } finally {
            lock.unlock();
        }

        return atLeastUntil;
    }
    
    /**
     * Delivers to the delegate until the registered target has been caught up with.
     * <p>
     * Called with the lock held, and returns with it held. The lock is released around the delegate
     * call, so a slow listener never blocks the threads notifying it — they register their target and
     * find a delivery already in progress. Because the decision to stop is taken under the lock, and a
     * target is always registered before its thread takes the lock, no target can be registered
     * unnoticed: whoever registers one either finds this loop still running, or gets the lock itself
     * and delivers.
     */
    private void notifyDecoratedListener() {
        while (true) {
            EventReference target = nextEventReference.get();

            if (lastNotifiedReference.get() != null && !target.happenedAfter(lastNotifiedReference.get())) {
                return;
            }
            
            updateInProgress = true;
            lock.unlock();
            
            try {
                EventReference lastSeenByDelegate = delegate.eventsAppended(target);
                // Advance to the target whatever the delegate reports, and past it only when it reports
                // going further. Null -- "I processed nothing" -- has to count as reaching the target too,
                // because this loop only stops once the target has been reached: a delegate that keeps
                // returning null left it with nothing to compare against and nothing to reach, so it
                // re-delivered the same target without pausing, measured at ~700.000 deliveries a second
                // on one pinned virtual thread. That is not an exotic listener. Projector.eventsAppended
                // returns run().lastEventReference(), which is null whenever the query matched no events,
                // so any subscribed projector whose event type had not occurred yet burned a core from the
                // first unrelated append to its stream until the first matching one -- nothing failing,
                // nothing logged, just a core gone. No notification is lost by advancing here: the next
                // append carries a later reference, which is after this one and so still delivered.
                lastNotifiedReference.set((lastSeenByDelegate != null) && lastSeenByDelegate.happenedAfter(target)? lastSeenByDelegate:target);
            } finally {
                lock.lock();
                updateInProgress = false;
            }
        }
    }
}