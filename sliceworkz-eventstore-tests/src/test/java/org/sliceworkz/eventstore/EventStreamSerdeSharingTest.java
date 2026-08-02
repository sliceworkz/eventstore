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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Pins that {@code getEventStream} shares the payload serializer between streams opened with the same
 * event root classes, and that it shares nothing else.
 * <p>
 * The serde is the expensive half of the call by a wide margin. Building one constructs two Jackson
 * {@code JsonMapper}s and walks the sealed hierarchy reflectively, but the construction cost is not
 * really the point: Jackson caches its per-type serializers <em>inside the mapper</em>, so a serde
 * built per call gives every stream a cold cache and re-runs bean introspection on the first
 * serialize of each record type. Measured on a 24-record hierarchy that made a query through a
 * freshly obtained stream about four times the work of the same query through a stream that was kept
 * — while {@code EventSource.close()}'s documentation tells callers a stream is a cheap per-operation
 * handle, and the examples obtain one inline per operation.
 * <p>
 * The other half of this test matters just as much: the {@code EventStreamImpl} must <em>not</em> be
 * shared. A stream holds subscriber lists and a subscribed flag, so handing one instance to two
 * callers would make either caller's {@code close()} end the other's subscriptions. Sharing the
 * immutable serde and nothing else is what gets the cost down without touching that contract.
 */
class EventStreamSerdeSharingTest {

	sealed interface ShopEvent permits CustomerEvent, OrderEvent { }

	sealed interface CustomerEvent extends ShopEvent {
		record CustomerRegistered ( String id, String name ) implements CustomerEvent { }
		record CustomerChurned ( String id ) implements CustomerEvent { }
	}

	sealed interface OrderEvent extends ShopEvent {
		record OrderPlaced ( String orderId, String customerId ) implements OrderEvent { }
	}

	sealed interface OtherEvent {
		record SomethingHappened ( String what ) implements OtherEvent { }
	}

	/** not sealed, so registering it must fail */
	interface BrokenEvent { }

	private static EventStore storeOn ( EventStorage storage ) {
		return EventStoreFactory.get().eventStore(storage, new SimpleMeterRegistry());
	}

	/**
	 * Reads the serde an event stream was given. White-box on purpose: the sharing this test is about
	 * is invisible from the outside, and the alternative — asserting on timings — is exactly the kind
	 * of test that passes for the wrong reason on a loaded CI machine.
	 */
	private static Object serdeOf ( EventStream<?> stream ) {
		try {
			Field field = stream.getClass().getDeclaredField("serde");
			field.setAccessible(true);
			return field.get(stream);
		} catch ( ReflectiveOperationException e ) {
			throw new AssertionError("could not read the serde of " + stream.getClass()
					+ " — if the field was renamed, update this test rather than deleting it", e);
		}
	}

	@Test
	void streamsOpenedWithTheSameRootClassesShareOneSerde ( ) {
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = storeOn(storage);
			EventStreamId streamId = EventStreamId.forContext("shop");

			EventStream<ShopEvent> first = eventStore.getEventStream(streamId, ShopEvent.class);
			EventStream<ShopEvent> second = eventStore.getEventStream(streamId, ShopEvent.class);

			assertSame(serdeOf(first), serdeOf(second),
					"each getEventStream call built its own serde — two Jackson mappers and a reflective walk of the sealed hierarchy per call, and a cold Jackson type cache for every stream");
		}
	}

	@Test
	void theStreamItselfIsNeverShared ( ) {
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = storeOn(storage);
			EventStreamId streamId = EventStreamId.forContext("shop");

			EventStream<ShopEvent> first = eventStore.getEventStream(streamId, ShopEvent.class);
			EventStream<ShopEvent> second = eventStore.getEventStream(streamId, ShopEvent.class);

			assertNotSame(first, second,
					"getEventStream handed out the same stream twice — a stream is stateful (subscriber lists, subscribed flag), so one caller's close() would silently end the other's subscriptions");

			// and closing one really must leave the other working
			first.close();
			second.append(AppendCriteria.none(),
					Event.of(new CustomerEvent.CustomerRegistered("c1", "Jane"), Tags.none()));
			assertEquals(1, second.query(EventQuery.matchAll()).count(),
					"closing one stream disturbed another opened on the same id");
		}
	}

	@Test
	void differentRootClassesGetDifferentSerdes ( ) {
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = storeOn(storage);
			EventStreamId streamId = EventStreamId.forContext("shop");

			EventStream<ShopEvent> shop = eventStore.getEventStream(streamId, ShopEvent.class);
			EventStream<OtherEvent> other = eventStore.getEventStream(streamId, OtherEvent.class);
			EventStream<Object> raw = eventStore.getEventStream(streamId);

			assertNotSame(serdeOf(shop), serdeOf(other),
					"two streams with different event root classes shared a serde — they do not have the same type mappings");
			assertNotSame(serdeOf(shop), serdeOf(raw),
					"a typed stream and a raw stream shared a serde");

			// and the mappings really are the ones asked for. The DOMAIN_EVENT_TYPE parameter normally
			// makes this a compile error, so go around it to reach the runtime check the serde backs
			EventStream<Object> shopWithoutItsTypeParameter = eventStore.getEventStream(streamId, ShopEvent.class);
			other.append(AppendCriteria.none(),
					Event.of(new OtherEvent.SomethingHappened("x"), Tags.none()));
			assertThrows(IllegalArgumentException.class,
					() -> shopWithoutItsTypeParameter.append(AppendCriteria.none(),
							List.of(Event.of(new OtherEvent.SomethingHappened("y"), Tags.none()))),
					"a stream opened for ShopEvent accepted an event type it was never given a mapping for — it is using another stream's serde");
			assertTrue(serdeOf(shop) == serdeOf(shopWithoutItsTypeParameter),
					"the type parameter, which is erased, changed which serde was resolved");
		}
	}

	@Test
	void theCacheIsKeyedByValueNotByIdentityAndTheKeyIsCopied ( ) {
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = storeOn(storage);
			EventStreamId streamId = EventStreamId.forContext("shop");

			Set<Class<?>> mutableRoots = new HashSet<>(Set.of(ShopEvent.class));
			EventStream<ShopEvent> first = eventStore.getEventStream(streamId, mutableRoots);
			EventStream<ShopEvent> second = eventStore.getEventStream(streamId, Set.of(ShopEvent.class));

			assertSame(serdeOf(first), serdeOf(second),
					"two equal-but-distinct root class sets got different serdes — the cache is keyed by identity rather than by value");

			// mutating the set the caller passed must not reach the cached entry
			mutableRoots.add(OtherEvent.class);
			EventStream<ShopEvent> third = eventStore.getEventStream(streamId, Set.of(ShopEvent.class));
			assertSame(serdeOf(first), serdeOf(third),
					"mutating the set passed to an earlier call changed which serde a later call resolves to — the key was not copied");
		}
	}

	@Test
	void aRootClassSetThatFailsToRegisterIsNotRemembered ( ) {
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = storeOn(storage);
			EventStreamId streamId = EventStreamId.forContext("shop");

			// a non-sealed interface cannot be walked, so registration fails -- and must keep failing,
			// rather than the failure being cached and turned into something else on the second call
			assertThrows(IllegalArgumentException.class,
					() -> eventStore.getEventStream(streamId, BrokenEvent.class));
			assertThrows(IllegalArgumentException.class,
					() -> eventStore.getEventStream(streamId, BrokenEvent.class),
					"the second call failed differently from the first — a failed registration left something behind in the cache");

			// and a good mapping still works afterwards
			EventStream<ShopEvent> shop = eventStore.getEventStream(streamId, ShopEvent.class);
			shop.append(AppendCriteria.none(),
					Event.of(new OrderEvent.OrderPlaced("o1", "c1"), Tags.none()));
			assertEquals(1, shop.query(EventQuery.matchAll()).count());
		}
	}

	@Test
	void separateStoresDoNotShareSerdes ( ) {
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore one = storeOn(storage);
			EventStore two = storeOn(storage);
			EventStreamId streamId = EventStreamId.forContext("shop");

			assertNotSame(serdeOf(one.getEventStream(streamId, ShopEvent.class)),
					serdeOf(two.getEventStream(streamId, ShopEvent.class)),
					"two event stores shared a serde — the cache is static, which would pin the event classes' class loader for the life of the JVM");
		}
	}
}
