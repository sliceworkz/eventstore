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
package org.sliceworkz.eventstore.testing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.ServiceLoader;

/**
 * The set of backends the shared scenarios run against.
 * <p>
 * Backends are discovered with the {@link ServiceLoader}: put the implementation class name in
 * {@code META-INF/services/org.sliceworkz.eventstore.testing.EventStoreBackend} on the test
 * classpath and every {@link ForEachBackend} scenario picks it up. Nothing else is needed to run
 * the whole TCK against a new storage implementation.
 * <p>
 * Two escape hatches:
 * <ul>
 *   <li>{@link #use(EventStoreBackend...)} replaces the discovered set programmatically. Handy in a
 *       build that wants one backend per surefire execution.</li>
 *   <li>The system property {@code eventstore.testing.backends} narrows the set to a comma-separated
 *       list of backend names — {@code -Deventstore.testing.backends=inmem} runs the TCK without
 *       starting any container.</li>
 * </ul>
 * Backends are resolved once per JVM and {@link EventStoreBackend#close() closed} on JVM shutdown.
 */
public final class EventStoreBackends {

	/** System property narrowing the backend set to a comma-separated list of names. */
	public static final String BACKENDS_PROPERTY = "eventstore.testing.backends";

	private static List<EventStoreBackend> registered;
	private static boolean shutdownHookInstalled;

	private EventStoreBackends ( ) {
	}

	/**
	 * Replaces the discovered backends for this JVM. Call before the first scenario runs — a
	 * suite that has already started will not be re-parameterised.
	 *
	 * @param backends the backends to run against; at least one
	 */
	public static synchronized void use ( EventStoreBackend... backends ) {
		if ( backends == null || backends.length == 0 ) {
			throw new IllegalArgumentException("at least one backend is required");
		}
		registered = List.of(backends);
		installShutdownHook();
	}

	/**
	 * The backends to run against, after ServiceLoader discovery and any narrowing by
	 * {@link #BACKENDS_PROPERTY}.
	 *
	 * @return the backends, in discovery order; never empty
	 * @throws IllegalStateException if none are registered, or if the property names none that exist
	 */
	public static synchronized List<EventStoreBackend> registered ( ) {
		if ( registered == null ) {
			registered = discover();
			installShutdownHook();
		}
		return registered;
	}

	private static List<EventStoreBackend> discover ( ) {
		List<EventStoreBackend> discovered = new ArrayList<>();
		ServiceLoader.load(EventStoreBackend.class).forEach(discovered::add);

		if ( discovered.isEmpty() ) {
			throw new IllegalStateException(
					"""
					No EventStoreBackend found. Register one in \
					META-INF/services/org.sliceworkz.eventstore.testing.EventStoreBackend on the test \
					classpath, or call EventStoreBackends.use(...) before running the scenarios.""");
		}

		String requested = System.getProperty(BACKENDS_PROPERTY);
		if ( requested == null || requested.isBlank() ) {
			return Collections.unmodifiableList(discovered);
		}

		Set<String> names = new LinkedHashSet<>(Arrays.asList(requested.split("\\s*,\\s*")));
		List<EventStoreBackend> selected = discovered.stream().filter(b -> names.contains(b.name())).toList();
		if ( selected.isEmpty() ) {
			throw new IllegalStateException("%s=%s matches none of the registered backends %s".formatted(
					BACKENDS_PROPERTY, requested, discovered.stream().map(EventStoreBackend::name).toList()));
		}
		return selected;
	}

	private static void installShutdownHook ( ) {
		if ( shutdownHookInstalled ) {
			return;
		}
		shutdownHookInstalled = true;
		Runtime.getRuntime().addShutdownHook(new Thread(EventStoreBackends::closeAll, "eventstore-testing-backend-close"));
	}

	private static void closeAll ( ) {
		List<EventStoreBackend> backends;
		synchronized (EventStoreBackends.class) {
			backends = registered;
		}
		if ( backends == null ) {
			return;
		}
		for ( EventStoreBackend backend : backends ) {
			try {
				backend.close();
			} catch (RuntimeException e) {
				// shutdown is best-effort: one backend failing to close must not hide the others
				System.err.println("failed to close backend " + backend.name() + ": " + e);
			}
		}
	}

}
