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

import java.util.Set;

/**
 * The six bounded contexts, each paired with the stream context name it writes to, its root event
 * class, and the entity tag key its events are identified by.
 *
 * <p>Having this as an enum rather than as scattered string literals is what lets the corpus
 * generator, the workloads and the reports agree on what a context <em>is</em> -- and it is what makes
 * "generate five noise contexts at five times the volume" a loop rather than five hand-written cases.
 *
 * <p>{@link #CRM} is the odd one out: it declares {@code Shreddable} components, so a stream over it
 * cannot be opened at all without a shredding codec configured. Callers that build a store with
 * shredding switched off must use {@link #withoutPersonalData()} rather than {@link #values()}.
 */
public enum WebshopContext {

	/** The hot DCB boundary: stock per SKU. */
	INVENTORY("inventory", InventoryEvent.class, TagKeys.SKU),

	/** The primary system under test: baskets and orders. */
	SALES("sales", SalesEvent.class, TagKeys.ORDER),

	/** A second real domain, and noise for the others. */
	PAYMENTS("payments", PaymentEvent.class, TagKeys.PAYMENT),

	/** Noise. */
	SHIPPING("shipping", ShippingEvent.class, TagKeys.SHIPMENT),

	/** Personal data, and therefore the shredding dimension. */
	CRM("crm", CrmEvent.class, TagKeys.CUSTOMER),

	/** Cheap high-volume noise. */
	CATALOG("catalog", CatalogEvent.class, TagKeys.SKU);

	private final String streamContext;
	private final Class<?> rootClass;
	private final String entityTagKey;

	WebshopContext ( String streamContext, Class<?> rootClass, String entityTagKey ) {
		this.streamContext = streamContext;
		this.rootClass = rootClass;
		this.entityTagKey = entityTagKey;
	}

	/** The {@code EventStreamId} context name this writes to. */
	public String streamContext ( ) {
		return streamContext;
	}

	/** The sealed root to register with {@code getEventStream}. */
	public Class<?> rootClass ( ) {
		return rootClass;
	}

	/** The tag key identifying what one of its events is about. */
	public String entityTagKey ( ) {
		return entityTagKey;
	}

	/** Whether opening a stream over this context requires a shredding codec. */
	public boolean requiresShredding ( ) {
		return this == CRM;
	}

	/**
	 * Every context that can be opened on a store built without shredding -- which is the default,
	 * since shredding is a dimension rather than a baseline.
	 */
	public static Set<WebshopContext> withoutPersonalData ( ) {
		return Set.of(INVENTORY, SALES, PAYMENTS, SHIPPING, CATALOG);
	}
}
