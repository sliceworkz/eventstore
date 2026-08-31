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
package org.sliceworkz.eventstore.benchmark.report;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.testing.backend.PostgresContainer;

/**
 * Where the plans {@code auto_explain} writes can be read back from.
 *
 * <p><b>This exists because the capture worked on exactly the targets whose numbers are not
 * publishable.</b> It read the Testcontainers log, so a container run got real plans and an external
 * run got none -- and {@code EXTERNAL} is the only target the publisher accepts. Every plan in every
 * published baseline was therefore a <em>reconstruction</em>: a statement the report writes itself to
 * imitate the store's, with the tag arrays inlined as literals where the store binds them as
 * parameters. Those are the plans that have already been caught reporting an execution time the whole
 * measured operation fits inside, and the ones now carrying the {@code large-tier-writes} finding --
 * a DCB check that appears to sequentially scan ten million rows to return nothing. A finding that
 * size cannot rest on the weaker half of the evidence.
 *
 * <p>Two ways in, tried in that order:
 *
 * <ul>
 *   <li><b>The container's log</b>, for a Testcontainers target -- unchanged, needs nothing.</li>
 *   <li><b>The server's own log file</b>, read through {@code pg_read_file}, for anything else. The
 *       server names its current file with {@code pg_current_logfile()} and {@code pg_stat_file}
 *       gives its size, so a capture can mark a position and read only what was appended after it --
 *       the same shape as the container path, and it does not drag a day of unrelated logging back
 *       over the wire.</li>
 * </ul>
 *
 * <p><b>What the second one needs, and why it is worth asking for.</b> {@code logging_collector} must
 * be on (otherwise the server has no file of its own to name, and {@code pg_current_logfile()}
 * answers null), and the benchmark's role needs {@code pg_read_server_files} -- or superuser, which
 * amounts to the same thing and is more than this needs. {@code GRANT pg_read_server_files TO
 * <role>} on a benchmark host is a fair price for turning the suite's headline finding from an
 * inference into a fact, and {@code doctor} now says up front whether the grant is there.
 *
 * <p>Every failure here is reported as "no plans", never as a failed run: this is evidence the report
 * would like to carry, not something a measurement depends on.
 */
public interface ServerLog {

	Logger LOGGER = LoggerFactory.getLogger(ServerLog.class);

	/** How much log there is now, as an opaque mark to hand back to {@link #since}. */
	long mark ( );

	/** Whatever the server logged after the given mark. */
	String since ( long mark );

	/** How this log is being read, for the report to say which evidence it is showing. */
	String describe ( );

	/**
	 * The log for a target, or empty when this process cannot read one.
	 *
	 * @param image the container image tag whose log to read, or null for a server this process did
	 *        not start
	 */
	static Optional<ServerLog> of ( String image, DataSource dataSource ) {
		if ( image != null ) {
			return Optional.of(new ContainerLog(image));
		}
		return dataSource == null ? Optional.empty() : ReadableServerFile.open(dataSource);
	}

	/** The log of a container this process started, which it can read without asking the server. */
	final class ContainerLog implements ServerLog {

		private final String image;

		ContainerLog ( String image ) {
			this.image = image;
		}

		@Override
		public long mark ( ) {
			return PostgresContainer.logs(image).length();
		}

		@Override
		public String since ( long mark ) {
			String log = PostgresContainer.logs(image);
			return mark >= log.length() ? "" : log.substring((int) mark);
		}

		@Override
		public String describe ( ) {
			return "the container's log";
		}
	}

	/** The server's own log file, read back through {@code pg_read_file}. */
	final class ReadableServerFile implements ServerLog {

		/**
		 * Never read more than this in one go.
		 *
		 * <p>A capture marks the file and reads what follows, so the slice is normally a handful of
		 * plans. The cap is for the case where something else is writing to the same log -- a chatty
		 * {@code log_statement}, another application on the same server -- and keeps a plan capture from
		 * pulling tens of megabytes of somebody else's logging across the connection.
		 */
		private static final long MAX_SLICE_BYTES = 64L * 1024 * 1024;

		private final DataSource dataSource;

		private ReadableServerFile ( DataSource dataSource ) {
			this.dataSource = dataSource;
		}

		/**
		 * Opens the server's log, or empty with a reason logged.
		 *
		 * <p>Probes rather than assumes: it reads one byte, so a missing {@code logging_collector} or a
		 * missing grant is discovered here -- once, with a message naming which -- rather than as an
		 * empty plan section at the end of an hour-long run.
		 */
		static Optional<ServerLog> open ( DataSource dataSource ) {
			try ( Connection connection = dataSource.getConnection();
					Statement statement = connection.createStatement();
					ResultSet row = statement.executeQuery("SELECT pg_current_logfile()") ) {
				if ( !row.next() || row.getString(1) == null ) {
					LOGGER.info("this server collects no log file of its own (logging_collector is off),"
							+ " so the report will carry no plans captured from the store's own statements");
					return Optional.empty();
				}
			} catch ( SQLException e ) {
				LOGGER.info("could not ask this server for its log file, so the report will carry no"
						+ " captured plans: {}", e.getMessage());
				return Optional.empty();
			}

			ReadableServerFile log = new ReadableServerFile(dataSource);
			try {
				log.readSlice(log.currentFile(), 0, 1);
			} catch ( SQLException e ) {
				LOGGER.info("this role may not read the server's log file, so the report will carry no"
						+ " captured plans -- GRANT pg_read_server_files TO <role> enables them: {}",
						e.getMessage());
				return Optional.empty();
			}
			return Optional.of(log);
		}

		@Override
		public long mark ( ) {
			try {
				return size(currentFile());
			} catch ( SQLException e ) {
				LOGGER.debug("could not measure the server log, so this capture starts from its head", e);
				return 0;
			}
		}

		@Override
		public String since ( long mark ) {
			try {
				String file = currentFile();
				long size = size(file);
				// A rotation between the mark and here leaves the new file shorter than the mark, and the
				// plans this capture wants are at its head rather than after that offset. Reading the whole
				// of the new file is the only answer that does not silently return nothing.
				long from = mark > size ? 0 : mark;
				long length = Math.min(size - from, MAX_SLICE_BYTES);
				return length <= 0 ? "" : readSlice(file, from, length);
			} catch ( SQLException e ) {
				LOGGER.debug("could not read the server log", e);
				return "";
			}
		}

		@Override
		public String describe ( ) {
			return "the server's own log file";
		}

		private String currentFile ( ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
					Statement statement = connection.createStatement();
					ResultSet row = statement.executeQuery("SELECT pg_current_logfile()") ) {
				return row.next() ? row.getString(1) : null;
			}
		}

		private long size ( String file ) throws SQLException {
			if ( file == null ) {
				return 0;
			}
			try ( Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"SELECT size FROM pg_stat_file(?, true)") ) {
				statement.setString(1, file);
				try ( ResultSet row = statement.executeQuery() ) {
					return row.next() ? row.getLong(1) : 0;
				}
			}
		}

		/**
		 * Reads a slice of a server-side file.
		 *
		 * <p>{@code pg_read_binary_file} rather than {@code pg_read_file}, and the bytes are decoded
		 * here: the text form fails outright on a byte sequence that is not valid in the server
		 * encoding, and a log carrying one line of someone else's mangled output would otherwise take
		 * the whole capture with it.
		 */
		private String readSlice ( String file, long offset, long length ) throws SQLException {
			if ( file == null ) {
				return "";
			}
			try ( Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"SELECT pg_read_binary_file(?, ?, ?, true)") ) {
				statement.setString(1, file);
				statement.setLong(2, offset);
				statement.setLong(3, length);
				try ( ResultSet row = statement.executeQuery() ) {
					if ( !row.next() ) {
						return "";
					}
					byte[] bytes = row.getBytes(1);
					return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
				}
			}
		}
	}
}
