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
package org.sliceworkz.eventstore.infra.file.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32C;

import org.sliceworkz.eventstore.infra.file.Durability;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The append-only log: an ordered set of segments, written one batch at a time.
 *
 * <h2>A batch is atomic because of its trailer, not because of a watermark</h2>
 * A batch is written as its event records followed by a commit trailer, and a batch with no valid
 * trailer never happened. The trailer carries a checksum over <em>every byte of every record in the
 * batch</em> plus the batch's own {@code tx}, {@code firstPosition} and count.
 * <p>
 * That last part is the whole reason this design does not keep a separate "committed up to here"
 * file. A trailer sitting physically later in the file does <em>not</em> prove the bytes before it
 * landed: after a power loss the tail of a file is not guaranteed to be a prefix of what was written,
 * because sectors can reach the device out of order. Per-record checksums catch a record that was
 * <em>garbled</em>; only a checksum spanning the whole batch catches a record that was never written
 * at all but whose slot happens to hold plausible old bytes — a recycled extent, a sparse hole, a
 * reused file. So the trailer gives all-or-nothing with one file, one flush, and no second write whose
 * ordering against the first would then have to be reasoned about.
 *
 * <h2>Recovery truncates to a batch boundary, and discards everything after it</h2>
 * The scan stops at the first frame that does not validate and cuts the file back to the start of the
 * batch that frame belonged to — not to the frame itself. Cutting at the frame would leave a
 * <em>partial batch</em> committed, which is exactly the atomicity the trailer exists to provide.
 * <p>
 * Everything after that point goes too, including later segments and any well-formed batch among them.
 * Under {@link Durability#SYNC} that is free, since a torn tail is necessarily the last thing written.
 * Under {@link Durability#OS} it is load-bearing: several batches can sit in the page cache at once and
 * reach the device out of order, so a good batch really can follow a torn one, and keeping it would
 * leave the log with a hole — which no reader of a positional log can represent.
 *
 * <h2>A batch never spans segments</h2>
 * {@link #appendBatch} rolls to a new segment before writing rather than during, so a batch and its
 * trailer are always in one file. Recovery therefore never has to carry a running checksum across a
 * file boundary, and a segment is always a whole number of batches.
 */
public final class EventLog implements AutoCloseable {

	private static final Logger LOGGER = LoggerFactory.getLogger(EventLog.class);

	private static final String SEGMENT_SUFFIX = ".seg";

	private final Path directory;
	private final Durability durability;
	private final long segmentSizeBytes;
	private final List<Segment> segments = new ArrayList<>();

	private long nextPosition = 1;
	private long lastTx;

	/**
	 * Where one record lives: which segment, and at what offset the frame begins.
	 * <p>
	 * The offset is an {@code int} because a segment is capped below 2 GiB, which is what makes the
	 * primary index four bytes an event rather than eight.
	 *
	 * @param segment the segment's ordinal
	 * @param offset the offset of the frame header within that segment
	 */
	public record Location ( int segment, int offset ) {

	}

	/** Told about each committed record as the log is replayed at open. */
	@FunctionalInterface
	public interface RecordVisitor {

		/**
		 * Accepts one committed record.
		 *
		 * @param body the record body, ready to decode
		 * @param location where it lives
		 */
		void visit ( ByteBuffer body, Location location );
	}

	private EventLog ( Path directory, Durability durability, long segmentSizeBytes ) {
		this.directory = directory;
		this.durability = durability;
		this.segmentSizeBytes = segmentSizeBytes;
	}

	/**
	 * Opens the log, replaying every committed record and repairing a torn tail.
	 *
	 * @param directory the directory the segments live in; created if absent
	 * @param durability how hard {@link #appendBatch} tries to reach the device
	 * @param segmentSizeBytes the size at which the log rolls to a new segment
	 * @param visitor told about each committed record, in log order
	 * @return the open log, positioned to append
	 * @throws EventStorageException if the directory or a segment header cannot be read
	 */
	public static EventLog open ( Path directory, Durability durability, long segmentSizeBytes, RecordVisitor visitor ) {
		EventLog log = new EventLog(directory, durability, segmentSizeBytes);
		try {
			Files.createDirectories(directory);
			for ( Path path : segmentPaths(directory) ) {
				log.segments.add(Segment.open(path, ordinalOf(path)));
			}
			log.replay(visitor);
			if ( log.segments.isEmpty() ) {
				log.segments.add(Segment.create(log.pathFor(0), 0, 1, System.currentTimeMillis()));
			}
			return log;
		} catch (IOException e) {
			log.closeQuietly();
			throw new EventStorageException("could not open the event log in " + directory, e);
		} catch (RuntimeException e) {
			log.closeQuietly();
			throw e;
		}
	}

	/**
	 * The position the next appended event will take.
	 *
	 * @return one past the highest committed position
	 */
	public long nextPosition ( ) {
		return nextPosition;
	}

	/**
	 * The transaction number of the last committed batch.
	 *
	 * @return that number, or zero if the log is empty
	 */
	public long lastTx ( ) {
		return lastTx;
	}

	/**
	 * Writes one batch of records and the trailer that commits them.
	 * <p>
	 * On any failure the segment is cut back to where the batch began, so a caller that sees this throw
	 * knows the log is exactly as it was. That is what lets the caller update its in-memory index only
	 * <em>after</em> this returns: an index that learned about records the log does not have would
	 * answer queries with events that will not survive a restart.
	 *
	 * @param bodies the encoded record bodies, in position order
	 * @param tx the transaction number shared by the whole batch
	 * @param firstPosition the position of the first record
	 * @return where each record landed, in the same order
	 */
	public List<Location> appendBatch ( List<byte[]> bodies, long tx, long firstPosition ) {
		if ( bodies.isEmpty() ) {
			return List.of();
		}

		Segment segment = segmentForBatch(bodies);
		int batchStart = segment.writeOffset();

		try {
			ByteBuffer batch = encodeBatch(bodies, tx, firstPosition);
			List<Location> locations = new ArrayList<>(bodies.size());
			int offset = batchStart;
			for ( byte[] body : bodies ) {
				locations.add(new Location(segment.ordinal(), offset));
				offset += BinaryFormat.FRAME_HEADER_BYTES + body.length;
			}

			segment.append(batch);
			if ( durability == Durability.SYNC ) {
				// with metadata: the file grew, so its length has to be durable too, or a crash can leave
				// the data written and the directory entry still claiming the old, shorter file
				segment.force(true);
			}

			nextPosition = firstPosition + bodies.size();
			lastTx = tx;
			return locations;
		} catch (RuntimeException e) {
			rollBack(segment, batchStart, e);
			throw e;
		}
	}

	/**
	 * Reads the body of a committed record.
	 *
	 * @param location where the record lives
	 * @return the body, ready to decode
	 * @throws EventStorageException if the frame does not validate, which for a committed record means
	 *         the bytes changed under us rather than that a write was interrupted
	 */
	public ByteBuffer readBody ( Location location ) {
		Segment segment = segmentByOrdinal(location.segment());
		ByteBuffer header = segment.read(location.offset(), BinaryFormat.FRAME_HEADER_BYTES);
		int magic = header.getInt();
		int bodyLength = header.getInt();
		int storedCrc = header.getInt();

		if ( magic != BinaryFormat.MAGIC_EVENT || bodyLength < 0 || bodyLength > BinaryFormat.MAX_FRAME_BODY_BYTES ) {
			throw new EventStorageException(("the frame at offset %d of segment %d is not the event record the index says "
					+ "it is; the log has been modified underneath this storage").formatted(location.offset(), location.segment()));
		}

		ByteBuffer body = segment.read(location.offset() + BinaryFormat.FRAME_HEADER_BYTES, bodyLength);
		if ( BinaryFormat.crc32c(body) != storedCrc ) {
			throw new EventStorageException(("the event record at offset %d of segment %d fails its checksum; it was intact "
					+ "when the log committed it, so its bytes have changed since").formatted(location.offset(), location.segment()));
		}
		return body;
	}

	@Override
	public void close ( ) {
		EventStorageException failure = null;
		for ( Segment segment : segments ) {
			try {
				segment.close();
			} catch (EventStorageException e) {
				if ( failure == null ) {
					failure = e;
				} else {
					failure.addSuppressed(e);
				}
			}
		}
		segments.clear();
		if ( failure != null ) {
			throw failure;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// writing
	// ---------------------------------------------------------------------------------------------

	private Segment segmentForBatch ( List<byte[]> bodies ) {
		Segment segment = segments.get(segments.size() - 1);
		long batchBytes = (long) BinaryFormat.FRAME_HEADER_BYTES + BinaryFormat.COMMIT_BODY_BYTES;
		for ( byte[] body : bodies ) {
			batchBytes += BinaryFormat.FRAME_HEADER_BYTES + body.length;
		}

		// roll only when the segment already holds something: a batch larger than the roll threshold has
		// to go somewhere, and splitting it across two files would break the one-batch-one-file rule that
		// keeps recovery's running checksum inside a single scan
		boolean wouldOverflow = segment.writeOffset() + batchBytes > segmentSizeBytes;
		boolean pastIntegerRange = segment.writeOffset() + batchBytes > Integer.MAX_VALUE;
		if ( ( wouldOverflow || pastIntegerRange ) && segment.writeOffset() > BinaryFormat.SEGMENT_HEADER_BYTES ) {
			int ordinal = segment.ordinal() + 1;
			Segment rolled = Segment.create(pathFor(ordinal), ordinal, nextPosition, System.currentTimeMillis());
			segments.add(rolled);
			return rolled;
		}
		return segment;
	}

	private ByteBuffer encodeBatch ( List<byte[]> bodies, long tx, long firstPosition ) {
		int size = BinaryFormat.FRAME_HEADER_BYTES + BinaryFormat.COMMIT_BODY_BYTES;
		for ( byte[] body : bodies ) {
			size += BinaryFormat.FRAME_HEADER_BYTES + body.length;
		}

		ByteBuffer batch = BinaryFormat.buffer(size);
		for ( byte[] body : bodies ) {
			batch.putInt(BinaryFormat.MAGIC_EVENT);
			batch.putInt(body.length);
			batch.putInt(BinaryFormat.crc32c(body, 0, body.length));
			batch.put(body);
		}

		int recordBytes = batch.position();
		int batchCrc = batchChecksum(batch.array(), 0, recordBytes, tx, firstPosition, bodies.size());

		ByteBuffer trailer = BinaryFormat.buffer(BinaryFormat.COMMIT_BODY_BYTES);
		trailer.putLong(tx);
		trailer.putLong(firstPosition);
		trailer.putInt(bodies.size());
		trailer.putInt(0);                                                      // reserved
		trailer.putInt(batchCrc);
		trailer.putInt(0);                                                      // reserved

		batch.putInt(BinaryFormat.MAGIC_COMMIT);
		batch.putInt(BinaryFormat.COMMIT_BODY_BYTES);
		batch.putInt(BinaryFormat.crc32c(trailer.array(), 0, BinaryFormat.COMMIT_BODY_BYTES));
		batch.put(trailer.array());

		batch.flip();
		return batch;
	}

	/**
	 * The checksum a trailer carries: every byte of every record frame in the batch, then the batch's
	 * own identity.
	 * <p>
	 * The trailer's frame header is deliberately outside this range. It holds the checksum of the
	 * trailer body, which holds this value — including it would make the two mutually dependent.
	 */
	private static int batchChecksum ( byte[] frames, int offset, int length, long tx, long firstPosition, int eventCount ) {
		CRC32C crc = new CRC32C();
		crc.update(frames, offset, length);
		ByteBuffer identity = BinaryFormat.buffer(Long.BYTES + Long.BYTES + Integer.BYTES);
		identity.putLong(tx);
		identity.putLong(firstPosition);
		identity.putInt(eventCount);
		crc.update(identity.array(), 0, identity.position());
		return (int) crc.getValue();
	}

	private void rollBack ( Segment segment, int batchStart, RuntimeException failure ) {
		try {
			if ( segment.fileSize() > batchStart ) {
				segment.truncate(batchStart);
			}
		} catch (RuntimeException e) {
			// the log is left with a partial batch on disk, which recovery will discard on the next open
			// because it has no valid trailer -- so this is untidy, not unsafe
			failure.addSuppressed(e);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// recovery
	// ---------------------------------------------------------------------------------------------

	private void replay ( RecordVisitor visitor ) {
		for ( int i = 0; i < segments.size(); i++ ) {
			Segment segment = segments.get(i);
			int cleanEnd = replaySegment(segment, visitor);
			if ( cleanEnd >= 0 ) {
				discardFrom(i, segment, cleanEnd);
				return;
			}
		}
	}

	/**
	 * Replays one segment.
	 *
	 * @return {@code -1} if the segment ended cleanly, or the offset the log must be cut back to
	 */
	private int replaySegment ( Segment segment, RecordVisitor visitor ) {
		int size = segment.fileSize();
		int offset = BinaryFormat.SEGMENT_HEADER_BYTES;
		int batchStart = offset;
		// the bodies are held until the trailer proves the batch, so a record is decoded once and only
		// after it is known to be committed; a batch is bounded by what one append() carried, so this
		// holds a caller-sized amount of memory rather than a store-sized one
		List<Pending> pending = new ArrayList<>();
		CRC32C running = new CRC32C();

		while ( true ) {
			if ( offset + BinaryFormat.FRAME_HEADER_BYTES > size ) {
				// not enough room left for even a frame header: either a clean end (nothing pending) or a
				// batch that was cut off mid-write
				return pending.isEmpty() && offset == batchStart ? -1 : batchStart;
			}

			ByteBuffer header = segment.read(offset, BinaryFormat.FRAME_HEADER_BYTES);
			int magic = header.getInt();
			int bodyLength = header.getInt();
			int storedCrc = header.getInt();

			if ( magic == BinaryFormat.MAGIC_NONE ) {
				return pending.isEmpty() ? -1 : batchStart;                     // unwritten space
			}
			if ( ( magic != BinaryFormat.MAGIC_EVENT && magic != BinaryFormat.MAGIC_COMMIT )
					|| bodyLength <= 0 || bodyLength > BinaryFormat.MAX_FRAME_BODY_BYTES
					|| offset + BinaryFormat.FRAME_HEADER_BYTES + bodyLength > size ) {
				return batchStart;
			}

			ByteBuffer body = segment.read(offset + BinaryFormat.FRAME_HEADER_BYTES, bodyLength);
			if ( BinaryFormat.crc32c(body) != storedCrc ) {
				return batchStart;
			}

			if ( magic == BinaryFormat.MAGIC_EVENT ) {
				running.update(header.array(), 0, BinaryFormat.FRAME_HEADER_BYTES);
				running.update(body.array(), 0, bodyLength);
				pending.add(new Pending(new Location(segment.ordinal(), offset), body));
				offset += BinaryFormat.FRAME_HEADER_BYTES + bodyLength;
				continue;
			}

			if ( bodyLength != BinaryFormat.COMMIT_BODY_BYTES ) {
				return batchStart;
			}
			long tx = body.getLong();
			long firstPosition = body.getLong();
			int eventCount = body.getInt();
			body.getInt();                                                      // reserved
			int batchCrc = body.getInt();

			int expected = finishBatchChecksum(running, tx, firstPosition, eventCount);

			if ( eventCount != pending.size() || firstPosition != nextPosition || tx <= lastTx || batchCrc != expected ) {
				return batchStart;
			}

			for ( Pending committed : pending ) {
				visitor.visit(committed.body(), committed.location());
			}

			nextPosition = firstPosition + eventCount;
			lastTx = tx;
			offset += BinaryFormat.FRAME_HEADER_BYTES + bodyLength;
			batchStart = offset;
			pending = new ArrayList<>();
			running = new CRC32C();
		}
	}

	private static int finishBatchChecksum ( CRC32C running, long tx, long firstPosition, int eventCount ) {
		ByteBuffer identity = BinaryFormat.buffer(Long.BYTES + Long.BYTES + Integer.BYTES);
		identity.putLong(tx);
		identity.putLong(firstPosition);
		identity.putInt(eventCount);
		running.update(identity.array(), 0, identity.position());
		return (int) running.getValue();
	}

	/** A record whose frame validated, waiting for the trailer that will commit its batch or discard it. */
	private record Pending ( Location location, ByteBuffer body ) {

	}

	private void discardFrom ( int segmentIndex, Segment segment, int cleanEnd ) {
		int discardedBytes = segment.fileSize() - cleanEnd;
		List<Segment> later = new ArrayList<>(segments.subList(segmentIndex + 1, segments.size()));

		segment.truncate(cleanEnd);
		for ( Segment dropped : later ) {
			dropped.close();
			try {
				Files.deleteIfExists(dropped.path());
			} catch (IOException e) {
				throw new EventStorageException("could not delete log segment " + dropped.path(), e);
			}
		}
		segments.subList(segmentIndex + 1, segments.size()).clear();

		LOGGER.warn(("Recovered the event log in {}: discarded {} bytes from segment {} at offset {}, and {} later "
				+ "segment(s). This is an append that did not finish -- most likely the process died mid-write -- so "
				+ "the events it carried were never committed and were never visible to any reader. The log now ends "
				+ "at position {}."),
				directory, discardedBytes, segment.ordinal(), cleanEnd, later.size(), nextPosition - 1);
	}

	// ---------------------------------------------------------------------------------------------
	// segments
	// ---------------------------------------------------------------------------------------------

	private Segment segmentByOrdinal ( int ordinal ) {
		for ( Segment segment : segments ) {
			if ( segment.ordinal() == ordinal ) {
				return segment;
			}
		}
		throw new EventStorageException("no log segment %d in %s".formatted(ordinal, directory));
	}

	private Path pathFor ( int ordinal ) {
		return directory.resolve("%010d%s".formatted(ordinal, SEGMENT_SUFFIX));
	}

	private static List<Path> segmentPaths ( Path directory ) throws IOException {
		try ( var paths = Files.list(directory) ) {
			return paths.filter(path -> path.getFileName().toString().endsWith(SEGMENT_SUFFIX))
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.toList();
		}
	}

	private static int ordinalOf ( Path path ) {
		String name = path.getFileName().toString();
		try {
			return Integer.parseInt(name.substring(0, name.length() - SEGMENT_SUFFIX.length()));
		} catch (NumberFormatException e) {
			throw new EventStorageException("log segment %s is not named as one".formatted(path), e);
		}
	}

	private void closeQuietly ( ) {
		try {
			close();
		} catch (RuntimeException e) {
			// we are already failing; a second failure closing what we could not open adds nothing
		}
	}

}
