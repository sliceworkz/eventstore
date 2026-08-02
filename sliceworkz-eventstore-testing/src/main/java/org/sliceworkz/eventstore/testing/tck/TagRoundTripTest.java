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
package org.sliceworkz.eventstore.testing.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;

/**
 * A tag written to a stream is found by a query for that same tag, and comes back off the event
 * unchanged — for every tag {@link Tag} lets you construct.
 * <p>
 * This is not as obvious as it looks, because a tag does not travel as a pair. The PostgreSQL backend
 * flattens it to {@link Tag#toString()} — {@code "key:value"} — stores that in a {@code text[]}
 * column, matches a query with {@code event_tags @> ARRAY[...]} built from the same rendering, and
 * hands it back through {@link Tags#parse(String[])}. The string form is the wire format, and it is
 * unescaped. So the property under test is a triangle: what is stored, what is matched, and what is
 * read back all have to agree, and the in-memory backends — which never flatten anything, and so
 * cannot fail the way a text backend can — have to agree with them.
 * <p>
 * {@link Tag}'s constructor is what makes this hold: it rejects the shapes whose rendering is
 * ambiguous (a {@code ':'} in the key, surrounding whitespace, an empty half), which leaves
 * {@code toString}/{@code parse} a bijection. Before that, {@code Tag.of("k", "")} was stored as
 * {@code "k:"}, read back as {@code Tag.of("k")}, and <em>not</em> found by a query for
 * {@code Tag.of("k")} — an event visibly carrying a tag that a query for that tag could not find,
 * with nothing raised anywhere. The cases below are therefore all legal tags; the illegal ones are
 * pinned down in {@code TagTest}, where they now throw at construction.
 *
 * @see Tag
 */
public class TagRoundTripTest extends AbstractEventStoreTest {

	/** Every shape a caller may construct, including the ones that are awkward to render. */
	private static final List<Tag> TAGS = List.of(
			Tag.of("customer", "123"),
			Tag.of("important"),                                  // key only, no value
			Tag.of(null, "keyless"),                              // renders as ":keyless"
			Tag.of("url", "https://example.org:8443/a:b"),        // colons in the value are fine
			Tag.of("first name", "Jan Van Roey"),                 // whitespace inside, not around
			Tag.of("json", "{\"a\":1,\"b\":[2]}"),
			Tag.of("punctuation", "a/b\\c,d;e'f\"g|h"),
			Tag.of("unicode", "élève-中文-🎉"),
			Tag.of("multiline", "line\nbreak"),
			Tag.of("percent", "100%_x"),
			Tag.of("sql", "'); DROP TABLE events; --"),
			Tag.of("brace", "{a,b}"),                             // reads as an array literal in text[]
			Tag.of("long", "x".repeat(1000)));

	private EventStream<MockDomainEvent> streamFor ( String purpose ) {
		return eventStore().getEventStream(
				EventStreamId.forContext("tag-roundtrip").withPurpose(purpose), MockDomainEvent.class);
	}

	private static List<Event<MockDomainEvent>> queryFor ( EventStream<MockDomainEvent> stream, Tag tag ) {
		return stream.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of(tag))).toList();
	}

	@ForEachBackend
	void everyConstructibleTagIsFoundByAQueryForItselfAndComesBackUnchanged ( ) {

		EventStream<MockDomainEvent> stream = streamFor("each");

		for ( int i = 0; i < TAGS.size(); i++ ) {
			Tag tag = TAGS.get(i);
			// a discriminator so each event is identifiable, and so a query has to select on the tag
			// under test rather than simply returning the only event in the stream
			Tags tags = Tags.of(tag, Tag.of("seq", i));
			stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("event " + i), tags));
		}

		for ( int i = 0; i < TAGS.size(); i++ ) {
			Tag tag = TAGS.get(i);

			List<Event<MockDomainEvent>> found = queryFor(stream, tag);

			assertEquals(1, found.size(),
					"a query for the tag just written found " + found.size() + " events instead of 1: " + tag);
			assertEquals("event " + i, ((FirstDomainEvent) found.getFirst().data()).value(),
					"the query for " + tag + " matched the wrong event");

			// and the tag survives the trip back: same key, same value, no stripping, no re-splitting
			Tags readBack = found.getFirst().tags();
			assertEquals(2, readBack.tags().size(),
					"the event should carry both its tag and its discriminator, got " + readBack.tags());
			assertTrue(readBack.tags().contains(tag),
					"the tag read off the event is not the tag that was written: wrote " + tag
							+ ", read " + readBack.tags());
		}
	}

	@ForEachBackend
	void distinctTagsOnOneEventStayDistinct ( ) {

		// tags whose renderings are close enough to collide if the encoding were ambiguous:
		// "a:b:c" is what Tag.of("a:b","c") would have rendered to, and Tag.of("a:b","c") is now
		// rejected precisely so that this one is unambiguous
		Tags tags = Tags.of(
				Tag.of("a", "b:c"),
				Tag.of("a", "b"),
				Tag.of("a"),
				Tag.of("ab", "c"));

		EventStream<MockDomainEvent> stream = streamFor("distinct");
		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("multi"), tags));

		for ( Tag tag : tags.tags() ) {
			assertEquals(1, queryFor(stream, tag).size(), "the event should be found by its tag " + tag);
		}

		Tags readBack = stream.query(EventQuery.matchAll()).toList().getFirst().tags();
		assertEquals(tags, readBack, "four distinct tags must not collapse on the way through storage");
	}

	@ForEachBackend
	void aKeyOnlyTagIsNotAPrefixOfAKeyValueTag ( ) {

		// documented and deliberate: matching is exact containment, never key-prefix. Users reasonably
		// expect Tag.of("customer") to act as "any customer"; it does not, on any backend.
		EventStream<MockDomainEvent> stream = streamFor("prefix");

		stream.append(AppendCriteria.none(),
				Event.of(new FirstDomainEvent("with value"), Tags.of(Tag.of("customer", "123"))));
		stream.append(AppendCriteria.none(),
				Event.of(new FirstDomainEvent("flag only"), Tags.of(Tag.of("customer"))));

		List<Event<MockDomainEvent>> byKeyOnly = queryFor(stream, Tag.of("customer"));
		assertEquals(1, byKeyOnly.size(), "a key-only tag matches only events carrying the key-only tag");
		assertEquals("flag only", ((FirstDomainEvent) byKeyOnly.getFirst().data()).value());

		List<Event<MockDomainEvent>> byKeyValue = queryFor(stream, Tag.of("customer", "123"));
		assertEquals(1, byKeyValue.size(), "a key-value tag matches only events carrying that exact tag");
		assertEquals("with value", ((FirstDomainEvent) byKeyValue.getFirst().data()).value());

		assertEquals(0, queryFor(stream, Tag.of("customer", "456")).size(),
				"a tag nobody wrote must match nothing");
	}
}
