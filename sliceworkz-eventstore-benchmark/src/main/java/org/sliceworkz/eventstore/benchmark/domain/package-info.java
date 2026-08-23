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
/**
 * The business case the benchmark suite measures: a webshop platform, modelled as six bounded
 * contexts plus one legacy hierarchy.
 *
 * <p>A benchmark needs a domain for the same reason an integration test does -- invented operations
 * measure invented costs. This one was chosen because every append shape worth measuring falls out
 * of a real business rule rather than being contrived to fill a cell in a matrix:
 *
 * <table border="1">
 *   <caption>invariants and the filter shapes they produce</caption>
 *   <tr><th>invariant</th><th>filter</th><th>boundary heat</th></tr>
 *   <tr><td>a SKU is never oversold</td><td>4 types + one tag</td><td>hot for top SKUs, cold for the long tail</td></tr>
 *   <tr><td>a basket is checked out at most once</td><td>1 type + one tag</td><td>cold, unique per basket</td></tr>
 *   <tr><td>a coupon is redeemed at most N times</td><td>1 type + one tag</td><td>very hot, every writer collides</td></tr>
 *   <tr><td>an order is captured once</td><td>1 type + one tag</td><td>cold</td></tr>
 *   <tr><td>placing an order needs stock for three SKUs, a valid coupon and a customer in good standing</td>
 *       <td>five OR-ed filter items</td><td>mixed</td></tr>
 * </table>
 *
 * <p><b>Event type simple names are unique across all seven hierarchies, deliberately.</b>
 * {@code EventType.of(Class)} is {@code Class.getSimpleName()}, and that name is global to a storage
 * rather than scoped to a stream -- two contexts sharing a {@code Created} write indistinguishable
 * {@code event_type} values into one table, and nothing catches it across streams. Since the whole
 * point of the {@code multi-domain} composition is to put six contexts in one table, this package
 * has to obey that rule or it would be measuring a bug. It doubles as a worked example of it.
 *
 * <p>Contexts, and what each is for in the suite:
 *
 * <dl>
 *   <dt>{@code inventory}</dt><dd>the hot DCB boundary -- stock movements per SKU</dd>
 *   <dt>{@code sales}</dt><dd>the primary system under test -- baskets and orders</dd>
 *   <dt>{@code payments}</dt><dd>a second real domain, and noise for the others</dd>
 *   <dt>{@code shipping}</dt><dd>noise</dd>
 *   <dt>{@code crm}</dt><dd>where personal data lives, so the shredding dimension is not synthetic</dd>
 *   <dt>{@code catalog}</dt><dd>cheap high-volume noise</dd>
 * </dl>
 */
package org.sliceworkz.eventstore.benchmark.domain;
