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
package org.sliceworkz.eventstore.infra.file.log;

/**
 * Bytes on disk that do not decode as the record they claim to be.
 * <p>
 * This is deliberately <em>not</em> an
 * {@link org.sliceworkz.eventstore.spi.EventStorageException}, because during recovery it is not an
 * error at all: a half-written record at the very end of the log is the expected shape of a crash, and
 * the scan turns this into a truncation. It only becomes a storage failure when it is thrown from
 * somewhere that had already been told the record was intact — reading a record the log committed —
 * and the caller is responsible for making that distinction explicit.
 */
public class MalformedRecordException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Constructs an exception describing what did not decode.
	 *
	 * @param message what was expected and what was found
	 */
	public MalformedRecordException ( String message ) {
		super(message);
	}

	/**
	 * Constructs an exception describing what did not decode, keeping the underlying failure.
	 *
	 * @param message what was expected and what was found
	 * @param cause the failure that surfaced it
	 */
	public MalformedRecordException ( String message, Throwable cause ) {
		super(message, cause);
	}

}
