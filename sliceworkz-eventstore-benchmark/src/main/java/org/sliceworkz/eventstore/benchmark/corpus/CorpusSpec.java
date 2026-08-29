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
package org.sliceworkz.eventstore.benchmark.corpus;

import java.util.List;

/**
 * What a store should contain: the input to provisioning, and the thing a corpus is identified by.
 *
 * <p>A corpus is expensive -- ten million events is minutes of bulk import -- and is shared by every
 * profile that asks for the same one, so this record has to be a complete and stable description of
 * the resulting data. Anything that changes what gets written belongs here, because the fingerprint
 * derived from it is what decides "already provisioned, reuse" against "build it again".
 *
 * @param volume how many events the context under test holds
 * @param streamDesign whether entities are separated by tag or by stream
 * @param composition what else shares the table and the database
 * @param payload how large and how heavily tagged the events are
 * @param entityCount how many distinct SKUs, baskets and customers the volume is spread over, which
 *        is what decides tag selectivity -- and, under {@link StreamDesign#PER_ENTITY}, how many
 *        distinct stream purposes exist
 * @param neighbourVolumes the sizes of the other prefixed stores to create alongside, used only by
 *        {@link Composition#MULTI_STORE} and {@link Composition#BOTH}
 * @param seed the source of every random choice the generator makes; two provisionings of one spec
 *        must produce identical stores, which is what makes reusing one defensible
 */
public record CorpusSpec (
		long volume,
		StreamDesign streamDesign,
		Composition composition,
		PayloadProfile payload,
		int entityCount,
		List<Long> neighbourVolumes,
		long seed ) {

	/** How entities are separated within a context. */
	public enum StreamDesign {
		/**
		 * One stream per bounded context, entities told apart by tags. Reads lean on the
		 * {@code (stream_context, stream_purpose, event_tags)} GIN index; the btree prefix is
		 * unselective because every event in the context shares it.
		 */
		TAGGED,
		/**
		 * One stream per entity, the purpose being the entity id. The btree
		 * {@code (context, purpose, tx, position)} prefix becomes highly selective and GIN is barely
		 * used. Also the design that walks into the {@code purpose} meter cardinality cap, which is
		 * part of what makes measuring it worthwhile.
		 */
		PER_ENTITY
	}

	/** What else is in the way. */
	public enum Composition {
		/** Only the context under test. The control. */
		CLEAN,
		/**
		 * The other five contexts too, in the <b>same table</b>, at a multiple of the volume. This is
		 * the one that moves index selectivity, table size and BRIN correlation -- i.e. the one that
		 * actually slows a query down.
		 */
		MULTI_DOMAIN,
		/**
		 * Other prefixed stores, which are <b>other tables</b> in the same database. A different
		 * mechanism entirely: shared buffers, WAL, autovacuum and the cluster-wide notification queue,
		 * but not one row of extra work for any query. Reported separately for that reason.
		 */
		MULTI_STORE,
		/** Both, which is what a real multi-tenant deployment looks like. */
		BOTH
	}

	/**
	 * How heavy an event is.
	 *
	 * <p>Two independent things vary here, and it is worth being precise about which, because the
	 * names suggest one axis and there are really two: <b>tag count</b>, which is what the GIN index
	 * works through and what the {@code text[]} column costs, and <b>body size</b>, which is what the
	 * serializer and TOAST cost. {@code SLIM} and {@code WIDE_TAGS} vary only the first and have
	 * identical bodies; {@code FAT} varies the second.
	 *
	 * <p>Every provisioned corpus records its <em>measured</em> mean payload size, so a report states
	 * what a profile actually produced rather than repeating the adjective in its name.
	 */
	public enum PayloadProfile {
		/** One tag -- the entity's own. The floor for tag cost; the body is the ordinary domain event. */
		SLIM,
		/** Four tags: the entity, plus channel, country and warehouse. The realistic case. */
		REALISTIC,
		/**
		 * Twelve tags on an otherwise realistic body. Tag count is what the GIN index works through, so
		 * this isolates that cost from body size.
		 */
		WIDE_TAGS,
		/**
		 * Large bodies: forty-line orders, with the sales event mix biased so most events are such
		 * orders. Measures about 2.9 KB mean against 127 bytes for {@link #REALISTIC}. Inventory events
		 * stay small under this profile, because a stock movement genuinely is small -- so this measures
		 * "a store whose sales context holds big documents", not "every event is large".
		 */
		FAT,
		/** A realistic body with two {@code Shreddable} components bound to different subjects. */
		SHREDDED,
		/** Written as legacy types, so every read pays for an upcast. */
		LEGACY
	}

	/** The three volume tiers the suite is built around. */
	public static final long TIER_SMALL = 1_000L;
	public static final long TIER_MEDIUM = 100_000L;
	public static final long TIER_LARGE = 10_000_000L;

	/** How much noise {@link Composition#MULTI_DOMAIN} adds, as a multiple of {@link #volume()}. */
	public static final int NOISE_MULTIPLIER = 5;

	public CorpusSpec {
		if ( volume <= 0 ) {
			throw new IllegalArgumentException("a corpus needs a positive volume, was " + volume);
		}
		if ( streamDesign == null ) {
			streamDesign = StreamDesign.TAGGED;
		}
		if ( composition == null ) {
			composition = Composition.CLEAN;
		}
		if ( payload == null ) {
			payload = PayloadProfile.REALISTIC;
		}
		if ( entityCount <= 0 ) {
			throw new IllegalArgumentException("a corpus needs a positive entityCount, was " + entityCount);
		}
		if ( entityCount > volume ) {
			// otherwise most entities hold no events at all and every "find this entity's history"
			// benchmark measures an empty result
			throw new IllegalArgumentException(
					"entityCount (%d) cannot exceed volume (%d): most entities would have no events"
							.formatted(entityCount, volume));
		}
		neighbourVolumes = neighbourVolumes == null ? List.of() : List.copyOf(neighbourVolumes);
		if ( neighbourVolumes.stream().anyMatch(v -> v == null || v <= 0) ) {
			throw new IllegalArgumentException("neighbour volumes must all be positive");
		}
	}

	/** Whether this corpus writes contexts other than the one under test into the same table. */
	public boolean hasNoiseContexts ( ) {
		return composition == Composition.MULTI_DOMAIN || composition == Composition.BOTH;
	}

	/** Whether this corpus creates further prefixed stores beside its own. */
	public boolean hasNeighbourStores ( ) {
		return composition == Composition.MULTI_STORE || composition == Composition.BOTH;
	}

	/** Whether opening a store over this corpus requires a shredding codec. */
	public boolean requiresShredding ( ) {
		return payload == PayloadProfile.SHREDDED;
	}

	/** Roughly how many events this will write in total, noise contexts included. */
	public long totalEventsInOwnStore ( ) {
		return hasNoiseContexts() ? volume * ( 1 + NOISE_MULTIPLIER ) : volume;
	}

	/**
	 * Which properties differ from another spec, named one per line.
	 *
	 * <p>What makes a comparison between two corpora readable. Two fingerprints differ or they do not,
	 * and neither says which knob was turned -- so a "stream design shootout" result would otherwise
	 * come with no statement of what it varied, which is precisely the sort of number this suite exists
	 * not to produce.
	 */
	public List<String> differencesFrom ( CorpusSpec other ) {
		List<String> differences = new java.util.ArrayList<>();
		if ( volume != other.volume ) {
			differences.add("volume: %,d vs %,d".formatted(volume, other.volume));
		}
		if ( streamDesign != other.streamDesign ) {
			differences.add("stream design: %s vs %s".formatted(streamDesign, other.streamDesign));
		}
		if ( composition != other.composition ) {
			differences.add("composition: %s vs %s".formatted(composition, other.composition));
		}
		if ( payload != other.payload ) {
			differences.add("payload: %s vs %s".formatted(payload, other.payload));
		}
		if ( entityCount != other.entityCount ) {
			differences.add("entities: %,d vs %,d".formatted(entityCount, other.entityCount));
		}
		if ( !neighbourVolumes.equals(other.neighbourVolumes) ) {
			differences.add("neighbour stores: %s vs %s".formatted(neighbourVolumes, other.neighbourVolumes));
		}
		if ( seed != other.seed ) {
			// not an experimental variable but a confound: two seeds produce different data, so a
			// difference between them is partly the data rather than the property under test
			differences.add("seed: %d vs %d (different data, not a property under test)".formatted(seed, other.seed));
		}
		return differences;
	}

	/**
	 * The canonical string the fingerprint is taken over. Written out by hand rather than derived from
	 * {@code toString()} so that a change to a record's rendering cannot silently invalidate every
	 * provisioned corpus -- or, worse, silently fail to.
	 */
	public String canonicalForm ( ) {
		return "v1|volume=%d|design=%s|composition=%s|payload=%s|entities=%d|neighbours=%s|seed=%d".formatted(
				volume, streamDesign, composition, payload, entityCount,
				neighbourVolumes.stream().map(String::valueOf).reduce(( a, b ) -> a + "," + b).orElse(""),
				seed);
	}
}
