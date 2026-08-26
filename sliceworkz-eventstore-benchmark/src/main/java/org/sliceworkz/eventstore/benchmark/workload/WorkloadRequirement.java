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
package org.sliceworkz.eventstore.benchmark.workload;

import java.util.Optional;
import java.util.Set;

import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec.PayloadProfile;

/**
 * What a workload needs in order to mean anything, so a nonsensical pairing fails at setup rather
 * than producing a number.
 *
 * <p>Two properties matter enough to be modelled:
 *
 * <ul>
 *   <li>{@link #mutatesStore()} tells the JMH layer whether the corpus has to be restored between
 *       iterations. Getting this wrong in the safe direction costs a restore; getting it wrong the
 *       other way lets a store grow under a benchmark that assumes a fixed size.</li>
 *   <li>{@link #requiredPayloads()} catches the pairings that would silently measure nothing -- an
 *       upcasting workload against a corpus holding no legacy events reads current events and reports
 *       an upcasting cost of zero, which looks like good news.</li>
 *   <li>{@link #distinctCompanions()} catches the same failure one level down. A workload that widens
 *       its filter with companion entities needs that many <em>distinct</em> ones, and the companion
 *       band is a fraction of the entity space -- so on a small corpus
 *       {@link WorkloadContext#companionEntity} wraps and the same tag appears twice in one filter.
 *       Nothing fails: {@code append-or-groups-10} against a 20-entity corpus quietly measures six
 *       distinct facts spelled as ten disjuncts, and reports it as ten. Measured, that is the
 *       difference between "a decision resting on ten facts" and "a longer statement".</li>
 * </ul>
 *
 * @param mutatesStore whether invoking this writes events
 * @param requiredPayloads payload profiles this workload needs; empty means any will do
 * @param requiresSql whether this needs a SQL-backed target (plan capture, raw statements)
 * @param distinctCompanions how many distinct companion entities this needs; 0 for none
 */
public record WorkloadRequirement (
		boolean mutatesStore,
		Set<PayloadProfile> requiredPayloads,
		boolean requiresSql,
		int distinctCompanions ) {

	public WorkloadRequirement {
		requiredPayloads = requiredPayloads == null ? Set.of() : Set.copyOf(requiredPayloads);
		if ( distinctCompanions < 0 ) {
			throw new IllegalArgumentException("distinctCompanions must not be negative");
		}
	}

	/** A read-only workload that works against any corpus. */
	public static WorkloadRequirement readOnly ( ) {
		return new WorkloadRequirement(false, Set.of(), false, 0);
	}

	/** A workload that appends events, so the corpus grows and has to be restored. */
	public static WorkloadRequirement mutating ( ) {
		return new WorkloadRequirement(true, Set.of(), false, 0);
	}

	/** A read-only workload that only means something against particular payload profiles. */
	public static WorkloadRequirement readOnlyOn ( PayloadProfile... payloads ) {
		return new WorkloadRequirement(false, Set.of(payloads), false, 0);
	}

	/**
	 * A mutating workload that widens its filter over {@code companions} distinct companion entities.
	 *
	 * <p>Declaring the number is what makes an undersized corpus a refusal rather than a quieter
	 * measurement of a different thing -- see {@link #distinctCompanions()}.
	 */
	public static WorkloadRequirement mutatingOverCompanions ( int companions ) {
		return new WorkloadRequirement(true, Set.of(), false, companions);
	}

	/**
	 * Why this workload cannot run against the given corpus, or empty if it can.
	 *
	 * @return a sentence naming the mismatch, suitable for putting in front of a person
	 */
	public Optional<String> rejectionFor ( CorpusSpec spec ) {
		if ( !requiredPayloads.isEmpty() && !requiredPayloads.contains(spec.payload()) ) {
			return Optional.of("needs a corpus with payload profile %s but this one is %s"
					.formatted(requiredPayloads, spec.payload()));
		}
		int available = WorkloadContext.companionCapacity(spec.entityCount());
		if ( distinctCompanions > available ) {
			return Optional.of(
					"needs %d distinct companion entities and this corpus offers %d (%d entities); raise entityCount to at least %d"
							.formatted(distinctCompanions, available, spec.entityCount(),
									WorkloadContext.entityCountFor(distinctCompanions)));
		}
		return Optional.empty();
	}
}
