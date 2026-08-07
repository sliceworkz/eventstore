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
package org.sliceworkz.eventstore.testing.tck.spi;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.sliceworkz.eventstore.events.Lease;
import org.sliceworkz.eventstore.spi.EventStorage.LeaseRequest;
import org.sliceworkz.eventstore.spi.EventStorage.LeaseResponse;
import org.sliceworkz.eventstore.spi.EventStorage.LeaseStatus;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.EventStoreBackend.Capability;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Shared compliance scenarios for the lease operations backing leader election —
 * {@code requestLease}, {@code releaseLease} and {@code getLeases} — run against every backend
 * claiming {@link Capability#LEASE}, so election behaves identically whatever the storage.
 * <p>
 * The load-bearing scenarios are the concurrent one (exactly one of N racing contenders may win an
 * acquirable lease — the single-writer property everything above this rests on) and the fencing
 * monotonicity one (a token, once superseded, must never be handed out again). The rest pin the
 * state machine: renewal keeps the token, expiry and release make a lease acquirable, priorities
 * request — but never force — a step-down.
 * <p>
 * Time-to-live scenarios use TTLs of a few hundred milliseconds and generous waits, since expiry is
 * judged on the storage's clock (the database server's, for the Postgres backends) which this test
 * deliberately does not read.
 */
public class LeaseTest extends AbstractEventStoreTest {

	private static final String LEASE = "tck/lease/under-test";
	private static final Duration LONG_TTL = Duration.ofSeconds(60);
	private static final Duration SHORT_TTL = Duration.ofMillis(200);

	private LeaseRequest request ( String owner, long priority, Duration ttl ) {
		return new LeaseRequest(LEASE, owner, priority, ttl);
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testFirstRequestAcquiresTheLeaseWithTokenOne ( ) {
		LeaseResponse response = eventStorage().requestLease(request("owner-a", 0, LONG_TTL));

		assertEquals(LeaseStatus.LEADER, response.status());
		assertEquals(1, response.fencingToken());
		assertEquals("owner-a", response.currentOwner());

		List<Lease> leases = eventStorage().getLeases();
		assertEquals(1, leases.size());
		Lease lease = leases.get(0);
		assertEquals(LEASE, lease.leaseName());
		assertEquals("owner-a", lease.owner());
		assertEquals(1, lease.fencingToken());
		assertEquals(LONG_TTL, lease.ttl());
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testRenewalKeepsTheFencingTokenAndOwnership ( ) {
		eventStorage().requestLease(request("owner-a", 0, LONG_TTL));
		LeaseResponse renewal = eventStorage().requestLease(request("owner-a", 0, LONG_TTL));

		assertEquals(LeaseStatus.LEADER, renewal.status());
		assertEquals(1, renewal.fencingToken());
		assertEquals("owner-a", renewal.currentOwner());
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testSecondOwnerStandsByWhileTheLeaseIsLive ( ) {
		eventStorage().requestLease(request("owner-a", 0, LONG_TTL));
		LeaseResponse response = eventStorage().requestLease(request("owner-b", 0, LONG_TTL));

		assertEquals(LeaseStatus.STANDBY, response.status());
		assertEquals(1, response.fencingToken());
		assertEquals("owner-a", response.currentOwner());

		// standing by has not disturbed the holder
		LeaseResponse renewal = eventStorage().requestLease(request("owner-a", 0, LONG_TTL));
		assertEquals(LeaseStatus.LEADER, renewal.status());
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testExpiredLeaseIsTakenOverWithAnIncrementedToken ( ) {
		eventStorage().requestLease(request("owner-a", 0, SHORT_TTL));

		// the takeover happens when the storage clock says the ttl has passed, so poll rather than
		// assume this JVM's clock and the storage's agree on when that is
		await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() ->
				eventStorage().requestLease(request("owner-b", 0, LONG_TTL)).status() == LeaseStatus.LEADER);

		List<Lease> leases = eventStorage().getLeases();
		assertEquals(1, leases.size());
		assertEquals("owner-b", leases.get(0).owner());
		assertEquals(2, leases.get(0).fencingToken());
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testReleaseLetsAContenderAcquireImmediately ( ) {
		eventStorage().requestLease(request("owner-a", 0, LONG_TTL));
		assertEquals(LeaseStatus.STANDBY, eventStorage().requestLease(request("owner-b", 0, LONG_TTL)).status());

		eventStorage().releaseLease(LEASE, "owner-a");

		LeaseResponse takeover = eventStorage().requestLease(request("owner-b", 0, LONG_TTL));
		assertEquals(LeaseStatus.LEADER, takeover.status());
		assertEquals(2, takeover.fencingToken());
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testReleaseByANonOwnerLeavesTheLeaseUntouched ( ) {
		eventStorage().requestLease(request("owner-a", 0, LONG_TTL));

		assertDoesNotThrow(() -> eventStorage().releaseLease(LEASE, "owner-b"));
		assertDoesNotThrow(() -> eventStorage().releaseLease("tck/lease/never-acquired", "owner-b"));

		assertEquals(LeaseStatus.STANDBY, eventStorage().requestLease(request("owner-b", 0, LONG_TTL)).status());
		assertEquals(LeaseStatus.LEADER, eventStorage().requestLease(request("owner-a", 0, LONG_TTL)).status());
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testHigherPriorityContenderTriggersAStepDownRequest ( ) {
		eventStorage().requestLease(request("owner-a", 1, LONG_TTL));
		assertEquals(LeaseStatus.STANDBY, eventStorage().requestLease(request("owner-b", 2, LONG_TTL)).status());

		LeaseResponse renewal = eventStorage().requestLease(request("owner-a", 1, LONG_TTL));

		// still the leader — the storage requests the step-down, it never enforces it
		assertEquals(LeaseStatus.LEADER_STEP_DOWN_REQUESTED, renewal.status());
		assertEquals("owner-a", renewal.currentOwner());
		assertEquals(1, renewal.fencingToken());
		assertEquals("owner-a", eventStorage().getLeases().get(0).owner());
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testEqualOrLowerPriorityContendersDoNotTriggerAStepDown ( ) {
		eventStorage().requestLease(request("owner-a", 1, LONG_TTL));
		eventStorage().requestLease(request("owner-equal", 1, LONG_TTL));
		eventStorage().requestLease(request("owner-lower", 0, LONG_TTL));

		assertEquals(LeaseStatus.LEADER, eventStorage().requestLease(request("owner-a", 1, LONG_TTL)).status());
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testAStepDownRequestLapsesWithTheContender ( ) {
		eventStorage().requestLease(request("owner-a", 1, LONG_TTL));
		// the challenger registers with a short ttl and then goes away
		eventStorage().requestLease(request("owner-b", 2, SHORT_TTL));
		assertEquals(LeaseStatus.LEADER_STEP_DOWN_REQUESTED, eventStorage().requestLease(request("owner-a", 1, LONG_TTL)).status());

		// once the challenger's own ttl has lapsed on the storage clock, it is no longer live and
		// the step-down request disappears with it
		await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() ->
				eventStorage().requestLease(request("owner-a", 1, LONG_TTL)).status() == LeaseStatus.LEADER);
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testStepDownHandover ( ) {
		// the full failback protocol: leader steps down on request, challenger takes over
		eventStorage().requestLease(request("owner-standby", 1, LONG_TTL));
		assertEquals(LeaseStatus.STANDBY, eventStorage().requestLease(request("owner-preferred", 2, LONG_TTL)).status());
		assertEquals(LeaseStatus.LEADER_STEP_DOWN_REQUESTED, eventStorage().requestLease(request("owner-standby", 1, LONG_TTL)).status());

		eventStorage().releaseLease(LEASE, "owner-standby");

		LeaseResponse takeover = eventStorage().requestLease(request("owner-preferred", 2, LONG_TTL));
		assertEquals(LeaseStatus.LEADER, takeover.status());
		assertEquals(2, takeover.fencingToken());
		// the demoted owner is a plain standby now
		assertEquals(LeaseStatus.STANDBY, eventStorage().requestLease(request("owner-standby", 1, LONG_TTL)).status());
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testFencingTokensIncreaseStrictlyAcrossOwnershipChanges ( ) {
		long first = eventStorage().requestLease(request("owner-a", 0, LONG_TTL)).fencingToken();
		eventStorage().releaseLease(LEASE, "owner-a");
		long second = eventStorage().requestLease(request("owner-b", 0, LONG_TTL)).fencingToken();
		eventStorage().releaseLease(LEASE, "owner-b");
		long third = eventStorage().requestLease(request("owner-a", 0, LONG_TTL)).fencingToken();

		assertTrue(first < second, "token must increase on takeover: %d -> %d".formatted(first, second));
		assertTrue(second < third, "token must increase on takeover: %d -> %d".formatted(second, third));
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testLeasesAreIndependentOfEachOther ( ) {
		eventStorage().requestLease(new LeaseRequest("tck/lease/one", "owner-a", 0, LONG_TTL));
		LeaseResponse other = eventStorage().requestLease(new LeaseRequest("tck/lease/two", "owner-b", 0, LONG_TTL));

		assertEquals(LeaseStatus.LEADER, other.status());
		assertEquals(2, eventStorage().getLeases().size());
	}

	/**
	 * The single-writer property under concurrency, which every backend has to earn its own way (the
	 * in-memory store by synchronizing, Postgres with its per-lease advisory lock): N contenders race
	 * for one acquirable lease from a common start signal, and exactly one may be told LEADER.
	 */
	@ForEachBackend(requires = Capability.LEASE)
	public void testConcurrentFirstAcquireElectsExactlyOneLeader ( ) throws Exception {
		int contenders = 8;
		CountDownLatch start = new CountDownLatch(1);

		try ( ExecutorService executor = Executors.newFixedThreadPool(contenders) ) {
			List<Future<LeaseResponse>> futures = IntStream.range(0, contenders)
					.mapToObj(i -> executor.submit(() -> {
						start.await();
						return eventStorage().requestLease(request("owner-" + i, 0, LONG_TTL));
					}))
					.toList();
			start.countDown();

			long leaders = 0;
			long standbys = 0;
			for ( Future<LeaseResponse> future : futures ) {
				LeaseResponse response = future.get();
				if ( response.status() == LeaseStatus.LEADER ) {
					leaders++;
					assertEquals(1, response.fencingToken());
				} else {
					assertEquals(LeaseStatus.STANDBY, response.status());
					standbys++;
				}
			}
			assertEquals(1, leaders, "exactly one contender may win the race");
			assertEquals(contenders - 1, standbys);
		}
	}

	@ForEachBackend(requires = Capability.LEASE)
	public void testLeaseOperationsThrowOnAClosedStorage ( ) {
		eventStorage().requestLease(request("owner-a", 0, LONG_TTL));
		eventStorage().close();

		assertThrows(EventStorageClosedException.class, () -> eventStorage().requestLease(request("owner-a", 0, LONG_TTL)));
		assertThrows(EventStorageClosedException.class, () -> eventStorage().releaseLease(LEASE, "owner-a"));
		assertThrows(EventStorageClosedException.class, () -> eventStorage().getLeases());
	}

}
