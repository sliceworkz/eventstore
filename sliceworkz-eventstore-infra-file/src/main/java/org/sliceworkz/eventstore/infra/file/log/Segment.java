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
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.sliceworkz.eventstore.spi.EventStorageException;

/**
 * One segment file of the log: a header, then frames.
 *
 * <h2>Header, 32 bytes at offset zero</h2>
 * <pre>
 *   0  u32  magic "SWGS"
 *   4  u32  formatVersion
 *   8  u64  firstPosition   the position of the first event this segment may hold
 *  16  u64  createdAt, epoch millis
 *  24  u32  reserved
 *  28  u32  crc32c over bytes 0..27
 * </pre>
 * The format version lives here as well as in the manifest, so a segment stays self-describing if the
 * manifest is lost — which it is allowed to be, since the manifest is only ever a hint.
 *
 * <h2>A bad header is not a torn tail</h2>
 * The header is written once, when the segment is created, and never touched again. If it does not
 * validate, the damage is in a region that was fsynced long ago and has no business changing — so this
 * class refuses to open rather than treating it as the ragged end of a crash. Only the tail of the last
 * segment is ever legitimately incomplete, and that is the recovery scan's business, not this class's.
 *
 * <h2>Reads do not move a file pointer</h2>
 * Every read is positional, so concurrent readers need no lock and no channel of their own, and a read
 * cannot be perturbed by the writer appending at the same time.
 */
final class Segment implements AutoCloseable {

	private final int ordinal;
	private final long firstPosition;
	private final Path path;
	private final FileChannel channel;

	/** The offset at which the next frame will be written; equivalently, the end of valid data. */
	private int writeOffset;

	private Segment ( int ordinal, long firstPosition, Path path, FileChannel channel, int writeOffset ) {
		this.ordinal = ordinal;
		this.firstPosition = firstPosition;
		this.path = path;
		this.channel = channel;
		this.writeOffset = writeOffset;
	}

	/**
	 * Creates a new, empty segment and writes its header.
	 *
	 * @param path where the segment lives
	 * @param ordinal the segment's number in the log
	 * @param firstPosition the position of the first event it may hold
	 * @param createdAtEpochMillis a timestamp for the header, passed in rather than read from a clock
	 * @return the open segment, positioned to append after its header
	 */
	static Segment create ( Path path, int ordinal, long firstPosition, long createdAtEpochMillis ) {
		try {
			FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
					StandardOpenOption.WRITE);
			ByteBuffer header = BinaryFormat.buffer(BinaryFormat.SEGMENT_HEADER_BYTES);
			header.putInt(BinaryFormat.MAGIC_SEGMENT);
			header.putInt(BinaryFormat.FORMAT_VERSION);
			header.putLong(firstPosition);
			header.putLong(createdAtEpochMillis);
			header.putInt(0);                                                   // reserved
			header.putInt(BinaryFormat.crc32c(header.array(), 0, header.position()));
			header.flip();
			writeFully(channel, header, 0);
			channel.force(true);
			return new Segment(ordinal, firstPosition, path, channel, BinaryFormat.SEGMENT_HEADER_BYTES);
		} catch (IOException e) {
			throw new EventStorageException("could not create log segment " + path, e);
		}
	}

	/**
	 * Opens an existing segment and validates its header.
	 *
	 * @param path where the segment lives
	 * @param ordinal the segment's number in the log
	 * @return the open segment, with its write offset at the end of the file pending a recovery scan
	 * @throws EventStorageException if the header is absent, truncated or does not validate
	 */
	static Segment open ( Path path, int ordinal ) {
		FileChannel channel;
		long size;
		ByteBuffer header = BinaryFormat.buffer(BinaryFormat.SEGMENT_HEADER_BYTES);
		try {
			channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
			size = channel.size();
			if ( size >= BinaryFormat.SEGMENT_HEADER_BYTES ) {
				readFully(channel, header, 0, path);
			}
		} catch (IOException e) {
			throw new EventStorageException("could not open log segment " + path, e);
		}

		if ( size < BinaryFormat.SEGMENT_HEADER_BYTES ) {
			throw closing(channel, new EventStorageException("log segment %s is %d bytes, too short to hold its %d-byte header"
					.formatted(path, size, BinaryFormat.SEGMENT_HEADER_BYTES)));
		}

		header.flip();
		int magic = header.getInt();
		int version = header.getInt();
		long firstPosition = header.getLong();
		int storedCrc = header.getInt(BinaryFormat.SEGMENT_HEADER_BYTES - Integer.BYTES);
		int actualCrc = BinaryFormat.crc32c(header.array(), 0, BinaryFormat.SEGMENT_HEADER_BYTES - Integer.BYTES);

		if ( magic != BinaryFormat.MAGIC_SEGMENT || storedCrc != actualCrc ) {
			throw closing(channel, new EventStorageException(("log segment %s does not start with a valid segment header. "
					+ "This is not the ragged end of a crash -- a segment header is written once and never rewritten -- so "
					+ "the file is either corrupt or not a segment of this log.").formatted(path)));
		}
		if ( version != BinaryFormat.FORMAT_VERSION ) {
			throw closing(channel, new EventStorageException(("log segment %s was written in format version %d and this "
					+ "release reads version %d").formatted(path, version, BinaryFormat.FORMAT_VERSION)));
		}
		if ( size > Integer.MAX_VALUE ) {
			throw closing(channel, new EventStorageException("log segment %s is %d bytes, past the 2 GiB a segment may reach"
					.formatted(path, size)));
		}

		return new Segment(ordinal, firstPosition, path, channel, (int) size);
	}

	/**
	 * Releases a channel we are about to abandon, and hands back the failure that made us abandon it.
	 * <p>
	 * A failure to close is deliberately swallowed: it would replace a message that says exactly what is
	 * wrong with the segment by one that says a file handle could not be released, sending whoever reads
	 * the log after the wrong thing entirely.
	 */
	private static EventStorageException closing ( FileChannel channel, EventStorageException failure ) {
		try {
			channel.close();
		} catch (IOException e) {
			failure.addSuppressed(e);
		}
		return failure;
	}

	int ordinal ( ) {
		return ordinal;
	}

	long firstPosition ( ) {
		return firstPosition;
	}

	Path path ( ) {
		return path;
	}

	/** @return the offset just past the last valid byte, which is where the next frame goes */
	int writeOffset ( ) {
		return writeOffset;
	}

	/**
	 * Appends bytes at the current write offset.
	 *
	 * @param bytes the bytes to append, positioned and limited to what should be written
	 * @return the offset the bytes were written at
	 */
	int append ( ByteBuffer bytes ) {
		int offset = writeOffset;
		int length = bytes.remaining();
		try {
			writeFully(channel, bytes, offset);
		} catch (IOException e) {
			// leave writeOffset where it was: the caller will truncate back to the batch start, and the
			// index must never learn about a record whose bytes may not be there
			throw new EventStorageException("could not append to log segment " + path, e);
		}
		writeOffset = offset + length;
		return offset;
	}

	/**
	 * Reads a range of this segment.
	 *
	 * @param offset the first byte to read
	 * @param length how many bytes
	 * @return a buffer over exactly those bytes, flipped and ready to read
	 */
	ByteBuffer read ( int offset, int length ) {
		ByteBuffer buffer = BinaryFormat.buffer(length);
		try {
			readFully(channel, buffer, offset, path);
		} catch (IOException e) {
			throw new EventStorageException("could not read %d bytes at offset %d of log segment %s".formatted(length, offset, path), e);
		}
		buffer.flip();
		return buffer;
	}

	/** @return how many bytes the file currently holds, valid or not */
	int fileSize ( ) {
		try {
			return (int) channel.size();
		} catch (IOException e) {
			throw new EventStorageException("could not size log segment " + path, e);
		}
	}

	/**
	 * Cuts the file back to a length, discarding everything after it.
	 *
	 * @param offset the new length
	 */
	void truncate ( int offset ) {
		try {
			channel.truncate(offset);
			channel.force(true);
			writeOffset = offset;
		} catch (IOException e) {
			throw new EventStorageException("could not truncate log segment %s to %d bytes".formatted(path, offset), e);
		}
	}

	/**
	 * Flushes this segment's data to the device.
	 *
	 * @param withMetadata whether the file's length must be flushed too, which it must whenever the
	 *        file has grown
	 */
	void force ( boolean withMetadata ) {
		try {
			channel.force(withMetadata);
		} catch (IOException e) {
			throw new EventStorageException("could not flush log segment " + path, e);
		}
	}

	@Override
	public void close ( ) {
		try {
			channel.close();
		} catch (IOException e) {
			throw new EventStorageException("could not close log segment " + path, e);
		}
	}

	private static void writeFully ( FileChannel channel, ByteBuffer buffer, long position ) throws IOException {
		long at = position;
		while ( buffer.hasRemaining() ) {
			int written = channel.write(buffer, at);
			if ( written <= 0 ) {
				throw new IOException("wrote %d bytes at %d, making no progress".formatted(written, at));
			}
			at += written;
		}
	}

	private static void readFully ( FileChannel channel, ByteBuffer buffer, long position, Path path ) throws IOException {
		long at = position;
		while ( buffer.hasRemaining() ) {
			int read = channel.read(buffer, at);
			if ( read < 0 ) {
				throw new IOException("reached the end of %s at %d with %d bytes still wanted".formatted(path, at, buffer.remaining()));
			}
			at += read;
		}
	}

}
