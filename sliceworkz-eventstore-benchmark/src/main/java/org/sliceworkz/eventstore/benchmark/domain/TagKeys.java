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
 * The tag keys the webshop uses, in one place because a tag key is matched by exact containment and
 * a typo produces a query that quietly matches nothing rather than an error.
 *
 * <p>Two groups. The <b>entity</b> keys identify what an event is about and are what the DCB
 * boundaries filter on. The <b>cross-cutting</b> keys are dimensions a report might slice by; they
 * exist mainly to give events a realistic tag count, since the number of entries in the
 * {@code text[]} column is what the GIN index actually has to work through.
 *
 * <p>Every one of these is used as {@code key:value}, never as a bare key. Matching is exact
 * containment and there is no wildcard form, so a bare {@code customer} tag would <em>not</em> be
 * found by a query for {@code customer:42} nor the other way round.
 */
public final class TagKeys {

	/* entity keys -- what the consistency boundaries filter on */

	public static final String SKU = "sku";
	public static final String ORDER = "order";
	public static final String BASKET = "basket";
	public static final String CUSTOMER = "customer";
	public static final String PAYMENT = "payment";
	public static final String SHIPMENT = "shipment";
	public static final String COUPON = "coupon";

	/* cross-cutting keys -- present to make tag counts realistic */

	public static final String WAREHOUSE = "warehouse";
	public static final String CHANNEL = "channel";
	public static final String COUNTRY = "country";

	/* only on the wide-tags payload profile */

	public static final String CAMPAIGN = "campaign";
	public static final String SEGMENT = "segment";
	public static final String EXPERIMENT = "experiment";
	public static final String CATEGORY = "category";
	public static final String CARRIER = "carrier";

	private TagKeys ( ) { }
}
