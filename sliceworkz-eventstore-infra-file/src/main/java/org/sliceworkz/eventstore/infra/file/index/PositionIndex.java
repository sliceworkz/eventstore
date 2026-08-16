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
package org.sliceworkz.eventstore.infra.file.index;

import java.util.ArrayList;
import java.util.List;

import org.sliceworkz.eventstore.infra.file.log.EventLog.Location;

/**
 * Where every event lives, indexed by its position.
 *
 * <h2>Why this is an array and not a tree</h2>
 * This is the dividend the single-writer design pays. Positions are handed out at commit, by one
 * writer, so they are dense and start at one — position <em>is</em> the array subscript. A store whose
 * positions come from a sequence taken before commit cannot do this: its positions have gaps wherever a
 * transaction rolled back, so it needs a structure that tolerates holes and a search that costs
 * logarithmic time rather than none.
 * <p>
 * The cost is eight bytes an event: a segment ordinal and an offset within it, both {@code int}. The
 * offset can be an {@code int} because a segment is capped below 2 GiB, which is the only reason that
 * cap exists.
 *
 * <h2>Chunked, because the alternative is a copy of the whole index</h2>
 * Growing a single array doubles it and copies it, so a store with ten million events would periodically
 * allocate an eighty-megabyte array to copy an eighty-megabyte array. Fixed chunks grow by appending a
 * chunk and never copy what is already there.
 *
 * <h2>Not thread-safe on its own</h2>
 * Writes happen under the storage's writer lock, and reads happen under it too. Making this structure
 * safe for lock-free reads is a later change, and it needs the chunk list published before the count
 * that makes a chunk reachable — which is exactly the kind of thing that is easy to get subtly wrong,
 * so it is not being guessed at here.
 */
public final class PositionIndex {

	/** 65.536 entries a chunk: 256 KiB per chunk pair, small enough to allocate freely. */
	static final int CHUNK_SIZE = 1 << 16;

	private final List<int[]> segmentChunks = new ArrayList<>();
	private final List<int[]> offsetChunks = new ArrayList<>();

	private long count;

	/**
	 * Creates an empty index, holding nothing until the log is replayed into it.
	 */
	public PositionIndex ( ) {

	}

	/**
	 * Records where the next event lives.
	 *
	 * @param position the event's position, which must be exactly one past the last one added
	 * @param location where its record lives
	 */
	public void add ( long position, Location location ) {
		if ( position != count + 1 ) {
			throw new IllegalStateException(
					"positions must be dense and ascending: expected %d, got %d".formatted(count + 1, position));
		}

		int chunk = (int) ( count / CHUNK_SIZE );
		int slot = (int) ( count % CHUNK_SIZE );
		if ( chunk == segmentChunks.size() ) {
			segmentChunks.add(new int[CHUNK_SIZE]);
			offsetChunks.add(new int[CHUNK_SIZE]);
		}
		segmentChunks.get(chunk)[slot] = location.segment();
		offsetChunks.get(chunk)[slot] = location.offset();
		count++;
	}

	/**
	 * Where the event at a position lives.
	 *
	 * @param position the position, counting from one
	 * @return the record's location
	 * @throws IndexOutOfBoundsException if no event holds that position
	 */
	public Location locationOf ( long position ) {
		if ( position < 1 || position > count ) {
			throw new IndexOutOfBoundsException("position %d is outside the log's 1..%d".formatted(position, count));
		}
		long index = position - 1;
		int chunk = (int) ( index / CHUNK_SIZE );
		int slot = (int) ( index % CHUNK_SIZE );
		return new Location(segmentChunks.get(chunk)[slot], offsetChunks.get(chunk)[slot]);
	}

	/**
	 * How many events the log holds.
	 *
	 * @return the count, which is also the highest position in the log
	 */
	public long count ( ) {
		return count;
	}

}
