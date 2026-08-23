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

/**
 * Stock movements, one SKU at a time. This is the suite's <b>hot DCB boundary</b>: "a SKU is never
 * oversold" is decided by querying every movement carrying {@code sku:X} and appending a reservation
 * only if nothing new has arrived since, which is the canonical Dynamic Consistency Boundary check
 * and the thing most worth putting a number on.
 *
 * <p>Reservation traffic concentrates on a few popular SKUs and thins out over a long tail, so the
 * same filter shape is both a contended boundary and an uncontended one depending on which SKU it
 * names -- which is why the corpus publishes a hot and a cold SKU as facts rather than letting a
 * benchmark pick one at random.
 *
 * <p>{@code StockCounted} is a savepoint event in the sense of {@code Projection.initQuery()}: it
 * states an absolute level, so a projection can find the last one backwards with limit 1 and replay
 * only the movements after it instead of the whole history.
 */
public sealed interface InventoryEvent {

	/** Stock arrived at a warehouse. */
	record StockReceived ( String sku, int quantity, String warehouseId, Money unitCost ) implements InventoryEvent { }

	/** Stock earmarked for an order. The event the oversell boundary guards. */
	record StockReserved ( String sku, int quantity, String orderId ) implements InventoryEvent { }

	/** A reservation given back, because the order was cancelled or the basket expired. */
	record StockReleased ( String sku, int quantity, String orderId, String reason ) implements InventoryEvent { }

	/** Reserved stock physically taken off the shelf. */
	record StockPicked ( String sku, int quantity, String orderId, String warehouseId ) implements InventoryEvent { }

	/**
	 * A physical count, stating the level absolutely rather than as a delta -- the savepoint that
	 * lets a projection skip the movements before it.
	 */
	record StockCounted ( String sku, int counted, String warehouseId ) implements InventoryEvent { }
}
