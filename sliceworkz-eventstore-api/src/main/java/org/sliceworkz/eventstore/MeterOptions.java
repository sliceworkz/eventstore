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
package org.sliceworkz.eventstore;

/**
 * How much detail the event store's meters are allowed to carry, and therefore how many time series
 * they can create.
 *
 * <h2>Why this exists</h2>
 * Every meter the store registers is tagged with the stream's {@code context} and {@code purpose}.
 * {@code context} is a code-level concept — a bounded context — so its cardinality is a property of
 * the application. {@code purpose} is not: it is documented as "an optional secondary identifier to
 * distinguish multiple streams within the same context (e.g. customer ID, order number)", and the
 * examples throughout this library use {@code forContext("customer").withPurpose("123")}. Used that
 * way it takes one value per entity.
 * <p>
 * That is expensive, and it never stops growing. A Micrometer registry does not evict meters, so the
 * cost is driven by the number of distinct purposes the process has <em>ever</em> seen, not by how
 * many streams are alive — dropping the stream handle releases nothing. Measured against an in-memory
 * store with two event types, each distinct purpose costs:
 * <ul>
 *   <li>15 meters, plus 2 more for every additional event type ({@code query.event} and
 *       {@code append.event} carry an {@code eventtype} tag on top of the stream tags)</li>
 *   <li>~5.5 KB of heap, held for the lifetime of the registry</li>
 *   <li>18 Prometheus series and ~2.4 KB of scrape body</li>
 * </ul>
 * So 100.000 customers is roughly 550 MB of heap, 1.8 million series and a 234 MB scrape — for a
 * breakdown nobody can read at that width anyway.
 *
 * <h2>What this does about it</h2>
 * The store tags meters with the first {@link #maxPurposeTagValues()} distinct purposes it sees and
 * reports every purpose after that as {@value #OVERFLOW_PURPOSE_TAG_VALUE}, logging a warning the
 * first time it has to. Below the cap nothing changes, which is exactly the case where the breakdown
 * is worth having; above it the meters stay bounded and the events are still counted, just pooled.
 * <p>
 * Which purposes get through is arrival order, so it is first-N-wins and not stable across restarts.
 * That is the accepted cost of a bound that needs no configuration — past the cap, a per-purpose
 * breakdown was not going to be useful. An application that wants a specific breakdown should keep
 * its purposes below the cap rather than rely on which ones happen to arrive first.
 * <p>
 * The cap is applied where the tag value is chosen, so it bounds everything downstream: all the
 * per-stream meters, the {@code eventtype} cross product, and the store's own map of gauge state.
 * A Micrometer {@code MeterFilter} cannot do the last of those — a filter runs at registration, and
 * the store keys its gauge state on the tags it asked for, so filtering alone still leaks ~730 bytes
 * per distinct purpose inside the store.
 *
 * <h2>Choosing a value</h2>
 * <pre>{@code
 * // the default: 1000 purposes per store, then _other
 * EventStore store = EventStoreFactory.get().eventStore(storage, registry);
 *
 * // a context with genuinely few purposes, and an alert if that ever stops being true
 * EventStore store = EventStoreFactory.get().eventStore(storage, registry,
 *                        MeterOptions.withMaxPurposeTagValues(50));
 *
 * // purpose is an entity id here -- do not break down by it at all
 * EventStore store = EventStoreFactory.get().eventStore(storage, registry,
 *                        MeterOptions.withoutPurposeBreakdown());
 * }</pre>
 *
 * @param maxPurposeTagValues how many distinct {@code purpose} tag values this store may report
 *                            before pooling the rest under {@value #OVERFLOW_PURPOSE_TAG_VALUE};
 *                            0 pools every purpose, {@link Integer#MAX_VALUE} pools none
 * @see EventStoreFactory#eventStore(org.sliceworkz.eventstore.spi.EventStorage, io.micrometer.core.instrument.MeterRegistry, MeterOptions)
 */
public record MeterOptions ( int maxPurposeTagValues ) {

	/**
	 * The default cap on distinct {@code purpose} tag values per store: {@value}.
	 * <p>
	 * Chosen to be far above what a purpose used the low-cardinality way ever reaches — separating
	 * event kinds within a context, one stream per subsystem — and far below what a purpose used as an
	 * entity id costs. At the cap a store holds on the order of 15.000 meters and a few MB of heap,
	 * which is a large but survivable metrics footprint; an order of magnitude more is not.
	 */
	public static final int DEFAULT_MAX_PURPOSE_TAG_VALUES = 1000;

	/**
	 * The {@code purpose} tag value reported once the cap is reached: {@value}.
	 * <p>
	 * The tag is pooled rather than dropped so that the series keeps its shape — a query summing over
	 * {@code purpose} keeps working, and the overflow is visible as itself rather than as a gap.
	 */
	public static final String OVERFLOW_PURPOSE_TAG_VALUE = "_other";

	/**
	 * Validates the cap.
	 *
	 * @throws IllegalArgumentException if {@code maxPurposeTagValues} is negative
	 */
	public MeterOptions {
		if ( maxPurposeTagValues < 0 ) {
			throw new IllegalArgumentException("maxPurposeTagValues cannot be negative, was %d. Use 0 to stop breaking meters down by purpose".formatted(maxPurposeTagValues));
		}
	}

	/**
	 * The options a store gets when none are given: a cap of {@value #DEFAULT_MAX_PURPOSE_TAG_VALUES}
	 * distinct purposes.
	 *
	 * @return the default options
	 */
	public static MeterOptions defaults ( ) {
		return new MeterOptions(DEFAULT_MAX_PURPOSE_TAG_VALUES);
	}

	/**
	 * Options capping the {@code purpose} tag at the given number of distinct values.
	 *
	 * @param maxPurposeTagValues the cap; 0 pools every purpose under
	 *                            {@value #OVERFLOW_PURPOSE_TAG_VALUE}
	 * @return the options
	 * @throws IllegalArgumentException if the cap is negative
	 */
	public static MeterOptions withMaxPurposeTagValues ( int maxPurposeTagValues ) {
		return new MeterOptions(maxPurposeTagValues);
	}

	/**
	 * Options that never break meters down by purpose: every stream reports
	 * {@value #OVERFLOW_PURPOSE_TAG_VALUE}.
	 * <p>
	 * The right choice where purpose is an entity id and the breakdown was never going to be read.
	 * Meters are then bounded by {@code context} alone, which is a property of the code.
	 *
	 * @return the options
	 */
	public static MeterOptions withoutPurposeBreakdown ( ) {
		return new MeterOptions(0);
	}

	/**
	 * Options that let the {@code purpose} tag take as many values as the application produces.
	 * <p>
	 * Only safe where purpose is known to be low-cardinality by construction. See the class
	 * documentation for what this costs when that assumption turns out to be wrong; it is unbounded
	 * and nothing reclaims it.
	 *
	 * @return the options
	 */
	public static MeterOptions withUnlimitedPurposeTagValues ( ) {
		return new MeterOptions(Integer.MAX_VALUE);
	}

	/**
	 * Whether this store pools every purpose, i.e. never breaks its meters down by purpose.
	 *
	 * @return true if the cap is 0
	 */
	public boolean poolsEveryPurpose ( ) {
		return maxPurposeTagValues == 0;
	}

}
