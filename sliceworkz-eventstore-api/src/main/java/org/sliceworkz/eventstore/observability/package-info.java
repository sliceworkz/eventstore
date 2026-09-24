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
/**
 * Observability - the SPI through which a store, its storage and its projectors report what they do.
 * <p>
 * The library depends on no metrics or tracing library. It reports operations and lifecycle events in its
 * own terms to an {@link org.sliceworkz.eventstore.observability.EventStoreObserver}, and an
 * implementation of that interface turns them into meters, spans or anything else:
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.observability.EventStoreObserver} - the observer an application provides; {@code NOOP} by default</li>
 *   <li>{@link org.sliceworkz.eventstore.observability.Observation} - an operation about to be performed, and its {@link org.sliceworkz.eventstore.observability.Observation.Scope Scope}</li>
 *   <li>{@link org.sliceworkz.eventstore.observability.Outcome} - what an operation answered</li>
 *   <li>{@link org.sliceworkz.eventstore.observability.StreamInfo} - which stream an observation is about</li>
 *   <li>{@link org.sliceworkz.eventstore.observability.NotificationChannel} - the channels whose health a storage reports</li>
 *   <li>{@link org.sliceworkz.eventstore.observability.StreamObservation} - how a source is observed, which is how a projector finds its observer</li>
 * </ul>
 */
package org.sliceworkz.eventstore.observability;
