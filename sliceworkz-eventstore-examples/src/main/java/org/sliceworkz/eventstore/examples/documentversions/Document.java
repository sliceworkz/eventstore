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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.DocumentCreated;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.ParagraphAdded;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.ParagraphRemoved;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.ParagraphTextReplaced;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.TitleChanged;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.VersionBranched;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.VersionsMerged;

/**
 * The content of a document as one version sees it: a title and ordered paragraphs, folded from the
 * events of that version's history in the order they were stored.
 */
public final class Document {

	public record Paragraph ( String id, String text ) { }

	private String title;
	private final List<Paragraph> paragraphs = new ArrayList<>();

	private Document ( ) { }

	/**
	 * Folds a version's history into its content.
	 *
	 * @param history the events of one version's history, in stored order
	 * @return the content they describe
	 */
	public static Document of ( List<Event<DocumentEvent>> history ) {
		Document document = new Document();
		history.forEach(event -> document.apply(event.data()));
		return document;
	}

	private void apply ( DocumentEvent event ) {
		switch ( event ) {
			case DocumentCreated c -> title = c.title();
			case TitleChanged t -> title = t.title();
			case ParagraphAdded a -> paragraphs.add(a.after() == null ? 0 : indexOf(a.after()) + 1, new Paragraph(a.paragraphId(), a.text()));
			case ParagraphTextReplaced r -> paragraphs.set(indexOf(r.paragraphId()), new Paragraph(r.paragraphId(), r.text()));
			case ParagraphRemoved r -> paragraphs.remove(indexOf(r.paragraphId()));
			case VersionBranched b -> { } // structure, no content
			case VersionsMerged m -> { } // structure, no content
		}
	}

	private int indexOf ( String paragraphId ) {
		for ( int i = 0; i < paragraphs.size(); i++ ) {
			if ( paragraphs.get(i).id().equals(paragraphId) ) {
				return i;
			}
		}
		throw new IllegalStateException("no paragraph " + paragraphId + " in this version");
	}

	public String title ( ) {
		return title;
	}

	public List<Paragraph> paragraphs ( ) {
		return Collections.unmodifiableList(paragraphs);
	}

	public boolean hasParagraph ( String paragraphId ) {
		return paragraphs.stream().anyMatch(p -> p.id().equals(paragraphId));
	}

	/** The paragraph texts in order, for showing and comparing. */
	public List<String> texts ( ) {
		return paragraphs.stream().map(Paragraph::text).toList();
	}

	@Override
	public String toString ( ) {
		return "# " + title + "\n" + paragraphs.stream().map(p -> "  " + p.text()).collect(Collectors.joining("\n"));
	}

}
