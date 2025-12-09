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
package org.sliceworkz.eventstore.benchmark;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.CustomerEvent;
import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.SupplierEvent;
import org.sliceworkz.eventstore.benchmark.consumer.CustomerConsumer;
import org.sliceworkz.eventstore.benchmark.consumer.SupplierConsumer;
import org.sliceworkz.eventstore.benchmark.producer.CustomerEventProducer;
import org.sliceworkz.eventstore.benchmark.producer.SupplierEventProducer;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.infra.postgres.DataSourceFactory;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class BenchmarkApplication {

	public static final int EVENTS_PER_PRODUCER_INSTANCE = 1000;
	public static final int PARALLEL_WORKERS = 2;
	public static final int TOTAL_CONSUMER_EVENTS = PARALLEL_WORKERS * EVENTS_PER_PRODUCER_INSTANCE; 
	public static final int TOTAL_SUPPLIER_EVENTS = PARALLEL_WORKERS * EVENTS_PER_PRODUCER_INSTANCE; 

	private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkApplication.class);
	
	public static void main ( String[] args ) throws InterruptedException {
		LOGGER.info("starting...");
		
		//EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
		EventStore eventStore = PostgresEventStorage.newBuilder().prefix("benchmark_").initializeDatabase().buildStore();
		
		// stream-design: one single stream "customer", tags to differentiate
		EventStream<CustomerEvent> customerStream = eventStore.getEventStream(EventStreamId.forContext("customer").defaultPurpose(), CustomerEvent.class);
		
		// stream-design: stream per supplier "supplier/<id>"
		EventStream<SupplierEvent> supplierStream = eventStore.getEventStream(EventStreamId.forContext("supplier").anyPurpose(), SupplierEvent.class);

		EventStream<SupplierEvent> supplier42Stream = eventStore.getEventStream(EventStreamId.forContext("supplier").withPurpose("42"), SupplierEvent.class);
		supplier42Stream.subscribe(new EventStreamEventuallyConsistentAppendListener() {
			@Override
			public EventReference eventsAppended(EventReference atLeastUntil) {
				System.err.println("-------> " + atLeastUntil);
				System.err.println(supplier42Stream.getEventById(atLeastUntil.id()).get());
				return atLeastUntil;
			}
		});

		DataSource dataSource = DataSourceFactory.fromConfiguration(DataSourceFactory.loadProperties());
		initializeBenchmarkReadModel(dataSource);

		CustomerConsumer cc = new CustomerConsumer(customerStream, dataSource);
		SupplierConsumer sc = new SupplierConsumer(supplierStream);

		CustomerEventProducer cep = new CustomerEventProducer(customerStream, EVENTS_PER_PRODUCER_INSTANCE);
		SupplierEventProducer sep = new SupplierEventProducer(supplierStream, EVENTS_PER_PRODUCER_INSTANCE);
		
		
		Instant start = Instant.now();
		
		ExecutorService executor = Executors.newFixedThreadPool(20);

		for ( int i = 0; i < PARALLEL_WORKERS; i++ ) {
		    executor.submit(cep);
		    executor.submit(sep);
		}

		executor.shutdown();
		executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		
		Instant stopProduce = Instant.now();

		cc.runProjector();
		sc.runProjector();
		
		for ( int i = 0; i < 10; i++ ) {
			System.err.println("====================================================================================================");
		}
		
		System.out.println("CUSTOMER EVENTS PROCESSED : %d (%d batches)".formatted(cc.getProjection().eventsProcessed(), cc.getProjection().batchesProcessed()));
		System.out.println("SUPPLIER EVENTS PROCESSED : %d (%d batches)".formatted(sc.getProjection().eventsProcessed(), sc.getProjection().batchesProcessed()));

		long produceDurationMs = stopProduce.toEpochMilli() - start.toEpochMilli();
		
		EventStream<Object> allStream = eventStore.getEventStream(EventStreamId.anyContext().anyPurpose());
		allStream.queryBackwards(EventQuery.matchAll(),Limit.to(10)).forEach(System.out::println);

		long position = allStream.queryBackwards(EventQuery.matchAll(),Limit.to(1)).findFirst().get().reference().position();
		
		System.err.println("duration: %d".formatted(produceDurationMs));
		System.err.println("last pos: %d".formatted(position));

		double producedEventsPerSec = ((1000*position)/(double)produceDurationMs);
		
		System.err.println("events/sec produced: %f".formatted(producedEventsPerSec));

		if ( cc.getProjection().eventsProcessed() < TOTAL_CONSUMER_EVENTS ) {
			System.err.println("== Customer Event COUNT NOT OK ! ==================================================================================");
		}
		if ( sc.getProjection().eventsProcessed() < TOTAL_SUPPLIER_EVENTS ) {
			System.err.println("== Supplier Event COUNT NOT OK ! ==================================================================================");
		}
		
		Instant stopConsume = Instant.now();

		long consumeDurationMs = stopConsume.toEpochMilli() - start.toEpochMilli();

		double consumedEventsPerSec = ((1000*position)/(double)consumeDurationMs);
		
		System.err.println("events/sec consumed: %f".formatted(consumedEventsPerSec));

		System.out.println("received notifications: %d".formatted(cc.recievedAppendNotifications()));

		report(dataSource);
		
		LOGGER.info("done.");
	}

	private static void initializeBenchmarkReadModel(DataSource dataSource) {
		LOGGER.info("Initializing benchmark_readmodel table...");
		try (InputStream is = BenchmarkApplication.class.getClassLoader().getResourceAsStream("benchmark_readmodel.sql")) {
			if (is == null) {
				throw new RuntimeException("Could not find benchmark_readmodel.sql in classpath");
			}

			// Filter out comment lines before splitting by semicolon
			String sql = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
				.lines()
				.filter(line -> {
					String trimmed = line.trim();
					return !trimmed.isEmpty() && !trimmed.startsWith("--");
				})
				.collect(Collectors.joining("\n"));

			try (Connection conn = dataSource.getConnection();
			     Statement stmt = conn.createStatement()) {

				// Execute the SQL (may contain multiple statements separated by semicolons)
				for (String statement : sql.split(";")) {
					String trimmed = statement.trim();
					if (!trimmed.isEmpty()) {
						stmt.execute(trimmed);
					}
				}

				LOGGER.info("benchmark_readmodel table initialized successfully");
			}
		} catch (Exception e) {
			LOGGER.error("Failed to initialize benchmark_readmodel table", e);
			throw new RuntimeException("Failed to initialize benchmark_readmodel table", e);
		}
	}

	public static void report (DataSource dataSource) {
		LOGGER.info("reporting ...");

		String query = """
			SELECT
			    COUNT(*) as total_events,
			    AVG(EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as avg_latency_ms,
			    MIN(EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as min_latency_ms,
			    MAX(EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as max_latency_ms,
			    PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as median_latency_ms,
			    PERCENTILE_CONT(0.90) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as p90_latency_ms,
			    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as p95_latency_ms
			FROM benchmark_events e
			INNER JOIN benchmark_readmodel r ON e.event_position = r.event_position
			""";

		try (Connection conn = dataSource.getConnection();
		     Statement stmt = conn.createStatement();
		     ResultSet rs = stmt.executeQuery(query)) {

			ResultSetMetaData metaData = rs.getMetaData();
			int columnCount = metaData.getColumnCount();

			// Calculate column widths
			int[] columnWidths = new int[columnCount];
			String[] columnNames = new String[columnCount];
			String[][] values = new String[1][columnCount]; // Assuming single row result

			// Get column names and initialize widths
			for (int i = 0; i < columnCount; i++) {
				columnNames[i] = metaData.getColumnName(i + 1);
				columnWidths[i] = columnNames[i].length();
			}

			// Get values and update column widths
			if (rs.next()) {
				for (int i = 0; i < columnCount; i++) {
					Object value = rs.getObject(i + 1);
					String valueStr;
					if (value == null) {
						valueStr = "NULL";
					} else if (value instanceof Number && !(value instanceof Long || value instanceof Integer)) {
						valueStr = String.format("%.4f", ((Number) value).doubleValue());
					} else {
						valueStr = value.toString();
					}
					values[0][i] = valueStr;
					columnWidths[i] = Math.max(columnWidths[i], valueStr.length());
				}
			}

			// Print column headers
			for (int i = 0; i < columnCount; i++) {
				System.out.print(String.format("%-" + columnWidths[i] + "s", columnNames[i]));
				if (i < columnCount - 1) {
					System.out.print(" | ");
				}
			}
			System.out.println();

			// Print separator
			for (int i = 0; i < columnCount; i++) {
				System.out.print("-".repeat(columnWidths[i]));
				if (i < columnCount - 1) {
					System.out.print("-+-");
				}
			}
			System.out.println();

			// Print values
			for (int i = 0; i < columnCount; i++) {
				System.out.print(String.format("%-" + columnWidths[i] + "s", values[0][i]));
				if (i < columnCount - 1) {
					System.out.print(" | ");
				}
			}
			System.out.println();

			LOGGER.info("Report completed.");

		} catch (Exception e) {
			LOGGER.error("Failed to execute report query", e);
			throw new RuntimeException("Failed to execute report query", e);
		}
	}
}
