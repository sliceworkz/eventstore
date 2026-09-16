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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins that the single-class {@code getEventStream} overloads fix the stream's type parameter, and that
 * {@code getRawEventStream} fixes its own: a read-only {@code EventSource<Object>}, assignable to no
 * domain-typed stream.
 * <p>
 * The guarantee is a compile-time one, so the only way to test it is to compile: each probe below is
 * handed to javac against this module's classes, and the test asserts which probes it rejects and which it
 * accepts. The failure this guards against is a regression to a {@code Class<?>} parameter, under which
 * {@code EventStream<OrderEvent> s = store.getEventStream(id, CustomerEvent.class)} compiles and the
 * mismatch surfaces as a runtime append failure instead.
 */
public class EventStoreTypeParameterTest {

	private static final String PROBE_PRELUDE = """
			import java.util.Set;
			import org.sliceworkz.eventstore.EventStore;
			import org.sliceworkz.eventstore.stream.EventSource;
			import org.sliceworkz.eventstore.stream.EventStream;
			import org.sliceworkz.eventstore.stream.EventStreamId;

			class Probe {
				interface CustomerEvent { }
				interface LegacyCustomerEvent { }
				interface OrderEvent { }

				void probe ( EventStore store, EventStreamId id ) {
			""";

	private static final String PROBE_EPILOGUE = """
				}
			}
			""";

	// javac's key for "incompatible types", stable across locales and versions
	private static final String INCOMPATIBLE_TYPES = "compiler.err.prob.found.req";

	@TempDir
	Path outputDirectory;

	@Test
	void aRootClassOfAnotherTypeDoesNotCompile ( ) {
		assertRejected("EventStream<OrderEvent> s = store.getEventStream(id, CustomerEvent.class);");
	}

	@Test
	void aRootClassOfAnotherTypeDoesNotCompileWithAHistoricalRootEither ( ) {
		assertRejected("EventStream<OrderEvent> s = store.getEventStream(id, CustomerEvent.class, LegacyCustomerEvent.class);");
	}

	@Test
	void aStreamCannotBeTypedWiderThanItsRootClass ( ) {
		// the case that lets an append of a foreign event type compile: an EventStream<Object> over one
		// root accepts Event<Object>, and the serde has no mapping for anything but the root
		assertRejected("EventStream<Object> s = store.getEventStream(id, CustomerEvent.class);");
	}

	@Test
	void aRootClassOfTheStreamsTypeCompiles ( ) {
		assertAccepted("EventStream<CustomerEvent> s = store.getEventStream(id, CustomerEvent.class);");
	}

	@Test
	void theHistoricalRootClassIsNotConstrainedByTheStreamsType ( ) {
		// legacy events upcast into current ones and never surface under their own type
		assertAccepted("EventStream<CustomerEvent> s = store.getEventStream(id, CustomerEvent.class, LegacyCustomerEvent.class);");
	}

	@Test
	void theSetOverloadStaysTheWayToTypeAStreamWiderThanItsRoots ( ) {
		assertAccepted("EventStream<Object> s = store.getEventStream(id, Set.of(CustomerEvent.class));");
	}

	@Test
	void aRawStreamIsAReadOnlySourceOfObjects ( ) {
		assertAccepted("EventSource<Object> s = store.getRawEventStream(id);");
	}

	@Test
	void aRawStreamCannotBeTypedAsADomainStream ( ) {
		// the trap a free type parameter would leave open: a JSON tree under the domain type, found out
		// at the first switch over data() as a ClassCastException
		assertRejected("EventSource<CustomerEvent> s = store.getRawEventStream(id);");
	}

	@Test
	void aRawStreamIsNotAnEventStreamBecauseItCannotAppend ( ) {
		assertRejected("EventStream<Object> s = store.getRawEventStream(id);");
	}

	private void assertRejected ( String statement ) {
		List<Diagnostic<? extends JavaFileObject>> errors = compile(statement);
		assertFalse(errors.isEmpty(), "javac accepted: " + statement);
		Diagnostic<? extends JavaFileObject> error = errors.get(0);
		assertEquals(INCOMPATIBLE_TYPES, error.getCode(),
				"javac rejected the probe, but not as a type mismatch: " + error.getMessage(Locale.ENGLISH));
		assertEquals(probeLine(), error.getLineNumber(),
				"javac rejected something other than the probe statement: " + error.getMessage(Locale.ENGLISH));
	}

	private void assertAccepted ( String statement ) {
		List<Diagnostic<? extends JavaFileObject>> errors = compile(statement);
		assertTrue(errors.isEmpty(), () -> "javac rejected: " + statement + "\n" + errors.stream()
				.map(d -> d.getLineNumber() + ": " + d.getMessage(Locale.ENGLISH))
				.collect(Collectors.joining("\n")));
	}

	/** the line of the probe statement in the generated source, so a rejection can be pinned to it */
	private static long probeLine ( ) {
		return PROBE_PRELUDE.lines().count() + 1;
	}

	private List<Diagnostic<? extends JavaFileObject>> compile ( String statement ) {
		String source = PROBE_PRELUDE + "\t\t" + statement + "\n" + PROBE_EPILOGUE;
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		assertNotNull(javac, "no system compiler: the tests need a JDK, not a JRE");
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		JavaFileObject probe = new SimpleJavaFileObject(URI.create("string:///Probe.java"), JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent ( boolean ignoreEncodingErrors ) {
				return source;
			}
		};
		List<String> options = List.of(
				"-classpath", classpath(),
				"-d", outputDirectory.toString(),
				"-proc:none",
				"-implicit:none");
		try ( StandardJavaFileManager fileManager = javac.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8) ) {
			javac.getTask(null, fileManager, diagnostics, options, null, List.of(probe)).call();
		} catch ( IOException e ) {
			throw new IllegalStateException(e);
		}
		return diagnostics.getDiagnostics().stream()
				.filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
				.collect(Collectors.toList());
	}

	/**
	 * The classes the probe compiles against: this module's own, plus whatever the test JVM was started with
	 * (javac follows a manifest-only jar's Class-Path, so surefire's booter jar is enough on its own).
	 */
	private static String classpath ( ) {
		List<String> entries = new ArrayList<>();
		entries.add(new File(EventStore.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getPath());
		String inherited = System.getProperty("java.class.path");
		if ( inherited != null && !inherited.isBlank() ) {
			entries.add(inherited);
		}
		return String.join(File.pathSeparator, entries);
	}
}
