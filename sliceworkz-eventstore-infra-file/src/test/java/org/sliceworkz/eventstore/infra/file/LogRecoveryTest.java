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
package org.sliceworkz.eventstore.infra.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * What happens to the log when the process does not get to finish writing.
 *
 * <h2>Why these tests exist here and not in the TCK</h2>
 * The shared compliance scenarios never close a store and open it again — every scenario gets a fresh,
 * empty storage and the harness discards it afterwards. So the whole of recovery, which is the hardest
 * part of a storage engine to get right and the easiest to get quietly wrong, is invisible to them. A
 * backend can pass all 28 scenario classes and still lose a caller's events on the first crash.
 *
 * <h2>The property being tested</h2>
 * After any truncation of the log, the events the store reports must be <strong>exactly one of the
 * prefixes that were committed</strong> — every event of some batch, and no event of any later one.
 * Never a partial batch. That is the guarantee the commit trailer exists to provide, and the reason
 * recovery cuts back to a batch boundary rather than to the frame that failed to validate.
 */
class LogRecoveryTest {

	private static final EventStreamId STREAM = EventStreamId.forContext("recovery");

	@TempDir
	Path root;

	@Test
	@DisplayName("a store reopens onto everything it committed, and keeps appending where it left off")
	void reopensOntoEverythingItCommitted ( ) {
		Path directory = root.resolve("reopen");

		List<String> before = new ArrayList<>();
		try ( EventStorage storage = open(directory) ) {
			before.addAll(ids(storage.append(AppendCriteria.none(), Optional.of(STREAM), events("a", "b"))));
			before.addAll(ids(storage.append(AppendCriteria.none(), Optional.of(STREAM), events("c"))));
			storage.bookmark("reader", lastReference(storage), Tags.of("k", "v"));
		}

		try ( EventStorage storage = open(directory) ) {
			assertEquals(before, ids(all(storage)), "every committed event should come back, in order");
			assertEquals(3, all(storage).size());

			// the next append continues the sequence rather than restarting it
			List<StoredEvent> appended = storage.append(AppendCriteria.none(), Optional.of(STREAM), events("d"));
			assertEquals(4, appended.get(0).reference().position());

			assertTrue(storage.getBookmark("reader").isPresent(), "a bookmark should survive a restart");
			assertEquals(Tags.of("k", "v"), storage.getBookmarks().get(0).tags());
		}
	}

	@Test
	@DisplayName("an idempotency key still deduplicates after a restart")
	void idempotencyKeysSurviveARestart ( ) {
		Path directory = root.resolve("idempotency");

		try ( EventStorage storage = open(directory) ) {
			assertEquals(1, storage.append(AppendCriteria.none(), Optional.of(STREAM), List.of(event("a", "key-1"))).size());
		}
		try ( EventStorage storage = open(directory) ) {
			assertEquals(0, storage.append(AppendCriteria.none(), Optional.of(STREAM), List.of(event("a", "key-1"))).size(),
					"the key was used before the restart, so the event is a duplicate afterwards too");
			assertEquals(1, all(storage).size());
		}
	}

	@Test
	@DisplayName("truncating the log at any byte leaves exactly a committed prefix, never half a batch")
	void truncatingAnywhereLeavesACommittedPrefix ( ) throws IOException {
		Path original = root.resolve("truncation");

		// batches of different sizes, so a truncation inside a multi-event batch is actually reachable:
		// with only single-event batches the "never half a batch" property would be untestable
		List<Integer> committedCounts = new ArrayList<>(List.of(0));
		List<String> committedIds = new ArrayList<>();
		try ( EventStorage storage = open(original) ) {
			for ( String[] batch : new String[][] { { "a" }, { "b", "c", "d" }, { "e", "f" }, { "g" } } ) {
				committedIds.addAll(ids(storage.append(AppendCriteria.none(), Optional.of(STREAM), events(batch))));
				committedCounts.add(committedIds.size());
			}
		}

		Path segment = segmentOf(original);
		int size = (int) Files.size(segment);
		assertTrue(size > 200, "the fixture should be big enough for this to mean something, was " + size);

		for ( int cut = 0; cut <= size; cut++ ) {
			Path copy = root.resolve("cut-" + cut);
			copyDirectory(original, copy);
			truncate(segmentOf(copy), cut);

			if ( cut < 32 ) {
				// inside the segment header, which is written once and fsynced long before any of this.
				// Damage there is corruption, not an interrupted append, so it must be refused loudly
				assertThrows(EventStorageException.class, () -> open(copy).close(),
						"a damaged segment header must refuse to open, not be treated as a ragged tail");
				deleteRecursively(copy);
				continue;
			}

			List<String> survived;
			try ( EventStorage storage = open(copy) ) {
				survived = ids(all(storage));
				assertTrue(committedCounts.contains(survived.size()),
						"truncating at byte %d left %d events, which is not a committed batch boundary (%s)"
								.formatted(cut, survived.size(), committedCounts));
				assertEquals(committedIds.subList(0, survived.size()), survived,
						"the surviving events must be a prefix of what was committed, truncated at byte " + cut);

				// and the store has to be usable afterwards, not merely readable
				StoredEvent appended = storage.append(AppendCriteria.none(), Optional.of(STREAM), events("next")).get(0);
				assertEquals(survived.size() + 1, appended.reference().position(),
						"appending after recovery must continue from the recovered end, truncated at byte " + cut);
			}

			// The load-bearing half, and the reason this reopens a second time. Recovery has to leave the
			// file ending on a batch boundary, not merely report the right events: records of the torn
			// batch left behind on disk are read as the beginning of whatever batch is written after them,
			// and the trailer that follows then describes a different set of records than the scan
			// accumulated -- so the *next* append is discarded too, and only on the restart after that.
			// Asserting from a single open cannot see this, because the first open gets the right answer.
			try ( EventStorage storage = open(copy) ) {
				List<String> afterSecondOpen = ids(all(storage));
				assertEquals(survived.size() + 1, afterSecondOpen.size(),
						("an event appended after recovering from a cut at byte %d did not survive the next restart: "
								+ "recovery left the log ending inside a discarded batch").formatted(cut));
				assertEquals(survived, afterSecondOpen.subList(0, survived.size()));
			}
			deleteRecursively(copy);
		}
	}

	@Test
	@DisplayName("flipping any single byte leaves a committed prefix or refuses to open")
	void corruptingAnyByteIsCaught ( ) throws IOException {
		Path original = root.resolve("corruption");

		List<Integer> committedCounts = new ArrayList<>(List.of(0));
		List<String> committedIds = new ArrayList<>();
		try ( EventStorage storage = open(original) ) {
			for ( String[] batch : new String[][] { { "a", "b" }, { "c" }, { "d", "e" } } ) {
				committedIds.addAll(ids(storage.append(AppendCriteria.none(), Optional.of(STREAM), events(batch))));
				committedCounts.add(committedIds.size());
			}
		}

		Path segment = segmentOf(original);
		int size = (int) Files.size(segment);

		for ( int at = 0; at < size; at++ ) {
			Path copy = root.resolve("flip-" + at);
			copyDirectory(original, copy);
			flipBit(segmentOf(copy), at);

			try ( EventStorage storage = open(copy) ) {
				List<String> survived = ids(all(storage));
				assertTrue(committedCounts.contains(survived.size()),
						"flipping byte %d left %d events, which is not a committed batch boundary".formatted(at, survived.size()));
				assertEquals(committedIds.subList(0, survived.size()), survived,
						"a flipped byte must never produce an event that was not committed, at byte " + at);
			} catch (EventStorageException e) {
				// equally acceptable: refusing to open is the honest answer to damage that is not a
				// half-finished append. What must never happen is opening and reporting altered events.
			}
			deleteRecursively(copy);
		}
	}

	@Test
	@DisplayName("a directory is owned by one storage, and the message says which mistake was made")
	void aDirectoryIsOwnedByOneStorage ( ) {
		Path directory = root.resolve("locking");

		try ( EventStorage first = open(directory) ) {
			EventStorageException failure = assertThrows(EventStorageException.class, () -> open(directory),
					"a second storage on the same directory must not open");
			assertTrue(failure.getMessage().contains("already open in this JVM"),
					"the message should name the mistake actually made, was: " + failure.getMessage());
		}

		// and the lock is released, so the directory can be opened again afterwards
		try ( EventStorage reopened = open(directory) ) {
			assertEquals(0, all(reopened).size());
		}
	}

	// ---------------------------------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------------------------------

	private static EventStorage open ( Path directory ) {
		return FileEventStorage.newBuilder().directory(directory).name("recovery-test").build();
	}

	private static List<StoredEvent> all ( EventStorage storage ) {
		return storage.query(EventQuery.matchAll(), Optional.empty(), null, Limit.none(), QueryDirection.FORWARD).toList();
	}

	private static org.sliceworkz.eventstore.events.EventReference lastReference ( EventStorage storage ) {
		List<StoredEvent> events = all(storage);
		return events.get(events.size() - 1).reference();
	}

	private static List<String> ids ( List<StoredEvent> events ) {
		return events.stream().map(e -> e.reference().id().value()).collect(Collectors.toList());
	}

	private static List<EventToStore> events ( String... names ) {
		List<EventToStore> events = new ArrayList<>();
		for ( String eventName : names ) {
			events.add(event(eventName, null));
		}
		return events;
	}

	private static EventToStore event ( String eventName, String idempotencyKey ) {
		return new EventToStore(STREAM, EventType.of("Recorded"), "{\"name\":\"%s\"}".formatted(eventName), null,
				Tags.of("name", eventName), idempotencyKey);
	}

	private static Path segmentOf ( Path directory ) throws IOException {
		try ( var paths = Files.list(directory.resolve("events")) ) {
			return paths.filter(p -> p.getFileName().toString().endsWith(".seg"))
					.max(Comparator.comparing(p -> p.getFileName().toString()))
					.orElseThrow(() -> new IllegalStateException("no segment in " + directory));
		}
	}

	private static void truncate ( Path file, int length ) throws IOException {
		try ( FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE) ) {
			channel.truncate(length);
		}
	}

	private static void flipBit ( Path file, int at ) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		bytes[at] ^= 0x40;
		Files.write(file, bytes);
	}

	private static void copyDirectory ( Path from, Path to ) throws IOException {
		try ( var paths = Files.walk(from) ) {
			for ( Path source : paths.toList() ) {
				Path target = to.resolve(from.relativize(source).toString());
				if ( Files.isDirectory(source) ) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(source, target);
				}
			}
		}
	}

	private static void deleteRecursively ( Path directory ) throws IOException {
		if ( !Files.exists(directory) ) {
			return;
		}
		try ( var paths = Files.walk(directory) ) {
			for ( Path path : paths.sorted(Comparator.reverseOrder()).toList() ) {
				Files.deleteIfExists(path);
			}
		}
	}

}
