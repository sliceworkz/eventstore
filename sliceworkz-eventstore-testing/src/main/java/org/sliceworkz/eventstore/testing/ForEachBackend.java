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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sliceworkz.eventstore.testing.EventStoreBackend.Capability;

/**
 * Runs this test once against every registered {@link EventStoreBackend}.
 * <p>
 * Use it instead of {@link org.junit.jupiter.api.Test} on scenarios that must hold for all storage
 * implementations. The class it lives in normally extends {@link AbstractEventStoreTest}, which
 * receives the backend-provided store before each invocation.
 * <pre>{@code
 * class MyStorageScenarios extends AbstractEventStoreTest {
 *     @ForEachBackend
 *     void appendedEventIsQueryable ( ) {
 *         ...
 *     }
 * }
 * }</pre>
 * Each invocation is reported under its own name — {@code appendedEventIsQueryable [postgres:18]} —
 * so a failure names the backend that produced it without any digging.
 *
 * @see EventStoreBackends
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE })
@TestTemplate
@ExtendWith(BackendTestTemplateProvider.class)
public @interface ForEachBackend {

	/**
	 * Capabilities the scenario needs. Backends not supporting all of them are skipped for this
	 * test — reported as skipped, with the reason, rather than passed silently.
	 *
	 * @return the required capabilities; empty means the scenario runs everywhere
	 */
	Capability[] requires ( ) default { };

}
