# Guacamole HA Clustering — status through P4b

What is built, what it cost, and what the implementation proved the design wrong
about. Written after P4b merged, 2026-09-18.

- Design: `specs/2026-09-06-guacamole-ha-clustering-design.md`
- Plans: `plans/2026-09-06-guacamole-ha-p0-p1.md` through `plans/2026-09-18-guacamole-ha-p4b.md`
- Live measurements: `deploy/README.md` sections 1-11

Every phase below is merged to `main` and was verified on a two-replica
deployment in the `remote-access` namespace of the devqa EKS cluster, not only
by its tests.

## What works

| Phase | Capability | The measurement that proves it |
|---|---|---|
| P0/P1 | guacd pool, least-loaded selection, route table | 6 connections split 3/3 across two guacd pods |
| P2 | Connection limits enforced across replicas | `max-connections=1`: replica A 200, replica B 409 |
| P3a | Active-connection listing and kill from any replica | Kill issued on B returned 204 in 0.062 s; A closed its tunnel 4 ms later |
| P3b | Share keys redeemable on any replica | One guacd connection, two users, one arriving from each replica |
| P4a | Brute-force bans counted across replicas | 2 failures on A + 2 on B trips a limit of 4; a 5th on either is refused |
| P4b | Session survives the loss of its replica | Replica force-deleted; token still returns 200 on the survivor |

Clustering is off by default. With `cluster-enabled=false` every patched path
behaves as upstream, and each phase's verification includes that control.

## What is deliberately not built

- **Tunnels never migrate.** Losing a replica costs a user their sessions, not
  their login. Reconnecting is the recovery path.
- **guacd session state is never replicated.** A guacd pod death drops its
  sessions.
- **Authorization is never cached.** It is re-derived from the database on every
  rebuild, which is what makes a revocation take effect immediately.
- **Redis Cluster mode is not supported**, and cannot be without code changes.
  See *Redundancy* below.

## Six things the implementation proved the design wrong about

The design document was accurate on nearly every line reference. These are the
places it was not, each found by running the code rather than reading it.

**1. `TrackedActiveConnection` has no usable setters** (§4.5 claims it does).
`setConnectionIdentifier` throws, and `getConnectionIdentifier()` reads through
to the connection object, so a remote entry fails on serialisation whether or
not the setter is called. P3a loads the real `ModeledConnection` from the shared
database instead. Cost: one build and deploy cycle.

**2. Clearing failures on success is not parity** (§5.7 specifies `DEL` on
success). `InMemoryAuthenticationFailureTracker` does not do that — success and
a received request take the identical path, and only time removes a count.
Implementing the spec would also have let an attacker who guesses one valid
account clear their own address. P4a implements parity and tests the deviation.

**3. Cross-replica join was never reachable in P1.** The routing half was correct,
but a join cannot be *initiated* from a replica that does not own the session,
because the active-connection directory was replica-local until P3a. Measured
with a live session: `podA -> {...}`, `podB -> {}`.

**4. The share-key refactor is not the largest diff in the design** (§4.3). The
coupling is four facts — the connection, the guacd connection ID, who shared it,
and the sharing profile — so `SharedConnectionDefinition` was widened rather
than subclassed.

**5. Cluster code cannot live in its own module.** Both the web application and
every extension need those classes, and a type crossing that boundary must be
loaded from exactly one place. A module of its own is copied into the WAR *and*
into each extension's nested jars, so the same name becomes two `Class` objects.
The package now lives in `guacamole-ext`, which is in the WAR once and which
every extension already takes as `provided`. `ClusterModule` moved to the JDBC
extension: it was the only one of 22 classes touching Guice, and keeping it out
is what lets the rest be shared.

**6. Two defects predated the phase that found them.** A share-key join carried
no cluster seat token, so its tunnel registration was keyed on null and threw as
soon as clustering was enabled — a P2 defect. And with clustering *off*,
`NoOpClusterStore.lookupRoute` returns null, so P1's fail-closed join guard
rejected every join with a 404 — a P1 defect, live since P1, affecting the
default configuration. Neither had been exercised because nobody had used
connection sharing on a clustered deployment.

## What only a deployment could have caught

All 153 tests passed while each of these was broken. Every one needs a
classloader, a servlet container, or a second replica to exist at all:

- Duplicate cluster classes across classloaders (`argument type mismatch`).
- Guice itself duplicated once the classes were shared (`ClusterModule does not
  implement the requested interface com.google.inject.Module`).
- Every provider arriving wrapped in `AuthenticationProviderFacade`, so an
  `instanceof` check against the rehydration SPI could never be true — the
  feature was unreachable, and a "does an invalid token get rejected" test would
  have passed for entirely the wrong reason.
- `Credentials` cannot be fabricated without a request; its constructor
  dereferences one.
- A replica racing its own logout broadcast, reporting 404 for a logout that had
  succeeded.
- `RESTExceptionMapper` calling `destroyGuacamoleSession(null)` on every
  unauthorized response, which an unconditional hash turned into a 500.

## Redis

**Redis is security-sensitive as of P4b.** Before it, Redis held routing and
counting state. It now holds session identity: a token hash plus a username is
enough to learn who is logged in and from where. Authentication and TLS are
requirements, not hardening.

**A dedicated instance, not the shared cache.** The two uses conflict. A cache
evicts, and evicting one member of `guac:idx:*` or `guac:seat:*` corrupts
concurrency limits and guacd routing *silently* — nothing errors, the numbers
are simply wrong afterwards. `FLUSHALL` also crosses logical databases, so
giving Guacamole its own database number protects nothing. `deploy/redis-hardened.yaml`
carries a dedicated node with `noeviction`, persistence, an ACL scoped to
`guac:`, and a NetworkPolicy.

**Redundancy means replication, not sharding.** Redis Cluster cannot be used:
`RedisClusterStore` builds a `RedisClient` rather than a `RedisClusterClient`,
and `acquireSeats` passes one key per limit-bearing index to a single `EVAL`,
which spans hash slots and is rejected with `CROSSSLOT`. Sentinel needs no code
change, because Lettuce resolves the primary from a `redis-sentinel://` URI.
`deploy/redis-sentinel-ha.yaml` carries that shape. Note that Guacamole already
degrades rather than fails during a Redis outage, so Sentinel shortens a
degraded window rather than preventing an outage.

**The ACL is derived from the code, not guessed.** `deploy/redis-acl.md` records
both the ACL and the commands that regenerate its command list. Two entries
matter most: `&guac:*` for channels, because kill, share revocation and logout
are all pub/sub and a key-only pattern disables them silently; and denying
`FLUSHALL`, which exists in the code solely for the test suite.

## Remaining

- **P5**: Helm packaging, Prometheus metrics, TLS to Redis, and the
  `ClusterModule` Lettuce-client leak on Tomcat redeploy. Sentinel leaves P5 if
  a managed Redis with a stable primary endpoint is used instead.
- **Spec §7.3 scenarios 9 and 10** — clock skew, and cluster-wide ban counting
  under concurrency — were never executed.
- **Lettuce's own DEBUG logging** has not been checked against an authenticated
  Redis. The application no longer logs the URI; whether the client does is
  unverified.
