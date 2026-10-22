# Listeners: notified holding none of the storage's locks

**A storage notifies its `EventStoreListener`s after it has released every lock of its own.** The
in-memory backend does its work under its monitor (`synchronized`), returns the notifications that work
produced, and delivers them once the monitor is released; the Postgres backend delivers on its
LISTEN/NOTIFY monitor threads, which hold none of the storage's locks to begin with.

## Why

A listener runs code the storage does not control, and what it waits for is its own business: a logger's
appender lock, an executor's queue, a thread it hands the notification to. Notified with the storage
locked, two things go wrong.

- **A listener waiting for a thread that needs the storage never gets an answer.** That thread blocks on
  the lock the notifying thread holds while it waits for the listener.
- **On Java 21 the whole process can stop without any listener touching the storage.** A virtual thread
  that waits while holding a monitor, or waits to enter one, pins its carrier thread, and there are as
  many carriers as cores. A listener that waits for a lock under the storage's monitor pins one carrier;
  threads waiting to enter the monitor pin the others; the lock's next owner is a virtual thread that
  then has no carrier to run on. Nothing is thrown or logged. Every thread that touches the storage or
  that lock stops for good.

The ordinary shape on four cores: a bookmark listener logging at DEBUG under the in-memory storage's
monitor, three projector threads waiting to enter it, and the logger's appender lock handed to a projector
with no carrier left. Logging at DEBUG makes it likely; any lock a listener takes makes it possible.

## Rejected alternatives

- **A `ReentrantLock` in place of the monitor.** A thread waiting on a `ReentrantLock` does not pin its
  carrier, so the deadlock goes. But every listener would still run with the storage locked, so a slow
  listener would stall every reader and writer, and a listener waiting for a thread that reads the
  storage would still never get an answer.
- **Delivering on a thread of the storage's own, as Postgres does.** It also releases the lock, and it
  would make a listener's own slowness nobody else's. It costs ordering guarantees and a thread per
  storage for a backend whose listeners are, in practice, the event store's own stream handles, which
  already hand every notification to an executor. Delivering on the caller's thread after the lock keeps
  the in-memory backend's listeners told before the append or bookmark call returns.

## What it changes for a listener

Notifications of two operations racing on different threads may now reach a listener in either order.
That was already the case on Postgres, and the listeners the store builds treat a notification naming a
reference they have already passed as caught up (`OptimizingAppendListenerDecorator`), so nothing is lost:
the newest notification still arrives.

`ListenerMayUseTheStorageTest` in the TCK pins it per backend: a listener that hands a read of the storage
to another thread and waits for it gets its answer, for an append, a bookmark and a read position alike.
