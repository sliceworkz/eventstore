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
package org.sliceworkz.eventstore.infra.file.bookmark;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.file.Durability;
import org.sliceworkz.eventstore.infra.file.log.BinaryFormat;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where readers' cursors live: an append-only log of placements and removals, replayed at open.
 *
 * <h2>Why append-and-compact rather than a file per reader</h2>
 * A bookmark is written far more often than it is read — {@code Projector} places one after
 * <em>every</em> batch, deliberately, so that a crash costs a re-projection of one batch rather than of
 * a whole catch-up run. That makes the write path the one that matters, and appending a record is the
 * cheapest durable write there is.
 * <p>
 * The cost is that the file grows without bound, which for a projector catching up over a large store
 * is not a theoretical concern: a million events at the default batch size is two thousand records. So
 * the log is rewritten, atomically, once it holds enough dead records to be worth the rewrite — and on
 * a clean close, so that a store that shuts down tidily reopens with a file the size of its live set.
 *
 * <h2>Frames, magic {@code "SWBK"}</h2>
 * <pre>
 *  u8   kind          0 placed, 1 removed
 *  str  reader
 *  -- kind 0 only:
 *  str  eventId
 *  u64  position
 *  u64  tx
 *  i32  index
 *  i64  updatedAt, seconds from the epoch
 *  i32  updatedAt, nanosecond of second
 *  u16  tagCount
 *  tag[tagCount]      each a nullable key and a nullable value
 * </pre>
 * A record that does not validate ends the replay, exactly as in the event log: the tail of a file
 * after a crash is the one part of it allowed to be ragged.
 */
public final class BookmarkLog implements AutoCloseable {

	private static final Logger LOGGER = LoggerFactory.getLogger(BookmarkLog.class);

	private static final byte KIND_PLACED = 0;
	private static final byte KIND_REMOVED = 1;

	/** Rewrite once the log holds this many records and at least four times its live set. */
	private static final int COMPACTION_FLOOR = 1000;
	private static final int COMPACTION_RATIO = 4;

	private final Path path;
	private final Durability durability;
	private final Map<String, Bookmark> live = new LinkedHashMap<>();

	private int recordCount;

	private BookmarkLog ( Path path, Durability durability ) {
		this.path = path;
		this.durability = durability;
	}

	/**
	 * Opens the log, replaying it into memory.
	 *
	 * @param path the log file, created if absent
	 * @param durability whether each placement is flushed before it is reported as done
	 * @return the open log
	 */
	public static BookmarkLog open ( Path path, Durability durability ) {
		BookmarkLog log = new BookmarkLog(path, durability);
		log.replay();
		return log;
	}

	/**
	 * The bookmark a reader last placed.
	 *
	 * @param reader the reader's name
	 * @return its bookmark, or empty if it never placed one
	 */
	public Optional<Bookmark> get ( String reader ) {
		return Optional.ofNullable(live.get(reader));
	}

	/**
	 * Every bookmark, as an independent snapshot.
	 *
	 * @return the bookmarks; later changes do not show up in the returned list
	 */
	public List<Bookmark> all ( ) {
		return List.copyOf(live.values());
	}

	/**
	 * Records a reader's cursor.
	 *
	 * @param bookmark the bookmark to place
	 */
	public void place ( Bookmark bookmark ) {
		append(encodePlaced(bookmark));
		live.put(bookmark.reader(), bookmark);
		compactIfWorthwhile();
	}

	/**
	 * Forgets a reader's cursor.
	 *
	 * @param reader the reader's name
	 */
	public void remove ( String reader ) {
		append(encodeRemoved(reader));
		live.remove(reader);
		compactIfWorthwhile();
	}

	/** Rewrites the log down to its live set, so a tidy shutdown reopens onto a tidy file. */
	@Override
	public void close ( ) {
		if ( recordCount > live.size() ) {
			compact();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// writing
	// ---------------------------------------------------------------------------------------------

	private void append ( byte[] body ) {
		ByteBuffer frame = BinaryFormat.buffer(BinaryFormat.FRAME_HEADER_BYTES + body.length);
		frame.putInt(BinaryFormat.MAGIC_BOOKMARK);
		frame.putInt(body.length);
		frame.putInt(BinaryFormat.crc32c(body, 0, body.length));
		frame.put(body);

		try {
			List<StandardOpenOption> options = new ArrayList<>(
					List.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND));
			if ( durability == Durability.SYNC ) {
				options.add(StandardOpenOption.SYNC);
			}
			try ( var channel = java.nio.channels.FileChannel.open(path, Set.copyOf(options)) ) {
				frame.flip();
				while ( frame.hasRemaining() ) {
					channel.write(frame);
				}
			}
		} catch (IOException e) {
			throw new EventStorageException("could not write to the bookmark log " + path, e);
		}
		recordCount++;
	}

	private void compactIfWorthwhile ( ) {
		if ( recordCount >= COMPACTION_FLOOR && recordCount >= COMPACTION_RATIO * Math.max(1, live.size()) ) {
			compact();
		}
	}

	private void compact ( ) {
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			ByteBuffer rewritten = BinaryFormat.buffer(rewrittenSize());
			for ( Bookmark bookmark : live.values() ) {
				byte[] body = encodePlaced(bookmark);
				rewritten.putInt(BinaryFormat.MAGIC_BOOKMARK);
				rewritten.putInt(body.length);
				rewritten.putInt(BinaryFormat.crc32c(body, 0, body.length));
				rewritten.put(body);
			}
			Files.write(temporary, java.util.Arrays.copyOf(rewritten.array(), rewritten.position()),
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
					StandardOpenOption.SYNC);
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			recordCount = live.size();
		} catch (IOException e) {
			throw new EventStorageException("could not compact the bookmark log " + path, e);
		}
	}

	private int rewrittenSize ( ) {
		int size = 0;
		for ( Bookmark bookmark : live.values() ) {
			size += BinaryFormat.FRAME_HEADER_BYTES + encodePlaced(bookmark).length;
		}
		return size;
	}

	private static byte[] encodePlaced ( Bookmark bookmark ) {
		EventReference reference = bookmark.reference();
		Set<Tag> tags = bookmark.tags() == null ? Set.of() : bookmark.tags().tags();

		int size = Byte.BYTES + BinaryFormat.stringSize(bookmark.reader())
				+ BinaryFormat.stringSize(reference.id().value())
				+ Long.BYTES + Long.BYTES + Integer.BYTES
				+ Long.BYTES + Integer.BYTES + Short.BYTES;
		for ( Tag tag : tags ) {
			size += BinaryFormat.stringSize(tag.key()) + BinaryFormat.stringSize(tag.value());
		}

		ByteBuffer body = BinaryFormat.buffer(size);
		body.put(KIND_PLACED);
		BinaryFormat.putString(body, bookmark.reader());
		BinaryFormat.putString(body, reference.id().value());
		body.putLong(reference.position());
		body.putLong(reference.tx());
		body.putInt(reference.index());
		body.putLong(bookmark.updatedAt().getEpochSecond());
		body.putInt(bookmark.updatedAt().getNano());
		body.putShort((short) tags.size());
		for ( Tag tag : tags ) {
			BinaryFormat.putString(body, tag.key());
			BinaryFormat.putString(body, tag.value());
		}
		return body.array();
	}

	private static byte[] encodeRemoved ( String reader ) {
		ByteBuffer body = BinaryFormat.buffer(Byte.BYTES + BinaryFormat.stringSize(reader));
		body.put(KIND_REMOVED);
		BinaryFormat.putString(body, reader);
		return body.array();
	}

	// ---------------------------------------------------------------------------------------------
	// replay
	// ---------------------------------------------------------------------------------------------

	private void replay ( ) {
		if ( !Files.exists(path) ) {
			return;
		}

		byte[] bytes;
		try {
			bytes = Files.readAllBytes(path);
		} catch (IOException e) {
			throw new EventStorageException("could not read the bookmark log " + path, e);
		}

		ByteBuffer buffer = BinaryFormat.wrap(bytes);
		int offset = 0;
		while ( offset + BinaryFormat.FRAME_HEADER_BYTES <= bytes.length ) {
			buffer.position(offset);
			int magic = buffer.getInt();
			int bodyLength = buffer.getInt();
			int storedCrc = buffer.getInt();

			if ( magic != BinaryFormat.MAGIC_BOOKMARK || bodyLength <= 0
					|| bodyLength > BinaryFormat.MAX_FRAME_BODY_BYTES
					|| offset + BinaryFormat.FRAME_HEADER_BYTES + bodyLength > bytes.length
					|| BinaryFormat.crc32c(bytes, offset + BinaryFormat.FRAME_HEADER_BYTES, bodyLength) != storedCrc ) {
				truncateTo(offset, bytes.length);
				return;
			}

			try {
				applyRecord(buffer);
			} catch (RuntimeException e) {
				LOGGER.warn("The bookmark record at offset {} of {} did not decode; the log is truncated there.",
						offset, path, e);
				truncateTo(offset, bytes.length);
				return;
			}

			offset += BinaryFormat.FRAME_HEADER_BYTES + bodyLength;
			recordCount++;
		}

		if ( offset < bytes.length ) {
			truncateTo(offset, bytes.length);
		}
	}

	private void applyRecord ( ByteBuffer body ) {
		byte kind = body.get();
		String reader = BinaryFormat.getString(body);

		if ( kind == KIND_REMOVED ) {
			live.remove(reader);
			return;
		}
		if ( kind != KIND_PLACED ) {
			throw new IllegalStateException("unknown bookmark record kind " + kind);
		}

		String eventId = BinaryFormat.getString(body);
		long position = body.getLong();
		long tx = body.getLong();
		int index = body.getInt();
		long epochSecond = body.getLong();
		int nano = body.getInt();
		int tagCount = Short.toUnsignedInt(body.getShort());

		Set<Tag> tags = new LinkedHashSet<>();
		for ( int i = 0; i < tagCount; i++ ) {
			String key = BinaryFormat.getString(body);
			String value = BinaryFormat.getString(body);
			tags.add(new Tag(key, value));
		}

		live.put(reader, new Bookmark(reader, EventReference.of(new EventId(eventId), position, tx, index),
				new Tags(tags), Instant.ofEpochSecond(epochSecond, nano)));
	}

	private void truncateTo ( int offset, int had ) {
		if ( offset >= had ) {
			return;
		}
		LOGGER.warn(("The bookmark log {} ends with {} bytes that do not form a complete record; discarding them. A "
				+ "bookmark that was being written when the process died is simply not placed, which costs a reader one "
				+ "re-read of the batch it had already handled."), path, had - offset);
		try ( var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE) ) {
			channel.truncate(offset);
			channel.force(true);
		} catch (IOException e) {
			throw new EventStorageException("could not truncate the bookmark log " + path, e);
		}
	}

}
