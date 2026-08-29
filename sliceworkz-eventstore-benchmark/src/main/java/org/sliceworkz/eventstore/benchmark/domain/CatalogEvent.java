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
 * Product catalogue changes: cheap, high-volume noise.
 *
 * <p>Its job in the suite is bulk. Price changes are the most numerous events a webshop produces and
 * nothing in the measured workloads queries them, so this context is what makes a store "large"
 * without making it interesting -- which is precisely what a composition axis needs. Its payloads are
 * deliberately small, so a corpus can be grown by an order of magnitude without the row width
 * becoming the thing being measured.
 */
public sealed interface CatalogEvent {

	record ProductListed ( String sku, String name, String category ) implements CatalogEvent { }

	record PriceChanged ( String sku, Money price ) implements CatalogEvent { }

	record ProductDelisted ( String sku, String reason ) implements CatalogEvent { }
}
