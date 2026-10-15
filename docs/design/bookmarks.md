# Bookmarks: a cursor that must name a stored event, and a foreign key that never cascades

The rules in brief are in the root `CLAUDE.md`, under "Bookmarks". This file holds the full design: each rule, the reasons for it, and the alternatives that were rejected.


- **`placeBookmark` rejects a reference this storage never stored** — `EventStorageException`, nothing
  written, and a previously placed bookmark for that reader stays. The realistic mistake it catches is a
  reference from a *different* store or prefix in a miswired multi-store setup, which would otherwise
  poison the reader's cursor silently. Postgres enforces it with the `fk_bookmarks_event_id` foreign key
  (recognised by the constraint name the server reports, like the idempotency index — never by message
  text); the in-memory store checks its log under the same monitor that guards `append`. The contract is
  documented on `EventStorage.bookmark`, and `BookmarksTest` in the TCK pins it per backend, so a backend
  that accepts any reference fails compliance rather than diverging quietly
- **A bookmark stores the event id and nothing else about the event; its position and transaction
  are answered from the event.** On Postgres the bookmarks table has no ordering columns, and
  `getBookmark`/`getBookmarks` join the events row on the unique `event_id` index (one probe; the
  bookmark trigger does the same for its notification payload). The in-memory store resolves the
  reference from its log at placement and again when a persisted bookmark is loaded. So the reference
  read back — and the one a `BookmarkPlacedNotification` carries — is always the store's own for that
  event, whatever the caller passed: a bookmark cannot carry a cursor that disagrees with the event it
  names, and a bookmarks table carried between stores by id (an import preserves ids and reassigns
  both ordering columns) is valid as it stands. The alternative — storing the caller's `(tx, position)`
  beside the id — loses because the foreign key never checks that copy, so a bookmark could pass
  validation with a stored id and a wrong cursor, and because it makes every bookmark meaningless
  outside the store it was taken from. `BookmarksTest.bookmarkIsResolvedToTheStoresOwnCoordinates`
  and `bookmarkNotificationCarriesTheStoresOwnCoordinates` pin it per backend. A Postgres database
  created while the columns existed needs a hand-applied migration — `ALTER TABLE <prefix>bookmarks
  DROP COLUMN event_position, DROP COLUMN event_tx;` — which `checkDatabase()` reports under `VALIDATE`
  and `ENSURE`, and the first placement names under `NONE`; see "Migrating the bookmarks table" in the
  postgres module README
- **A bookmark holds two positions: `reference`, the last event the reader handled, and `readUpTo`,
  the event up to which it has read the stream, relevant to it or not.** A reader's query names the
  event types it handles, so its reference never moves past an event of another type; counting "events
  after the reference" as its backlog counts events it will never handle, for good. `readUpTo` is what
  lag is counted from, through `Bookmark.readUpToOrReference()`, and what a caller waiting for a reader
  to have seen an event compares with (`EventSource.findBookmark(reader)` answers the whole bookmark,
  `EventStorage.findBookmark` is the SPI half). It is **never a resume point**: a projector restarts from
  `reference`. The alternative — resuming from `readUpTo` — loses because it buys nothing (the typed
  query skips irrelevant events through the index anyway) and because a query that later gains an event
  type would never be handed the events of that type between the two positions, silently. The one
  exception is opted into on the projector: `Projector.Builder.resumeAfterReadPosition()` makes a
  projector whose bookmark names **no handled event** read on after its read position instead of from the
  beginning — for a reader started at a point on purpose (its first bookmark a read position placed at the
  head), which resumed from the beginning would be handed the very history it was started to skip. The
  handled reference stays empty until an event is really handed over, so it is only ever an event the
  projection handled, never the point reading began at; once one is, that event is the resume point as for
  every projector. The cost the rule above avoids is accepted there deliberately: a query gaining an event
  type is not handed that type's events before the read position — which, for such a reader, is the
  history it skips. The alternative — `startingAfter(readPosition)` — loses because the projector's cursor
  is also the reference it records as handled, so the start point would be written as handled on the next
  idle move. `ProjectorReadPositionTest` pins it per backend `readUpTo` is
  optional: a bookmark placed without one (by a writer or storage that does not record it) reads back
  empty, and readers fall back to `reference`; the next placement that carries one fills it in. It is held
  to the rules `reference` is — an unknown event is rejected, only the id is stored, and it reads back (and
  arrives on `BookmarkPlacedNotification`) as the store's own coordinates. On Postgres it is the nullable
  `read_up_to_event_id` column with its own non-cascading `fk_bookmarks_read_up_to_event_id`, resolved by
  a second (LEFT) join on the events table; `ENSURE` adds the column, its foreign key and index to an
  existing table — the one column it adds, since it is nullable and needs no data change — and
  `checkDatabase()` reports it missing under `VALIDATE`, while under `NONE` the first bookmark read or
  write names the migration (see "Migrating the bookmarks table to record a read position" in the
  postgres module README). The SPI's `bookmark(reader, reference, readUpTo, tags)` is a `default` that
  drops `readUpTo` and delegates to the three-argument method, so a third-party storage keeps compiling
  and simply records none; `BookmarksTest` holds every in-tree backend to the full contract
- **The handled reference is optional too: `Bookmark.reference()` is an `Optional`, empty for a reader
  that has read the stream without handling anything yet** — one whose query selects event types that
  have not occurred. Without it such a reader has no bookmark, and monitoring counts its whole stream as
  backlog although nothing in it concerns it. A bookmark names at least one of the two positions (the
  record refuses neither, as do `BookmarkPlacedNotification`, the JSON codec and the Postgres check).
  It is placed through its own method — `EventSource.placeReadPosition(reader, readUpTo, tags)`, the
  SPI's `EventStorage.bookmarkReadPosition(...)` — rather than a nullable reference on `placeBookmark`,
  so no public signature takes a null to mean "nothing handled". Its rules: the reader **resumes from
  the beginning**, exactly as without a bookmark — `getBookmark(reader)`, the resume point, answers
  empty, and the read position is never a resume point; a read position placed alone **never clears a
  handled reference** already there (in memory under the monitor, on Postgres by `COALESCE` in the same
  upsert, so a concurrent placement is never lost to it); the first placement naming a handled event
  fills it in; `removeBookmark` removes such a bookmark too (answering empty). Its notification carries
  an empty `bookmark()` — or the reference the row kept — and a `BookmarkListener`, which reports a
  processed-until, is not told of a read position placed with nothing processed. The SPI method is a
  `default` that records nothing, so a third-party storage keeps compiling and its readers simply have no
  bookmark until they handle something, as before. On Postgres `event_id` is nullable, the foreign key
  stays (MATCH SIMPLE: a `NULL` is not checked, a present id still has to name a stored event), and
  `ck_bookmarks_handled_or_read_position` refuses a row naming neither; `getBookmark(s)` LEFT JOINs the
  handled event as it does the read position, and the trigger payload's `event*` fields are null for such
  a row. `ENSURE` relaxes an existing table in place (`DROP NOT NULL`, guarded on the column's
  nullability so an ordinary start takes no lock, and the check added when absent — no data change);
  `checkDatabase()` reports an unrelaxed table under `VALIDATE`, and under `NONE` the first
  read-position-only placement names the migration (see "Migrating the bookmarks table to record a read
  position without a handled event" in the postgres module README)
- **A projector keeps `readUpTo` by four rules** (on `Projector`'s class javadoc):
  - it takes the source's `head()` at the start of every run — behind the same visibility barrier as
    every read, so anything committed later sorts after it; never a raw `MAX(position)`
  - after each committed batch the bookmark records the last event handled as both positions, so a run
    that fails or dies part-way leaves `readUpTo` at the last batch that landed, never at the head
  - when a run has read to the end (its last page came back shorter than its limit, nothing failed),
    `readUpTo` is the later of that head and the last event handled, compared with `happenedAfter` in the
    total `(tx, position, index)` order; a `runUntil` extends it only when the head is at or before its
    boundary
  - a run that handled nothing moves only `readUpTo`, and at most once per
    `Builder.idleBookmarkInterval(Duration)` (default 2s, `Duration.ZERO` for every run). Every subscribed
    projector runs on every append to its stream and each placement is a write — on Postgres an upsert,
    its trigger and a notification — so unthrottled it would be a write per processor per append. A move
    held back is written by the first run after the interval; `Projector.deferredReadUpToDueIn()` says
    when that is due, for a caller that drives its projector itself and would otherwise leave the read
    position trailing until the next append. A projector that has handled nothing yet records its read
    position alone by the same rules — after a run that read to the end, the head within its `runUntil`
    boundary, as an idle move held to the interval — and still resumes from the beginning; the first
    event it handles fills the handled reference in. `ProjectorReadPositionTest` in the TCK pins every
    rule per backend
- **The foreign key deliberately does not cascade.** An absent bookmark — or one recording a read
  position alone — means "replay from the beginning" — for a dispatcher in the eventmodeling framework, duplicate publishing to an external
  system, the worst outcome it documents. `ON DELETE CASCADE` handed exactly that to the readers least
  able to afford it: an event deletion (retention pruning, surgically removing a poison event) cascades
  away the bookmarks of readers still pointing into the deleted range — the *lagging* ones — silently,
  with no notification, since the bookmark trigger fires on INSERT/UPDATE only. With the default NO
  ACTION, deleting events out from under an outstanding bookmark fails loudly, and whoever prunes
  decides explicitly what happens to the reader. The constraint carries the reads too: a bookmark
  resolves to its event on every read (next bullet), so an event deleted from under one would leave
  the reader with no position at all — the same "replay from the beginning"
- **Migration for a database created while the cascade existed**: `ENSURE` only ever creates tables, so
  an existing database keeps its constraint until migrated by hand —
  `ALTER TABLE <prefix>bookmarks DROP CONSTRAINT fk_bookmarks_event_id; ALTER TABLE <prefix>bookmarks
  ADD CONSTRAINT fk_bookmarks_event_id FOREIGN KEY (event_id) REFERENCES <prefix>events(event_id);` —
  no data migration is needed. `checkDatabase()` validates the constraint by name only, not its delete
  rule, so an un-migrated database still starts, with the old cascade behaviour
