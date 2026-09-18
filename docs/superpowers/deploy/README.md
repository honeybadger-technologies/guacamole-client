# Two-Replica Cluster Deployment

Runs two Guacamole web application replicas against a pool of two `guacd`
instances, coordinated through Redis.

**This file grew phase by phase and is now the record of all of them.** Sections
1-3 were written for P1 and sections 4-11 were appended as later phases landed,
so a section describes what was true when it was measured. For the programme as
a whole -- what is built, what the implementation proved the design wrong about,
and what is left -- read `../HA-CLUSTERING-STATUS.md` first.

| Want | Read |
|---|---|
| What each phase delivers, and its live measurement | sections 1-11 below |
| Which manifest to apply | *Files* below |
| Why Redis needs authentication as of P4b | section 11, and `redis-acl.md` |
| The programme summary | `../HA-CLUSTERING-STATUS.md` |

## Files

| File | Use |
|---|---|
| `guacamole-deployment.yaml` | the web application, two replicas |
| `guacd-headless-service.yaml` | the guacd pool and its headless Service |
| `redis-hardened.yaml` | **the Redis to deploy** -- authenticated, `noeviction`, persistent, NetworkPolicy |
| `redis-sentinel-ha.yaml` | optional: three nodes and three Sentinels, for a shorter degraded window |
| `redis-acl.md` | the ACL, how its command list was derived, and preflight checks for a shared Redis |
| `redis.yaml` | **superseded.** The original unauthenticated single pod, kept only because sections 1-10 were measured against it |
| `ingress-sticky.yaml` | sticky-session Ingress |
| `hpa.yaml` | autoscaling for both tiers |

What P1 delivers, and therefore what is worth verifying in sections 1-3:

1. **Multi-guacd load spreading** — new connections go to the least-loaded
   `guacd` in the pool.
2. **Join routing** — when a join happens, it reaches the `guacd` already
   hosting the session. Note the limit measured below: in P1 a join can only be
   *initiated* on the replica that owns the session.
3. **Fail-closed routing** — if the route is gone, the join is refused rather
   than silently opening a new desktop.

## Build the image first — this is not optional

**The upstream `guacamole/guacamole` image contains none of this code.** The
cluster module and the patched JDBC extension only exist in an image built from
this repository. Deploying the stock image produces a stack that starts
normally, spreads nothing, and verifies nothing.

```bash
cd <repository root>
docker build -t guacamole-cluster:1.6.1 --build-arg MAVEN_ARGUMENTS=-DskipTests .
```

`--build-arg MAVEN_ARGUMENTS=-DskipTests` is required. The Dockerfile defaults
to `-DskipTests=false`, and `guacamole-cluster`'s tests are Testcontainers tests
that need a Docker daemon — which the build container does not have. Run those
tests on the host instead:

```bash
mvn -pl guacamole-cluster test
```

Two notes on the build:

- `mvn package` runs `generate-license-files` (bound to `generate-resources`)
  over the `guacamole` webapp module. On a host with a stale
  `guacamole/src/main/frontend/node_modules` this fails with `Missing license
  information in LICENSE for <package>`. A clean container build installs the
  frontend fresh and does not hit it. If it does, add
  `-DdownloadMissingLicenses` to `MAVEN_ARGUMENTS`.
- No separate layering step is needed. The extension is packaged as a
  jar-of-jars and already bundles `guacamole-cluster`, `lettuce-core`,
  `reactor-core` and the netty modules. Confirm with:

  ```bash
  unzip -l extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-postgresql/target/*.jar \
      | grep -E 'cluster|lettuce'
  ```

If your cluster pulls from a registry rather than a local daemon, tag and push,
then update `image:` in `guacamole-deployment.yaml` to match.

## Apply order

Redis and `guacd` must be ready before the webapp starts — the webapp opens its
Redis connection while the Guice injector is built, and resolves the `guacd`
headless Service on the first connection.

```bash
kubectl apply -f redis.yaml
kubectl apply -f guacd-headless-service.yaml
kubectl rollout status deploy/redis deploy/guacd

kubectl apply -f guacamole-deployment.yaml   # ConfigMap + Deployment + Service
kubectl rollout status deploy/guacamole

kubectl apply -f ingress-sticky.yaml

# Autoscaling. Requires metrics-server and the resource requests already
# declared in the deployment manifests above.
kubectl apply -f hpa.yaml
```

A database is still required — this stack carries only the cluster pieces. Add
your existing PostgreSQL/MySQL Service and the matching `POSTGRESQL_*` /
`MYSQL_*` environment variables to the Guacamole Deployment. Without them the
JDBC extension does not load, and neither does the cluster module that rides
inside it.

## Confirm the pool resolved

```bash
kubectl exec deploy/guacamole -- getent hosts guacd
```

Expected: **one line per ready `guacd` pod**. A single line means the Service is
not headless, or only one pod is ready. An empty result means the webapp will
fail every connection with "No guacd instances are available."

Confirm clustering actually engaged:

```bash
kubectl logs deploy/guacamole | grep 'Cluster coordination'
```

Expected: `Cluster coordination is ENABLED against "redis://redis:6379".`
If it says nothing, `CLUSTER_ENABLED` did not reach the webapp — check
`envFrom` resolved the ConfigMap.

## Manual verification

These three checks are the point of the deployment. They are manual because
each needs a real RDP/VNC session against a real `guacd`.

### 1. Multi-guacd load spreading

Open four connections, then:

```bash
kubectl logs deploy/guacd --all-containers --prefix | grep "Creating new client"
```

**Expected:** connections appear in *both* pods' logs. All four in one pod means
selection is not consulting cluster load — check that `countTunnels` is reaching
Redis, and that `guacd-cluster-dns` is set rather than falling back to the single
configured `guacd`.

### 2. Join routing, and the P1 limit on it

**Measured on a real two-replica deployment (devqa, 2026-09-16): a join cannot
be initiated from a replica that does not own the session.** With a session open
and provably live on replica A — registered in Redis under A's `nodeId` — the
two replicas disagree:

```
podA  /api/session/data/postgresql/activeConnections  ->  {"6caf3bd5-...": {...}}
podB  /api/session/data/postgresql/activeConnections  ->  {}
```

That is not a bug in the routing. It is the replica-local active-connection
directory listed under *What P1 does NOT do* below, which lands in P3. The
routing half is built and works; the discovery half is not here yet, so replica
B has no identifier to join with.

What you can verify today, on the owning replica:

```bash
# Open a session, then join it. The connect response carries the tunnel session
# token in the Guacamole-Tunnel-Token header, which read/write requests need.
curl -s -D headers.txt -X POST "$BASE/tunnel?connect" \
    --data-urlencode "token=$AUTH" --data-urlencode "GUAC_DATA_SOURCE=postgresql" \
    --data-urlencode "GUAC_ID=<connection id>" --data-urlencode "GUAC_TYPE=c" \
    --data-urlencode "GUAC_WIDTH=1024" --data-urlencode "GUAC_HEIGHT=768" \
    --data-urlencode "GUAC_DPI=96" --data-urlencode "GUAC_TIMEZONE=UTC" \
    --data-urlencode "GUAC_AUDIO=audio/L16" --data-urlencode "GUAC_IMAGE=image/png"
```

Then join the resulting active connection with `GUAC_TYPE=a`. A successful join
returns a new tunnel UUID, and the route it used is visible as:

```bash
kubectl exec deploy/redis -- redis-cli --scan --pattern 'guac:route:*'
kubectl exec deploy/redis -- redis-cli get 'guac:route:$<guacd connection id>'
```

The value is the endpoint key (`host|port|encryptionMethod`) of the `guacd`
hosting that session.

**End-to-end cross-replica join therefore requires P3**, which makes the active
connection directory (and share keys) cluster-wide. P1 supplies the route table
it will use.

### 3. Fail-closed on a vanished join target

With a connection open, delete its route and then attempt a join:

```bash
kubectl exec deploy/redis -- redis-cli del 'guac:route:$<connection id>'
```

**Expected:** the join is refused with *"The connection being joined is no longer
available."* A **new** desktop session here is a serious failure, not a cosmetic
one — it means a user asked to join a colleague's session and silently got a
fresh login instead.

### 4. Scaling guacd up and down

**Measured on devqa, 2026-09-16.** Membership is DNS, so nothing needs telling.

Scale up, then confirm the new pods actually receive traffic — not merely that
DNS resolves:

```bash
kubectl scale deploy/guacd --replicas=4
kubectl exec deploy/guacamole -- getent hosts guacd     # one line per ready pod
```

Opening 12 connections across a freshly-scaled 4-pod pool distributed them
**3 / 3 / 3 / 3**, both new pods included. Note `getent` reports the *OS*
resolver, not the JVM's view — the pool caches for 5s on top of the JVM's own
DNS TTL, so distribution is the real check and DNS is only a precondition.

Scaling down to 1 shrank the pool and sent **6 of 6** subsequent connections to
the survivor.

**Scaling guacd down disconnects users.** Every session on a removed pod dies;
guacd session state is never replicated. That is a design decision of this work,
which is why the HPA in `hpa.yaml` scales down far more slowly than it scales up.

### 5. Losing a replica, and the stale window

This is the crash-recovery mechanism, and the reason cluster entries carry a
heartbeat score at all.

```bash
# Open a session on a specific replica, then kill that replica outright.
# --grace-period=0 --force means the cleanup path never runs.
kubectl delete pod <replica> --grace-period=0 --force
watch "kubectl exec deploy/redis -- redis-cli exists 'guac:tunnel:<uuid>'"
```

Measured with `cluster-stale-window` at its 30000ms default: the route key
disappeared at ~27s and the tunnel record at ~36s, with **no cleanup code
running anywhere**. The heartbeat that had been refreshing their TTLs died with
the replica, so they simply expired.

**One thing does not get cleaned up, and it is worth knowing.** The tunnel's
membership in the `guac:idx:*` sorted sets survives. Verified against a dead
tunnel 84s after the replica was killed:

```
zcard  guac:idx:guacd:<endpoint>              -> 1     (member still present)
zcount guac:idx:guacd:<endpoint> (cutoff +inf -> 0     (not counted as live)
```

Correctness is unaffected: `countTunnels` filters by score, so a dead member is
never counted and never influences selection. But nothing physically removes it.
The only pruning is the `ZREMRANGEBYSCORE` inside the seat script. Gracefully
closed tunnels are removed properly by `unregisterTunnel`, so this grows with
crashes, not with traffic.

**As of P2 the seat script runs on every acquire**, and its `ZREMRANGEBYSCORE`
removes these tombstones — see `SeatPruningTest`. A connection index that is
never acquired again still keeps its tombstones, which is harmless: nothing reads
it, and `countTunnels` filters by score regardless.

### 6. Autoscaling

`hpa.yaml` scales both tiers on CPU (the webapp also on memory), at the 70%/80%
targets this cluster already uses elsewhere. It requires `metrics-server` and the
resource **requests** set in the deployment manifests — an HPA divides by the
request, so without them it cannot compute utilization and silently never scales.

Up and down are deliberately asymmetric, because scaling down costs user
sessions: 60s stabilization up, versus 900s (guacd) and 600s (webapp) down, and
never more than one pod per 5 minutes. Observed holding a manual over-scale in
place rather than shedding pods immediately:

```
AbleToScale=True  ScaleDownStabilized: recent recommendations were higher than
                  current one, applying the highest recent recommendation
```

**CPU is a poor proxy for guacd load.** The cluster already knows the exact live
tunnel count per guacd instance — that is what `guac:idx:guacd:<endpoint>` is —
but an HPA cannot read it without a custom metrics adapter. Scaling on that
number directly (KEDA's Redis scaler, or a prometheus-adapter external metric)
would be strictly better, and belongs with the P5 hardening work rather than here.

### 7. Cluster-wide concurrency limits (P2)

**Measured on devqa, 2026-09-17**, two replicas, one connection with
`max-connections=1`.

**The limit holds across replicas.** This is the defect P2 exists to prevent, and
it cannot be reproduced in a single JVM:

```
replica A  POST /tunnel?connect  ->  200  (tunnel opened)
replica B  POST /tunnel?connect  ->  409  "Cannot connect. This connection is in use."
redis      zcard guac:idx:conn:<id>  ->  1
```

Closing the session on A frees the seat cluster-wide; B's retry then succeeds.

**A Redis outage degrades, it does not block.** With Redis scaled to zero, both
replicas grant the same connection — per-replica enforcement, which is exactly
upstream behaviour:

```
replica A -> 200 in 0.27s
replica B -> 200 in 0.16s
ERROR: Cluster seat acquisition failed for connection "<id>".
       Concurrency limits are now enforced per replica only.
```

**Redis returning needs no restart.** After scaling Redis back up and waiting out
the 10 s re-probe window, the cluster-wide limit is enforced again — A `200`,
B `409` — with the same pods still running.

#### Three defects this test found, all inherited from P1

Every one of them was invisible until the degraded path was exercised deliberately,
and all three made a Redis outage into a connection outage:

| Symptom | Cause | Fix |
|---|---|---|
| First connect after Redis died hung **over 180 s** | Lettuce buffers commands while disconnected and waits out a long timeout, so the first caller absorbs all of it | Reject commands while disconnected; 2 s command timeout |
| Connections then failed `500 Unable to register tunnel with cluster` | `registerTunnel` was called unguarded on the connect path | Publication is best-effort; the connection survives, only its cluster visibility is lost |
| A replica **started** during an outage came up dead — no authentication at all | `RedisClusterStore` connected in its constructor; Lettuce throws a `RuntimeException`, which `ClusterModule` does not catch, so Guice could not build the injector and the whole JDBC auth provider failed to load | Connect lazily on first use; re-probe every 10 s |

The last one is worth dwelling on: the symptom was
`Authentication attempt ignored because the relevant authentication provider could
not be loaded`, which names neither Redis nor clustering. It only appeared because
pods happened to restart while Redis was down.

### 8. Cluster-wide listing and kill (P3a)

**Measured on devqa, 2026-09-17**, two replicas.

**A session is visible from the replica that does not own it.** Opened on
replica A, then read from replica B:

```
A  /api/session/data/postgresql/activeConnections -> {"f91a88a4-...": {...}}
B  /api/session/data/postgresql/activeConnections -> {"f91a88a4-...": {..., "connectable": false}}
```

The same identifier, connection, start date, remote host and username. Before
P3a, B returned `{}` — that is the measurement recorded in section 2, and this
is what changed it. `connectable` is deliberately false: a remote session is
visible and killable, but not joinable until P3b.

**It can be killed from the replica that does not own it.** `DELETE` issued to
B returned **204 in 0.138 s**, after which both replicas listed `{}`. The logs
show the round trip:

```
13:14:23.037  replica B  successfully deleted active connection "f91a88a4-..."
13:14:23.041  replica A  HTTP tunnel request rejected: No such tunnel.
```

B published the request and A closed its tunnel 4 ms later, at which point A's
held reader failed. The kill crossed replicas rather than B silently doing
nothing.

**Clustering disabled is unchanged from upstream.** With `CLUSTER_ENABLED=false`
and a session open on A, A lists it and B lists `{}` — the replica-local view
upstream produces.

#### Two findings

**The spec was wrong about `TrackedActiveConnection`.** §4.5 states it "exposes
plain setters for every field the UI needs". `setConnectionIdentifier` instead
throws `UnsupportedOperationException`, and `getConnectionIdentifier()` reads
through to the connection object — so a remote entry failed with *"Unexpected
internal error: The connection identifier of TrackedActiveConnection is
inherited from the underlying connection"* and would have failed on
serialisation even without calling the setter. The connection is loaded from the
database instead, which every replica shares.

**A remote kill during a Redis outage returns 404, not a timeout.** The bounded
two-second wait is reached only when the session was listed and Redis died
before the kill. Otherwise `deleteObject` calls `retrieveObject` first, the
outage makes the remote session invisible, and the request 404s in ~0.025 s.
That is honest — it never reports success — but it is not the path the plan
predicted, and the timeout is a narrower race than it first appears.

### 9. Cross-replica share keys (P3b)

Image `1.6.1-p3e`, two web-app replicas, two guacd. A is `10.1.72.224`, B is
`10.1.70.166`. The API was driven from an in-cluster pod; the connection is an
SSH session against `ssh-target`, with a read-only sharing profile on it.

A share key minted on A and redeemed on B:

```
share key:              deNHDAQ_N7QRW8P9YOWyD5YIcwtstjBSb8rCJmXGpIXT
auth on B:              {"dataSource": "postgresql-shared", ...}
shared directory on B:  {"deNHDAQ...":{"name":"share-test","protocol":"ssh",
                          "attributes":{"jdbc-shared-by":"guacadmin"}}}
```

Before P3b that POST returned `INVALID_CREDENTIALS` on B, because the key lived
only in A's heap. The connection name and the sharing user are answered from the
definition's own fields, rebuilt from the Redis hash plus the shared database —
no record of the session exists anywhere on B.

**The join lands on the session, not on a new desktop.** Both users appear on one
guacd connection, one arriving from each replica:

```
guacd: User "@118689c4-..." joined connection "$68ab07a1-..." (1 users now present)
guacd: Joining existing connection "$68ab07a1-..."
guacd: User "@dae06d14-..." joined connection "$68ab07a1-..." (2 users now present)
```

`guac:share:<key>` carries five fields — `seatToken`, `guacdConnectionId`,
`connIdentifier`, `sharingProfileId`, `sharedBy` — and one `guac:route:` entry
covers both tunnels.

**Revocation.** Killing the shared session from B returned 204 in 0.062 s. The
share key disappeared from Redis immediately (`exists` → 0), and the key was then
refused on **both** replicas.

#### Three findings, two of them defects older than this phase

**Joining a shared connection crashed as soon as clustering was on.** The share
key path never minted a cluster seat token, so its `TunnelRegistration` was keyed
on null:

```
java.lang.NullPointerException
  ConcurrentHashMap.putVal(ConcurrentHashMap.java:1011)
  ClusterHeartbeat.add(ClusterHeartbeat.java:73)
  AbstractGuacamoleTunnelService.assignGuacamoleTunnel(...:698)
  SharedConnection.connect(SharedConnection.java:134)
```

This is a P2 defect. The two primary connect paths mint a token; this third one
was missed, and no clustered deployment had exercised sharing until now. The join
now gets a token without acquiring a seat — it joins a session that already holds
one — and cleanup withdraws registrations for joins as well as primaries.

**Every share-key join failed closed when clustering was off.**
`selectGuacdEndpoint` routes a join through the cluster route table and fails
closed when no route exists, which is right while clustering is on. With
`cluster-enabled=false` the bound store is `NoOpClusterStore`, whose
`lookupRoute` always returns null, so the same guard rejected every join with a
404. Since `cluster-enabled` defaults to false, this broke sharing for anyone
running this fork unclustered — present since P1.

Isolated by A/B on the same config and script: `1.6.1-p3d` failed 2 of 2 runs,
`1.6.1-p3e` passed 2 of 2. The join branch is now taken only when the store
really coordinates a cluster.

**Driving the HTTP tunnel by hand has four traps**, all of which produced
misleading failures before being understood:

- `key` is a query parameter, but the POST still needs
  `Content-Type: application/x-www-form-urlencoded`, or Jersey throws on
  `@FormParam`.
- `sync` carries two arguments (`4.sync,7.9636167,1.0;`); the ack must echo only
  the timestamp.
- The read response is a stream. Waiting for the whole body means the ack is
  never sent and guacd kills the client with status 776 (CLIENT_TIMEOUT), logged
  as *"User is not responding"*.
- `HTTPResponse.read(n)` blocks until n bytes arrive. A sync is ~20 bytes, so
  `read(512)` lagged the ack by ~25 s. `read1()` fixes it.

**The devqa Postgres is on an `emptyDir`.** Every connection defined in earlier
phases was gone after a pod restart; only the schema-seeded `guacadmin` user
survives. Reseed before testing.

### 10. Cluster-wide brute-force bans (P4a)

Image `1.6.1-p4a`, two web-app replicas, `BAN_MAX_INVALID_ATTEMPTS=4` and
`BAN_ADDRESS_DURATION=120` so the test is short. All attempts come from one
in-cluster pod, so every request carries the same source address.

Startup names the tracker, which is the first thing to check:

```
Addresses will be automatically banned for 120 seconds after 4 failed
authentication attempts, counted across the whole cluster. The
"ban-max-addresses" property no longer bounds the number of tracked addresses
-- it applies only to the fallback used while Redis is unreachable.
```

**Failures split across replicas are counted once.** Two bad logins against A, then
two against B:

```
attempt 1 against A -> 403  rejected
attempt 2 against A -> 403  rejected
attempt 3 against B -> 403  rejected
attempt 4 against B -> 429  BANNED
attempt 5 against B -> 429  BANNED
attempt 6 against A -> 429  BANNED
```

Attempt 6 is the result that matters: **replica A had seen only two failures of its
own, and still refuses.** Before this phase each replica counted separately, so this
same sequence left both at two and banned nothing.

Redis holds one key for the address:

```
guac:authfail:10.1.76.23  ->  4      ttl 107
```

The value stays at 4 through attempts 5 and 6. A blocked request throws before it can
record anything, which is also what the in-memory tracker does.

**A successful login does not clear the count**, which is this phase's deliberate
departure from the spec. With the counter at 2:

```
successful login -> 200
counter after success -> 2
```

§5.7 specifies `DEL` on success. `InMemoryAuthenticationFailureTracker` does not do
that -- `notifyAuthenticationSuccess` and `notifyAuthenticationRequestReceived` make
the identical call -- and implementing it would let an attacker who guesses one valid
account clear their own address and resume.

**With clustering disabled the upstream weakness is visible again**, which is the
control that proves the cluster counter is what changed:

```
CLUSTER_ENABLED=false
attempt 1 against A -> 403  rejected
attempt 2 against A -> 403  rejected
attempt 3 against B -> 403  rejected
attempt 4 against B -> 403  rejected      <- four failures, no ban
```

Startup reverts to the upstream wording, and the pre-existing Redis key was left at
2 with its TTL still draining, so the disabled path wrote nothing to Redis at all.

#### One finding

**`ClusterModule.isEnabled` cannot be called from an extension that lacks Guice.**
`ClusterModule extends AbstractModule`, so naming the class fails compilation with
`cannot access AbstractModule / class file for com.google.inject.AbstractModule not
found`. Adding Guice to `guacamole-auth-ban` purely to read one boolean would have
put roughly a megabyte of Guice into the extension jar for no return. The check moved
to `ClusterProperties`, which imports only `guacamole-ext` property types;
`ClusterModule.isEnabled` delegates to it, so existing callers are unaffected.
Verified afterwards: the built extension contains **0** Guice jars, and does contain
`guacamole-cluster-1.6.1.jar` and `lettuce-core-6.3.2.RELEASE.jar` among its 15
nested jars.

### 11. Auth token store and session recovery (P4b)

Image `1.6.1-p4g`, two web-app replicas. The API was driven from an in-cluster
pod. Startup names the store:

```
Session tokens will be shared across the cluster via "redis://redis:6379".
A session will survive the loss of the replica it authenticated against.
```

**A token issued by one replica works on another.**

```
token issued by A -> use on A -> 200
                     use on B -> 200
```

B logs `Session for user "guacadmin" rebuilt on this replica.` Before P4b, B
returned 403: the token existed only in A's heap.

**The store holds a hash, not the token, and no secret.**

```
token       F2652A4DBCF494DCFC1A...
sha256      64ebcf7323e2a4ff6f0677fd0369ac4c6b3734d9bd1ef1e60a1932dfd6b802ed
redis key   guac:token:64ebcf7323e2a4ff6f0677fd0369ac4c6b3734d9bd1ef1e60a1932dfd6b802ed
```

The hash contains `username`, `authProvider`, `remoteAddress`, `remoteHostname`
and `authenticatedTime` -- no password and no permissions. Scanning the whole
keyspace for the token string returns nothing.

**The login survives the replica that issued it.** Authenticated against A, then
deleted A's pod with `--grace-period=0`, then used the token on the survivor:

```
use on surviving replica B -> 200
Session for user "guacadmin" rebuilt on this replica.
```

Tunnels A was hosting are gone, as designed. The login is not.

**Logout is cluster-wide.**

```
use on B (rebuilds there) -> 200
logout on A               -> 204
use on B after logout     -> 403
use on A after logout     -> 403
```

B's 403 is itself the proof the key was withdrawn from Redis: a rebuild only
fails when the lookup returns nothing.

**With clustering disabled the behaviour is upstream's.**

```
CLUSTER_ENABLED=false
use on A -> 200
use on B -> 403
```

and the startup banner is absent.

#### Four findings, none of which a unit test could have caught

All 147 tests passed throughout. Every defect below appeared only on a
deployment, because each involves a classloader, a servlet container, or a
second replica.

**Cluster classes cannot be shared by copying them.** Adding `guacamole-cluster`
to the web application put those classes in the WAR *and* in the nested jars
every extension embeds, so the same name resolved to two `Class` objects:

```
IllegalArgumentException: argument type mismatch
  at RedisSharedConnectionMap.registerRevocationHandler
```

Making the extensions take the module as `provided` moved the collision one
level down, onto Guice itself:

```
Class org.apache.guacamole.cluster.ClusterModule does not implement
the requested interface com.google.inject.Module
```

The cluster package now lives in `guacamole-ext`, which is in the WAR once and
which every extension already takes as `provided`. `ClusterModule` moved to the
JDBC extension instead -- it was the only one of the 22 classes touching Guice,
and keeping it out is what lets the rest be shared.

**Every provider is wrapped.** `AuthenticationProviderFacade` implements
`AuthenticationProvider` and nothing else, so `instanceof
RehydratableAuthenticationProvider` tested the wrapper and could never be true:

```
Session for user "guacadmin" will not be rebuilt:
provider "postgresql" is not rehydratable.
```

The facade now implements the interface and denies on behalf of any provider
that does not. Default-deny is preserved; it moves to a null return.

**Credentials cannot be fabricated.** Its constructor copies the request into a
`RequestDetails`, which dereferences it, so a null request throws. The rebuilt
session now carries the request that triggered the rebuild, which is also the
more accurate source.

**A replica races its own logout broadcast.** Announcing the logout before
removing the session locally let this replica's own subscriber remove it first,
after which the local removal found nothing and returned "No such token" -- a
404 for a logout that had succeeded. Separately, `RESTExceptionMapper` calls
`destroyGuacamoleSession` for every unauthorized response, including ones with
no token, so hashing unconditionally turned every 401 into a 500.

#### Redis is now security-sensitive

Before this phase Redis held routing and counting state. It now holds session
identity, and a token hash plus a username is enough to tell an attacker who is
logged in and from where. **Any deployment of the token store requires
`requirepass` or ACL authentication and TLS, with the Guacamole user scoped to
the `guac:` prefix.** The devqa Redis has neither. That is acceptable for a test
namespace on a private cluster and is not acceptable anywhere else.

**Supplying the credential.** A Redis URI carries its credentials inline, so
authentication and encryption are configured entirely through
`CLUSTER_REDIS_URI`:

```
rediss://guacamole:PASSWORD@redis.example.com:6379
```

`rediss` selects TLS and the userinfo selects ACL authentication; Lettuce reads
both from the scheme and the URI, so neither needs a code change.

Deliver it from a Secret rather than a literal:

```yaml
env:
  - name: CLUSTER_REDIS_URI
    valueFrom:
      secretKeyRef:
        name: guacamole-redis
        key: uri
```

**An environment variable is readable by anyone who can describe the pod or read
the Deployment**, which is a wider audience than those who can read the Secret.
That is the accepted trade-off here. If that audience ever needs narrowing, the
image also reads any property from a file named by a `*_FILE` variable, so
`CLUSTER_REDIS_URI_FILE` pointing at a mounted Secret is a drop-in change with
no code impact.

**The URI is redacted before it is logged.** Two lines used to print it
verbatim, which would have written the password to the log the moment one
existed -- `AuthenticationService` on startup and `ClusterModule` when cluster
coordination is enabled. Both now pass it through `RedisUris.redact`, which
keeps the scheme, host and port and replaces any credentials:

```
Cluster coordination is ENABLED against "rediss://***@redis.example.com:6379".
```

The scheme is deliberately preserved: it is how an operator confirms from the
log alone that the connection is encrypted. A URI the redactor cannot parse is
reported as `(redacted)` rather than echoed, because an unrecognised shape is
the one most likely to carry a credential in a form the parser did not expect.

**One check still outstanding.** Lettuce logs connection details of its own at
DEBUG. Before enabling authentication anywhere real, run once with
`LOG_LEVEL=debug` against an authenticated Redis and grep the output for the
password, to confirm the client does not print what this change stopped the
application from printing.

**Dedicated Redis, not the shared one.** `redis-hardened.yaml` deploys a
single authenticated node with `maxmemory-policy noeviction`, persistence, an
ACL scoped to `guac:`, and a NetworkPolicy admitting only the Guacamole pods.
`redis-acl.md` carries the ACL itself, how its command list was derived from
the code, and the preflight checks to run if you point Guacamole at an existing
Redis instead.

Sharing an existing cache is the tempting option and the wrong one, for one
reason that outranks the others: a cache runs an LRU eviction policy, and
evicting a member of `guac:idx:*` or `guac:seat:*` corrupts concurrency limits
and guacd routing **silently**. Nothing errors; the numbers are simply wrong
from then on. Add that the ACL scoping only protects this keyspace if every
other client is scoped too, and that `FLUSHALL` crosses logical databases, and
a shared instance is carrying risks a dedicated pod removes for the cost of one
container.

**Redundancy means replication, not sharding.** `redis-sentinel-ha.yaml`
deploys three nodes and three Sentinels. Redis Cluster mode cannot be used at
all, for two reasons in the code rather than in preference:

- `RedisClusterStore` builds a `RedisClient`, not a `RedisClusterClient`
  (`RedisClusterStore.java:141`), so it talks to one node and does not follow
  MOVED redirects.
- `acquireSeats` passes one key per limit-bearing index to a single `EVAL`
  (`RedisClusterStore.java:188-202`). In cluster mode those keys hash to
  different slots, and the script is rejected with `CROSSSLOT`.

Sentinel needs no code change, because Lettuce resolves the current primary
from a `redis-sentinel://` URI and reconnects after a failover by itself:

```
CLUSTER_REDIS_URI = redis-sentinel://guacamole:PASSWORD@redis-0.redis:26379,\
redis-1.redis:26379,redis-2.redis:26379/0#guacamole-primary
```

Two things to weigh before deploying it. Guacamole already degrades rather than
fails while Redis is unreachable -- P2 verified that limits fall back to
per-replica -- so Sentinel shortens a degraded window rather than preventing an
outage. And whether Lettuce applies the URI's credentials to the Sentinels as
well as to the primary is **unverified**; if it does not, either leave Sentinel
authentication off behind the NetworkPolicy, or set sentinel credentials
explicitly, which needs a `RedisURI` builder and therefore a code change.
Neither manifest has been deployed yet; both pass `kubectl --dry-run=client`.

## What P1 does NOT do

Stated so a later phase's gap is not mistaken for a bug in this one:

- **Concurrency limits are still per-replica.** The seat script is built and
  tested but not wired into `RestrictedGuacamoleTunnelService`. That is P2.
- **The admin active-connection view is still replica-local**, and a kill only
  works on the owning replica. That is P3. **This is also what stops a join from
  being initiated on a non-owning replica** — verified on a live two-replica
  deployment, see section 2 above.
- **Share keys still do not cross replicas** — `HashSharedConnectionMap` remains
  bound. That is P3.
- **Auth tokens are still replica-local**, so a replica death forces re-login.
  That is P4.
- **Brute-force ban counts are still per-replica.** That is P4.

Sticky sessions are what make P1 correct in the meantime: every user stays on
one replica, so the replica-local state above stays consistent for that user.
Removing the affinity annotations from `ingress-sticky.yaml` breaks P1.
