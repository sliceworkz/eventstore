# Schema migration: findings and recommendation

Review briefing 13 — *No schema migration path; `ENSURE` cannot update an existing object*.

Everything below was verified against PostgreSQL 17 and 18 in Testcontainers by
`PostgresSchemaDriftTest` (postgres module, `src/test`). That class asserts the **current, broken**
behaviour on purpose: it is the evidence for this document and the regression net for whatever
lands next, and its assertions are meant to be inverted by the fix.

---

## 1. Confirmation results

### 1.1 A stale function survives `ENSURE` — confirmed

`testStaleFunctionSurvivesEnsure`. Create the schema, replace `PREFIX_notify_event_appended` with a
body that notifies a different channel, run `ENSURE` again. `ENSURE` logs
`Database schema validation completed successfully` and the replaced body is still in `pg_proc`.
The body shipped with the release never reaches the database.

Cause: `ensure-schema.sql` is create-if-absent throughout. Tables and indexes use `IF NOT EXISTS`;
the two functions and two triggers are wrapped in `DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_proc …)
THEN CREATE FUNCTION …` guards. Nothing is `CREATE OR REPLACE`.

### 1.2 A stale function survives `INITIALIZE` — confirmed (the headline)

`testStaleFunctionSurvivesInitialize`. Same setup, but run `INITIALIZE` — documented on
`DatabaseInitMode` as *"Drop all event store objects and recreate them from scratch"*. The events
table really is dropped and recreated (the test asserts it comes back empty), and the hijacked
function body is **still there afterwards**.

Cause: `drop-schema.sql` drops the two tables and nothing else. The triggers go with the tables via
`CASCADE`; the functions do not — they are schema-level objects, not table-dependent ones. So when
`ensure-schema.sql` runs immediately after, its `IF NOT EXISTS` guard finds the old function present
and declines to recreate it. The freshly created trigger is then wired to the old body.

The mode that exists to give a clean slate does not give one. This is the sharpest form of the
problem: there is currently **no mode, and no supported operation, that updates a function body**.

### 1.3 The drift is functional, not cosmetic — confirmed

`testStaleFunctionBreaksNotificationsAfterInitialize`. With the stale body in place, a listener
registered on a storage that has just run `INITIALIZE` receives nothing when a row is inserted: the
trigger fires, calls the old function, and notifies the old channel. The store's LISTEN/NOTIFY path
is dead while the store reports a healthy, validated schema — projections simply stop waking up.

### 1.4 Validation does not notice — confirmed

`testValidateDoesNotNoticeStaleFunction`. `VALIDATE` passes against the drifted database.

`checkDatabase()` asks only whether named objects **exist**:

| checked | not checked |
|---|---|
| table exists | — |
| column type and nullability | column default |
| foreign key constraint exists (by name) | — |
| function exists (by name) | function **body**, arguments, return type, language |
| trigger exists (by name) | trigger **timing**, orientation, event, which function it calls |
| index exists (by name) | index **method**, **columns**, **uniqueness**, predicate |

### 1.5 The boundary of what validation catches

`testColumnDriftIsCaught` confirms the column checks work: dropping `NOT NULL` from `stream_purpose`
makes `VALIDATE` fail with a message naming the column and the nullability mismatch. That part is
fine.

`testIndexTriggerAndDefaultDriftAreNotCaught` maps the gap. All four of these pass both `ENSURE` and
`VALIDATE` without a word:

- `idx_events_stream_tags` replaced by a **btree index on `event_type`** — the GIN index the DCB read
  path depends on is gone; only the name is checked.
- `idx_events_stream_idempotency` replaced by a **non-unique** index — idempotency is silently no
  longer enforced. This one is a correctness hole, not a performance one.
- `stream_purpose` default reverted to `''` — the exact pre-alignment state `CLAUDE.md` documents as
  needing a manual fix.
- `table_insert_trigger` changed from `AFTER INSERT` to `BEFORE INSERT`.

So validation catches *additive* drift (a missing object fails loudly, correctly) and is blind to
*mutative* drift. That matters for the recommendation: `ENSURE` already delivers brand-new objects to
old databases correctly, because a new object is absent and `IF NOT EXISTS` creates it. The gap is
precisely **changing an object that already exists** — and, for `VALIDATE`-only deployments, noticing
that it wasn't changed.

### 1.6 No version marker — confirmed

`grep -rn "schema_version"` over the SQL and Java returns nothing. The store cannot distinguish a
v0.9 database from a v0.10 one, so it cannot know which of the manual migrations below an operator
has run.

### 1.7 The manual migrations already documented

`CLAUDE.md` carries these as prose for operators to run by hand:

1. `ALTER TABLE <prefix>events ALTER COLUMN stream_purpose SET DEFAULT 'default';` (line 572)
2. `ALTER TABLE <prefix>events DROP CONSTRAINT <prefix>events_idempotency_key_key;` plus
   `CREATE UNIQUE INDEX <prefix>idx_events_stream_idempotency …` (line 573)
3. The `btree_gin` extension and `idx_events_stream_tags` (PostgreSQL notes) — this one needs no
   manual statement, because both objects are additive and `ENSURE` does create them.

Three schema changes in the project's history; two of them could not be automated. That is the
evidence the problem is recurring rather than hypothetical.

---

## 2. Concurrent `ENSURE` — a separate and more urgent finding

**Answer: `ensure-schema.sql` is not safe to run concurrently, and it fails most of the time, not
rarely.**

`testConcurrentEnsureFromSeveralInstances` starts 8 instances simultaneously against a database that
does not have the schema yet, repeated over 10 rounds on a fresh prefix each time:

| backend | rounds with a failing instance | instances that failed to start |
|---|---|---|
| postgres:17 | 10 of 10 | **64 of 80** |
| postgres:18 | 10 of 10 | **64 of 80** |

The failures are catalog races:

```
ERROR: duplicate key value violates unique constraint "pg_type_typname_nsp_index"
  Detail: Key (typname, typnamespace)=(<prefix>events, 2200) already exists.
ERROR: duplicate key value violates unique constraint "pg_class_relname_nsp_index"
  Detail: Key (relname, relnamespace)=(<prefix>events_event_position_seq, 2200) already exists.
ERROR: relation "<prefix>events" already exists
```

`CREATE TABLE / INDEX / EXTENSION IF NOT EXISTS` is not atomic against a concurrent creator: the
existence check and the catalog insert are separate steps, so several transactions all find the
object absent and all try to create it. PostgreSQL documents this. The `DO $$ … IF NOT EXISTS
(SELECT 1 FROM pg_proc …)` guards have the same shape and the same race.

Two things bound the damage, and neither makes it acceptable:

- **The whole script runs as one transaction** (`executeSqlScript` sets `autoCommit(false)`, executes
  the script as a single statement, commits). A loser rolls back entirely, so no half-built schema is
  left behind — the test asserts the schema is complete and valid after each round.
- **The window is first creation only.** Once the objects exist, every `ENSURE` is a no-op sweep of
  existence checks and cannot race.

But the default mode is `ENSURE`, and the normal way to deploy is several replicas at once. The first
rollout onto a fresh database therefore has ~80% of instances failing to start with an opaque
`pg_type_typname_nsp_index` error. They recover on restart, so this shows up as a crash-looping
deployment that eventually settles rather than as a permanent outage — which is arguably worse,
because it looks like a flake and gets ignored.

This is fixable independently of everything else, and cheaply: take a
`pg_advisory_xact_lock` keyed on the prefix at the top of the ensure path, exactly as the append path
already does per stream. The project has the pattern in place and documented.

---

## 3. Recommendation

**Land C now, then A. Adopt D as part of A. Do not take B.**

### Step 1 — C: make every object idempotently replaceable (immediately)

In `ensure-schema.sql`, replace the `DO $$ … IF NOT EXISTS` guards with:

```sql
CREATE OR REPLACE FUNCTION PREFIX_notify_event_appended() …;
DROP TRIGGER IF EXISTS table_insert_trigger ON PREFIX_events;
CREATE TRIGGER table_insert_trigger AFTER INSERT ON PREFIX_events …;
```

and in `drop-schema.sql`, drop the functions too:

```sql
DROP FUNCTION IF EXISTS PREFIX_notify_event_appended() CASCADE;
DROP FUNCTION IF EXISTS PREFIX_notify_bookmark_placed() CASCADE;
```

Add the advisory lock around the whole ensure path at the same time — it is the same file and the
same release note, and it removes the concurrency finding above. `DROP TRIGGER` + `CREATE TRIGGER`
takes an `ACCESS EXCLUSIVE` lock on the events table for the duration of the transaction; that is
brief, but it is a reason the lock and the one-transaction property both matter.

This is cheap, needs no dependency, no DDL of its own, and no version marker. It makes function and
trigger bodies genuinely self-healing on every start, and it makes `INITIALIZE` mean what it says.
It does **not** solve data migrations: the two `ALTER TABLE` migrations in `CLAUDE.md` still cannot
be expressed this way, and a store still cannot tell which schema generation it is talking to.

### Step 2 — A: a prefixed version table with ordered steps

```sql
CREATE TABLE IF NOT EXISTS PREFIX_schema_version (
    version     INT PRIMARY KEY,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    description TEXT NOT NULL
);
```

with a list of numbered steps in the library applied in order, the whole run under a
`pg_advisory_xact_lock` keyed on the prefix. `ENSURE` becomes *create if absent, then apply every
step above the recorded version*. `VALIDATE` becomes *the recorded version is the version this
library expects* — a far stronger check than today's existence sweep, and one that does not need
validation to learn how to compare function bodies and index definitions.

Keeps the zero-dependency property, which for a library is worth more than the convenience of a
migration framework.

### Why not B (Flyway/Liquibase)

The project's stated posture on dependencies is visible in the tree: `uuid-creator` is *optional*,
with a fail-fast message when it is absent. Making a schema-migration framework a hard dependency of
the storage backend contradicts that, and it forces a choice of framework on every consumer — a
library cannot assume it owns the application's migration story, and an application that already runs
Flyway will not accept a second, competing migrator writing to its database.

The middle path — "if Flyway is on the classpath, hand it our migrations" — is worth keeping in mind
but should not be the mechanism. Option A's step list is the thing that would be handed over, so
building A first loses nothing and an optional Flyway adapter stays possible later.

### D — refuse to start on a mismatch: yes, as part of A

Today a store runs happily against a database older than it expects. Once a version marker exists,
`VALIDATE` and `NONE`… should behave as follows:

| mode | database version < library | database version > library |
|---|---|---|
| `NONE` | unchanged: no check at all | unchanged |
| `VALIDATE` | **fail**, naming both versions and the steps not applied | **fail** — an older library against a newer database is not safe |
| `ENSURE` | apply the missing steps | **fail** — never downgrade |
| `INITIALIZE` | drop and recreate at the current version | drop and recreate at the current version |

Note that for *additive* drift `VALIDATE` already fails loudly and correctly today (a database
missing `idx_events_stream_tags` fails with `Required index … does not exist`). D closes the mutative
half of that.

---

## 4. The four questions the briefing asks any proposal to answer

### The prefix

The version table is prefixed like everything else: `PREFIX_schema_version`. Two prefixed stores in
one database therefore have two version tables and version independently, which is right — they are
separate stores that happen to share a database, and they may well be on different library versions
during a staged rollout. The advisory-lock key is a SHA-256 of the prefix, the same construction the
append path already uses for `(prefix, stream_context, stream_purpose)`, so two prefixes never block
each other.

### Permissions

The briefing's premise needs one correction, verified on PostgreSQL 17:

**`btree_gin` is a trusted extension** (`pg_available_extension_versions.trusted = t`, true since
PostgreSQL 13), so it does **not** need superuser. A non-superuser that owns the database — or has
`CREATE` on it — installs it fine. A role without `CREATE` gets
`permission denied to create extension "btree_gin"`. And critically: **once the extension is
installed, `CREATE EXTENSION IF NOT EXISTS btree_gin` succeeds for an unprivileged role**, emitting
only a `NOTICE … skipping`. So the DBA-installs-once / app-runs-unprivileged split already works
today for that line, which softens review item 14 considerably. It is still worth splitting the
extension out so a `VALIDATE`-only app never issues DDL at all.

For the split deployment (app runs `NONE` or `VALIDATE`, a DBA applies DDL) the mechanism must emit
its steps as a script a DBA can apply. The project already has exactly this wiring: the
`quickstart.ddl.sql` DBA script is **generated at build time** from `ensure-schema.sql` by the
replacer plugin (`sliceworkz-eventstore-infra-postgres/pom.xml`, stripping `PREFIX_`). Migration
steps must flow through the same generation, so a DBA gets `quickstart.migrate-<n>.sql` alongside it,
and the version row must be written by that script too — otherwise a DBA-migrated database reports an
old version and `VALIDATE` fails against a database that is in fact current.

### The existing manual migrations

**Forward-looking, with a documented one-time baseline.** The version table starts at the current
release; the three historical migrations stay documented in `CLAUDE.md` as a one-time checklist for
databases created before it, and are not reimplemented as steps 1..3.

Why: the library cannot detect which of them an operator already ran — that is the whole point of
having no version marker — so a step that tries to apply them must be written to be safe on a
database where they are already applied *and* on one where they are not, guessing from the shape of
the schema. Two of the three are `ALTER`s against constraints whose names depend on how the database
was created. Encoding that guesswork as step 1 makes the mechanism's first act its least trustworthy
one. A documented baseline is honest about where the automation begins.

### Backfill for existing users

A database with the events table but no `PREFIX_schema_version` table is an existing pre-mechanism
database. It is recognised exactly that way — table absent, events table present — and is recorded as
**the baseline version**, i.e. the release that introduces the mechanism, on the stated assumption
that the operator has applied the documented manual migrations. `ENSURE` writes the baseline row and
proceeds; `VALIDATE` should report clearly that it is assuming the baseline rather than silently
accepting it.

A database with *neither* table is a fresh install: create everything and record the current version
directly.

---

## 5. What briefing 11 should do in the meantime

Briefing 11 (statement-level `NOTIFY` trigger) needs to replace both `PREFIX_notify_event_appended`
and `table_insert_trigger` on databases that already have them. Findings 1.1–1.3 are exactly that
case, so **shipping briefing 11 on today's `ensure-schema.sql` would deliver the new trigger to fresh
databases only, leave every existing database on the row-level trigger, and report success** — with
no way for an operator to tell which behaviour they have.

Recommended order:

1. **Land step C first, as its own change.** It is small, self-contained, has no DDL of its own, and
   is the minimum that makes briefing 11 correct on existing databases. Fold the advisory lock in
   with it.
2. **Briefing 11 then writes ordinary `CREATE OR REPLACE FUNCTION` + `DROP TRIGGER IF EXISTS` /
   `CREATE TRIGGER`** and needs no special handling of its own.

If the two are worked in parallel and C cannot land first, briefing 11 should hand-roll
`CREATE OR REPLACE` + `DROP TRIGGER IF EXISTS` **for its own two objects only**, and leave the
bookmark function's guard alone, so the later C change is a clean generalisation rather than a
conflict. Either way the two changes touch the same region of `ensure-schema.sql` and the order must
be agreed before either writes SQL.

Option A is not on briefing 11's critical path and should not block it.
