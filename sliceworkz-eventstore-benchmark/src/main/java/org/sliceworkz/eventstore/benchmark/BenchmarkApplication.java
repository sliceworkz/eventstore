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
import org.sliceworkz.eventstore.benchmark.consumer.CustomerEventProjection;
import org.sliceworkz.eventstore.benchmark.consumer.SupplierEventProjection;
import org.sliceworkz.eventstore.benchmark.producer.CustomerEventProducer;
import org.sliceworkz.eventstore.benchmark.producer.SupplierEventProducer;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.infra.postgres.DataSourceFactory;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class BenchmarkApplication {

	public static final int EVENTS_PER_PRODUCER_INSTANCE = 10000;
	public static final int PARALLEL_WORKERS_CUSTOMER = 2;
	public static final int PARALLEL_WORKERS_SUPPLIER = 2;
	public static final int TOTAL_CONSUMER_EVENTS = PARALLEL_WORKERS_CUSTOMER * EVENTS_PER_PRODUCER_INSTANCE; 
	public static final int TOTAL_SUPPLIER_EVENTS = PARALLEL_WORKERS_SUPPLIER * EVENTS_PER_PRODUCER_INSTANCE; 
	public static final int MS_WAIT_BETWEEN_EVENTS = 5;

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

		DataSource dataSource = DataSourceFactory.fromConfiguration("pooled");
		initializeBenchmarkReadModel(dataSource);


		CustomerEventProjection customerProjection = new CustomerEventProjection(dataSource);
		Projector<CustomerEvent> customerProjector = Projector.<CustomerEvent>newBuilder()
			.from(customerStream)
			.towards(customerProjection)
			.bookmarkProgress()
				.withReader("customer-projector")
				.readBeforeFirstExecution()
				.done()
			.build();
		customerStream.subscribe(customerProjector);

		

		SupplierEventProjection supplierProjection = new SupplierEventProjection(dataSource);
		Projector<SupplierEvent> supplierProjector = Projector.<SupplierEvent>newBuilder()
			.from(supplierStream)
			.towards(supplierProjection)
			.bookmarkProgress()
				.withReader("supplier-projector")
				.readBeforeFirstExecution()
				.done()
			.build();
		customerStream.subscribe(supplierProjector);
		
		
		CustomerEventProducer cep = new CustomerEventProducer(customerStream, EVENTS_PER_PRODUCER_INSTANCE, MS_WAIT_BETWEEN_EVENTS);
		SupplierEventProducer sep = new SupplierEventProducer(supplierStream, EVENTS_PER_PRODUCER_INSTANCE, MS_WAIT_BETWEEN_EVENTS);
		
		
		Instant start = Instant.now();
		
		ExecutorService executor = Executors.newFixedThreadPool(20);

		for ( int i = 0; i < PARALLEL_WORKERS_CUSTOMER; i++ ) {
		    executor.submit(cep);
		}
		for ( int i = 0; i < PARALLEL_WORKERS_SUPPLIER; i++ ) {
		    executor.submit(sep);
		}

		executor.shutdown();
		
		while ( !executor.isTerminated() ) {
			long done = customerProjector.accumulatedMetrics().eventsHandled() + supplierProjection.eventsProcessed();
			long total = TOTAL_CONSUMER_EVENTS + TOTAL_SUPPLIER_EVENTS;
			System.out.print("Events: %d / %d \r".formatted(done, total));
			executor.awaitTermination(1, TimeUnit.SECONDS);
		}
		
		Instant stopProduce = Instant.now();

		customerProjector.run(); // to process the last events (as producing events has stopped now, still will run to the last one) 
		supplierProjector.run();
		
		for ( int i = 0; i < 10; i++ ) {
			System.err.println("====================================================================================================");
		}
		
		System.out.println("CUSTOMER EVENTS PROCESSED : %d (%d batches)".formatted(customerProjection.eventsProcessed(), customerProjection.batchesProcessed()));
		System.out.println("SUPPLIER EVENTS PROCESSED : %d (%d batches)".formatted(supplierProjection.eventsProcessed(), supplierProjection.batchesProcessed()));

		long produceDurationMs = stopProduce.toEpochMilli() - start.toEpochMilli();
		
		EventStream<Object> allStream = eventStore.getEventStream(EventStreamId.anyContext().anyPurpose());
		allStream.queryBackwards(EventQuery.matchAll(),Limit.to(10)).forEach(System.out::println);

		long position = 0;
		EventReference at = allStream.queryBackwards(EventQuery.matchAll(),Limit.to(1)).map(Event::reference).findFirst().orElse(null);
		if(at != null ) {
			position = at.position();
		}
		
		System.err.println("duration: %d".formatted(produceDurationMs));
		System.err.println("last pos: %d".formatted(position));

		double producedEventsPerSec = ((1000*position)/(double)produceDurationMs);
		
		System.err.println("events/sec produced: %f".formatted(producedEventsPerSec));

		if ( customerProjection.eventsProcessed() < TOTAL_CONSUMER_EVENTS ) {
			System.err.println("== Customer Event COUNT NOT OK ! ==================================================================================");
		}
		if ( supplierProjection.eventsProcessed() < TOTAL_SUPPLIER_EVENTS ) {
			System.err.println("== Supplier Event COUNT NOT OK ! ==================================================================================");
		}
		
		Instant stopConsume = Instant.now();

		long consumeDurationMs = stopConsume.toEpochMilli() - start.toEpochMilli();

		double consumedEventsPerSec = ((1000*position)/(double)consumeDurationMs);
		
		System.err.println("events/sec consumed: %f".formatted(consumedEventsPerSec));

//		System.out.println("received notifications: %d".formatted(cc.recievedAppendNotifications()));

		report(dataSource);
		reportByTimeBucket(dataSource);

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

	public static void reportByTimeBucket (DataSource dataSource) {
		LOGGER.info("reporting by time bucket ...");

		String query = """
			SELECT
			    DATE_TRUNC('second', e.event_timestamp) as time_bucket,
			    COUNT(*) as total_events,
			    AVG(EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as avg_latency_ms,
			    MIN(EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as min_latency_ms,
			    MAX(EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as max_latency_ms,
			    PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as median_latency_ms,
			    PERCENTILE_CONT(0.90) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as p90_latency_ms,
			    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (r.processed_at - e.event_timestamp)) * 1000) as p95_latency_ms
			FROM benchmark_events e
			INNER JOIN benchmark_readmodel r ON e.event_position = r.event_position
			GROUP BY DATE_TRUNC('second', e.event_timestamp)
			ORDER BY time_bucket
			""";

		try (Connection conn = dataSource.getConnection();
		     Statement stmt = conn.createStatement();
		     ResultSet rs = stmt.executeQuery(query)) {

			ResultSetMetaData metaData = rs.getMetaData();
			int columnCount = metaData.getColumnCount();

			// Calculate column widths - scan all rows first
			int[] columnWidths = new int[columnCount];
			String[] columnNames = new String[columnCount];
			java.util.List<String[]> rows = new java.util.ArrayList<>();

			// Get column names and initialize widths
			for (int i = 0; i < columnCount; i++) {
				columnNames[i] = metaData.getColumnName(i + 1);
				columnWidths[i] = columnNames[i].length();
			}

			// Collect all rows and calculate column widths
			while (rs.next()) {
				String[] row = new String[columnCount];
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
					row[i] = valueStr;
					columnWidths[i] = Math.max(columnWidths[i], valueStr.length());
				}
				rows.add(row);
			}

			// Print separator before table
			System.out.println();
			System.out.println("=".repeat(120));
			System.out.println("LATENCY REPORT BY TIME BUCKET");
			System.out.println("=".repeat(120));

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

			// Print all rows
			for (String[] row : rows) {
				for (int i = 0; i < columnCount; i++) {
					System.out.print(String.format("%-" + columnWidths[i] + "s", row[i]));
					if (i < columnCount - 1) {
						System.out.print(" | ");
					}
				}
				System.out.println();
			}

			System.out.println("=".repeat(120));
			System.out.println("Total time buckets: " + rows.size());
			System.out.println();

			LOGGER.info("Time bucket report completed.");

		} catch (Exception e) {
			LOGGER.error("Failed to execute time bucket report query", e);
			throw new RuntimeException("Failed to execute time bucket report query", e);
		}
	}
}
