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
package org.sliceworkz.eventstore.infra.inmem.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sliceworkz.eventstore.observability.HealthCheck;
import org.sliceworkz.eventstore.observability.HealthStatus;
import org.sliceworkz.eventstore.observability.StorageHealth;
import org.sliceworkz.eventstore.spi.EventStorage;

/**
 * What the file-backed storage reports beside {@link HealthCheck#OPEN}: whether the directories it writes
 * every event and bookmark to are still there to be written to.
 */
class InMemoryFsStorageHealthTest {

	@Test
	void writableDirectoriesAreUp ( @TempDir Path dir ) {
		try ( EventStorage storage = InMemoryFsEventStorage.newBuilder().directory(dir).name("fs-health").build() ) {
			StorageHealth health = storage.health();

			assertEquals(HealthStatus.UP, health.status(), health.toString());
			assertEquals(List.of(HealthCheck.OPEN, InMemoryFsEventStorage.HEALTH_DIRECTORIES), health.checks().stream().map(HealthCheck::name).toList());
			assertEquals(dir.resolve("events").toAbsolutePath().toString(), health.check(InMemoryFsEventStorage.HEALTH_DIRECTORIES).orElseThrow().details().get("events"));
		}
	}

	@Test
	void aDirectoryThatIsGoneIsDown ( @TempDir Path dir ) throws IOException {
		try ( EventStorage storage = InMemoryFsEventStorage.newBuilder().directory(dir).name("fs-health").build() ) {
			Files.delete(dir.resolve("bookmarks"));

			HealthCheck directories = storage.health().check(InMemoryFsEventStorage.HEALTH_DIRECTORIES).orElseThrow();

			assertEquals(HealthStatus.DOWN, directories.status());
			assertTrue(directories.message().contains("bookmarks"), directories.message());
		}
	}

	@Test
	void aClosedStorageDoesNotLookAtItsDirectories ( @TempDir Path dir ) {
		EventStorage storage = InMemoryFsEventStorage.newBuilder().directory(dir).name("fs-health").build();
		storage.close();

		StorageHealth health = storage.health();

		assertEquals(HealthStatus.DOWN, health.status());
		assertEquals(List.of(HealthCheck.OPEN), health.checks().stream().map(HealthCheck::name).toList());
	}

}
