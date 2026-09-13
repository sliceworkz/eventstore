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
package org.sliceworkz.eventstore.migration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.ImportMode;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.spi.EventToImport;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Copies events from one {@link EventStorage} into another, preserving event identity, timestamps and
 * idempotency keys.
 * <p>
 * The importer streams the source in batches, optionally passes each event through a transformation, and
 * hands the result to {@link EventStorage#importEvents(List, ImportMode)}. It works purely at the storage
 * level: payloads travel as opaque JSON, so no domain classes are needed on the classpath, no upcasting
 * happens, and legacy event types arrive as legacy event types.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * ImportReport report = EventStoreImporter.from(sourceStorage).to(targetStorage)
 *     .mode(ImportMode.SKIP_EXISTING_ID)
 *     .transform(src -> Optional.of(EventToImport.from(src)
 *                          .withStream(EventStreamId.forContext("archive").withPurpose(src.stream().purpose()))))
 *     .batchSize(1000)
 *     .onProgress(r -> LOGGER.info("import progress: {}", r))
 *     .run();
 * }</pre>
 *
 * <h2>Selecting what to copy</h2>
 * By default every event in the source is read. {@link #stream(EventStreamId)} and
 * {@link #matching(EventFilter)} narrow that to one stream, or to the types and tags of a filter, and the
 * selection is handed to the storage query rather than applied to what comes back: on an indexed backend the
 * run then costs what the selected events cost, not what the whole source costs. That is what makes the
 * importer an archiving tool — copying a closed period, or one logical stream, into a cold store:
 * <pre>{@code
 * // the events of one logical stream
 * EventStoreImporter.from(live).to(cold)
 *     .stream(EventStreamId.forContext("ledger").withPurpose("2024Q1"))
 *     .run();
 *
 * // every event carrying a tag, whatever its stream
 * EventStoreImporter.from(live).to(cold)
 *     .matching(EventFilter.forEvents(EventTypesFilter.any(), Tags.of("period", "2024Q1")))
 *     .run();
 * }</pre>
 * The alternative — dropping the unwanted events from the transformation — reads the whole source to
 * discard most of it, which is a full pass over the table on every run. Use the transformation for what
 * a storage query cannot express; use the selection for what it can. The two compose: the transformation
 * sees only the selected events. What a selection does <em>not</em> do is touch the source: an archive is
 * a copy, and removing the copied range from the source is a separate, deliberate act.
 *
 * <h2>What it does and does not guarantee</h2>
 * <ul>
 *   <li><b>Order is preserved, ordering numbers are not.</b> Events are read and written in source order,
 *       but position and transaction are always assigned by the target.</li>
 *   <li><b>Bounded at the source head.</b> The source's last event is captured before the first read and
 *       every read is bounded by it, so the run covers a fixed range. Events appended to the source during
 *       the run are excluded; a later run started {@link #after(EventReference)} that boundary picks them
 *       up. This is also what makes it safe to import a store into itself.</li>
 *   <li><b>Not atomic overall.</b> Each batch commits on its own. A failure part-way leaves the batches
 *       already written in the target. Re-running with {@link ImportMode#SKIP_EXISTING_ID} continues from
 *       whatever landed.</li>
 *   <li><b>Nothing is verified.</b> {@link ImportMode#SKIP_EXISTING_ID} matches on identifier alone and no
 *       payload is ever read back. If a migration needs to be proven faithful, that is a separate job.</li>
 *   <li><b>One importer at a time per target.</b> The conflict check and the insert are not held under a
 *       common lock, so concurrent runs against one target can produce spurious conflicts.</li>
 * </ul>
 *
 * <h2>Checking a target up front</h2>
 * There is no dry-run mode. An application that wants to know in advance whether a target already holds
 * some of the events can look them up through the public API — but must do so in <em>raw</em> mode:
 * <pre>{@code
 * // raw: no event root classes registered, therefore no upcasting
 * EventStream<?> probe = eventStore.getEventStream(EventStreamId.anyContext());
 * boolean present = !probe.getEventById(id).isEmpty();
 * }</pre>
 * Registering domain classes would run the event through upcasting, and an event whose upcast yields no
 * current events comes back as an empty list even though it exists — a false negative. Such a check is
 * also only a snapshot: nothing stops the target changing before the import runs.
 *
 * @see EventStorage#importEvents(List, ImportMode)
 * @see EventToImport
 * @see ImportReport
 */
public final class EventStoreImporter {

	/**
	 * Events read, transformed and written per batch. Also the transaction granularity of the import,
	 * since each batch is one {@link EventStorage#importEvents(List, ImportMode)} call.
	 */
	public static final int DEFAULT_BATCH_SIZE = 1000;

	private final EventStorage source;
	private EventStorage target;
	private ImportMode mode = ImportMode.FAIL_ON_EXISTING_ID;
	private EventStreamId stream;
	private EventFilter filter = EventFilter.matchAll();
	private EventReference after;
	private Function<StoredEvent,Optional<EventToImport>> transform = storedEvent -> Optional.of(EventToImport.from(storedEvent));
	private int batchSize = DEFAULT_BATCH_SIZE;
	private Consumer<ImportReport> onProgress = report -> { };

	private EventStoreImporter ( EventStorage source ) {
		this.source = source;
	}

	/**
	 * Starts configuring an import reading from the given storage.
	 *
	 * @param source the storage to read events from (required)
	 * @return an importer to configure further
	 * @throws IllegalArgumentException if source is null
	 */
	public static EventStoreImporter from ( EventStorage source ) {
		if ( source == null ) {
			throw new IllegalArgumentException("source storage is required");
		}
		return new EventStoreImporter(source);
	}

	/**
	 * Sets the storage to write events into.
	 * <p>
	 * May be the same storage as the source, which — combined with a transformation that mints new
	 * identifiers — clones events within one store.
	 *
	 * @param target the storage to import into (required)
	 * @return this importer
	 * @throws IllegalArgumentException if target is null
	 */
	public EventStoreImporter to ( EventStorage target ) {
		if ( target == null ) {
			throw new IllegalArgumentException("target storage is required");
		}
		this.target = target;
		return this;
	}

	/**
	 * Sets how the target should treat an event whose identifier it already holds.
	 * <p>
	 * Defaults to {@link ImportMode#FAIL_ON_EXISTING_ID}.
	 *
	 * @param mode the import mode (required)
	 * @return this importer
	 * @throws IllegalArgumentException if mode is null
	 */
	public EventStoreImporter mode ( ImportMode mode ) {
		if ( mode == null ) {
			throw new IllegalArgumentException("import mode is required");
		}
		this.mode = mode;
		return this;
	}

	/**
	 * Reads only the events of the given stream instead of every stream in the source.
	 * <p>
	 * A wildcard stream — {@link EventStreamId#anyPurpose()} for every purpose of a context,
	 * {@link EventStreamId#anyContext()} for every stream — is accepted and scopes accordingly. The scope
	 * is part of the storage query, so a backend answers it from its stream index rather than reading
	 * everything and discarding the rest. Combines with {@link #matching(EventFilter)}: an event is read
	 * when it is on this stream <em>and</em> matches the filter.
	 * <p>
	 * The scope selects what is read; it says nothing about where the events land. Imported events keep
	 * their source stream unless the transformation moves them.
	 *
	 * @param stream the stream to read, possibly a wildcard (required)
	 * @return this importer
	 * @throws IllegalArgumentException if stream is null
	 */
	public EventStoreImporter stream ( EventStreamId stream ) {
		if ( stream == null ) {
			throw new IllegalArgumentException("stream is required, omit the call to read every stream");
		}
		this.stream = stream;
		return this;
	}

	/**
	 * Reads only the events matching the given filter instead of every event in the source.
	 * <p>
	 * The filter's types and tags become part of the storage query, so a backend answers it from its
	 * indexes rather than reading everything and discarding the rest. Event types are matched by their
	 * stored name, so a legacy type is selected by its legacy name — nothing is upcast on this path.
	 * <p>
	 * A filter carrying an {@link EventFilter#until() until} reference bounds the run at that reference
	 * when it is earlier than the source head, and {@link ImportReport#sourceTo()} then reports it, so a
	 * later run started {@link #after(EventReference)} that report continues correctly. A filter matching
	 * nothing reads nothing.
	 *
	 * @param filter the types, tags and optional boundary to select on (required)
	 * @return this importer
	 * @throws IllegalArgumentException if filter is null
	 */
	public EventStoreImporter matching ( EventFilter filter ) {
		if ( filter == null ) {
			throw new IllegalArgumentException("filter is required, omit the call to read every event");
		}
		this.filter = filter;
		return this;
	}

	/**
	 * Starts reading after the given source reference instead of at the beginning of the source.
	 * <p>
	 * Pass {@link ImportReport#sourceTo()} of an earlier run to import only what the source has
	 * accumulated since. Null means start at the beginning.
	 *
	 * @param after the source reference to resume after, or null to start at the beginning
	 * @return this importer
	 */
	public EventStoreImporter after ( EventReference after ) {
		this.after = after;
		return this;
	}

	/**
	 * Sets the transformation applied to each source event on its way to the target.
	 * <p>
	 * Returning an empty {@link Optional} drops the event. The default keeps every event exactly as it is.
	 * The transformation may rewrite anything {@link EventToImport} exposes — stream, tags, payload, type,
	 * identifier, timestamp — so it doubles as a remapping and schema-migration hook.
	 * <p>
	 * Two consequences worth keeping in mind. Rewriting identifiers makes
	 * {@link ImportMode#SKIP_EXISTING_ID} meaningless, since a re-run recognises nothing. And collapsing
	 * several source streams onto one target stream can turn idempotency keys that were previously scoped
	 * apart into a conflict.
	 *
	 * @param transform the transformation to apply (required)
	 * @return this importer
	 * @throws IllegalArgumentException if transform is null
	 */
	public EventStoreImporter transform ( Function<StoredEvent,Optional<EventToImport>> transform ) {
		if ( transform == null ) {
			throw new IllegalArgumentException("transform is required, omit the call to keep events unchanged");
		}
		this.transform = transform;
		return this;
	}

	/**
	 * Sets how many events are read, transformed and written per batch.
	 * <p>
	 * Also the transaction granularity: one batch is one import call on the target, and therefore the unit
	 * that commits or rolls back together. Defaults to {@link #DEFAULT_BATCH_SIZE}.
	 *
	 * @param batchSize the batch size, must be greater than 0
	 * @return this importer
	 * @throws IllegalArgumentException if batchSize is not positive
	 */
	public EventStoreImporter batchSize ( int batchSize ) {
		if ( batchSize <= 0 ) {
			throw new IllegalArgumentException("batch size must be larger than 0");
		}
		this.batchSize = batchSize;
		return this;
	}

	/**
	 * Registers a callback invoked after every batch with a running total.
	 * <p>
	 * The only progress signal the importer offers: this module carries no logging dependency. The callback
	 * runs on the importing thread, so it should stay cheap.
	 *
	 * @param onProgress the callback to invoke after each batch (required)
	 * @return this importer
	 * @throws IllegalArgumentException if onProgress is null
	 */
	public EventStoreImporter onProgress ( Consumer<ImportReport> onProgress ) {
		if ( onProgress == null ) {
			throw new IllegalArgumentException("progress callback is required, omit the call for no progress reporting");
		}
		this.onProgress = onProgress;
		return this;
	}

	/**
	 * Runs the import and returns what it did.
	 *
	 * @return the final report
	 * @throws IllegalStateException if no target storage was configured
	 * @throws org.sliceworkz.eventstore.spi.EventImportConflictException if the target already holds a conflicting event
	 * @throws org.sliceworkz.eventstore.spi.EventStorageException if reading or writing fails
	 * @throws UnsupportedOperationException if the target storage does not support importing
	 */
	public ImportReport run ( ) {
		if ( target == null ) {
			throw new IllegalStateException("no target storage configured, call to(...) before run()");
		}

		long startedAt = System.nanoTime();

		// Fix the range before writing anything. Without this an import into its own source would keep
		// finding the events it just wrote and never terminate. A filter carrying its own, earlier
		// boundary narrows the range further; it never widens it past the head.
		EventReference boundary = boundedBy(filter, headOf(source));
		Optional<EventStreamId> scope = Optional.ofNullable(stream);
		EventReference cursor = after;

		long read = 0;
		long dropped = 0;
		long imported = 0;
		long skipped = 0;
		EventReference firstTargetReference = null;
		EventReference lastTargetReference = null;

		if ( boundary != null && ( cursor == null || cursor.happenedBefore(boundary) ) ) {

			EventQuery pageQuery = new EventQuery(filter.until(boundary), EventQuery.Direction.FORWARD, Limit.none());

			while ( true ) {
				List<StoredEvent> page = source.query(pageQuery, scope, cursor, Limit.to(batchSize), QueryDirection.FORWARD).toList();
				if ( page.isEmpty() ) {
					break;
				}
				cursor = page.getLast().reference();
				read += page.size();

				List<EventToImport> batch = new ArrayList<>(page.size());
				for ( StoredEvent storedEvent : page ) {
					Optional<EventToImport> transformed = transform.apply(storedEvent);
					if ( transformed == null || transformed.isEmpty() ) {
						dropped++;
					} else {
						batch.add(transformed.get());
					}
				}

				if ( !batch.isEmpty() ) {
					List<StoredEvent> written = target.importEvents(batch, mode);
					imported += written.size();
					// under SKIP_EXISTING_ID the target returns only what it actually inserted
					skipped += batch.size() - written.size();
					if ( !written.isEmpty() ) {
						if ( firstTargetReference == null ) {
							firstTargetReference = written.getFirst().reference();
						}
						lastTargetReference = written.getLast().reference();
					}
				}

				onProgress.accept(new ImportReport(read, dropped, imported, skipped, after, boundary, firstTargetReference, lastTargetReference, elapsedSince(startedAt)));
			}
		}

		return new ImportReport(read, dropped, imported, skipped, after, boundary, firstTargetReference, lastTargetReference, elapsedSince(startedAt));
	}

	/**
	 * Returns the reference of the last event in the storage, or null when it holds none.
	 */
	private static EventReference headOf ( EventStorage storage ) {
		return storage.head(Optional.empty()).orElse(null);
	}

	/**
	 * Returns the boundary a run is bounded by: the source head, or the filter's own {@code until} when
	 * that is earlier. Null when the source holds nothing, whatever the filter says — there is nothing
	 * to read then, and the report should say so rather than name a boundary the source never reached.
	 */
	private static EventReference boundedBy ( EventFilter filter, EventReference head ) {
		if ( head == null ) {
			return null;
		}
		return filter.untilIfEarlier(head).until();
	}

	private static Duration elapsedSince ( long startedAtNanos ) {
		return Duration.ofNanos(System.nanoTime() - startedAtNanos);
	}

}
