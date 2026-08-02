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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;

/**
 * Pins down that a db properties <em>value</em> never reaches an error message.
 * <p>
 * Every non-{@code datasource.} key flows through
 * {@link HikariConfigurationUtil#setHikariProperty(HikariConfig, String, String)}, and
 * {@code db.<name>.password} is one of them, so a value interpolated into the exception message is a
 * database password in every log, stack trace and error reporter downstream of it.
 */
class HikariConfigurationUtilTest {

	private static final String SECRET = "hunter2-Th3-Actual-Passw0rd";

	/** A config whose password setter fails, standing in for any Hikari setter that rejects its argument. */
	private static class FailingPasswordConfig extends HikariConfig {

		@Override
		public void setPassword ( String password ) {
			throw new IllegalStateException("setter refused the argument");
		}
	}

	private static String stackTraceOf ( Throwable throwable ) {
		StringWriter writer = new StringWriter();
		throwable.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}

	@Test
	void testSetterFailureOnASecretDoesNotLeakTheValue ( ) {

		RuntimeException thrown = assertThrows(RuntimeException.class,
			() -> HikariConfigurationUtil.setHikariProperty(new FailingPasswordConfig(), "password", SECRET));

		assertFalse(thrown.getMessage().contains(SECRET), "the password must not appear in the exception message: " + thrown.getMessage());
		// the whole chain, not only the top frame -- an error reporter renders all of it
		assertFalse(stackTraceOf(thrown).contains(SECRET), "the password must not appear anywhere in the stack trace");

		// what is left has to stay actionable
		assertTrue(thrown.getMessage().contains("password"), "the property name is what makes the error actionable: " + thrown.getMessage());
		// the cause carries the detail: the reflective wrapper, and under it what the setter actually threw
		InvocationTargetException cause = assertInstanceOf(InvocationTargetException.class, thrown.getCause());
		assertInstanceOf(IllegalStateException.class, cause.getCause());
	}

	@Test
	void testSecretIsNotLeakedThroughCreateConfigEither ( ) {

		Properties properties = new Properties();
		properties.setProperty("db.pooled.url", "jdbc:postgresql://localhost:5432/eventstore");
		properties.setProperty("db.pooled.password", SECRET);
		// a numeric property with a non-numeric value: the realistic thrower, since convertValue parses unguarded
		properties.setProperty("db.pooled.maximumPoolSize", "twenty-five");

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> HikariConfigurationUtil.createConfig("pooled", properties));

		assertFalse(stackTraceOf(thrown).contains(SECRET), "no property value from the file may reach the failure of another property");
	}

	@Test
	void testNonNumericValueForANumericPropertyNamesThePropertyAndTheExpectedType ( ) {

		RuntimeException thrown = assertThrows(RuntimeException.class,
			() -> HikariConfigurationUtil.setHikariProperty(new HikariConfig(), "maximumPoolSize", "twenty-five"));

		assertTrue(thrown.getMessage().contains("maximumPoolSize"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains("int"), "the expected type is signal the value is not: " + thrown.getMessage());
		assertInstanceOf(NumberFormatException.class, thrown.getCause());
	}

	@Test
	void testEmptyPropertyNameIsRejectedWithContext ( ) {

		// "db.pooled.=x" -- a stray line used to reach charAt(0) and throw StringIndexOutOfBoundsException with no context
		Properties properties = new Properties();
		properties.setProperty("db.pooled.", "x");

		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> HikariConfigurationUtil.createConfig("pooled", properties));

		assertTrue(thrown.getMessage().contains("empty name"), thrown.getMessage());

		assertThrows(IllegalArgumentException.class, () -> HikariConfigurationUtil.setHikariProperty(new HikariConfig(), "", "x"));
		assertThrows(IllegalArgumentException.class, () -> HikariConfigurationUtil.setHikariProperty(new HikariConfig(), null, "x"));
	}

	@Test
	void testValidConfigurationStillLoads ( ) {

		Properties properties = new Properties();
		properties.setProperty("db.pooled.url", "jdbc:postgresql://localhost:5432/eventstore");
		properties.setProperty("db.pooled.username", "eventstore");
		properties.setProperty("db.pooled.password", SECRET);
		properties.setProperty("db.pooled.maximumPoolSize", "25");
		properties.setProperty("db.pooled.datasource.sslmode", "require");
		properties.setProperty("db.nonpooled.maximumPoolSize", "2");

		HikariConfig config = HikariConfigurationUtil.createConfig("pooled", properties);

		assertNotNull(config);
		assertEquals("jdbc:postgresql://localhost:5432/eventstore", config.getJdbcUrl());
		assertEquals("eventstore", config.getUsername());
		assertEquals(SECRET, config.getPassword());
		assertEquals(25, config.getMaximumPoolSize());
		assertEquals("require", config.getDataSourceProperties().getProperty("sslmode"));
	}
}
