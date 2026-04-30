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
package org.sliceworkz.eventstore.serialization.json;

import java.time.Instant;

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Deserialized bookmark payload: the reader identifier, the event reference it points at,
 * and the metadata (tags, last-update timestamp) supplied when the bookmark was placed.
 * <p>
 * {@code tags} and {@code updatedAt} may be absent in legacy on-disk payloads written before
 * the metadata extension; in that case the codec returns {@link Tags#none()} and an
 * {@code updatedAt} of {@link Instant#EPOCH}.
 */
public record JsonBookmark ( String reader, EventReference reference, Tags tags, Instant updatedAt ) { }
