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
package org.sliceworkz.eventstore.examples;

import java.util.List;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.MergeSource;
import org.sliceworkz.eventstore.examples.documentversions.DocumentRejectedException;
import org.sliceworkz.eventstore.examples.documentversions.DocumentVersions;
import org.sliceworkz.eventstore.examples.documentversions.DocumentVersions.DocumentVersion;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Versions of a document that share their first events, split off from a point in the history, and are
 * merged again — Git-style branches on an event store, with tags and per-item filter bounds.
 * <p>
 * Every event carries {@code document:<id>} and {@code version:<name>}. Going back to an older state
 * starts a version from an event of another one; nothing is copied. A version's history is then one
 * filter with an item per version it reaches, each bounded at the last event that version contributes,
 * and it is read in one query. See {@link DocumentVersions} for how, and why the consistency boundary of
 * an edit comes out of the same filter.
 */
public class DocumentVersionsExample {

	public static void main ( String[] args ) {

		try ( EventStore eventstore = InMemoryEventStorage.newBuilder().buildStore() ) {

			DocumentVersions documents = new DocumentVersions(eventstore.getEventStream(EventStreamId.forContext("documents"), DocumentEvent.class));

			documents.create("plan", "main", "Plan");
			documents.addParagraph("plan", "main", "p1", null, "Intro");
			documents.addParagraph("plan", "main", "p2", "p1", "Scope");
			EventReference scopeAdded = documents.read("plan", "main").latest().reference();

			documents.addParagraph("plan", "main", "p3", "p2", "Budget");
			documents.replaceText("plan", "main", "p3", "Budget: 10k");
			documents.changeTitle("plan", "main", "Plan v2");

			// go back to the state right after "Scope" was added, and work from there
			documents.branch("plan", "main", scopeAdded, "draft");
			documents.replaceText("plan", "draft", "p1", "Introduction");
			documents.addParagraph("plan", "draft", "p4", "p1", "Risks");

			show(documents.read("plan", "main"));
			show(documents.read("plan", "draft"));

			// the two sides changed different paragraphs, so the merge goes through
			documents.merge("plan", "main", List.of(new MergeSource("draft", null)));
			show(documents.read("plan", "main"));

			// both sides change the same paragraph: refused, nothing appended
			documents.branch("plan", "main", null, "rewrite");
			documents.replaceText("plan", "rewrite", "p2", "Scope: phase one");
			documents.replaceText("plan", "main", "p2", "Scope: everything");
			try {
				documents.merge("plan", "main", List.of(new MergeSource("rewrite", null)));
			} catch ( DocumentRejectedException e ) {
				System.out.println("merge refused: " + e.getMessage());
			}

			documents.versions("plan").forEach(System.out::println);
		}
	}

	private static void show ( DocumentVersion version ) {
		System.out.println("--- " + version.version() + ", read with " + version.history().items().size() + " filter items in one query");
		System.out.println(version.document());
	}

}
