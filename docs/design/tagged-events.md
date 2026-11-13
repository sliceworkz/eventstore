# Tagged events

The rules are in the root `CLAUDE.md`, under "Tagged events". This file holds the reasons and the
rejected alternatives.

## What it solves

A consistency boundary selects the events a decision depends on by their tags. An event appended without
a tag it should carry matches no boundary that selects by it: the decision that should have conflicted is
admitted, nothing is thrown and nothing is logged. With tags passed at every place an event is appended,
that mistake can be made at every one of them. Most tags repeat a value the payload already carries, so
the event itself is the one place that can state them, once.

## The shape

- **An interface on the event's data, `Tagged`, answering `eventTags()`.** The event states its tags in
  code, so a tag can be anything code can compute: several values under one key (an event about two
  accounts), a role key beside an entity key, a period computed from a date. The store merges them with
  the tags the caller passed, so a caller can still add a tag whose value is not in the payload (a
  channel, a region, an entity the event is about but does not carry).
- **Requirements per key, `TagRequirement`.** `eventTags()` alone protects the tags the payload carries;
  a tag the caller must supply is still forgettable. A requirement names the key and how many distinct
  tags it must have, and the store checks it against the merged tags. Counting the merged tags rather
  than only the passed ones makes a requirement describe the stored event — "about exactly two accounts"
  — so it also catches a third account passed by mistake, or a role value put under the entity key.
- **Merged and checked on append, before serialization.** Every append — typed stream, `Event.of`, an
  `EphemeralEvent` built by hand, a framework's command — goes through `EventStreamImpl.append`.
  Serialization seals `Shreddable` values and commits their keys before the event, so the tags are
  derived first: an event whose tags fail mints no key. The whole batch is checked before anything is
  written, so one incomplete event stores nothing of it.
- **Two exception types.** `eventTags()` failing is a property of the payload, identical on every retry:
  an `EventSerializationException`, like any other failure to write the payload. An unmet requirement is
  the caller's mistake: an `IllegalArgumentException`, like the store's other argument checks.

## Rejected alternatives

**An annotation on a record component (`@Tag("account")`).** Declarative, but strictly less expressive:
an annotation value must be a compile-time constant, so it cannot refer to a framework's entity constant
and repeats the key as a string that can drift from it; it cannot compute a tag (a month from a date); a
nested or optional value needs a path language inside the annotation; and a key derived from the
component's name silently changes when the component is renamed, splitting the history under two keys.

**Role keys instead of the entity key (`from-account`/`to-account` and no `account`).** A decision about
everything that happened to an account would then have to name every role any event gives an account,
and a role added later is a key every such decision misses — the silent miss this exists to prevent. A
role key is useful *beside* the entity key: it narrows a decision that is genuinely about the role (what
an account sent), so a deposit into that account does not conflict with it, and it keeps the two sides
of a self-transfer apart where the entity key collapses them into one tag.

**Deriving tags on read.** A tag filter is answered by storage (the Postgres `text[]` and its index)
before anything is deserialized, so tags derived on read could never select an event. Tags are stored
with the event, and an event appended before its type stated a tag does not carry it: tagging an event
type with history in a new way is a migration of that history.

**`transferId.tag()` on the id type.** The store has no id types; a framework that has them (the
Sliceworkz event modeling framework's `Entity`) makes tags through the entity, `TRANSFER.tag(transferId)`,
and a second spelling of the same call on the id was turned down there. A registry from id class to
entity would also depend on class-initialisation order: an event record nested in the domain interface
loads without initialising the interface's constants.

**Requirements as an annotation, readable without an instance.** It would let a stream check a
declaration when it is opened and let tooling list requirements, but the same constant-value limit
applies, and checking every append is what the guarantee rests on. The instance method stays.

**A count of tags as given.** `Tags` is a set, and a stored event carries each tag once, so the count is
of distinct tags; a requirement that counted values as passed would describe something storage does not
keep.
