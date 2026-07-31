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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.platform.commons.support.AnnotationSupport;
import org.sliceworkz.eventstore.testing.EventStoreBackend.Capability;

/**
 * Turns one {@link ForEachBackend} method into one invocation per registered
 * {@link EventStoreBackend}.
 * <p>
 * This is what replaces the hand-written {@code @Nested OnInMem / OnPostgres17 / OnPostgres18}
 * triples: the backend list is data, not code, so adding a storage implementation to the run is a
 * service-loader entry rather than an edit to every scenario class.
 * <p>
 * Not part of the public API — use {@link ForEachBackend}.
 */
class BackendTestTemplateProvider implements TestTemplateInvocationContextProvider {

	@Override
	public boolean supportsTestTemplate ( ExtensionContext context ) {
		return AnnotationSupport.isAnnotated(context.getTestMethod(), ForEachBackend.class);
	}

	@Override
	public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts ( ExtensionContext context ) {
		List<Capability> required = AnnotationSupport.findAnnotation(context.getRequiredTestMethod(), ForEachBackend.class)
				.map(a -> Arrays.asList(a.requires()))
				.orElse(List.of());

		return EventStoreBackends.registered().stream()
				.map(backend -> (TestTemplateInvocationContext) new BackendInvocationContext(backend, required));
	}

	/**
	 * One invocation, bound to one backend.
	 */
	private record BackendInvocationContext ( EventStoreBackend backend, List<Capability> required ) implements TestTemplateInvocationContext {

		@Override
		public String getDisplayName ( int invocationIndex ) {
			return "[%s]".formatted(backend.name());
		}

		@Override
		public List<Extension> getAdditionalExtensions ( ) {
			return List.of(new BackendBinding(backend, required));
		}

	}

	/**
	 * Hands the backend to the test before it runs.
	 * <p>
	 * Runs as a {@link BeforeEachCallback}, so it lands before {@code AbstractEventStoreTest}'s own
	 * {@code @BeforeEach} creates the store. Also resolves an {@link EventStoreBackend} parameter,
	 * for scenarios that want the backend without extending the base class.
	 */
	private record BackendBinding ( EventStoreBackend backend, List<Capability> required ) implements BeforeEachCallback, ParameterResolver {

		@Override
		public void beforeEach ( ExtensionContext context ) {
			for ( Capability capability : required ) {
				Assumptions.assumeTrue(backend.supports(capability),
						() -> "backend '%s' does not support %s".formatted(backend.name(), capability));
			}
			if ( context.getTestInstance().orElse(null) instanceof AbstractEventStoreTest test ) {
				test.useBackend(backend);
			}
		}

		@Override
		public boolean supportsParameter ( ParameterContext parameterContext, ExtensionContext extensionContext ) {
			return parameterContext.getParameter().getType() == EventStoreBackend.class;
		}

		@Override
		public Object resolveParameter ( ParameterContext parameterContext, ExtensionContext extensionContext ) {
			return backend;
		}

	}

}
