# Two-Replica Cluster Deployment (P1)

Runs two Guacamole web application replicas against a pool of two `guacd`
instances, coordinated through Redis.

What P1 delivers, and therefore what is worth verifying here:

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
