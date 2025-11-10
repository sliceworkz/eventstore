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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.testcontainers.containers.PostgreSQLContainer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class PostgresContainer {
	
	public static final String POSTGRES_DOCKER_CONTAINER_IMAGE = "postgres:16";

	private static boolean ENABLE_DUMP_EVENTS_AFTER_TEST = false;
	
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
	
	public static void dumpEventsInTable() {
		if ( ENABLE_DUMP_EVENTS_AFTER_TEST ) {
			try (Connection conn = connection()) {
				String sql = "SELECT * FROM events ORDER BY position";
				try (PreparedStatement stmt = conn.prepareStatement(sql);
					 ResultSet rs = stmt.executeQuery()) {
					
					System.out.println("\n=== EVENTS TABLE DUMP ===");
					System.out.printf("%-10s %-36s %-20s %-15s %-20s %-36s %-20s %-15s %-15s %-15s %-50s %-30s%n", 
						"POSITION", "EVENT_ID", "STREAM_CONTEXT", "STREAM_PURPOSE", "EVENT_TYPE", 
						"TRC_TIMESTAMP", "TRC_CORRELATION", "TRC_TRANSACTION", "TRC_INSTANCE_LO", "TRC_INSTANCE_PH", "TRC_ACTOR", "TRC_CHANNEL", "EVENT_DATA", "TAGS");
					System.out.println("-".repeat(300));
					
					while (rs.next()) {
						long position = rs.getLong("position");
						String eventId = rs.getString("event_id");
						String streamContext = rs.getString("stream_context");
						String streamPurpose = rs.getString("stream_purpose");
						String eventType = rs.getString("event_type");
						String txTimestamp = rs.getString("trc_timestamp");
						String txCorrelation = rs.getString("trc_correlation");
						String txTransaction = rs.getString("trc_transaction");
						String txInstanceLogical = rs.getString("trc_instance_lo");
						String txInstancePhycial = rs.getString("trc_instance_ph");
						String txActor = rs.getString("trc_actor");
						String txChannel = rs.getString("trc_channel");
						String eventData = rs.getString("event_data");
						
						// Get tags array
						String tagsStr = "";
						if (rs.getArray("tags") != null) {
							String[] tags = (String[]) rs.getArray("tags").getArray();
							tagsStr = String.join(",", tags);
						}
						
						// Truncate long strings for display
						eventData = eventData.length() > 47 ? eventData.substring(0, 47) + "..." : eventData;
						tagsStr = tagsStr.length() > 27 ? tagsStr.substring(0, 27) + "..." : tagsStr;
						
						System.out.printf("%-10d %-36s %-20s %-15s %-20s %-36s %-36s %-20s %-15s %-15s %-15s %-15s %-50s %-30s%n",
							position, eventId, streamContext, streamPurpose, eventType,
							txCorrelation, txTransaction, txTimestamp, txInstanceLogical, txInstancePhycial, txActor, txChannel, eventData, tagsStr);
					}
					System.out.println("=== END EVENTS TABLE DUMP ===\n");
				}
			} catch (SQLException e) {
				System.err.println("Failed to dump events table: " + e.getMessage());
			}
		}
	}

}
