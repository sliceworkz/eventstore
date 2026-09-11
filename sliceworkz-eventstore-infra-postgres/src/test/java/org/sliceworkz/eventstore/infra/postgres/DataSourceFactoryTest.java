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
package org.sliceworkz.eventstore.infra.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sliceworkz.eventstore.spi.EventStorageException;

/**
 * Pins down where {@link DataSourceFactory} looks for {@code db.properties}, and in which order: an
 * explicitly configured path, the working directory, the classpath — and never a parent directory.
 * <p>
 * Every branch is driven through the seam that takes the environment as arguments, so no test sets a
 * real system property, needs a real environment variable, or writes into the real working directory.
 */
class DataSourceFactoryTest {

	private static final Function<String, String> NOTHING = key -> null;

	@TempDir
	Path dir;

	private Path write ( Path file, String origin ) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, "db.pooled.url=jdbc:postgresql://" + origin + "/db\n");
		return file;
	}

	private static String originOf ( Properties properties ) {
		return properties.getProperty("db.pooled.url").replace("jdbc:postgresql://", "").replace("/db", "");
	}

	private static ClassLoader classpathHolding ( Path root ) throws IOException {
		return new URLClassLoader(new URL[] { root.toUri().toURL() }, null);
	}

	@Test
	void testTheSystemPropertyWinsOverEverythingElse ( ) throws IOException {
		Path work = dir.resolve("work");
		Path classpath = dir.resolve("classpath");
		Path configured = write(dir.resolve("elsewhere/db.properties"), "system-property");
		Path viaEnvironment = write(dir.resolve("env/db.properties"), "environment");
		write(work.resolve("db.properties"), "working-directory");
		write(classpath.resolve("db.properties"), "classpath");

		Properties loaded = DataSourceFactory.loadProperties(
			Map.of(DataSourceFactory.SYSTEM_PROPERTY, configured.toString())::get,
			Map.of(DataSourceFactory.ENVIRONMENT_VARIABLE, viaEnvironment.toString())::get,
			work, classpathHolding(classpath));

		assertEquals("system-property", originOf(loaded));
	}

	@Test
	void testTheEnvironmentVariableIsNextWhenNoSystemPropertyIsSet ( ) throws IOException {
		Path work = dir.resolve("work");
		Path classpath = dir.resolve("classpath");
		Path viaEnvironment = write(dir.resolve("env/db.properties"), "environment");
		write(work.resolve("db.properties"), "working-directory");
		write(classpath.resolve("db.properties"), "classpath");

		Properties loaded = DataSourceFactory.loadProperties(NOTHING,
			Map.of(DataSourceFactory.ENVIRONMENT_VARIABLE, viaEnvironment.toString())::get,
			work, classpathHolding(classpath));

		assertEquals("environment", originOf(loaded));
	}

	@Test
	void testTheWorkingDirectoryWinsOverTheClasspath ( ) throws IOException {
		Path work = dir.resolve("work");
		Path classpath = dir.resolve("classpath");
		write(work.resolve("db.properties"), "working-directory");
		write(classpath.resolve("db.properties"), "classpath");

		Properties loaded = DataSourceFactory.loadProperties(NOTHING, NOTHING, work, classpathHolding(classpath));

		assertEquals("working-directory", originOf(loaded));
	}

	@Test
	void testTheClasspathIsTheLastResort ( ) throws IOException {
		Path work = dir.resolve("work");
		Path classpath = dir.resolve("classpath");
		Files.createDirectories(work);
		write(classpath.resolve("db.properties"), "classpath");

		Properties loaded = DataSourceFactory.loadProperties(NOTHING, NOTHING, work, classpathHolding(classpath));

		assertEquals("classpath", originOf(loaded));
	}

	@Test
	void testAParentDirectoryIsNeverConsulted ( ) throws IOException {
		// a file one level up from the working directory, where the process might have been started from
		// another project's tree: it must not be picked up
		Path work = dir.resolve("project/module");
		Files.createDirectories(work);
		write(dir.resolve("project/db.properties"), "parent");
		write(dir.resolve("db.properties"), "grandparent");

		EventStorageException thrown = assertThrows(EventStorageException.class,
			() -> DataSourceFactory.loadProperties(NOTHING, NOTHING, work, classpathHolding(dir.resolve("empty"))));

		assertTrue(thrown.getMessage().contains(work.resolve("db.properties").toString()),
			"the location tried must be the working directory itself: " + thrown.getMessage());
	}

	@Test
	void testAConfiguredPathThatDoesNotExistFailsInsteadOfFallingThrough ( ) throws IOException {
		Path work = dir.resolve("work");
		write(work.resolve("db.properties"), "working-directory");
		Path missing = dir.resolve("missing/db.properties");

		EventStorageException thrown = assertThrows(EventStorageException.class,
			() -> DataSourceFactory.loadProperties(Map.of(DataSourceFactory.SYSTEM_PROPERTY, missing.toString())::get,
				NOTHING, work, classpathHolding(dir.resolve("empty"))));

		assertTrue(thrown.getMessage().contains(missing.toString()), thrown.getMessage());
		assertTrue(thrown.getMessage().contains(DataSourceFactory.SYSTEM_PROPERTY),
			"the message must say what configured the path: " + thrown.getMessage());
	}

	@Test
	void testNothingFoundNamesEveryLocationTried ( ) throws IOException {
		Path work = dir.resolve("work");
		Files.createDirectories(work);

		EventStorageException thrown = assertThrows(EventStorageException.class,
			() -> DataSourceFactory.loadProperties(NOTHING, NOTHING, work, classpathHolding(dir.resolve("empty"))));

		String message = thrown.getMessage();
		assertTrue(message.contains(DataSourceFactory.SYSTEM_PROPERTY), message);
		assertTrue(message.contains(DataSourceFactory.ENVIRONMENT_VARIABLE), message);
		assertTrue(message.contains(work.resolve("db.properties").toString()), message);
		assertTrue(message.contains("classpath"), message);
	}

	@Test
	void testLoadingANamedFileConsultsNothingElse ( ) throws IOException {
		Path named = write(dir.resolve("named/db.properties"), "named");

		assertEquals("named", originOf(DataSourceFactory.loadProperties(named)));
		assertThrows(EventStorageException.class, () -> DataSourceFactory.loadProperties(dir.resolve("absent.properties")));
	}

}
