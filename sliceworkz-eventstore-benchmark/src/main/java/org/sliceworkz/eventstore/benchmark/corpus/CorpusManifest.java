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

import java.time.Instant;

/**
 * The record of a provisioned corpus: what was asked for, what was built, and by which version of
 * the generator.
 *
 * <p>It exists so that "reuse the corpus already in the database" can be a decision rather than an
 * assumption. Three fields carry that weight:
 *
 * <ul>
 *   <li>{@code fingerprint} says which spec this data answers -- it is the table prefix, so a
 *       mismatch is impossible by construction;</li>
 *   <li>{@code generatorVersion} says which code wrote it, so a change to payload shaping or tag
 *       distribution invalidates the corpus instead of silently being measured as if it had not
 *       happened;</li>
 *   <li>{@code eventCount} is checked against the real row count before reuse, which is what catches
 *       a provisioning run that died half way and left a store that looks present and is short.</li>
 * </ul>
 *
 * @param fingerprint the corpus fingerprint, which is also its table prefix
 * @param specJson the full spec as JSON, so a database can be read without the profile that made it
 * @param generatorVersion {@link CorpusFingerprint#GENERATOR_VERSION} at the time of writing
 * @param provisionedAt when provisioning finished
 * @param eventCount how many events were written
 * @param factsJson the {@link CorpusFacts} as JSON
 */
public record CorpusManifest (
		String fingerprint,
		String specJson,
		int generatorVersion,
		Instant provisionedAt,
		long eventCount,
		String factsJson ) {

	/**
	 * Whether this manifest describes a corpus that can be reused as-is.
	 *
	 * @param actualEventCount the row count read from the store right now
	 */
	public boolean isUsable ( long actualEventCount ) {
		return generatorVersion == CorpusFingerprint.GENERATOR_VERSION && eventCount == actualEventCount;
	}

	/** Why this manifest cannot be reused, for a log line that explains a rebuild. */
	public String reasonNotUsable ( long actualEventCount ) {
		if ( generatorVersion != CorpusFingerprint.GENERATOR_VERSION ) {
			return "written by generator version %d, this is version %d"
					.formatted(generatorVersion, CorpusFingerprint.GENERATOR_VERSION);
		}
		if ( actualEventCount > eventCount ) {
			// Two different faults produce a mismatch, and saying "provisioning did not finish" for both
			// sent a reader looking at the generator when the cause was a benchmark that appended and
			// did not put the corpus back.
			return "manifest says %d events but the store holds %d: something appended to this corpus and left it that way"
					.formatted(eventCount, actualEventCount);
		}
		if ( eventCount != actualEventCount ) {
			return "manifest says %d events but the store holds only %d, so provisioning did not finish"
					.formatted(eventCount, actualEventCount);
		}
		return "usable";
	}
}
