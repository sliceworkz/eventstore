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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

/**
 * The constants and primitives every part of the on-disk format shares.
 * <p>
 * Everything is little-endian, and every checksum is CRC32C — which the JDK has had since 9 and which
 * compiles to a hardware instruction on any CPU this library will realistically run on, so checksumming
 * is not a reason to skip checksumming.
 *
 * <h2>Frames</h2>
 * The log is a sequence of frames, each {@code magic | bodyLength | crc32c(body) | body}. A frame whose
 * magic reads as zero is not a frame at all: it is unwritten space, and it marks the end of what the
 * log has to say. That is why {@link #MAGIC_NONE} is reserved and why no real magic may be zero.
 *
 * <h2>Strings</h2>
 * A string is a length followed by that many UTF-8 bytes, with {@link #NULL_LENGTH} meaning null. Null
 * and empty are genuinely different here — a {@link org.sliceworkz.eventstore.events.Tag} may have a
 * null key, and reading one back as {@code ""} would quietly turn one tag into a different tag.
 */
public final class BinaryFormat {

	/** The byte order of every multi-byte field in every file this module writes. */
	public static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;

	/** The format this release writes. Bumped only for a change no existing reader could survive. */
	public static final int FORMAT_VERSION = 1;

	/** Unwritten space. A frame header reading this is the end of the log, not a corrupt frame. */
	public static final int MAGIC_NONE = 0x00000000;

	/** {@code "SWMF"} — the manifest. */
	public static final int MAGIC_MANIFEST = 0x464D5753;

	/** {@code "SWGS"} — a segment header. */
	public static final int MAGIC_SEGMENT = 0x53475753;

	/** {@code "SWER"} — one event record. */
	public static final int MAGIC_EVENT = 0x52455753;

	/** {@code "SWCT"} — the trailer that commits a batch of event records. */
	public static final int MAGIC_COMMIT = 0x54435753;

	/** {@code "SWBK"} — one bookmark record. */
	public static final int MAGIC_BOOKMARK = 0x4B425753;

	/** {@code "SWKY"} — one shredding key record. */
	public static final int MAGIC_KEY = 0x594B5753;

	/** magic + bodyLength + crc32c, in front of every frame body. */
	public static final int FRAME_HEADER_BYTES = 12;

	/** Written at offset 0 of every segment, before the first frame. */
	public static final int SEGMENT_HEADER_BYTES = 32;

	/** tx + firstPosition + eventCount + reserved + batchCrc + reserved. */
	public static final int COMMIT_BODY_BYTES = 32;

	/** The length that encodes a null string, distinct from a zero-length one. */
	public static final int NULL_LENGTH = -1;

	/**
	 * A body length no honest frame can have.
	 * <p>
	 * Recovery reads a length off the disk before it has any reason to trust it, so it needs a bound to
	 * reject before allocating. 64 MiB is far above any single event a caller could reasonably append
	 * and far below a length that would exhaust the heap on the way to being rejected.
	 */
	public static final int MAX_FRAME_BODY_BYTES = 64 * 1024 * 1024;

	private BinaryFormat ( ) {

	}

	/**
	 * Allocates a buffer in this format's byte order.
	 *
	 * @param capacity the capacity in bytes
	 * @return a heap buffer, little-endian
	 */
	public static ByteBuffer buffer ( int capacity ) {
		return ByteBuffer.allocate(capacity).order(ORDER);
	}

	/**
	 * Wraps existing bytes in this format's byte order.
	 *
	 * @param bytes the bytes to wrap
	 * @return a buffer over those bytes, little-endian
	 */
	public static ByteBuffer wrap ( byte[] bytes ) {
		return ByteBuffer.wrap(bytes).order(ORDER);
	}

	/**
	 * Computes the CRC32C of a buffer's remaining bytes, leaving its position where it found it.
	 *
	 * @param buffer the bytes to checksum
	 * @return the checksum, as the low 32 bits of the CRC
	 */
	public static int crc32c ( ByteBuffer buffer ) {
		CRC32C crc = new CRC32C();
		crc.update(buffer.duplicate());
		return (int) crc.getValue();
	}

	/**
	 * Computes the CRC32C of a byte range.
	 *
	 * @param bytes the array holding the range
	 * @param offset the first byte of the range
	 * @param length how many bytes to checksum
	 * @return the checksum, as the low 32 bits of the CRC
	 */
	public static int crc32c ( byte[] bytes, int offset, int length ) {
		CRC32C crc = new CRC32C();
		crc.update(bytes, offset, length);
		return (int) crc.getValue();
	}

	/**
	 * Writes a possibly-null string as a length and its UTF-8 bytes.
	 *
	 * @param buffer the buffer to write into
	 * @param value the string, or null
	 */
	public static void putString ( ByteBuffer buffer, String value ) {
		if ( value == null ) {
			buffer.putInt(NULL_LENGTH);
			return;
		}
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		buffer.putInt(bytes.length);
		buffer.put(bytes);
	}

	/**
	 * Reads a string written by {@link #putString}.
	 *
	 * @param buffer the buffer to read from
	 * @return the string, or null if a null was written
	 * @throws MalformedRecordException if the length is negative other than {@link #NULL_LENGTH}, or
	 *         runs past the end of the buffer
	 */
	public static String getString ( ByteBuffer buffer ) {
		int length = buffer.getInt();
		if ( length == NULL_LENGTH ) {
			return null;
		}
		if ( length < 0 || length > buffer.remaining() ) {
			throw new MalformedRecordException("string length %d is out of range with %d bytes left".formatted(length, buffer.remaining()));
		}
		byte[] bytes = new byte[length];
		buffer.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/**
	 * How many bytes {@link #putString} will write for this value.
	 *
	 * @param value the string, or null
	 * @return the encoded size in bytes
	 */
	public static int stringSize ( String value ) {
		return value == null ? Integer.BYTES : Integer.BYTES + value.getBytes(StandardCharsets.UTF_8).length;
	}

}
