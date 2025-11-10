/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventstore.infra.postgres.util;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.testcontainers.containers.PostgreSQLContainer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class PostgresContainer {
	
	public static final String POSTGRES_DOCKER_CONTAINER_IMAGE = "postgres:16";
	
	@SuppressWarnings("resource")
	public static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(POSTGRES_DOCKER_CONTAINER_IMAGE)
		      .withDatabaseName("integration-tests-db")
		      .withUsername("sa")
		      .withPassword("pwd");
	

	public static void start ( ) {
		postgreSQLContainer.start();
	}

	public static void stop ( ) {
		if ( postgreSQLContainer != null ) {
			postgreSQLContainer.stop();
		}
	}
	
	public static void cleanup ( ) {
	    if (postgreSQLContainer != null) {
	        postgreSQLContainer.close();
	    }
	}
	
	private static HikariDataSource DATASOURCE;

	
	
	public static DataSource dataSource ( ) {
		HikariConfig config = new HikariConfig();
		config.setUsername(postgreSQLContainer.getUsername());
		config.setPassword(postgreSQLContainer.getPassword());
		config.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
		
		DATASOURCE = new HikariDataSource(config);
		return DATASOURCE;
	}
	
	public static Connection connection ( ) {
        try {
			return DATASOURCE.getConnection();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static void closeDataSource () {
		DATASOURCE.close();
	}

	void close ( Connection connection ) {
        try {
			if (!connection.isClosed()) {
			    connection.close();
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

}
