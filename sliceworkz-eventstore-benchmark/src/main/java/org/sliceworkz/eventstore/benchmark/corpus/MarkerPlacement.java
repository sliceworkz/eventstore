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
 *
 * <p><b>Every marker lands in the inventory context</b>, because that is the stream the marker
 * workloads query. Markers used to spread across the whole volume under test -- inventory and sales
 * both -- while {@code query-by-tag-needle} reads a stream scoped to inventory, so the recorded
 * counts described the store and the workload measured only inventory's share of them: "~10
 * matches" was really six. The counts are still <em>sized</em> against the whole under-test volume
 * (a swathe is one percent of the store, not one percent of inventory), so the selectivity the
 * store-wide tag index sees is unchanged; what changed is that all of it now sits where the reads
 * look, and the recorded counts equal what a workload gets back.
 */
final class MarkerPlacement {

	private final long needleStride;
	private final long swatheStride;
	private final long swatheOffset;
	private final boolean enabled;

	/**
	 * @param markableVolume how many events can carry a marker -- the inventory context only, since
	 *        that is the stream the marker workloads query; see the class comment
	 * @param storeVolume the whole volume under test, which is what the swathe's one-percent target
	 *        is a percentage of
	 */
	private MarkerPlacement ( long markableVolume, long storeVolume, boolean enabled ) {
		this.enabled = enabled;

		long needleCount = Math.min(CorpusFacts.NEEDLE_TARGET_MATCHES, Math.max(markableVolume, 1));
		// sized against the store, placed within inventory -- and capped there, so a degenerate spec
		// whose inventory share is below one percent still gets a valid (if narrower) swathe
		long swatheCount = Math.min(Math.max(Math.round(storeVolume * CorpusFacts.SWATHE_TARGET_FRACTION), 1),
				Math.max(markableVolume, 1));

		this.needleStride = Math.max(markableVolume / needleCount, 1);
		this.swatheStride = Math.max(markableVolume / swatheCount, 1);
		// half a stride along, so a needle slot and a swathe slot can never be the same event
		this.swatheOffset = swatheStride > 1 ? swatheStride / 2 : 0;
	}

	static MarkerPlacement over ( long markableVolume, long storeVolume ) {
		return new MarkerPlacement(markableVolume, storeVolume, true);
	}

	/** Marks nothing, for every context the marker workloads do not read. */
	static MarkerPlacement none ( ) {
		return new MarkerPlacement(1, 1, false);
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
