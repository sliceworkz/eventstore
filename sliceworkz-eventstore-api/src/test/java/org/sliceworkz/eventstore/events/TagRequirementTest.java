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
package org.sliceworkz.eventstore.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class TagRequirementTest {

	private static final Tags TWO_ACCOUNTS = Tags.of(Tag.of("account", "A"), Tag.of("account", "B"), Tag.of("transfer", "t1"));

	@Test
	void ofMeansOneOrMore ( ) {
		TagRequirement accounts = TagRequirement.of("account");

		assertEquals(new TagRequirement("account", 1, TagRequirement.UNBOUNDED), accounts);
		assertEquals("1 or more", accounts.occurrence());
		assertTrue(accounts.isMetBy(TWO_ACCOUNTS));
		assertFalse(accounts.isMetBy(Tags.none()));
	}

	@Test
	void theNarrowingsSayWhatTheyMean ( ) {
		TagRequirement accounts = TagRequirement.of("account");

		assertEquals(new TagRequirement("account", 1, 1), accounts.exactlyOne());
		assertEquals(new TagRequirement("account", 2, 2), accounts.exactly(2));
		assertEquals(new TagRequirement("account", 1, 3), accounts.between(1, 3));
		assertEquals(new TagRequirement("account", 0, 1), accounts.atMostOne());

		assertEquals("exactly 1", accounts.exactlyOne().occurrence());
		assertEquals("exactly 2", accounts.exactly(2).occurrence());
		assertEquals("between 1 and 3", accounts.between(1, 3).occurrence());
		assertEquals("at most 1", accounts.atMostOne().occurrence());
	}

	@Test
	void aRequirementCountsTheTagsUnderItsKeyOnly ( ) {
		assertTrue(TagRequirement.of("account").exactly(2).isMetBy(TWO_ACCOUNTS));
		assertFalse(TagRequirement.of("account").exactlyOne().isMetBy(TWO_ACCOUNTS));
		assertTrue(TagRequirement.of("transfer").exactlyOne().isMetBy(TWO_ACCOUNTS));
		assertTrue(TagRequirement.of("channel").atMostOne().isMetBy(TWO_ACCOUNTS));
		assertFalse(TagRequirement.of("account").atMostOne().isMetBy(TWO_ACCOUNTS));
	}

	@Test
	void aCountIsOfDistinctTags ( ) {
		Tags selfTransfer = Tags.of(Tag.of("account", "A"), Tag.of("account", "A"));

		assertFalse(TagRequirement.of("account").exactly(2).isMetBy(selfTransfer));
		assertTrue(TagRequirement.of("account").between(1, 2).isMetBy(selfTransfer));
	}

	@Test
	void aKeyNoTagCanCarryIsRefused ( ) {
		assertThrows(IllegalArgumentException.class, () -> TagRequirement.of(null));
		assertThrows(IllegalArgumentException.class, () -> TagRequirement.of(""));
		assertThrows(IllegalArgumentException.class, () -> TagRequirement.of(" account"));
		assertThrows(IllegalArgumentException.class, () -> TagRequirement.of("account:from"));
	}

	@Test
	void aFrameworkTagIsRefused ( ) {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> TagRequirement.of("x-actor"));
		assertTrue(refused.getMessage().contains("x-actor"));
	}

	@Test
	void anOccurrenceThatCannotBeMetIsRefused ( ) {
		assertThrows(IllegalArgumentException.class, () -> new TagRequirement("account", -1, 1));
		assertThrows(IllegalArgumentException.class, () -> new TagRequirement("account", 0, 0));
		assertThrows(IllegalArgumentException.class, () -> TagRequirement.of("account").between(3, 2));
		assertThrows(IllegalArgumentException.class, () -> TagRequirement.of("account").exactly(0));
	}

	record Transfer ( String from, String to ) implements Tagged {
		@Override
		public Tags eventTags ( ) {
			return Tags.of(Tag.of("account", from), Tag.of("account", to));
		}

		@Override
		public Set<TagRequirement> tagRequirements ( ) {
			return Set.of(TagRequirement.of("account").exactly(2), TagRequirement.of("channel").exactlyOne());
		}
	}

	@Test
	void unmetRequirementsAreDescribedInKeyOrder ( ) {
		Transfer transfer = new Transfer("A", "A");
		Optional<String> unmet = Tagged.unmetTagRequirements(transfer, Tagged.tagsStatedBy(transfer));

		assertEquals(Optional.of("'account' tags required: exactly 2, found 1 [account:A]; 'channel' tags required: exactly 1, found 0"), unmet);
	}

	@Test
	void metRequirementsAndUntaggedDataDescribeNothing ( ) {
		Transfer transfer = new Transfer("A", "B");

		assertEquals(Optional.empty(), Tagged.unmetTagRequirements(transfer, Tagged.tagsStatedBy(transfer).merge(Tags.of("channel", "atm"))));
		assertEquals(Optional.empty(), Tagged.unmetTagRequirements("not tagged", Tags.none()));
		assertEquals(Tags.none(), Tagged.tagsStatedBy("not tagged"));
	}

}
