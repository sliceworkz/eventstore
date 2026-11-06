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
package org.sliceworkz.eventstore.examples.documentversions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.MergeSource;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.ParagraphTextReplaced;
import org.sliceworkz.eventstore.examples.documentversions.DocumentVersions.DocumentVersion;
import org.sliceworkz.eventstore.examples.documentversions.DocumentVersions.VersionSummary;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Versions of a document, branched and merged, on every backend.
 * <p>
 * The reads that matter here are the ones with filter items bounded at different points of the history,
 * which PostgreSQL answers with a branch per item merged in order — so every scenario writes the versions
 * interleaved, the way people working in parallel do, and a second document beside the first, which no
 * read may pick up.
 */
public class DocumentVersionsTest extends AbstractEventStoreTest {

	private static final String DOC = "plan";

	private EventStream<DocumentEvent> stream;
	private DocumentVersions documents;

	/** The reference of the event that added "Scope" on main: the state the draft goes back to. */
	private EventReference scopeAdded;

	@BeforeEach
	void createDocument ( ) {
		stream = eventStore().getEventStream(EventStreamId.forContext("documents"), DocumentEvent.class);
		documents = new DocumentVersions(stream);

		documents.create(DOC, "main", "Plan");
		documents.create("other", "main", "Another document");
		documents.addParagraph(DOC, "main", "p1", null, "Intro");
		documents.addParagraph("other", "main", "x1", null, "Not part of the plan");
		documents.addParagraph(DOC, "main", "p2", "p1", "Scope");
		scopeAdded = documents.read(DOC, "main").latest().reference();
		documents.addParagraph(DOC, "main", "p3", "p2", "Budget");
	}

	@ForEachBackend
	void aBranchSharesTheHistoryUpToItsForkPointAndAddsItsOwn ( ) {
		documents.branch(DOC, "main", scopeAdded, "draft");
		// interleaved: main moves on while the draft is worked on
		documents.replaceText(DOC, "draft", "p1", "Introduction");
		documents.replaceText(DOC, "main", "p3", "Budget: 10k");
		documents.addParagraph(DOC, "draft", "p4", "p1", "Risks");
		documents.changeTitle(DOC, "main", "Plan v2");

		DocumentVersion main = documents.read(DOC, "main");
		assertEquals("Plan v2", main.document().title());
		assertEquals(List.of("Intro", "Scope", "Budget: 10k"), main.document().texts());
		assertEquals(1, main.history().items().size(), "main is its own events only");

		DocumentVersion draft = documents.read(DOC, "draft");
		assertEquals("Plan", draft.document().title());
		assertEquals(List.of("Introduction", "Risks", "Scope"), draft.document().texts());
		assertEquals(Set.of("main", "draft"), draft.bounds().keySet());
		assertEquals(scopeAdded.id(), draft.bounds().get("main").id(), "main contributes up to the fork point");
		assertNull(draft.bounds().get("draft"), "the draft contributes all of its own events");
		assertEquals(2, draft.history().items().size(), "one item per version, read in one query");
	}

	@ForEachBackend
	void aBranchOfABranchFollowsTheWholeChain ( ) {
		documents.branch(DOC, "main", scopeAdded, "a");
		documents.addParagraph(DOC, "a", "a1", "p2", "From a");
		documents.addParagraph(DOC, "main", "m1", "p3", "Main only");
		EventReference a1 = documents.read(DOC, "a").latest().reference();
		documents.addParagraph(DOC, "a", "a2", "a1", "Later in a");
		documents.branch(DOC, "a", a1, "b");
		documents.addParagraph(DOC, "b", "b1", "a1", "From b");
		documents.addParagraph(DOC, "a", "a3", "a2", "Even later in a");

		DocumentVersion b = documents.read(DOC, "b");
		assertEquals(List.of("Intro", "Scope", "From a", "From b"), b.document().texts());
		assertEquals(Set.of("main", "a", "b"), b.bounds().keySet());
		assertEquals(3, b.history().items().size());
	}

	@ForEachBackend
	void aForkPointInAnAncestorsEventsBranchesFromThatAncestor ( ) {
		documents.branch(DOC, "main", null, "a");
		documents.addParagraph(DOC, "a", "a1", "p3", "From a");
		// branched "from a", at an event a inherited from main: the parent is main
		documents.branch(DOC, "a", scopeAdded, "early");

		VersionSummary early = documents.versions(DOC).stream().filter(v -> v.version().equals("early")).findFirst().orElseThrow();
		assertEquals("main", early.parent());
		assertEquals(List.of("Intro", "Scope"), documents.read(DOC, "early").document().texts());
	}

	@ForEachBackend
	void aMergeTakesTheOtherSideInWithoutCopyingIt ( ) {
		branchDraftAndWorkOnBoth();
		int before = stream.query(EventFilter.forTags(DocumentVersions.DOCUMENT, DOC)).size();

		documents.merge(DOC, "main", List.of(new MergeSource("draft", null)));

		assertEquals(before + 1, stream.query(EventFilter.forTags(DocumentVersions.DOCUMENT, DOC)).size(), "one merge event, nothing copied");
		DocumentVersion main = documents.read(DOC, "main");
		assertEquals("Plan v2", main.document().title());
		assertEquals(List.of("Introduction", "Risks", "Scope", "Budget: 10k"), main.document().texts());
		assertEquals(Set.of("main", "draft"), main.bounds().keySet());
		assertEquals(List.of("Introduction", "Risks", "Scope"), documents.read(DOC, "draft").document().texts(), "the draft is untouched");

		// both go on; a second merge takes only what is new on the draft
		documents.replaceText(DOC, "draft", "p4", "Risks and mitigations");
		documents.replaceText(DOC, "main", "p2", "Scope: phase one");
		documents.merge(DOC, "main", List.of(new MergeSource("draft", null)));
		assertEquals(List.of("Introduction", "Risks and mitigations", "Scope: phase one", "Budget: 10k"),
				documents.read(DOC, "main").document().texts());
	}

	@ForEachBackend
	void aMergeTakesASourceUpToTheEventItNames ( ) {
		documents.branch(DOC, "main", scopeAdded, "draft");
		documents.replaceText(DOC, "draft", "p1", "Introduction");
		EventReference introduced = documents.read(DOC, "draft").latest().reference();
		documents.addParagraph(DOC, "draft", "p4", "p1", "Risks");

		documents.merge(DOC, "main", List.of(new MergeSource("draft", introduced)));

		assertEquals(List.of("Introduction", "Scope", "Budget"), documents.read(DOC, "main").document().texts());
	}

	@ForEachBackend
	void twoVersionsAreMergedAtOnce ( ) {
		documents.branch(DOC, "main", null, "left");
		documents.branch(DOC, "main", null, "right");
		documents.addParagraph(DOC, "left", "l1", "p1", "Left");
		documents.replaceText(DOC, "right", "p3", "Budget: right");
		documents.changeTitle(DOC, "main", "Plan (merged)");

		documents.merge(DOC, "main", List.of(new MergeSource("left", null), new MergeSource("right", null)));

		DocumentVersion main = documents.read(DOC, "main");
		assertEquals("Plan (merged)", main.document().title());
		assertEquals(List.of("Intro", "Left", "Scope", "Budget: right"), main.document().texts());
		assertEquals(Set.of("main", "left", "right"), main.bounds().keySet());
	}

	@ForEachBackend
	void aMergeOfChangesToTheSameParagraphIsRefusedAndAppendsNothing ( ) {
		documents.branch(DOC, "main", null, "rewrite");
		documents.replaceText(DOC, "rewrite", "p2", "Scope: phase one");
		documents.replaceText(DOC, "main", "p2", "Scope: everything");
		EventReference head = stream.head().orElseThrow();

		DocumentRejectedException refused = assertThrows(DocumentRejectedException.class,
				( ) -> documents.merge(DOC, "main", List.of(new MergeSource("rewrite", null))));

		assertEquals(Set.of("paragraph:p2"), refused.conflicts());
		assertEquals(head, stream.head().orElseThrow(), "nothing appended");
	}

	@ForEachBackend
	void twoInsertsAtOneAnchorOrAnInsertAfterARemovedParagraphConflict ( ) {
		documents.branch(DOC, "main", null, "insert");
		documents.addParagraph(DOC, "insert", "i1", "p2", "Inserted after scope");
		documents.addParagraph(DOC, "main", "m1", "p2", "Also after scope");
		assertEquals(Set.of("anchor:p2"), assertThrows(DocumentRejectedException.class,
				( ) -> documents.merge(DOC, "main", List.of(new MergeSource("insert", null)))).conflicts());

		documents.branch(DOC, "main", scopeAdded, "late");
		documents.addParagraph(DOC, "late", "l1", "p1", "After intro");
		documents.removeParagraph(DOC, "main", "p1");
		assertTrue(assertThrows(DocumentRejectedException.class,
				( ) -> documents.merge(DOC, "main", List.of(new MergeSource("late", null)))).conflicts().contains("anchor:p1"));
	}

	@ForEachBackend
	void aMergeOfNothingNewIsRefused ( ) {
		documents.branch(DOC, "main", null, "idle");
		assertThrows(DocumentRejectedException.class, ( ) -> documents.merge(DOC, "main", List.of(new MergeSource("idle", null))));
	}

	@ForEachBackend
	void aBranchFromBeforeAMergeDoesNotSeeWhatWasMerged ( ) {
		branchDraftAndWorkOnBoth();
		EventReference beforeMerge = documents.read(DOC, "main").latest().reference();
		documents.merge(DOC, "main", List.of(new MergeSource("draft", null)));

		documents.branch(DOC, "main", beforeMerge, "unmerged");
		documents.branch(DOC, "main", null, "merged");

		assertEquals(List.of("Intro", "Scope", "Budget: 10k"), documents.read(DOC, "unmerged").document().texts());
		assertEquals(Set.of("main", "unmerged"), documents.read(DOC, "unmerged").bounds().keySet());
		assertEquals(List.of("Introduction", "Risks", "Scope", "Budget: 10k"), documents.read(DOC, "merged").document().texts());
		assertEquals(Set.of("main", "draft", "merged"), documents.read(DOC, "merged").bounds().keySet());
	}

	@ForEachBackend
	void everyReadIsExactlyItsFilterOverTheWholeDocument ( ) {
		branchDraftAndWorkOnBoth();
		documents.merge(DOC, "main", List.of(new MergeSource("draft", null)));
		documents.branch(DOC, "draft", null, "nested");
		documents.addParagraph(DOC, "nested", "n1", "p4", "Nested");
		documents.replaceText(DOC, "main", "p2", "Scope (main)");
		documents.addParagraph(DOC, "draft", "p5", "p4", "Draft goes on");
		documents.merge(DOC, "main", List.of(new MergeSource("nested", null)));

		List<Event<DocumentEvent>> everything = stream.query(EventFilter.matchAll());
		for ( VersionSummary summary : documents.versions(DOC) ) {
			DocumentVersion version = documents.read(DOC, summary.version());
			List<EventId> expected = everything.stream().filter(version.history()::matches).map(e -> e.reference().id()).toList();
			List<EventId> read = version.events().stream().map(e -> e.reference().id()).toList();
			assertEquals(expected, read, "the storage's answer for " + summary.version());
			assertTrue(version.events().stream().allMatch(e -> e.tags().containsAll(Tags.of(DocumentVersions.DOCUMENT, DOC))),
					"nothing of another document");
		}
		assertEquals(List.of("Introduction", "Risks", "Nested", "Scope (main)", "Budget: 10k"), documents.read(DOC, "main").document().texts());
		assertEquals(List.of("Introduction", "Risks", "Draft goes on", "Scope"), documents.read(DOC, "draft").document().texts());
	}

	@ForEachBackend
	void anEditConflictsWithAConcurrentEditOfTheSameVersionOnly ( ) {
		documents.branch(DOC, "main", scopeAdded, "draft");
		DocumentVersion main = documents.read(DOC, "main");
		DocumentVersion draft = documents.read(DOC, "draft");

		// the boundary an edit is appended under is the version's own events: ancestors are fixed
		assertEquals(1, AppendCriteria.of(draft.history(), draft.head()).eventFilter().items().size());

		documents.replaceText(DOC, "draft", "p1", "Introduction");
		// decided on main as read before that edit: the draft's edit is no new fact for main
		append(main, new ParagraphTextReplaced("p3", "Budget: 10k"));

		// but an edit on main since main was read is
		documents.changeTitle(DOC, "main", "Plan v2");
		assertThrows(OptimisticLockingException.class, ( ) -> append(main, new ParagraphTextReplaced("p3", "Budget: 20k")));
	}

	@ForEachBackend
	void aMergeIntoAVersionConflictsWithADecisionOnThatVersion ( ) {
		documents.branch(DOC, "main", scopeAdded, "draft");
		documents.replaceText(DOC, "draft", "p1", "Introduction");
		DocumentVersion main = documents.read(DOC, "main");

		documents.merge(DOC, "main", List.of(new MergeSource("draft", null)));

		assertThrows(OptimisticLockingException.class, ( ) -> append(main, new ParagraphTextReplaced("p1", "Intro, again")));
	}

	@ForEachBackend
	void theVersionsAreListedWithWhereTheyCameFrom ( ) {
		branchDraftAndWorkOnBoth();
		documents.merge(DOC, "main", List.of(new MergeSource("draft", null)));

		List<VersionSummary> versions = documents.versions(DOC);
		assertEquals(List.of("main", "draft"), versions.stream().map(VersionSummary::version).toList());
		assertNull(versions.get(0).parent());
		assertEquals(List.of("draft"), versions.get(0).mergedFrom());
		assertEquals("main", versions.get(1).parent());
		assertEquals(scopeAdded.id(), versions.get(1).forkPoint().id());
		assertEquals(documents.read(DOC, "draft").latest().reference().id(), versions.get(1).latest().id());
	}

	@ForEachBackend
	void requestsTheHistoryDoesNotAllowAreRefused ( ) {
		assertThrows(DocumentRejectedException.class, ( ) -> documents.read(DOC, "nope"));
		assertThrows(DocumentRejectedException.class, ( ) -> documents.read("nope", "main"));
		assertThrows(DocumentRejectedException.class, ( ) -> documents.create(DOC, "main", "Again"));
		documents.branch(DOC, "main", null, "draft");
		assertThrows(DocumentRejectedException.class, ( ) -> documents.branch(DOC, "main", null, "draft"));
		assertThrows(DocumentRejectedException.class, ( ) -> documents.replaceText(DOC, "draft", "p9", "No such paragraph"));
		EventReference otherDocument = stream.query(EventFilter.forTags(DocumentVersions.DOCUMENT, "other")).getFirst().reference();
		assertThrows(DocumentRejectedException.class, ( ) -> documents.branch(DOC, "main", otherDocument, "foreign"));
		assertFalse(documents.versions(DOC).stream().anyMatch(v -> v.version().equals("foreign")));
	}

	// ------------------------------------------------------------------------------------------------

	/** Branches the draft from the "Scope" state and changes different things on both sides. */
	private void branchDraftAndWorkOnBoth ( ) {
		documents.branch(DOC, "main", scopeAdded, "draft");
		documents.replaceText(DOC, "draft", "p1", "Introduction");
		documents.replaceText(DOC, "main", "p3", "Budget: 10k");
		documents.addParagraph(DOC, "draft", "p4", "p1", "Risks");
		documents.changeTitle(DOC, "main", "Plan v2");
	}

	private void append ( DocumentVersion decidedOn, DocumentEvent event ) {
		stream.append(AppendCriteria.of(decidedOn.history(), decidedOn.head()),
				Event.of(event, Tags.of(DocumentVersions.DOCUMENT, decidedOn.documentId(), DocumentVersions.VERSION, decidedOn.version())));
	}

}
