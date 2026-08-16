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
package org.sliceworkz.eventstore.infra.file;

/**
 * How hard an append tries to be on disk before it is reported as committed.
 * <p>
 * This is the one setting in this storage that trades correctness for speed, which is why it is an
 * explicit builder choice rather than a tuning parameter with a quietly convenient default.
 *
 * <h2>What neither mode can promise</h2>
 * No code running in a JVM can defeat a drive that lies about its write cache. {@link #SYNC} asks the
 * operating system to flush and waits for it to say that it has; if the hardware acknowledges a flush
 * it has not performed, the guarantee ends there. That is a property of the storage device, not of
 * this implementation, and it applies equally to every database that runs on it.
 */
public enum Durability {

	/**
	 * One flush per append, before the append returns. A committed event survives a power loss.
	 * <p>
	 * This is the default, and it is the mode the contract on
	 * {@link org.sliceworkz.eventstore.spi.EventStorage#append} is written against: when {@code append}
	 * returns, the events it returns are durable.
	 * <p>
	 * The cost is one flush per call, so a workload of many single-event appends is bounded by the
	 * device's flush latency rather than by anything in this library. Appending a batch costs the same
	 * single flush as appending one event, which is the reason {@code append} takes a list.
	 */
	SYNC,

	/**
	 * No flush. Records are written to the page cache and the operating system decides when they reach
	 * the device.
	 * <p>
	 * A committed event survives the JVM crashing, being killed, or throwing; it does not survive the
	 * machine losing power. Choose this only where the event log is reproducible from somewhere else —
	 * a test, a local development store, an import that will simply be re-run.
	 * <p>
	 * <strong>Recovery is weaker here in a way worth stating plainly.</strong> Several batches can be
	 * in flight in the page cache at once and land out of order, so after a power loss a
	 * <em>well-formed</em> batch can sit after a torn one. Recovery therefore discards the torn batch
	 * <em>and everything after it</em>. "This mode may lose recent appends" understates it: this mode
	 * may discard appends that did reach the disk, because the log has to stay a prefix of what was
	 * written to remain readable at all.
	 */
	OS

}
