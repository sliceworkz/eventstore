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
 * Shipment lifecycle. Carries no invariant the suite measures; it is here to be <em>noise</em> -- a
 * fourth context whose events sit in the same table, share the same indexes and dilute the
 * selectivity of everyone else's queries, which is exactly the effect the {@code multi-domain}
 * composition exists to quantify.
 */
public sealed interface ShippingEvent {

	/**
	 * Note the destination is a city and country, not an {@link Address}. A real shipping address is
	 * personal data and would belong in a {@code Shreddable} -- but a type declaring one cannot be
	 * registered at all unless a shredding codec is configured, which would make it impossible to open
	 * a shipping stream with shredding switched off. Since shredding is a dimension the suite turns on
	 * and off, personal data is confined to {@code crm}, and this context stays free of it.
	 */
	record ShipmentPlanned ( String shipmentId, String orderId, String carrier, String destinationCity, String destinationCountry )
			implements ShippingEvent { }

	record ShipmentDispatched ( String shipmentId, String trackingCode ) implements ShippingEvent { }

	record ShipmentDelivered ( String shipmentId, String signedBy ) implements ShippingEvent { }
}
