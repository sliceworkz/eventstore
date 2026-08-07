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

import org.sliceworkz.eventstore.infra.inmem.fs.InMemoryFsEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.testing.EventStoreBackend;
import org.sliceworkz.eventstore.testing.StorageOptions;

/**
 * The file-backed in-memory storage, as a backend for the shared scenarios.
 * <p>
 * Isolation comes from a fresh temporary directory per store; the directories are deleted when the
 * run is over. Requires {@code sliceworkz-eventstore-infra-inmem-fs}, an optional dependency of the
 * testing module.
 */
public class InMemoryFsBackend implements EventStoreBackend {

	private final Map<EventStorage, Path> directories = new ConcurrentHashMap<>();

	@Override
	public String name ( ) {
		return "inmem-fs";
	}

	@Override
	public EventStorage createEventStorage ( StorageOptions options ) {
		try {
			Path directory = Files.createTempDirectory("eventstore-testing-" + options.discriminator() + "-");
			InMemoryFsEventStorage.Builder builder = InMemoryFsEventStorage.newBuilder()
					.name(options.discriminator())
					.directory(directory);
			if ( options.resultLimit() != null ) {
				builder.resultLimit(options.resultLimit());
			}
			EventStorage storage = builder.build();
			directories.put(storage, directory);
			return storage;
		} catch (IOException e) {
			throw new IllegalStateException("could not create a directory for the inmem-fs backend", e);
		}
	}

	@Override
	public void destroyEventStorage ( EventStorage storage ) {
		Path directory = directories.remove(storage);
		if ( directory != null ) {
			deleteRecursively(directory);
		}
	}

	@Override
	public void close ( ) {
		directories.values().forEach(InMemoryFsBackend::deleteRecursively);
		directories.clear();
	}

	@Override
	public boolean supports ( Capability capability ) {
		// one store per directory, so a table prefix has nothing to separate
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
