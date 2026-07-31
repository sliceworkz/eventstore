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
package org.sliceworkz.eventstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.testing.EventStoreBackend;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.EventStoreBasicTest;

/**
 * Guards the property the shared suite exists for: every scenario runs against every backend.
 * <p>
 * Before the suite was parameterised, each scenario class carried its own hand-written
 * {@code @Nested OnInMem / OnPostgres17 / OnPostgres18} triple, and three classes had quietly been
 * written without one — so nine tests ran against the in-memory store only while looking exactly
 * like part of the shared suite. Nothing failed; the coverage simply was not there.
 * <p>
 * These two assertions make that failure mode loud: a scenario annotated {@code @Test} instead of
 * {@code @ForEachBackend} is a scenario that runs once, and a backend missing from the service file
 * is a backend nothing is verified against.
 * <p>
 * The service file is read directly rather than through
 * {@link org.sliceworkz.eventstore.testing.EventStoreBackends}, so this still checks the full set
 * during a narrowed local run ({@code -Deventstore.testing.backends=inmem}).
 */
class TckBackendCoverageTest {

	private static final String SERVICE_FILE = "META-INF/services/" + EventStoreBackend.class.getName();

	private static final List<String> EXPECTED_BACKENDS = List.of(
			"org.sliceworkz.eventstore.testing.backend.InMemoryBackend",
			"org.sliceworkz.eventstore.testing.backend.InMemoryFsBackend",
			"org.sliceworkz.eventstore.testing.backend.Postgres17Backend",
			"org.sliceworkz.eventstore.testing.backend.Postgres18Backend");

	@Test
	void everyInTreeBackendIsRegistered ( ) throws IOException {
		List<String> declared;
		try ( InputStream in = getClass().getClassLoader().getResourceAsStream(SERVICE_FILE) ) {
			assertTrue(in != null, SERVICE_FILE + " is missing from the test classpath");
			declared = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
					.map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.sorted()
					.toList();
		}

		assertEquals(EXPECTED_BACKENDS.stream().sorted().toList(), declared,
				"""
				The registered backends changed. Every storage implementation this repository ships \
				must run the shared TCK — add it to the service file rather than leaving it unverified.""");
	}

	@Test
	void everyTckScenarioRunsAgainstEveryBackend ( ) {
		List<String> singleBackendScenarios = new ArrayList<>();

		for ( Class<?> scenario : tckScenarioClasses() ) {
			for ( Method method : scenario.getDeclaredMethods() ) {
				if ( method.isAnnotationPresent(Test.class) && !method.isAnnotationPresent(ForEachBackend.class) ) {
					singleBackendScenarios.add(scenario.getSimpleName() + "." + method.getName());
				}
			}
		}

		assertTrue(singleBackendScenarios.isEmpty(),
				"""
				These TCK scenarios are annotated @Test, so they run against one backend only while \
				sitting in the shared suite. Annotate them @ForEachBackend, or move them out of the \
				tck package if they are genuinely backend-specific: %s""".formatted(singleBackendScenarios));
	}

	/**
	 * Every scenario class in the published TCK, found on the classpath rather than listed by hand
	 * so a newly added scenario is covered without anyone remembering to register it here. The TCK
	 * resolves to a jar in a normal build and to a directory in an IDE, so both are handled.
	 *
	 * @return the TCK scenario classes
	 */
	private static List<Class<?>> tckScenarioClasses ( ) {
		String prefix = "org/sliceworkz/eventstore/testing/tck/";
		Path location = Paths.get(URI.create(
				EventStoreBasicTest.class.getProtectionDomain().getCodeSource().getLocation().toString()));

		List<String> classNames = new ArrayList<>();
		try {
			if ( Files.isDirectory(location) ) {
				try ( Stream<Path> paths = Files.walk(location.resolve(prefix)) ) {
					paths.filter(p -> p.toString().endsWith("Test.class"))
							.forEach(p -> classNames.add(location.relativize(p).toString()));
				}
			} else {
				try ( JarFile jar = new JarFile(location.toFile()) ) {
					for ( Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
						String name = entries.nextElement().getName();
						if ( name.startsWith(prefix) && name.endsWith("Test.class") ) {
							classNames.add(name);
						}
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		List<Class<?>> classes = new ArrayList<>();
		for ( String name : classNames ) {
			try {
				classes.add(Class.forName(name.replace('/', '.').replace('\\', '.').replaceAll("\\.class$", "")));
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}
		}
		assertTrue(classes.size() >= 15, "expected to find the TCK scenario classes, found " + classes.size());
		return classes;
	}

}
