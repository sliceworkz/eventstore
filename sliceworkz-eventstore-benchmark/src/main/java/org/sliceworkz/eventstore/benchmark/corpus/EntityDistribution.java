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

import java.util.random.RandomGenerator;

/**
 * Picks which entity an event is about, with the skew real traffic has.
 *
 * <p>A uniform draw would make every SKU equally busy, and that quietly removes the thing most worth
 * measuring. Contention on a DCB boundary is a property of the <em>hot</em> entity; index selectivity
 * for "this entity's history" is a property of the <em>cold</em> one. Spread traffic evenly and both
 * numbers collapse into one unremarkable average that describes no real shop.
 *
 * <p>The distribution is Zipf with exponent 1, the usual first approximation for popularity: the
 * n-th most popular entity gets traffic proportional to 1/n. Over a thousand SKUs that puts roughly
 * 13% of all events on the busiest one and a handful on the least busy, which is the spread the hot
 * and cold facts are drawn from.
 *
 * <p>The cumulative weights are precomputed once, so a draw is a binary search rather than a sum --
 * generating ten million events would otherwise spend most of its time here.
 */
final class EntityDistribution {

	private final double[] cumulative;

	EntityDistribution ( int entityCount ) {
		if ( entityCount <= 0 ) {
			throw new IllegalArgumentException("entityCount must be positive");
		}
		this.cumulative = new double[entityCount];

		double running = 0;
		for ( int i = 0; i < entityCount; i++ ) {
			running += 1.0d / ( i + 1 );
			cumulative[i] = running;
		}
		// normalise in place, so a draw is a search for a value in [0, 1)
		double total = cumulative[entityCount - 1];
		for ( int i = 0; i < entityCount; i++ ) {
			cumulative[i] /= total;
		}
	}

	/** The index of the entity an event is about, most popular first. */
	int next ( RandomGenerator random ) {
		double value = random.nextDouble();
		int low = 0;
		int high = cumulative.length - 1;
		while ( low < high ) {
			int mid = ( low + high ) >>> 1;
			if ( cumulative[mid] < value ) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}
		return low;
	}

	/** The expected share of all events falling on the given entity. */
	double shareOf ( int index ) {
		return index == 0 ? cumulative[0] : cumulative[index] - cumulative[index - 1];
	}

	int entityCount ( ) {
		return cumulative.length;
	}
}
