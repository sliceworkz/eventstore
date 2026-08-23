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
package org.sliceworkz.eventstore.benchmark;

/**
 * Entry point of the benchmark suite.
 *
 * <p>The suite is a capacity-characterisation harness, not a regression gate: it exists to answer
 * "how does this store behave at N events, with M concurrent writers, under these query shapes, in a
 * store that also holds other domains" with numbers that can be published and reproduced.
 *
 * <p>Work is split over four subcommands because their costs differ by orders of magnitude:
 *
 * <dl>
 *   <dt>{@code provision}</dt>
 *   <dd>builds the corpora a profile needs, or reports that they are already there.  A ten-million
 *       event corpus is minutes of bulk import; it is content-addressed and reused across runs, so
 *       this is paid once rather than per measurement.</dd>
 *   <dt>{@code jmh}</dt>
 *   <dd>operation-level measurement against a provisioned corpus.</dd>
 *   <dt>{@code load}</dt>
 *   <dd>sustained load against a <em>growing</em> store, which is the part JMH cannot host: offered
 *       rate, latency percentiles, conflict rates and the two live-latency scenarios.</dd>
 *   <dt>{@code report}</dt>
 *   <dd>renders a run, and diffs it against a committed baseline.</dd>
 * </dl>
 */
public final class Main {

	private Main ( ) { }

	public static void main ( String[] args ) throws Exception {
		if ( args.length == 0 ) {
			usage();
			System.exit(2);
		}

		switch ( args[0] ) {
			case "help", "--help", "-h" -> usage();
			default -> {
				System.err.println("unknown subcommand '%s'".formatted(args[0]));
				usage();
				System.exit(2);
			}
		}
	}

	private static void usage ( ) {
		System.out.println("""
			sliceworkz eventstore benchmark suite

			  provision --profile=<name>     build (or reuse) the corpora a profile needs
			  jmh       --profile=<name>     run the profile's JMH benchmarks
			  load      --profile=<name>     run the profile's load scenarios
			  report    [--baseline=<path>]  render a run, optionally diffing a baseline
			  list                           list the available profiles
			""");
	}
}
