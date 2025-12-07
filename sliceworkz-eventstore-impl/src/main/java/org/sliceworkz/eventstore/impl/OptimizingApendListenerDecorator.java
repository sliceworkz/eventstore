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

public class OptimizingApendListenerDecorator implements EventStreamEventuallyConsistentAppendListener {
    private final EventStreamEventuallyConsistentAppendListener delegate;
    private final ReentrantLock lock;
    private final AtomicReference<EventReference> lastNotifiedReference;
    private final AtomicReference<EventReference> nextEventReference;
    private volatile boolean updateInProgress;
    
    public OptimizingApendListenerDecorator(EventStreamEventuallyConsistentAppendListener delegate) {
        this.delegate = delegate;
        this.lock = new ReentrantLock();
        this.lastNotifiedReference = new AtomicReference<>();
        this.nextEventReference = new AtomicReference<>();
        this.updateInProgress = false;
    }
    
    @Override
    public void eventsAppended ( EventReference atLeastUntil ) {
        if ((lastNotifiedReference.get() != null) && ( atLeastUntil.position() <= lastNotifiedReference.get().position()) ) {
            return;
        }
        
        // Update target to maximum position seen
        nextEventReference.updateAndGet(current -> (current == null || (current.position() < atLeastUntil.position()))? atLeastUntil:current);
        
        if (!lock.tryLock()) {
            return; // Someone else is handling it
        }
        
        try {
            if (updateInProgress) {
                return; // Update in progress, target already registered
            }
            
            notifyDecoratedListener();
            
        } finally {
            lock.unlock();
        }
    }
    
    private void notifyDecoratedListener() {
        while (true) {
            EventReference target = nextEventReference.get();
            
            if (lastNotifiedReference.get() != null && ( target.position() <= lastNotifiedReference.get().position())) {
                return;
            }
            
            updateInProgress = true;
            lock.unlock();
            
            try {
                delegate.eventsAppended(target);
                lastNotifiedReference.set(target);
            } finally {
                lock.lock();
                updateInProgress = false;
            }
        }
    }
}