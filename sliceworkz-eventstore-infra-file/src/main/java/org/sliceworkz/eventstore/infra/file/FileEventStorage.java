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

import java.nio.file.Path;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.MeterOptions;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;
import org.sliceworkz.eventstore.spi.EventStorage;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * A single-process, embedded event storage backed by an append-only binary log.
 * <p>
 * One JVM owns a directory and is the only writer in it. That single constraint is what this storage
 * is built around, and everything interesting about it follows from that one fact.
 *
 * <h2>Why a single writer is the whole design</h2>
 * The PostgreSQL backend assigns {@code event_position} from a {@code bigserial}, which hands out the
 * number when the {@code INSERT} runs rather than when the transaction commits. Two consequences
 * follow, and between them they account for most of that backend's complexity: positions can be
 * gapped, and a lower position can become visible <em>after</em> a higher one. It answers both with a
 * second ordering column ({@code event_tx}, an {@code xid8}), a visibility barrier
 * ({@code event_tx < pg_snapshot_xmin(pg_current_snapshot())}) and a per-stream advisory lock so that
 * the consistency-boundary check and the insert cannot interleave.
 * <p>
 * A single writer holding one lock assigns a position <em>at</em> commit. Every event of one call
 * shares a transaction number and holds consecutive positions, and the transaction number strictly
 * increases between calls — so the transaction number is a monotone non-decreasing step function of
 * the position, ordering by {@code (tx, position)} and ordering by {@code position} are the same
 * order, and they cannot disagree. There is nothing to withhold from a reader, no gap to skip, and no
 * lock to take beyond the one the writer already holds.
 * <p>
 * References are still compared as the full {@code (tx, position)} tuple, because a caller can hand
 * this storage a reference it never issued — a reference from another stream, or from another store by
 * way of {@link org.sliceworkz.eventstore.migration.EventStoreImporter}. Monotonicity means such a
 * comparison is still upward-closed in the position, so a query boundary remains a binary search over
 * a sorted range whoever minted the reference.
 *
 * <h2>What it gives up to get that</h2>
 * Everything a database was doing for you. There is no second process — not a sidecar, not a reporting
 * tool, not a read replica, and not two instances briefly overlapping during a rolling deploy. There
 * is no ad-hoc query surface, no backup or point-in-time recovery beyond stopping the process and
 * copying the directory, no replication or failover, no authentication or network boundary, and no
 * out-of-band way to correct or prune data. Use {@code sliceworkz-eventstore-infra-postgres} for a
 * deployment that needs any of that; use this one where a single process owning its own data is the
 * whole requirement — an embedded application, an edge deployment, a desktop tool, a single-tenant
 * install, or a test that wants durability without a container.
 *
 * <h2>Example usage</h2>
 * <pre>{@code
 * // simplest form: a directory, and a store that owns everything it created
 * try ( EventStore eventStore = FileEventStorage.newBuilder()
 *         .directory("eventstore-data")
 *         .buildStore() ) {
 *     ...
 * }
 *
 * // development store: no flush per append, so a laptop test suite is not bounded by fsync
 * EventStorage storage = FileEventStorage.newBuilder()
 *         .directory(Path.of("/tmp/eventstore"))
 *         .durability(Durability.OS)
 *         .build();
 *
 * // with personal data protected by the shipped codec, keys beside the events
 * Path directory = Path.of("eventstore-data");
 * EventStore eventStore = FileEventStorage.newBuilder()
 *         .directory(directory)
 *         .shredding(new FileShreddingKeyStore(directory))
 *         .buildStore();
 * }</pre>
 *
 * <h2>Directory layout</h2>
 * <pre>
 * eventstore-data/
 *   LOCK                      held exclusively for the storage's lifetime
 *   MANIFEST                  format version and hints; never the source of truth
 *   events/
 *     0000000000.seg          rolled segments of the append-only log
 *     0000000001.seg
 *   bookmarks.log
 *   keys.bin                  only when a FileShreddingKeyStore is opened here
 * </pre>
 *
 * @see EventStorage
 * @see EventStore
 * @see Durability
 */
public interface FileEventStorage {

	/**
	 * Starts configuring a new file-backed storage.
	 *
	 * @return a fresh builder
	 */
	static Builder newBuilder ( ) {
		return Builder.newBuilder();
	}

	/**
	 * Fluent configuration for {@link FileEventStorage}.
	 */
	class Builder {

		/** 128 MiB, comfortably below the 2 GiB ceiling that keeps a within-segment offset an {@code int}. */
		static final long DEFAULT_SEGMENT_SIZE_BYTES = 128L * 1024 * 1024;

		private Path directory = Path.of("eventstore-data");
		private Limit limit = Limit.none();
		private Durability durability = Durability.SYNC;
		private long segmentSizeBytes = DEFAULT_SEGMENT_SIZE_BYTES;
		private MeterRegistry meterRegistry = Metrics.globalRegistry;
		private MeterOptions meterOptions = MeterOptions.defaults();
		private ShreddingCodec shreddingCodec;
		private String name = "file-%s".formatted(System.identityHashCode(this));

		private Builder ( ) {

		}

		static Builder newBuilder ( ) {
			return new Builder();
		}

		/**
		 * Sets the directory this storage owns.
		 * <p>
		 * The directory is created if it does not exist, and is locked exclusively for as long as the
		 * storage is open. A second storage on the same directory — in this JVM or another process —
		 * fails to build rather than corrupting the log.
		 *
		 * @param directory the directory to own
		 * @return this builder for method chaining
		 */
		public Builder directory ( Path directory ) {
			this.directory = directory;
			return this;
		}

		/**
		 * Sets the directory this storage owns.
		 *
		 * @param directory the directory to own
		 * @return this builder for method chaining
		 * @see #directory(Path)
		 */
		public Builder directory ( String directory ) {
			this.directory = Path.of(directory);
			return this;
		}

		/**
		 * Chooses how hard an append tries to be on disk before it returns.
		 * <p>
		 * Defaults to {@link Durability#SYNC}. Read {@link Durability#OS} before choosing it: it weakens
		 * what recovery can promise, not merely how much is lost.
		 *
		 * @param durability the durability mode
		 * @return this builder for method chaining
		 */
		public Builder durability ( Durability durability ) {
			this.durability = durability;
			return this;
		}

		/**
		 * Sets the size at which the log rolls to a new segment.
		 * <p>
		 * Defaults to 128 MiB. A segment must stay below 2 GiB, which is what lets an offset within one
		 * be an {@code int} — four bytes per event in the primary index rather than eight.
		 *
		 * @param segmentSizeBytes the roll threshold in bytes; must be positive and below 2 GiB
		 * @return this builder for method chaining
		 */
		public Builder segmentSize ( long segmentSizeBytes ) {
			this.segmentSizeBytes = segmentSizeBytes;
			return this;
		}

		/**
		 * Configures an absolute limit on the number of results any query may return.
		 *
		 * @param absoluteLimit the maximum number of events any query can return (must be positive)
		 * @return this builder for method chaining
		 */
		public Builder resultLimit ( int absoluteLimit ) {
			this.limit = Limit.to(absoluteLimit);
			return this;
		}

		/**
		 * Configures a name for this storage instance, used in meters and messages.
		 *
		 * @param name the name to assign; must not be null or blank
		 * @return this builder for method chaining
		 */
		public Builder name ( String name ) {
			this.name = name;
			return this;
		}

		/**
		 * Configures the Micrometer registry for the store returned by {@link #buildStore()}.
		 *
		 * @param meterRegistry the registry to publish to
		 * @return this builder for method chaining
		 */
		public Builder meterRegistry ( MeterRegistry meterRegistry ) {
			this.meterRegistry = meterRegistry;
			return this;
		}

		/**
		 * Configures how much detail the meters of the store returned by {@link #buildStore()} may carry.
		 * <p>
		 * Ignored by {@link #build()}, which returns a storage rather than a store — pass the options to
		 * {@link EventStoreFactory#eventStore(EventStorage, MeterRegistry, MeterOptions)} there instead.
		 *
		 * @param meterOptions how much detail the store's meters may carry
		 * @return this builder for method chaining
		 * @see MeterOptions
		 */
		public Builder meterOptions ( MeterOptions meterOptions ) {
			this.meterOptions = meterOptions;
			return this;
		}

		/**
		 * Protects the {@link org.sliceworkz.eventstore.shredding.Shreddable} values in this store's
		 * events with the shipped AES-256-GCM codec, holding keys in the given key store.
		 * <p>
		 * Pair a file-backed store with a key store that also survives a restart, or the events outlive
		 * the keys and every protected value reads as erased the next time the store opens.
		 * <p>
		 * {@code FileShreddingKeyStore} pointed at this
		 * storage's own directory is the convenient pairing, and it puts the keys beside the ciphertext
		 * they protect — so anyone with the directory has both. That is fine for development and tests;
		 * it is not a deployment posture. Where an erasure has to hold up against someone with the disk,
		 * keep keys somewhere that can actually destroy them.
		 * <p>
		 * The key store is the caller's to close.
		 *
		 * @param shreddingKeyStore where keys are minted, resolved and destroyed
		 * @return this builder for method chaining
		 */
		public Builder shredding ( ShreddingKeyStore shreddingKeyStore ) {
			this.shreddingCodec = AesGcmShreddingCodec.over(shreddingKeyStore);
			return this;
		}

		/**
		 * Protects personal data with a codec of your own, taking over encryption as well as key storage.
		 *
		 * @param shreddingCodec seals and unseals protected values
		 * @return this builder for method chaining
		 */
		public Builder shredding ( ShreddingCodec shreddingCodec ) {
			this.shreddingCodec = shreddingCodec;
			return this;
		}

		/**
		 * Opens the directory and returns the configured {@link EventStorage}.
		 * <p>
		 * Opening takes the directory lock, validates the format version, replays the log to rebuild the
		 * indexes, and repairs a torn tail left by an earlier crash. The caller owns the returned
		 * storage and must {@link EventStorage#close() close} it to release the lock.
		 *
		 * @return a storage owning the configured directory
		 * @throws org.sliceworkz.eventstore.spi.EventStorageException if the directory cannot be opened,
		 *         is already owned by another storage, or holds a log this release cannot read
		 */
		public EventStorage build ( ) {
			return new FileEventStorageImpl(directory, name, limit, durability, segmentSizeBytes);
		}

		/**
		 * Opens the directory and returns a fully configured {@link EventStore} that owns the storage.
		 * <p>
		 * The storage is created here and never handed to the caller, so closing the returned store is
		 * the only way it will ever be closed.
		 *
		 * @return an event store backed by a newly opened file storage
		 */
		public EventStore buildStore ( ) {
			EventStorage eventStorage = build();
			return EventStore.owning(
					EventStoreFactory.get().eventStore(eventStorage, meterRegistry, meterOptions, shreddingCodec),
					eventStorage);
		}
	}

}
