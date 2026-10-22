# Health: asked, not cached, and never the caller's failure

The rules in brief are in the root `CLAUDE.md`, under "Health". This file holds the full design: each rule, the reasons for it, and the alternatives that were rejected.


- **One method on the SPI, answering a list of checks with a derived status.** A storage judges what
  only it can see — whether its database answers, whether its channels listen, whether its reads are
  held behind a barrier, whether callers queue for its pool — and hands that to whatever renders a
  health endpoint. The status of the whole is the worst of its checks and is computed, not stored, so
  a `StorageHealth` cannot say `UP` beside a check that is `DOWN`. The alternative — a typed record per
  backend (`PostgresHealth` with a field per aspect) — loses because a health endpoint renders every
  backend the same way and would need a case per backend, and a backend adding a check would be a
  breaking change to the record. Named checks with JSON-scalar details render as they are, and a new
  check is a new name.

- **On `EventStorage`, not only on the backend's own type.** The observer and the shredding codec
  travel with the storage for the same reason: whatever holds the storage — a bounded context, a
  deployment that widened `PostgresEventStorage` to `EventStorage` — can ask without a downcast. That
  `isNotificationsAvailable()` sat on the Postgres type alone is what made a deployment holding a plain
  `EventStorage` unable to report it.

- **The default answers `UNKNOWN`, not `UP`.** A backend written before the method existed has checked
  nothing, and a default saying `UP` would have it claim health it never judged. `UNKNOWN` is what a
  health endpoint can map deliberately; a lie it cannot tell from the truth.

- **It never throws on account of the storage.** A health endpoint whose call throws reports that the
  health check failed, which says nothing about the storage. A closed storage is the one state where
  every other operation throws, and here it is a `DOWN` answer — reported alone, since its pools may
  be closed and asking them would fail for a reason that is already known. An argument that is not a
  positive timeout is still refused: that is the caller's mistake, not the storage's state.

- **Bounded by the caller's timeout, with at most one round trip in flight.** The database probe runs
  on a virtual thread of its own and is waited on for the timeout. A pool that hands out no connection
  waits for its own connection timeout — typically tens of seconds — and a socket whose peer vanished
  without a FIN waits for nothing at all, so the wait cannot be left to them. A probe that outlives
  the timeout stays in flight, the answer reports how long it has been, and the next call waits on that
  same probe rather than starting another: a database that stopped answering is asked once, not once
  per probe interval per instance, which is the moment it can least afford extra connections. The
  probe also asks the server to give up after the timeout (`setQueryTimeout`), so a round trip nobody
  waits on any more does not hold a backend. The same shape — calls on threads of their own, waited on
  for a budget, never re-issued while in flight — is what the event modeling framework's leader elector
  does with lease calls, for the same reason.

- **`SELECT 1`, not a query against this store's tables.** The check answers "can this store reach its
  database on a pooled connection", which is the question a probe is asked every few seconds. A query
  against the events table would add a schema check, which `build()` already makes under `ENSURE` and
  `VALIDATE`, and which nothing between two probes can plausibly break; and it would be priced by the
  table's indexes rather than by the round trip. Not reported as an observation either: a probe is
  not an operation a caller performed, and at one per few seconds per instance it would dominate the
  operation counts an observer is there for.

- **Degraded, not down, for everything that leaves reads and writes working.** A channel that does
  not listen, a visibility stall and a saturated pool all leave every call succeeding — slower, or
  without waking subscribers — and none of them is fixed by restarting the process or by taking it out
  of a load balancer: the channel reconnects by itself, the stall is a transaction elsewhere in the
  cluster, the saturation is load. Reporting them `DOWN` would have a health endpoint that maps `DOWN`
  onto "restart me" or "stop sending traffic" turn a database-side condition into an outage of every
  instance at once. Which of them should still fail readiness is the application's call; the store
  states what it sees.

- **The visibility stall is judged at the moment of asking, on the notification withheld longest.**
  The append monitor already parks notifications behind the barrier and logs a stall past ten seconds;
  health reads the same state, published by the monitor as one volatile value since the monitor's own
  map belongs to its thread. The age is computed when health is asked rather than copied from the
  monitor's last pass, so it does not lag by a poll slice, and the observer callback fires at the same
  two moments the log lines do. A stall is reported cleared when the storage closes — the same reason
  its channels are reported down then: a gauge must not keep showing a stall for a storage that is gone.
  What it cannot see is a stall during which nothing is appended, since nothing is then withheld; that
  limit is the log line's already, and external monitoring on the database is still the complete
  answer (see the postgres module notes).

- **Pool counts for any HikariCP pool, the caller's included.** Reading a pool's counts changes nothing
  about it, so whether the builder created the pool decides who closes it, not whether it is reported.
  A pool of another kind is not reported: the counts exist only where the pool exposes them, and a
  check reporting nothing would be noise. `DEGRADED` while anyone waits, since a caller waiting for a
  connection is an operation of this store waiting its turn.
