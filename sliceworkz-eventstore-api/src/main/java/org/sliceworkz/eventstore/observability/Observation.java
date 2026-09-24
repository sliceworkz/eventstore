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
package org.sliceworkz.eventstore.observability;

import java.util.Map;
import java.util.Optional;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.shredding.ErasureReason;

/**
 * An operation about to be performed, handed to {@link EventStoreObserver#start(Observation)}.
 * <p>
 * Each record describes what the operation was asked to do; its type parameter is the {@link Outcome}
 * it reports when it completes, so a scope started for an {@link Append} can only complete with an
 * {@link Outcome.AppendResult}. The records carry the caller's own arguments — the filter a caller
 * queried with, not the one widened to the legacy types storage is asked for — since that is what a
 * reader of a trace recognises.
 * <p>
 * The interface is sealed, so an implementation can switch over it exhaustively. An operation added in a
 * later version is a further permitted record: an implementation that switches with a {@code default}
 * branch keeps compiling and handles it generically, one without it is told at compile time.
 * <p>
 * What an observation carries is the implementation's to record or not. Tag values in a query's filter
 * and a data subject's id are meant to be pseudonymous (see {@link org.sliceworkz.eventstore.events.Tag}
 * and {@link org.sliceworkz.eventstore.shredding.DataSubject}), but a tracing implementation putting them
 * into span attributes is shipping them to its tracing backend, and should decide so deliberately.
 *
 * @param <O> what the operation reports when it completes
 * @see EventStoreObserver
 * @see Outcome
 */
public sealed interface Observation<O extends Outcome> {

	/**
	 * The name of the storage the operation runs against.
	 *
	 * @return the storage's name
	 */
	String storage ( );

	/**
	 * The operations performed through a stream, which all carry it.
	 *
	 * @param <O> what the operation reports when it completes
	 */
	sealed interface OnStream<O extends Outcome> extends Observation<O> {

		/**
		 * The stream the operation runs through.
		 *
		 * @return the stream
		 */
		StreamInfo stream ( );

		@Override
		default String storage ( ) {
			return stream().storage();
		}

	}

	/**
	 * An append of a batch of events.
	 *
	 * @param stream the stream appended to
	 * @param submittedPerType how many events of each type the batch carries, by the name each is stored under
	 * @param conditional whether the append checks a consistency boundary ({@code AppendCriteria} other than none)
	 * @param idempotencyKeys how many events of the batch carry an idempotency key
	 */
	record Append ( StreamInfo stream, Map<EventType, Integer> submittedPerType, boolean conditional, int idempotencyKeys ) implements OnStream<Outcome.AppendResult> {
		public Append {
			submittedPerType = Map.copyOf(submittedPerType);
		}
	}

	/**
	 * A read of stored events: a query or a page, a projector's pages among them.
	 *
	 * @param stream the stream read through
	 * @param filter the caller's filter
	 * @param limit how many stored events the read may return
	 * @param direction the direction of the read
	 * @param after the cursor the read starts after, if any
	 */
	record Query ( StreamInfo stream, EventFilter filter, Limit limit, EventQuery.Direction direction, Optional<EventReference> after ) implements OnStream<Outcome.Read> { }

	/**
	 * A lookup of one stored event by its id.
	 *
	 * @param stream the stream looked through
	 * @param id the id looked up
	 */
	record GetEvent ( StreamInfo stream, EventId id ) implements OnStream<Outcome.Found> { }

	/**
	 * A lookup of the stream's head: the pin of a consistency boundary.
	 *
	 * @param stream the stream whose head is looked up
	 */
	record Head ( StreamInfo stream ) implements OnStream<Outcome.HeadRead> { }

	/**
	 * A bookmark placed for a reader.
	 *
	 * @param stream the stream the bookmark is placed through
	 * @param reader the reader
	 * @param reference where the bookmark is placed
	 */
	record PlaceBookmark ( StreamInfo stream, String reader, EventReference reference ) implements OnStream<Outcome.Done> { }

	/**
	 * A bookmark read for a reader.
	 *
	 * @param stream the stream the bookmark is read through
	 * @param reader the reader
	 */
	record GetBookmark ( StreamInfo stream, String reader ) implements OnStream<Outcome.Found> { }

	/**
	 * Every bookmark of the storage, listed.
	 *
	 * @param stream the stream the bookmarks are listed through
	 */
	record ListBookmarks ( StreamInfo stream ) implements OnStream<Outcome.Counted> { }

	/**
	 * One batch of a {@link org.sliceworkz.eventstore.projection.Projector}: a page read, its events handed
	 * to the projection, the batch committed, the bookmark placed. The page query and the bookmark placement
	 * are observations of their own, started inside this one's scope.
	 *
	 * @param stream the stream the projector reads from
	 * @param projection the projection, by its class's simple name
	 * @param reader the bookmark reader, if the projector is bookmarked
	 * @param phase whether this is the savepoint read of {@code initQuery()} or a page of {@code eventQuery()}
	 * @param batchSize how many stored events the batch may read
	 * @param after the cursor the batch starts after, if any
	 */
	record ProjectorBatch ( StreamInfo stream, String projection, Optional<String> reader, Phase phase, Limit batchSize, Optional<EventReference> after ) implements OnStream<Outcome.Projected> {

		/**
		 * Which part of a projector run a batch belongs to.
		 */
		public enum Phase {
			/** The savepoint read of {@code Projection.initQuery()}, run once before the first batch. */
			INIT,
			/** A page of {@code Projection.eventQuery()}. */
			BATCH
		}

	}

	/**
	 * An erasure of a data subject's keys.
	 *
	 * @param storage the storage of the store erasing
	 * @param subjectType the subject's type
	 * @param subjectId the subject's id, pseudonymous by the rule {@link org.sliceworkz.eventstore.shredding.DataSubject} sets
	 * @param category the one category erased, or empty for every category of the subject
	 * @param reason the recorded authority for the erasure
	 */
	record Erase ( String storage, String subjectType, String subjectId, Optional<String> category, ErasureReason reason ) implements Observation<Outcome.Erased> { }

	/**
	 * One observed operation, current on the caller's thread from {@link EventStoreObserver#start(Observation)}
	 * to {@link #close()}.
	 * <p>
	 * The store calls exactly one of {@link #completed(Outcome)} and {@link #failed(Throwable)}, then
	 * {@link #close()} in a {@code finally}, all on the thread that started the operation.
	 *
	 * @param <O> what the operation reports when it completes
	 */
	interface Scope<O extends Outcome> extends AutoCloseable {

		/**
		 * The operation answered. For an append that includes a consistency-boundary conflict and a retry
		 * swallowed whole: see {@link Outcome.AppendResult}.
		 *
		 * @param outcome what the operation answered
		 */
		void completed ( O outcome );

		/**
		 * The operation could not answer: a storage error, an event that cannot be read, a key store that is
		 * down, an idempotency-key conflict, a projection that threw. The throwable is the one the caller
		 * receives.
		 *
		 * @param failure what the operation failed with
		 */
		void failed ( Throwable failure );

		/**
		 * Ends the observation. Declared without a checked exception, unlike {@link AutoCloseable#close()}.
		 */
		@Override
		void close ( );

	}

}
