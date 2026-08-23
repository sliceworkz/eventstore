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

/**
 * Baskets and orders: the primary system under test.
 *
 * <p>Two invariants of very different shape live here, which is why this context carries most of the
 * append workloads. "A basket is checked out at most once" is a <b>cold</b> boundary -- the filter
 * names one basket, nobody else is writing to it, and the interesting cost is the check itself
 * rather than contention. "A coupon is redeemed at most N times" is the opposite: one filter that
 * every writer in the shop matches at once, so it measures the advisory lock and the conflict-retry
 * loop rather than the query.
 *
 * <p>{@code OrderPlaced} is also the event behind the suite's multi-fact append: placing an order
 * needs stock for each of its lines, a coupon that has not run out, and a customer in good standing,
 * which is one {@code AppendCriteria} holding five OR-ed filter items.
 */
public sealed interface SalesEvent {

	record BasketStarted ( String basketId, String customerId, String channel ) implements SalesEvent { }

	record ItemAddedToBasket ( String basketId, String sku, int quantity, Money unitPrice ) implements SalesEvent { }

	record ItemRemovedFromBasket ( String basketId, String sku, int quantity ) implements SalesEvent { }

	record CouponApplied ( String basketId, String couponCode, Money discount ) implements SalesEvent { }

	record OrderPlaced ( String orderId, String basketId, String customerId, List<OrderLine> lines, Money total )
			implements SalesEvent { }

	record OrderCancelled ( String orderId, String reason ) implements SalesEvent { }
}
