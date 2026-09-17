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
 * {@code getRawEventStream} fixes its own: a read-only {@code EventSource<String>} over the stored JSON
 * documents, assignable to no domain-typed stream. Also that {@code EventType.of} takes a class and
 * nothing else, so a stored name passed to it is a compile error rather than the type named {@code String}.
 * And that an append takes the batch as {@code List<? extends EphemeralEvent<? extends E>>}, so an ordinary
 * list of one event type needs no type witness while a list of a foreign one is still refused.
 * <p>
 * The guarantee is a compile-time one, so the only way to test it is to compile: each probe below is
 * handed to javac against this module's classes, and the test asserts which probes it rejects and which it
 * accepts. The failure this guards against is a regression to a {@code Class<?>} parameter, under which
 * {@code EventStream<OrderEvent> s = store.getEventStream(id, CustomerEvent.class)} compiles and the
 * mismatch surfaces as a runtime append failure instead.
 */
public class EventStoreTypeParameterTest {

	private static final String PROBE_PRELUDE = """
			import java.util.List;
			import java.util.Set;
			import org.sliceworkz.eventstore.EventStore;
			import org.sliceworkz.eventstore.events.EphemeralEvent;
			import org.sliceworkz.eventstore.events.EventType;
			import org.sliceworkz.eventstore.stream.AppendCriteria;
			import org.sliceworkz.eventstore.stream.EventSource;
			import org.sliceworkz.eventstore.stream.EventStream;
			import org.sliceworkz.eventstore.stream.EventStreamId;

			class Probe {
				interface CustomerEvent { }
				interface LegacyCustomerEvent { }
				interface OrderEvent { }
				record CustomerRegistered ( String name ) implements CustomerEvent { }

				void probe ( EventStore store, EventStreamId id,
						List<EphemeralEvent<CustomerRegistered>> batch,
						List<EphemeralEvent<OrderEvent>> foreign ) {
			""";

	private static final String PROBE_EPILOGUE = """
				}
			}
			""";

	// javac's key for "incompatible types", stable across locales and versions
	private static final String INCOMPATIBLE_TYPES = "compiler.err.prob.found.req";

	// javac's key for "no suitable method found", which is how an argument is rejected on an overloaded method
	private static final String NO_APPLICABLE_METHOD = "compiler.err.cant.apply.symbols";

	@TempDir
	Path outputDirectory;

	@Test
	void aRootClassOfAnotherTypeDoesNotCompile ( ) {
		assertRejected("EventStream<OrderEvent> s = store.getEventStream(id, CustomerEvent.class);");
	}

	@Test
	void aRootClassOfAnotherTypeDoesNotCompileWithALegacyRootEither ( ) {
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
	void theLegacyRootClassIsNotConstrainedByTheStreamsType ( ) {
		// legacy events upcast into current ones and never surface under their own type
		assertAccepted("EventStream<CustomerEvent> s = store.getEventStream(id, CustomerEvent.class, LegacyCustomerEvent.class);");
	}

	@Test
	void theSetOverloadStaysTheWayToTypeAStreamWiderThanItsRoots ( ) {
		assertAccepted("EventStream<Object> s = store.getEventStream(id, Set.of(CustomerEvent.class));");
	}

	@Test
	void aRawStreamIsAReadOnlySourceOfJsonDocuments ( ) {
		assertAccepted("EventSource<String> s = store.getRawEventStream(id);");
	}

	@Test
	void aRawStreamCannotBeTypedAsADomainStream ( ) {
		// the trap a free type parameter would leave open: a JSON document under the domain type, found
		// out at the first switch over data() as a ClassCastException
		assertRejected("EventSource<CustomerEvent> s = store.getRawEventStream(id);");
	}

	@Test
	void aRawStreamIsNotAnEventStreamBecauseItCannotAppend ( ) {
		assertRejected("EventStream<Object> s = store.getRawEventStream(id);");
	}

	@Test
	void anOrdinaryListOfOneEventTypeIsAppendableWithoutATypeWitness ( ) {
		// List is invariant, so a List<EphemeralEvent<CustomerRegistered>> only fits a parameter that
		// wildcards the list too. Without that, every call site mapping domain events into ephemeral ones
		// has to name the parameter type -- .<EphemeralEvent<? extends CustomerEvent>>map(...) -- to say
		// what the signature can say once
		assertAccepted("store.getEventStream(id, CustomerEvent.class).append(batch);");
		assertAccepted("store.getEventStream(id, CustomerEvent.class).append(AppendCriteria.none(), batch);");
	}

	@Test
	void aBatchOfAForeignEventTypeStillDoesNotCompile ( ) {
		// what the inner wildcard keeps: widening the list must not widen what may go in it
		assertRejected("store.getEventStream(id, CustomerEvent.class).append(foreign);", NO_APPLICABLE_METHOD);
	}

	@Test
	void anEventTypeIsNamedByAClassAndNotByAnInstance ( ) {
		assertAccepted("EventType t = EventType.of(CustomerEvent.class);");
		// the trap an of(Object) overload beside of(Class) leaves open: a stored name passed by mistake
		// compiles, and is the type named "String", matching no stored event and failing nothing
		assertRejected("EventType t = EventType.of(\"CustomerRegistered\");");
	}

	private void assertRejected ( String statement ) {
		assertRejected(statement, INCOMPATIBLE_TYPES);
	}

	private void assertRejected ( String statement, String expectedCode ) {
		List<Diagnostic<? extends JavaFileObject>> errors = compile(statement);
		assertFalse(errors.isEmpty(), "javac accepted: " + statement);
		Diagnostic<? extends JavaFileObject> error = errors.get(0);
		assertEquals(expectedCode, error.getCode(),
				"javac rejected the probe, but not for the expected reason: " + error.getMessage(Locale.ENGLISH));
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
