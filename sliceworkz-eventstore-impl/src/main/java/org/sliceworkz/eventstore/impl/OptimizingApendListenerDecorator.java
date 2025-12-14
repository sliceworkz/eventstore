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
 *
 * @see EventStreamEventuallyConsistentAppendListener
 */
public class OptimizingApendListenerDecorator implements EventStreamEventuallyConsistentAppendListener {
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
    public OptimizingApendListenerDecorator(EventStreamEventuallyConsistentAppendListener delegate) {
        this.delegate = delegate;
        this.lock = new ReentrantLock();
        this.lastNotifiedReference = new AtomicReference<>();
        this.nextEventReference = new AtomicReference<>();
        this.updateInProgress = false;
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

        // Update target to the latest event reference seen
        nextEventReference.updateAndGet(current -> (current == null || current.happenedBefore(atLeastUntil))? atLeastUntil:current);
        
        if (!lock.tryLock()) {
            return atLeastUntil; // Someone else is handling it
        }
        
        try {
            if (updateInProgress) {
                return atLeastUntil; // Update in progress, target already registered
            }
            
            notifyDecoratedListener();
            
        } finally {
            lock.unlock();
        }
        
        return atLeastUntil;
    }
    
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
                if ( lastSeenByDelegate != null ) {
                	lastNotifiedReference.set(lastSeenByDelegate.happenedAfter(target)?lastSeenByDelegate:target);
                }
            } finally {
                lock.lock();
                updateInProgress = false;
            }
        }
    }
}