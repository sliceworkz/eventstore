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
package org.sliceworkz.eventstore.benchmark.domain;

import java.util.List;
import java.util.Set;

import org.sliceworkz.eventstore.benchmark.domain.SalesEvent.CouponApplied;
import org.sliceworkz.eventstore.benchmark.domain.SalesEvent.OrderPlaced;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Upcast;

/**
 * Sales events as they were written by an earlier version of the shop, kept readable through
 * upcasters. This is the suite's <b>upcasting</b> dimension.
 *
 * <p>Two shapes, because they cost differently and one of them changes what a limit means:
 *
 * <ul>
 *   <li>{@link OrderPlacedV1} upcasts <b>one to one</b>. The cost is an extra deserialization target
 *       and an object allocation per event -- the ordinary case.</li>
 *   <li>{@link BasketCheckedOut} upcasts <b>one to many</b>: a single stored event becomes an
 *       {@code OrderPlaced} plus a {@code CouponApplied}, because the old shop conflated the two.
 *       This is the case worth having in a benchmark, since {@code EventQuery.limit(n)} counts
 *       <em>stored</em> events and is spent before the upcaster runs -- so a limit of 1 over one of
 *       these returns two events. A read benchmark that assumed limit means result size would report
 *       throughput for twice the work it thought it was doing.</li>
 * </ul>
 *
 * <p>Note the names: {@code OrderPlacedV1} rather than a second {@code OrderPlaced}. Event type simple
 * names are global to a storage, so a legacy hierarchy reusing a current name would write
 * indistinguishable {@code event_type} values -- and registering both on one stream throws.
 */
public sealed interface LegacySalesEvent {

	/**
	 * The original order event, before order lines carried a unit price.
	 */
	@LegacyEvent(upcast = OrderPlacedV1Upcaster.class)
	record OrderPlacedV1 ( String orderId, String basketId, String customerId, long totalCents ) implements LegacySalesEvent { }

	/**
	 * The original checkout event, which recorded the coupon inline instead of as its own fact.
	 */
	@LegacyEvent(upcast = BasketCheckedOutUpcaster.class)
	record BasketCheckedOut ( String orderId, String basketId, String customerId, long totalCents,
			String couponCode, long discountCents ) implements LegacySalesEvent { }

	/**
	 * One stored event in, one current event out.
	 *
	 * <p>The line list comes back empty rather than invented: the old event never recorded it, and a
	 * benchmark corpus that fabricated plausible lines here would be measuring the upcaster's
	 * imagination.
	 */
	final class OrderPlacedV1Upcaster implements Upcast<OrderPlacedV1, OrderPlaced> {

		@Override
		public List<OrderPlaced> upcast ( OrderPlacedV1 legacy ) {
			return List.of(new OrderPlaced(legacy.orderId(), legacy.basketId(), legacy.customerId(),
					List.of(), Money.euro(legacy.totalCents())));
		}

		@Override
		public Set<Class<? extends OrderPlaced>> targetTypes ( ) {
			return Set.of(OrderPlaced.class);
		}
	}

	/**
	 * One stored event in, <b>two</b> current events out -- the case that makes {@code limit(n)}
	 * return more than n.
	 */
	final class BasketCheckedOutUpcaster implements Upcast<BasketCheckedOut, SalesEvent> {

		@Override
		public List<SalesEvent> upcast ( BasketCheckedOut legacy ) {
			return List.of(
					new CouponApplied(legacy.basketId(), legacy.couponCode(), Money.euro(legacy.discountCents())),
					new OrderPlaced(legacy.orderId(), legacy.basketId(), legacy.customerId(),
							List.of(), Money.euro(legacy.totalCents())));
		}

		@Override
		public Set<Class<? extends SalesEvent>> targetTypes ( ) {
			return Set.of(CouponApplied.class, OrderPlaced.class);
		}
	}
}
