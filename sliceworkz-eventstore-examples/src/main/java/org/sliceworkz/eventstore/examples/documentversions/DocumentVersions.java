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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.DocumentCreated;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.MergeSource;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.ParagraphAdded;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.ParagraphRemoved;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.ParagraphTextReplaced;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.TitleChanged;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.VersionBranched;
import org.sliceworkz.eventstore.examples.documentversions.DocumentEvent.VersionsMerged;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;

/**
 * Versions of a document that share their history up to where they were split off, and can be merged
 * again — branches in the Git sense, built on tags and per-item bounds.
 *
 * <h2>The history of a version is a filter</h2>
 * A version's history is its own events, plus every version it links to (the version it was branched
 * from, and the versions merged into it) up to the event it was linked at, recursively. Within one
 * version the events form a line, so what a version contributes is always a prefix of that line: all of
 * its events up to one reference. Reached along several paths, a version contributes its longest prefix.
 * <p>
 * So the history of a version is one {@link EventFilter} with an item per version it reaches —
 * {@code document:<id>} and {@code version:<v>}, until that version's bound (the version itself
 * unbounded) — and reading it is one query: the store returns the events of every item merged in stored
 * order, each once. Working the bounds out takes a first, small read of the document's structure events
 * ({@link DocumentCreated}, {@link VersionBranched}, {@link VersionsMerged}). Both reads are bounded at the
 * stream head taken before the first, so the structure and the content describe the same moment.
 *
 * <h2>The consistency boundary falls out of the same filter</h2>
 * A change to a version is appended under {@code AppendCriteria.of(history, head)}. Every ancestor's item
 * is bounded at or before the head, so the criteria drops it: nothing appended later can change history
 * that is already fixed. What is left is the version's own events. Two writers on one version conflict;
 * writers on different versions never do; a merge into the version is tagged with it, so it conflicts too.
 *
 * <h2>Merging</h2>
 * Events of two versions are interleaved in the merged history by when they were stored, not by version,
 * so a merge is only sound when the order between the two sides does not matter. Every content event
 * names what it touches by id ({@link #touches}); a merge is refused when two sides touched the same thing
 * since they last shared their history. Whatever a merge lets through commutes, so the merged history
 * folds into one well-defined document.
 */
public class DocumentVersions {

	public static final String DOCUMENT = "document";
	public static final String VERSION = "version";

	private final EventStream<DocumentEvent> stream;

	public DocumentVersions ( EventStream<DocumentEvent> stream ) {
		this.stream = stream;
	}

	// ------------------------------------------------------------------------------------------------
	// Reading
	// ------------------------------------------------------------------------------------------------

	/**
	 * One version of a document as it stands: its content, the events of its history and the filter they
	 * were read with.
	 *
	 * @param documentId the document
	 * @param version the version
	 * @param head the stream head both reads were bounded at
	 * @param bounds per version in this version's history, the last event it contributes ({@code null}: all)
	 * @param history the filter selecting this version's history: an item per entry of {@code bounds}
	 * @param events the events of this version's history, in stored order — the references to branch from
	 */
	public record DocumentVersion ( String documentId, String version, EventReference head,
			Map<String, EventReference> bounds, EventFilter history, List<Event<DocumentEvent>> events ) {

		public Document document ( ) {
			return Document.of(events);
		}

		/** The newest event of this version's history. */
		public Event<DocumentEvent> latest ( ) {
			return events.getLast();
		}

	}

	/**
	 * A version of a document, for an overview of the versions that exist.
	 *
	 * @param version the version
	 * @param parent the version it was branched from, or {@code null} for the first
	 * @param forkPoint the last event of the parent it started from, or {@code null} for the first
	 * @param mergedFrom the versions merged into it, in the order they were merged
	 * @param latest the newest event written on it
	 */
	public record VersionSummary ( String version, String parent, EventReference forkPoint, List<String> mergedFrom, EventReference latest ) { }

	/**
	 * Reads one version of a document: a read of the structure events, then one query for the history.
	 *
	 * @param documentId the document
	 * @param version the version
	 * @return the version as it stands
	 * @throws DocumentRejectedException for an unknown document or version
	 */
	public DocumentVersion read ( String documentId, String version ) {
		EventReference head = head();
		return read(documentId, version, head, structure(documentId, head));
	}

	/**
	 * Every version of a document, in the order they were started.
	 *
	 * @param documentId the document
	 * @return a summary per version
	 */
	public List<VersionSummary> versions ( String documentId ) {
		EventReference head = head();
		Structure structure = structure(documentId, head);
		List<VersionSummary> versions = new ArrayList<>();
		for ( String version : structure.versions() ) {
			EventReference latest = stream.query(EventQuery.of(versionFilter(documentId, version)).backwards().limit(1).notAfter(head))
					.getFirst().reference();
			VersionBranched origin = structure.origins().get(version);
			versions.add(new VersionSummary(version,
					origin == null ? null : origin.parent(),
					origin == null ? null : origin.forkPoint(),
					structure.merges().getOrDefault(version, List.of()),
					latest));
		}
		return versions;
	}

	// ------------------------------------------------------------------------------------------------
	// Changing
	// ------------------------------------------------------------------------------------------------

	public void create ( String documentId, String version, String title ) {
		EventReference head = head();
		EventFilter document = EventFilter.forTags(DOCUMENT, documentId);
		if ( !stream.query(EventQuery.of(document).limit(1).notAfter(head)).isEmpty() ) {
			throw new DocumentRejectedException("document " + documentId + " exists");
		}
		// "no event of this document up to the head, and none after it": the empty-boundary pattern
		stream.append(AppendCriteria.of(document, head), Event.of(new DocumentCreated(title), tags(documentId, version)));
	}

	public void changeTitle ( String documentId, String version, String title ) {
		change(documentId, version, document -> List.of(new TitleChanged(title)));
	}

	/**
	 * Adds a paragraph after another one, or at the start when {@code after} is {@code null}.
	 */
	public void addParagraph ( String documentId, String version, String paragraphId, String after, String text ) {
		change(documentId, version, document -> {
			if ( document.hasParagraph(paragraphId) ) {
				throw new DocumentRejectedException("paragraph " + paragraphId + " exists in " + version);
			}
			requireParagraph(document, version, after);
			return List.of(new ParagraphAdded(paragraphId, after, text));
		});
	}

	public void replaceText ( String documentId, String version, String paragraphId, String text ) {
		change(documentId, version, document -> {
			requireParagraph(document, version, paragraphId);
			return List.of(new ParagraphTextReplaced(paragraphId, text));
		});
	}

	public void removeParagraph ( String documentId, String version, String paragraphId ) {
		change(documentId, version, document -> {
			requireParagraph(document, version, paragraphId);
			return List.of(new ParagraphRemoved(paragraphId));
		});
	}

	/**
	 * Starts a version from a point in the history of another: "go back to that state, and work from
	 * there". The version branched from is left as it is.
	 *
	 * @param documentId the document
	 * @param fromVersion the version whose history holds the fork point
	 * @param at an event of that history — the last one the new version includes — or {@code null} for its newest
	 * @param newVersion the name of the new version
	 */
	public void branch ( String documentId, String fromVersion, EventReference at, String newVersion ) {
		EventReference head = head();
		Structure structure = structure(documentId, head);
		if ( structure.versions().contains(newVersion) ) {
			throw new DocumentRejectedException("version " + newVersion + " exists");
		}
		DocumentVersion from = read(documentId, fromVersion, head, structure);
		Event<DocumentEvent> fork = at == null ? from.latest() : eventIn(from, at.id());
		// the parent is the version the fork point was written on, so it lies in the parent's own events
		VersionBranched branched = new VersionBranched(newVersion, versionOf(fork), fork.reference());
		// nothing of the new version up to the head, and nothing after it: its name is ours
		stream.append(AppendCriteria.of(versionFilter(documentId, newVersion), head), Event.of(branched, tags(documentId, newVersion)));
	}

	/**
	 * Takes the history of other versions into a version. Refused, with nothing appended, when a source
	 * adds nothing the version does not have, or when two sides changed the same thing since they last
	 * shared their history ({@link DocumentRejectedException#conflicts()} names them).
	 *
	 * @param documentId the document
	 * @param into the version that takes the others in
	 * @param sources the versions to merge, each up to an event of its history ({@code null}: its newest)
	 */
	public void merge ( String documentId, String into, List<MergeSource> sources ) {
		EventReference head = head();
		Structure structure = structure(documentId, head);
		structure.require(into);
		sources.forEach(source -> structure.require(source.version()));

		// one read for every side: the target, and each source unbounded for now
		EventFilter everything = historyFilter(documentId, structure.bounds(into, null));
		for ( MergeSource source : sources ) {
			everything = everything.or(historyFilter(documentId, structure.bounds(source.version(), null)));
		}
		List<Event<DocumentEvent>> events = stream.query(EventQuery.of(everything).notAfter(head));

		// pin each source at the event it is taken up to, on the version that event was written on
		List<MergeSource> resolved = new ArrayList<>();
		List<EventFilter> sides = new ArrayList<>();
		EventFilter target = historyFilter(documentId, structure.bounds(into, null));
		sides.add(target);
		for ( MergeSource source : sources ) {
			EventFilter unbounded = historyFilter(documentId, structure.bounds(source.version(), null));
			List<Event<DocumentEvent>> sourceHistory = events.stream().filter(unbounded::matches).toList();
			Event<DocumentEvent> upTo = source.upTo() == null ? sourceHistory.getLast() : sourceHistory.stream()
					.filter(e -> e.reference().id().equals(source.upTo().id())).findFirst()
					.orElseThrow(() -> new DocumentRejectedException("event " + source.upTo().id() + " is not in the history of " + source.version()));
			MergeSource pinned = new MergeSource(versionOf(upTo), upTo.reference());
			resolved.add(pinned);
			sides.add(historyFilter(documentId, structure.bounds(pinned.version(), pinned.upTo())));
		}

		List<Set<EventId>> sideIds = sides.stream().map(side -> idsOf(events, side)).toList();
		for ( int i = 1; i < sideIds.size(); i++ ) {
			// judged on content: a version branched and never edited holds only its own structure event
			if ( touched(events, sideIds.get(i), sideIds.getFirst()).isEmpty() ) {
				throw new DocumentRejectedException(into + " already has every change of " + resolved.get(i - 1).version());
			}
		}
		Set<String> conflicts = conflicts(events, sideIds);
		if ( !conflicts.isEmpty() ) {
			throw new DocumentRejectedException("cannot merge into " + into + ", both sides changed " + conflicts, conflicts);
		}

		// The decision read every side, but only the target can still change: the sources are pinned at
		// events before the head, so the criteria drops their items, and the target's ancestors with them.
		EventFilter decidedOn = sides.stream().reduce(EventFilter.matchNone(), EventFilter::or);
		stream.append(AppendCriteria.of(decidedOn, head), Event.of(new VersionsMerged(into, resolved), tags(documentId, into)));
	}

	// ------------------------------------------------------------------------------------------------
	// The history of a version
	// ------------------------------------------------------------------------------------------------

	/**
	 * The filter selecting a version's history: an item per version it reaches, that version's own
	 * events, until the last one it contributes.
	 *
	 * @param documentId the document
	 * @param bounds per version, the last event it contributes ({@code null}: all of them)
	 * @return the filter
	 */
	static EventFilter historyFilter ( String documentId, Map<String, EventReference> bounds ) {
		EventFilter filter = EventFilter.matchNone();
		for ( Map.Entry<String, EventReference> bound : bounds.entrySet() ) {
			EventFilter item = versionFilter(documentId, bound.getKey());
			filter = filter.or(bound.getValue() == null ? item : item.until(bound.getValue()));
		}
		return filter;
	}

	private DocumentVersion read ( String documentId, String version, EventReference head, Structure structure ) {
		structure.require(version);
		Map<String, EventReference> bounds = structure.bounds(version, null);
		EventFilter history = historyFilter(documentId, bounds);
		return new DocumentVersion(documentId, version, head, bounds, history, stream.query(EventQuery.of(history).notAfter(head)));
	}

	/** Reads the version, decides on its content and appends what was decided on its own line. */
	private void change ( String documentId, String version, Function<Document, List<DocumentEvent>> decision ) {
		EventReference head = head();
		DocumentVersion current = read(documentId, version, head, structure(documentId, head));
		List<DocumentEvent> decided = decision.apply(current.document());
		stream.append(AppendCriteria.of(current.history(), head),
				decided.stream().map(event -> Event.of(event, tags(documentId, version))).toList());
	}

	private Structure structure ( String documentId, EventReference head ) {
		EventQuery structure = EventQuery.of(EventFilter.forTypes(DocumentCreated.class, VersionBranched.class, VersionsMerged.class)
				.tagged(DOCUMENT, documentId)).notAfter(head);
		Structure result = Structure.of(stream.query(structure));
		if ( result.versions().isEmpty() ) {
			throw new DocumentRejectedException("no document " + documentId);
		}
		return result;
	}

	/**
	 * The links between the versions of a document, read off its structure events.
	 *
	 * @param versions every version, in the order they were started
	 * @param links per version, what it links to: the version it was branched from and the versions merged into it
	 * @param origins per branched version, its branch event
	 * @param merges per version, the versions merged into it
	 */
	private record Structure ( List<String> versions, Map<String, List<Link>> links, Map<String, VersionBranched> origins, Map<String, List<String>> merges ) {

		/**
		 * A version linking to another: at its own event {@code at}, it took that version's history up to
		 * {@code upTo}.
		 */
		record Link ( EventReference at, String version, EventReference upTo ) { }

		static Structure of ( List<Event<DocumentEvent>> events ) {
			Structure structure = new Structure(new ArrayList<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
			for ( Event<DocumentEvent> event : events ) {
				String version = versionOf(event);
				switch ( event.data() ) {
					case DocumentCreated c -> structure.versions().add(version);
					case VersionBranched b -> {
						structure.versions().add(version);
						structure.origins().put(version, b);
						structure.linksOf(version).add(new Link(event.reference(), b.parent(), b.forkPoint()));
					}
					case VersionsMerged m -> m.sources().forEach(source -> {
						structure.linksOf(version).add(new Link(event.reference(), source.version(), source.upTo()));
						structure.merges().computeIfAbsent(version, v -> new ArrayList<>()).add(source.version());
					});
					default -> { }
				}
			}
			return structure;
		}

		private List<Link> linksOf ( String version ) {
			return links.computeIfAbsent(version, v -> new ArrayList<>());
		}

		void require ( String version ) {
			if ( !versions.contains(version) ) {
				throw new DocumentRejectedException("no version " + version);
			}
		}

		/**
		 * Per version reached from {@code version} up to {@code upTo}, the last event it contributes.
		 *
		 * @param version where to start
		 * @param upTo the last of its own events included, or {@code null} for all of them
		 * @return the bounds, {@code null} meaning all of that version's events
		 */
		Map<String, EventReference> bounds ( String version, EventReference upTo ) {
			Map<String, EventReference> bounds = new LinkedHashMap<>();
			reach(version, upTo, bounds);
			return bounds;
		}

		private void reach ( String version, EventReference upTo, Map<String, EventReference> bounds ) {
			if ( bounds.containsKey(version) ) {
				EventReference reached = bounds.get(version);
				if ( reached == null || ( upTo != null && !upTo.storedEventHappenedAfter(reached) ) ) {
					return; // a prefix at least as long was reached already
				}
			}
			bounds.put(version, upTo);
			for ( Link link : links.getOrDefault(version, List.of()) ) {
				// only the links inside the prefix: a merge made after upTo is not part of it
				if ( upTo == null || !link.at().storedEventHappenedAfter(upTo) ) {
					reach(link.version(), link.upTo(), bounds);
				}
			}
		}

	}

	// ------------------------------------------------------------------------------------------------
	// Merge conflicts
	// ------------------------------------------------------------------------------------------------

	/**
	 * What a content event touches, for telling whether two sides of a merge changed the same thing.
	 * Adding after a paragraph touches its anchor, and removing a paragraph removes it as an anchor, so
	 * two inserts at one place, and an insert after a paragraph removed on the other side, conflict.
	 * Replacing a paragraph's text and adding after it do not: they commute.
	 *
	 * @param event a document event
	 * @return what it touches; nothing for the structure events
	 */
	static Set<String> touches ( DocumentEvent event ) {
		return switch ( event ) {
			case DocumentCreated c -> Set.of("title");
			case TitleChanged t -> Set.of("title");
			case ParagraphAdded a -> Set.of("paragraph:" + a.paragraphId(), "anchor:" + ( a.after() == null ? "<start>" : a.after() ));
			case ParagraphTextReplaced r -> Set.of("paragraph:" + r.paragraphId());
			case ParagraphRemoved r -> Set.of("paragraph:" + r.paragraphId(), "anchor:" + r.paragraphId());
			case VersionBranched b -> Set.of();
			case VersionsMerged m -> Set.of();
		};
	}

	/** Everything two sides both touched in what the other side does not have. */
	private static Set<String> conflicts ( List<Event<DocumentEvent>> events, List<Set<EventId>> sides ) {
		Set<String> conflicts = new TreeSet<>();
		for ( int i = 0; i < sides.size(); i++ ) {
			for ( int j = i + 1; j < sides.size(); j++ ) {
				Set<String> left = touched(events, sides.get(i), sides.get(j));
				Set<String> right = touched(events, sides.get(j), sides.get(i));
				left.retainAll(right);
				conflicts.addAll(left);
			}
		}
		return conflicts;
	}

	private static Set<String> touched ( List<Event<DocumentEvent>> events, Set<EventId> side, Set<EventId> other ) {
		return events.stream()
				.filter(e -> side.contains(e.reference().id()) && !other.contains(e.reference().id()))
				.flatMap(e -> touches(e.data()).stream())
				.collect(Collectors.toCollection(HashSet::new));
	}

	// ------------------------------------------------------------------------------------------------

	private static Set<EventId> idsOf ( List<Event<DocumentEvent>> events, EventFilter side ) {
		return events.stream().filter(side::matches).map(e -> e.reference().id()).collect(Collectors.toUnmodifiableSet());
	}

	private static EventFilter versionFilter ( String documentId, String version ) {
		return EventFilter.forTags(DOCUMENT, documentId).tagged(VERSION, version);
	}

	private static Tags tags ( String documentId, String version ) {
		return Tags.of(DOCUMENT, documentId, VERSION, version);
	}

	private static String versionOf ( Event<DocumentEvent> event ) {
		return event.tags().tag(VERSION).map(Tag::value).orElseThrow();
	}

	private static Event<DocumentEvent> eventIn ( DocumentVersion version, EventId id ) {
		return version.events().stream().filter(e -> e.reference().id().equals(id)).findFirst()
				.orElseThrow(() -> new DocumentRejectedException("event " + id + " is not in the history of " + version.version()));
	}

	private static void requireParagraph ( Document document, String version, String paragraphId ) {
		if ( paragraphId != null && !document.hasParagraph(paragraphId) ) {
			throw new DocumentRejectedException("no paragraph " + paragraphId + " in " + version);
		}
	}

	private EventReference head ( ) {
		return stream.head().orElse(null);
	}


}
