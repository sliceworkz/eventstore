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
package org.sliceworkz.eventstore.benchmark.corpus;

/**
 * Decides which events carry the needle and swathe marker tags.
 *
 * <p>Placement is by <b>absolute count at a fixed stride</b>, not by probability. A sprinkle with
 * probability p would give a different selectivity for every seed and every volume, and selectivity
 * is precisely the variable these markers exist to control -- a read benchmark comparing "ten
 * matches" against "one percent" is worthless if neither number is the real one.
 *
 * <p>The two markers are held <b>disjoint by construction</b>, via a half-stride offset on the
 * swathe. That matters more than it looks: at the small tier, ten events and one percent of a
 * thousand-event store are the same count, so the naive "every n-th event" placement puts both
 * markers on exactly the same events and one of them ends up on none at all. The corpus then looks
 * fine, and every swathe measurement silently reports the speed of a query that matches nothing.
 */
final class MarkerPlacement {

	private final long needleStride;
	private final long swatheStride;
	private final long swatheOffset;
	private final boolean enabled;

	/**
	 * @param volume how many events are eligible to carry a marker -- the contexts under test only,
	 *        since noise is never read and marking it would dilute the counts the facts promise
	 */
	private MarkerPlacement ( long volume, boolean enabled ) {
		this.enabled = enabled;

		long needleCount = Math.min(CorpusFacts.NEEDLE_TARGET_MATCHES, Math.max(volume, 1));
		long swatheCount = Math.max(Math.round(volume * CorpusFacts.SWATHE_TARGET_FRACTION), 1);

		this.needleStride = Math.max(volume / needleCount, 1);
		this.swatheStride = Math.max(volume / swatheCount, 1);
		// half a stride along, so a needle slot and a swathe slot can never be the same event
		this.swatheOffset = swatheStride > 1 ? swatheStride / 2 : 0;
	}

	static MarkerPlacement over ( long volume ) {
		return new MarkerPlacement(volume, true);
	}

	/** Marks nothing, for the noise contexts. */
	static MarkerPlacement none ( ) {
		return new MarkerPlacement(1, false);
	}

	/** @param index the position of this event among the markable ones, counted across all contexts */
	boolean isNeedle ( long index ) {
		return enabled && index % needleStride == 0;
	}

	/** Never true where {@link #isNeedle(long)} is, given the half-stride offset. */
	boolean isSwathe ( long index ) {
		if ( !enabled || isNeedle(index) ) {
			return false;
		}
		return swatheStride > 1 && index % swatheStride == swatheOffset;
	}
}
