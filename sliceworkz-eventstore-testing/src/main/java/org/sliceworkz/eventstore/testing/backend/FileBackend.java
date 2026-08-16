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
package org.sliceworkz.eventstore.testing.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.sliceworkz.eventstore.infra.file.FileEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.testing.EventStoreBackend;
import org.sliceworkz.eventstore.testing.StorageOptions;

/**
 * The single-process binary log storage, as a backend for the shared scenarios.
 * <p>
 * Every store gets a <strong>fresh temporary directory</strong>, and that is not merely for isolation:
 * the storage takes an exclusive lock on its directory, so two stores sharing one would fail to open.
 * {@link org.sliceworkz.eventstore.testing.tck.spi.EventImportTest} asks this backend for two
 * independent stores at once, which is exactly the case a shared directory would break — and it would
 * break as an opaque lock failure inside an import scenario rather than as anything that names the
 * cause.
 * <p>
 * Requires {@code sliceworkz-eventstore-infra-file}, an optional dependency of the testing module.
 */
public class FileBackend implements EventStoreBackend {

	private final Map<EventStorage, Path> directories = new ConcurrentHashMap<>();

	@Override
	public String name ( ) {
		return "file";
	}

	@Override
	public EventStorage createEventStorage ( StorageOptions options ) {
		try {
			Path directory = Files.createTempDirectory("eventstore-testing-file-" + options.discriminator() + "-");
			// a prefix has nothing to separate here -- see supports(TABLE_PREFIX) -- so it goes into the
			// name, which is enough to keep two stores requested by one scenario apart in messages
			FileEventStorage.Builder builder = FileEventStorage.newBuilder()
					.name(storeName(options))
					.directory(directory);
			if ( options.resultLimit() != null ) {
				builder.resultLimit(options.resultLimit());
			}
			EventStorage storage = builder.build();
			directories.put(storage, directory);
			return storage;
		} catch (IOException e) {
			throw new IllegalStateException("could not create a directory for the file backend", e);
		}
	}

	private String storeName ( StorageOptions options ) {
		return options.prefix() == null ? options.discriminator() : options.prefix() + options.discriminator();
	}

	@Override
	public void destroyEventStorage ( EventStorage storage ) {
		// close first: the storage holds the directory lock and open segment channels, and on Windows a
		// file cannot be deleted while a handle is open. The SPI contract already requires close() to
		// block until it has released everything, which is what makes deleting straight afterwards safe.
		storage.close();
		Path directory = directories.remove(storage);
		if ( directory != null ) {
			deleteRecursively(directory);
		}
	}

	@Override
	public void close ( ) {
		directories.values().forEach(FileBackend::deleteRecursively);
		directories.clear();
	}

	@Override
	public boolean supports ( Capability capability ) {
		// TABLE_PREFIX: the directory is the namespace, so a prefix would separate nothing that a second
		// directory does not separate better. RAW_STORAGE_ACCESS means a JDBC DataSource, which there is
		// no analogue of here -- and there is deliberately no out-of-band write path into the log at all.
		return switch (capability) {
			case IMPORT, RESULT_LIMIT, LEASE -> true;
			case TABLE_PREFIX, RAW_STORAGE_ACCESS -> false;
		};
	}

	private static void deleteRecursively ( Path directory ) {
		if ( !Files.exists(directory) ) {
			return;
		}
		try ( var paths = Files.walk(directory) ) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					// best effort: a leftover temp file must not fail a test
				}
			});
		} catch (IOException e) {
			// best effort, see above
		}
	}

}
