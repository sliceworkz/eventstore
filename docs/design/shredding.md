# Erasing personal data: `Shreddable` values and crypto-shredding

The rules in brief are in the root `CLAUDE.md`, under "Erasing personal data". This file holds the full design: each rule, the reasons for it, and the alternatives that were rejected.

**Personal data is wrapped, not annotated, and erasure destroys a key rather than rewriting an event.**
A record component declared `Shreddable<T>` is bound to a `DataSubject`, encrypted on append under the
key held for that subject, and stored as a sealed envelope inside the ordinary payload.
`EventStore.erase(type, id, reason)` destroys the keys; nothing in the events table is written.

```java
record TransferMade(
        String transferId, Money amount,
        String fromCustomerId,                  // pseudonymous — survives erasure
        Shreddable<PartyDetails> from,          // Alice's data, Alice's key
        Shreddable<PartyDetails> to             // Bob's data, Bob's key
) implements PaymentEvent { }

DataSubject alice = DataSubject.of("customer", "alice-42");
payments.append(AppendCriteria.none(), Event.of(new TransferMade(..., Shreddable.of(details, alice), ...), tags));

eventStore.erase("customer", "alice-42", ErasureReason.of("GDPR art.17 request #4711"));

transfer.from();   // Shredded[customer/alice-42/default, k-7f2a91c4]
transfer.to();     // Present[PartyDetails[Bob Jansen, ...]]   -- unaffected
transfer.from().map(PartyDetails::name).orElse("[erased]");
```

- **The stored event never changes.** Its bytes stay identical forever, so an erasure needs no UPDATE,
  produces no new tuple to VACUUM, leaves the heap in insertion order, and reaches
  the ciphertext already sitting in WAL, on replicas and in every backup. The alternative — nulling a
  separate erasable column with an `UPDATE` — reaches none of those copies and makes the log no longer
  append-only.
- **A shredded value is never null**, which is what keeps erasure from creating poison events: a record
  whose compact constructor rejects nulls still builds after its data is gone. Nor can "erased" be
  confused with "never held any", and a `Shreddable<Integer>` reads as shredded rather than as `0`.
- **A `Shreddable` anywhere works** — nested records, `List` elements, `Map` values — because it is one
  Jackson serializer on one document. The alternative — annotating personal components and splitting them
  into a second document — loses because reconciling the two on read takes a deep merge, and a merge that
  replaces JSON arrays wholesale silently drops the non-personal fields of partly-personal collection
  elements on every ordinary read, erasure or not.
- **Two subjects in one event each get their own key**, which no per-field annotation or per-event key
  can express. Keys are scoped to `(type, id, category)`, so "erase marketing, retain financial" is a
  category away.
- **`erase(type, id, reason)` erases the person; `eraseCategory(subject, reason)` erases one
  category.** The plain name is the whole-person erasure because that is what an art.17 request asks
  for, and the erasure that can leave data readable carries the qualifier. A `DataSubject` always
  names a category — `DataSubject.of("customer", id)` is the `default` one — so `eraseCategory`
  destroys the keys of that category only, reporting success because the erasure it names was
  performed: on a subject that also holds `marketing` data it leaves the marketing data readable,
  which is right for "erase marketing, retain financial" and wrong for an art.17 request. The
  whole-person erasure takes the type and id and no category, so it cannot be narrowed by accident,
  and answers a `SubjectErasureReport` with one `ErasureReport` per category that held live keys. It
  is a separate SPI method on `ShreddingKeyStore` and `ShreddingCodec` (`shredAllCategories`, beside
  the per-category `shred` a key store implements), whose defaults throw
  `UnsupportedOperationException` so a key store written before it is told rather than made to erase
  one category and report success; a restricted codec passes it through whole. The alternative —
  `erase` taking a `DataSubject` too and erasing the person it belongs to — loses because the category
  the argument carries is then either ignored or refused: ignored,
  `erase(alice.withCategory("marketing"), reason)` destroys the financial history a retention rule
  says to keep; refused, a subject read off a `Shredded` value cannot be handed straight to it. Two
  parameter shapes keep the two erasures apart at compile time, so a call that meant one category
  cannot silently become the whole person, or the other way round. Having the default-category
  erasure mean "every category" loses too: it makes erasing only the default category inexpressible,
  and a category is what a subject's data is *written* under, so which one a caller names is not a
  statement about the others.
- **The subject id must not itself be personal data.** It is stored in the clear in the envelope and
  survives erasure by construction — use a customer number, never an email address.
- **`KeyId` values are random and land on the event as `dek:` tags**, so "every event holding data under
  this key" is an ordinary tag query on the existing index. The tags stay after the key is destroyed, as
  a tombstone that says an erasure touched the event without saying what it took.
- **Erasure notifies nothing.** Read models, caches, search indexes and downstream systems keep their
  copies, and projections hold bookmarks so they never re-read. Re-projecting is the application's job.
- **Without a codec configured, registering an event type that declares a `Shreddable` fails** at
  `getEventStream` — before anything is read or written — rather than storing personal data in the clear.
  That check reads declarations (record components, type arguments, array elements) and cannot see a
  `Shreddable` held behind a component declared as an interface or a non-record class, so the
  codec-less mapper carries a `Shreddable` serializer of its own that throws: such an append fails as
  `EventSerializationException`, nothing stored, on the typed and the raw serde alike, instead of
  Jackson writing the `Present` record — value and subject in the clear — as it otherwise would.
  `CodecLessShreddableSerdeTest` in the impl module pins both routes and that a codec seals the same
  value; `ShreddableEventDataTest.registeringAProtectedEventTypeWithoutACodecFails` pins the
  registration check per backend.

**Two seams, and a shipped default.** `AesGcmShreddingCodec` (AES-256-GCM, random 96-bit IV per value,
envelope metadata bound as AAD) over a `ShreddingKeyStore`:

```java
InMemoryEventStorage.newBuilder().shredding(new InMemoryShreddingKeyStore()).buildStore();
InMemoryFsEventStorage.newBuilder().directory(dir).shredding(new InMemoryFsShreddingKeyStore(dir)).buildStore();
PostgresEventStorage.newBuilder().shredding().buildStore();          // keys in <prefix>shredding_keys
PostgresEventStorage.newBuilder().shredding(myKmsCodec).buildStore(); // take over encryption entirely
```

- **The codec travels with the storage, so `build()` honours `.shredding(...)` as `buildStore()` does.**
  `EventStorage.shreddingCodec()` answers the codec a builder was given (empty by default, so a backend
  written before it keeps working), and `EventStore.on(storage).build()` — and every factory overload
  not handed a codec of its own — uses it; a codec given to the builder's `.shredding(codec)` wins. The storage
  never seals or unseals anything itself, which is what keeps raw mode, exports and imports seeing the
  envelope as stored. The alternative — a codec living on the store alone, wired only by `buildStore()`
  — loses because a caller taking the storage from `build()` then gets a store that refuses the very
  event types the builder was configured for, and on Postgres cannot construct the key store the
  no-arg `shredding()` stands for at all: it needs the `DataSource` the builder resolves, and one
  loaded from `db.properties` is never handed out. `StorageShreddingCodecTest` pins the two in-memory
  backends and the precedence rule; `PostgresShreddingBuilderTest` pins the no-arg case per Postgres
  version. The observer travels with the storage the same way (`EventStorage.observer()`).

- **`ShreddingKeyStore`** is the narrow seam: keep the shipped encryption, hold keys in Vault/KMS/an HSM.
- **`ShreddingCodec`** is the outer seam: take over encryption too, so key material never enters the JVM.
- **`open`/`resolveKey` answering erased means *erased*; anything else must throw `ShreddingException`.**
  This is the contract that matters most. Reported as empty, a key-store outage renders every protected
  value as erased — and projections, being at-least-once and bookmarked, write those gaps into read
  models permanently and never revisit them. `TypedEventPayloadSerializerDeserializer` rethrows a
  `ShreddingException` unwrapped (and unwraps one Jackson wrapped) precisely so that "retry later" does
  not arrive as `EventDeserializationException`, which means "never retry".
- **A key id the store has never held is not erased either; it throws.** A shredded key keeps its row,
  so every shipped key store can tell "destroyed" from "never seen", and the second means the store is
  not the one the events were sealed against: the fs store pointed at the wrong directory, the Postgres
  one at the wrong prefix or database, events imported without their keys. Reported as erased, that is
  the outage failure above applied to the *whole* store at once. The alternative — a fourth
  `KeyResolution` answer — loses because a reader could do nothing with it but throw: the value is not
  erased, and not withheld from this reader in particular. The cost is that shredded rows must stay
  (which the audit already requires): pruning one turns that subject's events from "erased" into
  unreadable, with an error naming the key. `ShreddableEventDataTest.aKeyThisStoreNeverHeldThrowsRatherThanReadingAsErased`
  pins it per backend, at the seam and through a projector.
- **The shipped codec measures the key it seals under, and keeps the label it binds unambiguous.**
  The JCE encrypts under a 128-, 192- or 256-bit AES key alike, so a key store minting the wrong
  length would otherwise seal without complaint under an envelope recording `A256GCM` for a value
  not sealed that way. `AesGcmShreddingCodec.seal` refuses a key that is not 256-bit AES material —
  `ShreddingException` naming the key and its length, nothing sealed — and refuses a key whose
  material it cannot see (`getEncoded()` null, an HSM-resident key), since the key-store seam hands
  material into the JVM and a key that never leaves its hardware belongs behind a `ShreddingCodec` of
  its own. `open` deliberately does not measure: what is sealed is sealed, and refusing to read it
  would strand the data while protecting nothing. The metadata GCM authenticates is the algorithm,
  key id, subject type, id and category joined with `|`, nothing escaped, so `seal` also refuses a
  `|` in any of those — two labels differing only in where the `|` falls would otherwise authenticate
  as one, and a sealed value could be relabelled between them with decryption still succeeding. The
  alternative — escaping the fields — loses because the authenticated string is recomputed on both
  sides and stored nowhere, so an escaped form stops authenticating every value already sealed whose
  fields hold the escape character, with nothing on the envelope to say which form it was sealed
  under; an envelope already carrying a `|` stays readable, ambiguous as it was written. The subject
  rule applies to this codec only — a codec of your own binds what it likes — and a subject is refused
  before the key store is asked, so it is not given a key row it will never use.
  `AesGcmShreddingCodecTest` pins all of it, the layout of the authenticated bytes included.
- **Nothing here needs post-quantum work.** The design uses no asymmetric cryptography, so Shor has no
  target; Grover leaves AES-256 at ~128 bits of effective security. Shredding is in fact a stronger
  position than encryption at rest generally is — the threat model is ciphertext recovered from a backup
  with the key destroyed, and no computation recovers a key that does not exist. Post-quantum only
  becomes a question inside an implementor's own codec that wraps data keys under a KEK with RSA-OAEP or
  ECIES, which is exactly the decision the seam leaves to them.
- **`alg` is recorded per sealed value**, so a store can hold several algorithms at once and change
  algorithm without rewriting history. That agility, not the choice of cipher, is the real defence
  against harvest-now-decrypt-later.

**Rotation only ever applies forward, and that is the design rather than an omission.** There is no way
to rotate a live key and re-seal what it protects, because re-sealing means rewriting stored events —
the one thing this design exists to avoid. Events staying byte-identical is what makes destroying a key
reach every copy of them (WAL, replicas, backups) with nothing to chase; a re-seal would have to reach
all of those too, and would not. So:

- **A subject whose keys are shredded gets a fresh key** for anything appended afterwards, and
  everything sealed under the old key stays sealed under it for as long as that ciphertext exists.
  Erasing twice therefore destroys two keys, not one — which is why `shred` matches on *every* key a
  subject has ever held, not just the active one.
- **What can change without rewriting anything is the algorithm**, recorded per sealed value. New
  appends can use a new one while old events keep decrypting under the one they were written with. For
  a long-lived log that agility is what rotation is usually reached for anyway.
- **A key-encrypting key can be rotated freely**, since that lives inside an implementor's own codec or
  key store and never touches the events. That is where a KMS's rotation story belongs.

**A key store can report on itself, without being able to decrypt anything.** `ShreddingAudit` —
`EventStore.shreddingAudit()`, or `ShreddingKeyStore.audit()` — answers which subjects hold protected
data and which erasures have happened, and is the *only* way to read that: the events record nothing
about an erasure, since they are never rewritten, so the key store is the whole account of it.

```java
ShreddingAudit audit = eventStore.shreddingAudit().orElseThrow();

audit.totals();                                          // subjects with live keys, live keys, shredded keys
audit.keys(KeyAuditQuery.forSubject("customer", "alice-42"));   // one person, every category
audit.keys(KeyAuditQuery.all().onlyShredded());                 // the erasure log: what, when, on whose authority
audit.categories();                                             // which categories exist, and how much under each
audit.keys(KeyAuditQuery.forKeys(keysOnAnEvent));               // are the keys this event carries still live?
```

- **`KeyRecord` carries no key material, and no method here returns any.** That separation is the whole
  reason this is a second interface rather than another method on the key store: a dashboard credential
  granted it can see *that* data is protected and *when* it was erased, and never *what* it was. The
  Postgres implementation does not merely refrain from reading `key_material` — the column is absent
  from every statement the audit issues, predicates included, so key bytes cannot reach a log or a heap
  dump through this path. That absence is also what keeps the audit working for the reporting role the
  postgres README recommends, which is granted every column *but* that one: PostgreSQL checks `SELECT`
  privilege on every column a statement references, a `WHERE` or `FILTER` clause as much as the select
  list, so "shredded" is judged by `shredded_at` — stamped by the same statement that nulls the material
  — never by `key_material IS NULL`. `PostgresShreddingReportingRoleTest` pins every audit statement
  under that role. The same privilege rule hides the column from `information_schema`, so such a role
  starts its store with `DatabaseInitMode.NONE`; `VALIDATE` would report `key_material` as missing.
- **Bounded, with no cursor.** `KeyAuditQuery` always carries a limit (default 500). A store running for
  years holds one row per subject per category and never prunes the shredded ones, and unlike an event
  query there is nothing to resume from — so an accidental full enumeration is not offered.
- **`categories()` is the inventory: which categories of personal data the store holds, and how much
  under each** (`CategoryTotals`: live subjects, live keys, shredded keys per category, most live
  subjects first). A category is the unit of erasure *and* of access, and only the key store knows
  which exist — writers choose them, and events carry them only inside sealed envelopes — so this is
  what an operator reads before deciding which categories a service is `restrictedTo`. Deriving it
  from `keys()` is wrong on any store holding more keys than the query's limit, which is why it is a
  method rather than a recipe. An erased category stays listed with zero live keys and its erasures
  counted. The default throws `UnsupportedOperationException`, like the optional SPI methods on
  `EventStorage`, so an audit written before it compiles and a caller is told rather than shown an empty
  inventory; the three shipped key stores all answer it.
- **`KeyAuditQuery.forKeys(Set<KeyId>)` is the join back from an event.** An event carries its keys as
  `dek:` tags and in each envelope, and nothing else; whether those keys still exist is the key store's
  to say. A reader holding an event — a dashboard rendering it, a support tool — asks for exactly those
  keys and can tell "protected" from "erased on … because …" without holding a key of its own. A
  primary-key lookup on Postgres, however many keys a page of events carries; the limit defaults to the
  number of keys asked for. It narrows the other parts rather than replacing them, an empty set is
  refused (it could only mean "nothing"), and a key the store never held answers nothing rather than
  failing — an envelope from another store is a miswiring the caller can see from the gap. The
  five-argument constructor stays, meaning "any key".
- **Which *events* hold data under a key is not answered here** — the key store has never seen an event.
  That is an ordinary tag query, since each event carries its keys as `dek:` tags:
  `EventQuery.forEvents(EventTypesFilter.any(), Tags.of(KeyId.TAG_KEY, record.id().value()))`.
- **Optional, like leases.** A key store fronting a KMS that does not enumerate returns empty and
  callers do without; all three shipped key stores implement it.

**The Postgres key store caches resolved keys with a TTL, default one hour**
(`PostgresShreddingKeyStore.DEFAULT_CACHE_TTL`). Without a cache, replaying a stream costs a query per
protected value; with one that never expires, an erasure performed by *another* instance would never be
noticed. An erasure performed by *this* instance drops its entries immediately, so the ttl bounds only
the cross-instance case — which makes it the outer edge of "erased" for a multi-instance deployment, and
a number worth stating in a data protection notice rather than discovering. `Duration.ZERO` disables the
cache and makes an erasure effective everywhere at once, at a query per protected value. A key that was
never seen is deliberately not cached as absent, so a shredded key still costs one query per read rather
than reporting stale data as readable.

**The cache is bounded in size too, at `DEFAULT_MAX_CACHED_KEYS` (10.000) entries, least recently used
evicted first.** The ttl bounds how stale an entry can be, not how many there are: a lapsed entry is
dropped when it is next asked for, and one never asked for again is dropped by nothing else, so a
process that resolves a key per subject over its lifetime — a projection replaying a stream of a
million subjects — would otherwise hold every one of them for good, at a few hundred bytes each. Below
the bound nothing changes; above it a key outside the working set costs one query when it comes round
again, and the four-argument constructor sets the bound for a working set that is genuinely larger.
`KeyCacheTest` pins the bound, the ttl and the eviction order without a database;
`PostgresShreddingKeyStoreCacheTest` pins that the store honours them, by counting the connections it
takes.

**A key is committed before the event sealed under it, never with it.** `keyFor` runs while the
payload is sealed, before the storage is handed anything to append, and the Postgres key store takes a
connection of its own for it even though it writes to the same `DataSource` as the events. Sharing the
`DataSource` buys the schema machinery, one set of credentials and a backup carrying both; it does not
put the two in one transaction, on this or any key store, and the order is the guarantee: mint first,
durable before it is returned, append second. A rolled-back append leaves a key row with no event under
it, which the subject's next append seals under; an event whose key was never persisted cannot happen.

**Raw mode does not decrypt**, deliberately: a wildcard stream, an export or an import sees the sealed
envelope as stored, which is what lets `EventStoreImporter` copy events with no keys and no domain
classes.

**Not every reader may read everything, and a reader that may not gets a third state: `Withheld`.**
Access to a protected value is the ability to resolve its key, so who may read what is decided on the
key seams and never in the read path. `Shreddable` is `Present`, `Shredded` or `Withheld`; a withheld
value exists, may well still have its key, and this reader does not get it. It carries the subject and
key id like `Shredded`, cannot be appended (this process never held the plaintext), and says nothing
about erasure — a reader that may not decrypt a value cannot tell whether it was erased, and is not
told; `ShreddingAudit` stays the account of erasures.

- **Why a third state rather than either existing answer.** Reported as `Shredded`, a projection renders
  "erased" for data that is not and writes that into its read model for good. Reported as a
  `ShreddingException`, it means "retry later", so a `Projector` that is merely not entitled fails the
  batch and never advances. Withheld is neither: the read completes, the projection decides how to
  render the gap, and everything the reader *is* entitled to still gets projected. Adding a case to the
  sealed interface breaks every exhaustive `switch` at compile time, which is the point — each renderer
  has to decide what a withheld value looks like. `orElse("[erased]")` call sites are the ones to review,
  since the fallback now covers both cases.
- **The seams carry it as sealed results, not exceptions.** `ShreddingKeyStore.resolveKey` answers
  `KeyResolution.Resolved | Erased | Withheld`, and `ShreddingCodec.open` answers
  `Unsealed.Plaintext | Erased | Withheld`; each is the one abstract read method of its seam, and the
  refusal carries the same name on both, and on `Shreddable`, so that one word follows a refused key
  from the key store through the codec to the value a reader is handed. A sealed
  type rather than a `ShreddingException` subtype because the difference between "erased", "withheld"
  and "down" is the most important contract in this subsystem, and a `catch (ShreddingException)`
  retry loop would swallow a refusal by accident. The alternative — a two-answer
  `Optional`-returning method beside it, abstract, with the three-answer one a default derived from
  it — loses because such a method cannot say withheld, so the derived default never would: a new key
  store or codec would implement what nothing in the library calls and inherit an answer it never
  chose, and a `Withheld` could only be smuggled through the two-answer method as an exception, which
  is the retry-loop conflation above.
- **Three ways a reader is limited, from cheap to hard:**
  ```java
  // a reporting service: typed events, none of the personal data. Without a codec it could not open
  // the stream at all -- registering a type that declares a Shreddable fails on a store with none
  PostgresEventStorage.newBuilder().shredding(ShreddingCodec.withholdingAll()).buildStore();

  // a service that reads names and never addresses: an in-process policy on the category
  PostgresEventStorage.newBuilder().shredding(AesGcmShreddingCodec.over(keyStore).restrictedTo(Set.of("identity"))).buildStore();

  // the hard boundary: a key store that refuses keys this role is not granted
  KeyResolution resolveKey ( KeyId key ) { ... return new KeyResolution.Withheld("vault: 403"); }
  ```
  `restrictedTo` decides on the category the envelope carries in the clear, before any key lookup, so a
  denied category costs no key-store traffic. It is symmetric — the codec seals nothing outside its
  categories either, and such an append fails as an `EventSerializationException` with nothing stored —
  and it passes erasure and audit through whole, because an erasure that silently left another category
  readable while reporting success is the worst outcome an erasure can have. It is a data-minimisation
  boundary a deployment declares for itself, not a security boundary: the process still holds the codec.
  The security boundary is the key store's refusal — a KMS policy per service role, or on Postgres a
  role granted every column of `shredding_keys` *except* `key_material`, which `PostgresShreddingKeyStore`
  recognises by SQLSTATE 42501 and reports as `Withheld` (cached for the key ttl). Row-level security on
  that table does **not** produce a denial: a hidden row reads as erased. The two compose.
- **The unit of access is the unit of encryption: the `Shreddable` value, partitioned by `category`.**
  "Name but not address" is two wrapped values under two categories, chosen when the event is written,
  not one `Shreddable<ContactDetails>` holding both. Nothing inside one sealed value can be handed out on
  its own — the alternative, decrypting and blanking fields, leaves the plaintext in the reader's memory
  and needs the annotation-plus-split design already rejected above. A category is forward-only: a
  value sealed under `default` cannot be re-categorised without re-sealing, which means rewriting the
  event. Each category is one more key row per subject and one more `keyFor` query per append that
  carries it, so a handful per subject is the intended scale, not one per field.
- **A withheld reader still sees the pseudonymous subject id, the category, and the `dek:` tags.** That
  is unchanged and by design, and it is why the rule that subject ids and tags must not themselves be
  personal data is load-bearing for the PII-less reader.

**A component that was a plain field when its events were written cannot be read as a `Shreddable`.**
The stored value is bare, so nothing can say whose data it is, and the read fails with a message saying
to migrate the events via `EventStoreImporter.transform` or to read the old shape through a
`@LegacyEvent` upcaster. Guessing a subject would leave old personal data unprotected and unerasable.

`ShreddableEventDataTest` in the TCK pins all of this per backend, against *that backend's* key store:
the two-subject erasure, the collection case, a record whose constructor rejects nulls surviving erasure,
category independence and the whole-person erasure across every category (through a restricted codec
too), idempotent erasure and a fresh key afterwards, the `dek:` tags, the audit view
(including, reflectively, that `KeyRecord` cannot carry key material), that an unreachable key store
throws instead of reporting the data as erased and so does one asked for a key it never held — and, for entitlement, that a withholding codec reads the
typed events with every value withheld, that a restricted codec reads its categories and withholds the
rest without a key lookup, seals nothing outside them and erases everything, that a withheld value says
nothing about erasure, that a key store's `Withheld` reads as withheld and a projector advances over it,
and that a withheld value cannot be appended again. `ReaderEntitlementTest` in the api module pins the
seams below the store.

