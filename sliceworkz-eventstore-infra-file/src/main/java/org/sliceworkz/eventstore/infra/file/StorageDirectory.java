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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import org.sliceworkz.eventstore.infra.file.log.BinaryFormat;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The directory a storage owns: its layout, its exclusive lock, and its manifest.
 *
 * <h2>The lock is the single-writer guarantee, made real</h2>
 * Everything this storage promises rests on there being exactly one writer. "Don't open it twice" is not
 * a guarantee, it is a hope, so the directory is locked exclusively for the storage's lifetime and a
 * second attempt fails to open rather than quietly interleaving two writers into one log.
 * <p>
 * Two cases have to be told apart, because they are different mistakes with different fixes: a second
 * storage <em>in this JVM</em> (usually a store that was built twice, or one that was never closed) and
 * a second <em>process</em> (usually two instances of an application that only ever expected to be one).
 * The JVM reports these differently — an exception for the first, a null for the second — so both are
 * handled and both are named in the message.
 * <p>
 * On a network filesystem this is advisory at best. NFS locking has enough implementations and enough
 * failure modes that no promise made here would be worth anything; keep the directory on local storage.
 *
 * <h2>The manifest is a hint, and is allowed to be wrong</h2>
 * The log is the truth. The manifest exists to gate the format version, to say whether the last shutdown
 * was clean so that an unclean one can be logged rather than passed over in silence, and to carry hints
 * that let a later release skip work at startup. It deliberately does <em>not</em> record how far the log
 * is committed — that lives in each batch's own trailer, and a second place to look would eventually be a
 * second answer. A manifest that fails its checksum is rebuilt with a warning rather than refused.
 */
final class StorageDirectory implements AutoCloseable {

	private static final Logger LOGGER = LoggerFactory.getLogger(StorageDirectory.class);

	private static final String LOCK_FILE = "LOCK";
	private static final String MANIFEST_FILE = "MANIFEST";
	private static final String EVENTS_DIRECTORY = "events";
	private static final String BOOKMARKS_FILE = "bookmarks.log";

	private static final int MANIFEST_BYTES = 64;
	private static final int MANIFEST_CRC_OFFSET = 48;
	private static final byte FLAG_CLEANLY_CLOSED = 0x01;

	private final Path directory;
	private final FileChannel lockChannel;
	private final FileLock lock;
	private final boolean cleanlyClosed;

	private StorageDirectory ( Path directory, FileChannel lockChannel, FileLock lock, boolean cleanlyClosed ) {
		this.directory = directory;
		this.lockChannel = lockChannel;
		this.lock = lock;
		this.cleanlyClosed = cleanlyClosed;
	}

	/**
	 * Creates the layout if needed, takes the exclusive lock, and validates the manifest.
	 *
	 * @param directory the directory to own
	 * @param segmentSizeBytes recorded in the manifest as a hint
	 * @return the owned directory
	 * @throws EventStorageException if the directory cannot be created, is already owned, or holds a log
	 *         written in a format this release cannot read
	 */
	static StorageDirectory open ( Path directory, long segmentSizeBytes ) {
		FileChannel lockChannel = null;
		try {
			Files.createDirectories(directory);
			Files.createDirectories(directory.resolve(EVENTS_DIRECTORY));

			lockChannel = FileChannel.open(directory.resolve(LOCK_FILE), StandardOpenOption.CREATE,
					StandardOpenOption.WRITE);
			FileLock lock = acquire(lockChannel, directory);

			lockChannel.truncate(0);
			lockChannel.write(ByteBuffer.wrap(("%s %s%n".formatted(ProcessHandle.current().pid(), java.time.Instant.now()))
					.getBytes(StandardCharsets.UTF_8)), 0);

			boolean cleanlyClosed = readManifest(directory, segmentSizeBytes);
			writeManifest(directory, segmentSizeBytes, false);

			return new StorageDirectory(directory, lockChannel, lock, cleanlyClosed);
		} catch (IOException e) {
			closeQuietly(lockChannel);
			throw new EventStorageException("could not open the event storage directory " + directory, e);
		} catch (RuntimeException e) {
			closeQuietly(lockChannel);
			throw e;
		}
	}

	private static FileLock acquire ( FileChannel lockChannel, Path directory ) throws IOException {
		FileLock lock;
		try {
			lock = lockChannel.tryLock();
		} catch (OverlappingFileLockException e) {
			// the JVM tracks its own locks separately from the operating system's, and reports a second
			// attempt from the same JVM this way rather than by returning null
			throw new EventStorageException(("the event storage directory %s is already open in this JVM. A directory is "
					+ "owned by exactly one storage: build one and share it, and close it when you are done -- a storage "
					+ "that was never closed still holds the lock.").formatted(directory), e);
		}
		if ( lock == null ) {
			throw new EventStorageException(("the event storage directory %s is locked by another process. This storage is "
					+ "single-writer by design: exactly one process may own a directory, and there is no mode that relaxes "
					+ "that. If two instances of an application need one event log, use a backend that has a server behind "
					+ "it.").formatted(directory));
		}
		return lock;
	}

	/** @return where the log segments live */
	Path eventsDirectory ( ) {
		return directory.resolve(EVENTS_DIRECTORY);
	}

	/** @return where the bookmark log lives */
	Path bookmarksPath ( ) {
		return directory.resolve(BOOKMARKS_FILE);
	}

	/** @return the directory itself */
	Path path ( ) {
		return directory;
	}

	/** @return whether the previous owner of this directory closed it cleanly */
	boolean wasCleanlyClosed ( ) {
		return cleanlyClosed;
	}

	/**
	 * Records that this storage shut down cleanly.
	 *
	 * @param segmentSizeBytes the roll threshold in force
	 * @param committedPosition the highest position the log holds
	 * @param lastTx the last transaction number used
	 */
	void markCleanlyClosed ( long segmentSizeBytes, long committedPosition, long lastTx ) {
		try {
			writeManifest(directory, segmentSizeBytes, true, committedPosition, lastTx);
		} catch (IOException e) {
			// the manifest is a hint: failing to record a clean shutdown costs a WARN on the next open and
			// nothing else, so it must not turn a successful close into a failure
			LOGGER.warn("Could not record a clean shutdown in the manifest of {}; the next open will report an "
					+ "unclean shutdown and recover from the log, which is correct either way.", directory, e);
		}
	}

	@Override
	public void close ( ) {
		try {
			if ( lock.isValid() ) {
				lock.release();
			}
		} catch (IOException e) {
			LOGGER.warn("Could not release the lock on {}", directory, e);
		}
		closeQuietly(lockChannel);
	}

	// ---------------------------------------------------------------------------------------------
	// manifest
	// ---------------------------------------------------------------------------------------------

	private static boolean readManifest ( Path directory, long segmentSizeBytes ) throws IOException {
		Path path = directory.resolve(MANIFEST_FILE);
		if ( !Files.exists(path) ) {
			return true;                                                        // a directory with no history closed as cleanly as one can
		}

		byte[] bytes = Files.readAllBytes(path);
		if ( bytes.length != MANIFEST_BYTES ) {
			LOGGER.warn("The manifest of {} is {} bytes rather than {}; rebuilding it. The log itself is unaffected -- "
					+ "the manifest holds hints, never the record of what is committed.", directory, bytes.length, MANIFEST_BYTES);
			return false;
		}

		ByteBuffer manifest = BinaryFormat.wrap(bytes);
		int magic = manifest.getInt();
		int version = manifest.getInt();
		byte flags = manifest.get();
		int storedCrc = manifest.getInt(MANIFEST_CRC_OFFSET);
		int actualCrc = BinaryFormat.crc32c(bytes, 0, MANIFEST_CRC_OFFSET);

		if ( magic != BinaryFormat.MAGIC_MANIFEST || storedCrc != actualCrc ) {
			LOGGER.warn("The manifest of {} does not validate; rebuilding it. The log itself is unaffected.", directory);
			return false;
		}
		if ( version != BinaryFormat.FORMAT_VERSION ) {
			throw new EventStorageException(("the event log in %s was written in format version %d and this release reads "
					+ "version %d").formatted(directory, version, BinaryFormat.FORMAT_VERSION));
		}

		return ( flags & FLAG_CLEANLY_CLOSED ) != 0;
	}

	private static void writeManifest ( Path directory, long segmentSizeBytes, boolean cleanlyClosed ) throws IOException {
		writeManifest(directory, segmentSizeBytes, cleanlyClosed, 0, 0);
	}

	private static void writeManifest ( Path directory, long segmentSizeBytes, boolean cleanlyClosed,
			long committedPosition, long lastTx ) throws IOException {
		ByteBuffer manifest = BinaryFormat.buffer(MANIFEST_BYTES);
		manifest.putInt(BinaryFormat.MAGIC_MANIFEST);
		manifest.putInt(BinaryFormat.FORMAT_VERSION);
		manifest.put(cleanlyClosed ? FLAG_CLEANLY_CLOSED : 0);
		manifest.position(16);
		manifest.putLong(System.currentTimeMillis());
		manifest.putLong(segmentSizeBytes);
		manifest.putLong(committedPosition);
		manifest.putLong(lastTx);
		manifest.putInt(BinaryFormat.crc32c(manifest.array(), 0, MANIFEST_CRC_OFFSET));

		// written beside the real file and moved onto it, so a crash mid-write leaves the previous
		// manifest rather than a half-written one
		Path temporary = directory.resolve(MANIFEST_FILE + ".tmp");
		Files.write(temporary, manifest.array(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE, StandardOpenOption.SYNC);
		Files.move(temporary, directory.resolve(MANIFEST_FILE), StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
	}

	private static void closeQuietly ( FileChannel channel ) {
		if ( channel == null ) {
			return;
		}
		try {
			channel.close();
		} catch (IOException e) {
			// already failing, or already done: a second failure here would only obscure the first
		}
	}

}
