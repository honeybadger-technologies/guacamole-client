> **Status: delivered.** Merged in `a2cbc7c18`. Verified live — see `../deploy/README.md` section 8. The spec claim about `TrackedActiveConnection` setters was false; the connection is loaded from the shared database instead.
>
> See `../HA-CLUSTERING-STATUS.md` for the programme-level summary.

# Guacamole HA Clustering — P3a Implementation Plan (active-connection index and kill)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the administrative active-connection view cluster-wide — an admin on any replica sees every session in the cluster and can kill one that another replica owns.

**Architecture:** The admin listing merges local `ActiveConnectionRecord` instances with remote entries read from `guac:idx:all` and their `guac:tunnel:{seatToken}` hashes. Killing a remote session publishes its record UUID on a Redis channel; every replica subscribes, and only the owner finds that UUID in its local `activeTunnels` map and closes the tunnel. The caller waits, bounded, for the cluster entry to disappear so the API never reports a kill that did not land.

**Tech Stack:** Java 8, Maven, Guice 5.1.0, Lettuce 6.3.2.RELEASE (including its pub/sub API), Redis 7.x, JUnit 5.14.4, Testcontainers 1.21.3, Docker.

**Spec:** `docs/superpowers/specs/2026-09-06-guacamole-ha-clustering-design.md` (§4.5 cluster-wide admin listing and kill)

**Prior phases:** `docs/superpowers/plans/2026-09-06-guacamole-ha-p0-p1.md`, `docs/superpowers/plans/2026-09-17-guacamole-ha-p2.md`

## Scope: this is P3a, not all of P3

The spec's P3 row covers two separable subsystems. This plan delivers only the first:

| | Delivers | Status |
|---|---|---|
| **P3a** (this plan) | Cluster-wide active-connection listing and kill | planned here |
| **P3b** (separate plan) | Cross-replica share keys — Redis `SharedConnectionMap` plus a remote `SharedConnectionDefinition` | not yet written |

They are split because P3b is a genuinely larger and riskier change that the spec itself calls "the largest single diff in the design" (§4.3): `SharedConnectionDefinition` holds a live `ActiveConnectionRecord`, and the share-key join path builds a new record from it, so supporting a remote share key means refactoring that join to work without a live record. P3a is useful on its own — an admin gains cluster-wide visibility and control — and is worth shipping before taking on that refactor.

**P3a does not make cross-replica join work.** That needs P3b. What P3a changes is that an admin can *see* and *terminate* sessions on other replicas.

## Global Constraints

- **Java 8 source and target.** `pom.xml:259-260`. No `var`, no records, no `List.of`. Use `Arrays.asList`, `new HashMap<>()`, explicit types.
- **Apache RAT is active.** Every new file needs the ASF licence header. `.md` is excluded.
- **Commit convention is `GUACAMOLE-283: Capitalized summary`.** Not `feat:` or `docs:`.
- **`cluster-enabled` defaults to `false`.** With clustering off, every path here must behave exactly as unmodified upstream.
- **Never build a Redis key inline.** All keys come from `ClusterKeys`.
- **A Redis outage degrades, never blocks** (spec §6.1). For this plan that means the listing falls back to replica-local and a remote kill reports failure rather than hanging. Three P1 defects violating this were fixed in P2; do not reintroduce the pattern.
- **Existing suite must stay green:** `mvn -pl guacamole-cluster test` (52 tests as of P2).

---

## The identifier problem, and why Task 1 exists

Two different identifiers are already in play, and conflating them will produce a listing whose entries cannot be killed.

| Identifier | Where it comes from | What uses it |
|---|---|---|
| **Record UUID** | `ModeledActivityRecord.getUUID()`, derived from the database record ID | `activeTunnels` map key (`AbstractGuacamoleTunnelService.java:588`), `TrackedActiveConnection.identifier` (`:139`), and therefore every admin API call including `deleteObject` |
| **Seat token** | minted by P2 at the top of the connect flow | every Redis key and sorted-set member |

The seat token exists because at `acquire()` time the record UUID does not (it is null until connection history is inserted). The cluster is keyed by the seat token and cannot change.

So a remote entry read from Redis knows its seat token but not the identifier the UI will send back when an admin clicks Kill. **Task 1 stores the record UUID as a field on the tunnel hash, plus a pointer key from record UUID to seat token**, which is what lets Task 5 wait for the right sorted-set member to disappear.

The record UUID can legitimately be null — `AbstractGuacamoleTunnelService.java:494` notes "May be null if record not successfully inserted". Such a tunnel is simply not listable remotely, which Task 1 handles explicitly.

---

## File Structure

**New**

| File | Responsibility |
|---|---|
| `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterKillHandler.java` | The single seam by which the cluster asks the local replica to close a tunnel |
| `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/RemoteListingTest.java` | Tests for listing remote tunnels |
| `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/KillDispatchTest.java` | Tests for kill publish/subscribe and the bounded wait |

**Modified**

| File | Change |
|---|---|
| `.../cluster/TunnelRegistration.java` | Carry the record UUID |
| `.../cluster/ClusterKeys.java` | Add the record-pointer key and the kill channel name |
| `.../cluster/ClusterStore.java` | Add `listTunnels()`, `lookupSeatToken()`, `requestKill()`, `onKillRequest()` |
| `.../cluster/redis/RedisClusterStore.java` | Implement the four, including a pub/sub subscriber |
| `.../cluster/NoOpClusterStore.java` | No-op implementations, so the disabled path is unchanged |
| `.../jdbc/tunnel/AbstractGuacamoleTunnelService.java` | Pass the record UUID when registering; expose `closeLocalTunnel(String)`; register the kill handler |
| `.../jdbc/activeconnection/ActiveConnectionService.java` | Merge remote entries into listing; route a remote kill |

---

## Task 1: Carry the record UUID into the cluster

**Files:**
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/TunnelRegistration.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterKeys.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/redis/RedisClusterStore.java`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/tunnel/AbstractGuacamoleTunnelService.java`
- Test: `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/TunnelRegistrationTest.java`

**Interfaces:**
- Consumes: `TunnelRegistration` (P0), `registerTunnel` (P1), the seat token (P2).
- Produces:
  - `TunnelRegistration` constructor gains a trailing `String recordUuid` parameter, and `getRecordUuid()`
  - `ClusterKeys.record(String recordUuid)` returning `String`
  - `guac:tunnel:{seatToken}` hash gains a `recordUuid` field
  - `guac:record:{recordUuid}` holds the seat token, with the same TTL as the tunnel hash

- [ ] **Step 1: Write the failing test**

Add to `TunnelRegistrationTest.java`:

```java
    @Test
    public void recordUuidIsStoredAndResolvesToTheSeatToken() throws Exception {

        // The admin UI identifies a session by its record UUID, but every
        // cluster key is built from the seat token. Without this pointer a
        // remote session could be listed and never killed.
        store.registerTunnel(new TunnelRegistration("seat-1", "node-1", "$abc",
                GUACD_A, "conn-1", null, null, "alice", "10.0.0.5",
                System.currentTimeMillis(), "record-uuid-1"));

        assertEquals("record-uuid-1",
                connection.sync().hget(ClusterKeys.tunnel("seat-1"), "recordUuid"));
        assertEquals("seat-1", store.lookupSeatToken("record-uuid-1"));

    }

    @Test
    public void tunnelWithNoRecordUuidIsStillRegistered() throws Exception {

        // getUUID() is null when the history row was never inserted. Such a
        // tunnel still holds seats and still needs its guacd route, so it must
        // register -- it is simply not addressable by the admin UI.
        store.registerTunnel(new TunnelRegistration("seat-2", "node-1", "$def",
                GUACD_A, "conn-1", null, null, "alice", "10.0.0.5",
                System.currentTimeMillis(), null));

        assertEquals(GUACD_A, store.lookupRoute("$def"));
        assertNull(store.lookupSeatToken("record-uuid-missing"));

    }

    @Test
    public void unregisterRemovesTheRecordPointer() throws Exception {

        TunnelRegistration record = new TunnelRegistration("seat-3", "node-1", "$ghi",
                GUACD_A, "conn-1", null, null, "alice", "10.0.0.5",
                System.currentTimeMillis(), "record-uuid-3");

        store.registerTunnel(record);
        store.unregisterTunnel(record);

        assertNull(store.lookupSeatToken("record-uuid-3"));

    }
```

Every existing call to the ten-argument `TunnelRegistration` constructor in this test file must gain a trailing argument; pass `"record-" + <the seat token used>` so each is distinct.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
mvn -q -pl guacamole-cluster test -Dtest=TunnelRegistrationTest
```

Expected: FAIL — compilation error; the constructor takes ten arguments and `lookupSeatToken` does not exist.

- [ ] **Step 3: Add the field to `TunnelRegistration`**

Add the field, constructor parameter and getter:

```java
    private final String recordUuid;
```

Append `String recordUuid` to the constructor parameter list and assign `this.recordUuid = recordUuid;`, then:

```java
    /**
     * @return
     *     The UUID of this connection's history record, which is what the
     *     administrative API uses to identify it, or null if no history record
     *     was written.
     */
    public String getRecordUuid() { return recordUuid; }
```

- [ ] **Step 4: Add the key**

In `ClusterKeys.java`:

```java
    /**
     * Channel on which kill requests are published.
     */
    public static final String KILL_CHANNEL = "guac:kill";

    public static String record(String recordUuid) {
        return "guac:record:" + escape(recordUuid);
    }
```

- [ ] **Step 5: Write and read the pointer**

In `RedisClusterStore.registerTunnel`, inside the existing `try`, after the route is written:

```java
            if (registration.getRecordUuid() != null) {
                record.put("recordUuid", registration.getRecordUuid());
                commands.psetex(ClusterKeys.record(registration.getRecordUuid()),
                        staleWindowMs, registration.getTunnelUuid());
            }
```

Note the `record.put` must happen **before** `commands.hset(tunnelKey, record)`; move the block above the `hset` call rather than after it.

In `unregisterTunnel`, alongside the route deletion:

```java
            if (registration.getRecordUuid() != null)
                commands.del(ClusterKeys.record(registration.getRecordUuid()));
```

In `heartbeat`, alongside the route refresh:

```java
                if (registration.getRecordUuid() != null)
                    commands.pexpire(ClusterKeys.record(registration.getRecordUuid()),
                            staleWindowMs);
```

Add the lookup:

```java
    @Override
    public String lookupSeatToken(String recordUuid) {

        try {
            String token = commands().get(ClusterKeys.record(recordUuid));
            available = true;
            return token;
        }

        // The caller degrades to a replica-local view rather than failing
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            logger.warn("Unable to resolve record \"{}\" to a seat token.", recordUuid, e);
            return null;
        }

    }
```

- [ ] **Step 6: Declare it on the interface and no-op store**

In `ClusterStore.java`:

```java
    /**
     * Returns the seat token of the tunnel whose history record has the given
     * UUID.
     *
     * @param recordUuid
     *     The UUID of the connection history record.
     *
     * @return
     *     The seat token identifying that tunnel's cluster state, or null if
     *     no such tunnel is known or the store is unavailable.
     */
    String lookupSeatToken(String recordUuid);
```

In `NoOpClusterStore.java`:

```java
    @Override
    public String lookupSeatToken(String recordUuid) {
        return null;
    }
```

- [ ] **Step 7: Pass the record UUID at the call site**

In `AbstractGuacamoleTunnelService.java`, in the registration block, append the new argument. The record UUID may be null here, which the store handles:

```java
                    activeConnection.getStartDate().getTime(),
                    activeConnection.getUUID() != null
                            ? activeConnection.getUUID().toString() : null);
```

- [ ] **Step 8: Run the tests to verify they pass**

```bash
mvn -q -pl guacamole-cluster test -Dtest=TunnelRegistrationTest
mvn -q -pl extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base -am install -DskipTests
```

Expected: PASS, and BUILD SUCCESS for the extension.

- [ ] **Step 9: Commit**

```bash
git add guacamole-cluster/src extensions/guacamole-auth-jdbc
git commit -m "GUACAMOLE-283: Carry the history record UUID into cluster state

The administrative API identifies a session by its history record UUID,
while every cluster key is built from the seat token minted at acquire
time. Storing the record UUID on the tunnel hash, with a pointer key back
to the seat token, is what lets a session listed from another replica
also be killed."
```

---

## Task 2: Read remote tunnels out of the cluster

**Files:**
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/redis/RedisClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/NoOpClusterStore.java`
- Test: `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/RemoteListingTest.java`

**Interfaces:**
- Consumes: `TunnelRegistration` with `getRecordUuid()` (Task 1).
- Produces: `ClusterStore.listTunnels()` returning `Collection<TunnelRegistration>` — every live tunnel in the cluster, excluding stale members, never null.

- [ ] **Step 1: Write the failing test** (ASF header first)

```java
package org.apache.guacamole.cluster.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Collection;
import org.apache.guacamole.cluster.ClusterKeys;
import org.apache.guacamole.cluster.TunnelRegistration;
import org.apache.guacamole.cluster.guacd.GuacdEndpoint;
import org.apache.guacamole.net.auth.GuacamoleProxyConfiguration.EncryptionMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RemoteListingTest {

    private static final long STALE_WINDOW_MS = 30000L;

    private static final GuacdEndpoint GUACD_A =
            new GuacdEndpoint("guacd-a", 4822, EncryptionMethod.NONE);

    @BeforeAll
    public static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available; skipping Redis-backed tests.");
    }

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisClusterStore store;

    @BeforeEach
    public void setUp() {
        client = RedisClient.create(RedisTestSupport.redisUri());
        connection = client.connect();
        connection.sync().flushall();
        store = new RedisClusterStore(RedisTestSupport.redisUri(), STALE_WINDOW_MS, "node-1");
    }

    @AfterEach
    public void tearDown() {
        store.shutdown();
        connection.close();
        client.shutdown();
    }

    private TunnelRegistration registration(String seat, String user, String recordUuid) {
        return new TunnelRegistration(seat, "node-2", "$" + seat, GUACD_A,
                "conn-1", null, null, user, "10.0.0.5",
                System.currentTimeMillis(), recordUuid);
    }

    @Test
    public void listsEveryLiveTunnel() throws Exception {

        store.registerTunnel(registration("seat-1", "alice", "rec-1"));
        store.registerTunnel(registration("seat-2", "bob", "rec-2"));

        Collection<TunnelRegistration> listed = store.listTunnels();
        assertEquals(2, listed.size());

    }

    @Test
    public void listedEntriesCarryTheFieldsTheAdminViewNeeds() throws Exception {

        store.registerTunnel(registration("seat-1", "alice", "rec-1"));

        TunnelRegistration listed = store.listTunnels().iterator().next();
        assertEquals("seat-1", listed.getTunnelUuid());
        assertEquals("rec-1", listed.getRecordUuid());
        assertEquals("alice", listed.getUsername());
        assertEquals("conn-1", listed.getConnectionIdentifier());
        assertEquals("node-2", listed.getNodeId());
        assertEquals("10.0.0.5", listed.getRemoteHost());

    }

    @Test
    public void omitsStaleTunnelsLeftByDeadReplicas() throws Exception {

        store.registerTunnel(registration("seat-1", "alice", "rec-1"));

        // A tunnel whose replica died: still a member of the index, but its
        // hash has expired and its score is far outside the window
        long ancient = System.currentTimeMillis() - (STALE_WINDOW_MS * 10);
        connection.sync().zadd(ClusterKeys.ALL_INDEX, (double) ancient, "seat-dead");

        Collection<TunnelRegistration> listed = store.listTunnels();
        assertEquals(1, listed.size());
        assertEquals("seat-1", listed.iterator().next().getTunnelUuid());

    }

    @Test
    public void returnsEmptyRatherThanFailingWhenThereIsNothing() throws Exception {
        assertTrue(store.listTunnels().isEmpty());
    }

}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl guacamole-cluster test -Dtest=RemoteListingTest
```

Expected: FAIL — `listTunnels` does not exist.

- [ ] **Step 3: Declare it on the interface**

In `ClusterStore.java`:

```java
    /**
     * Returns every tunnel currently live anywhere in the cluster, including
     * those owned by this replica.
     *
     * @return
     *     Every live tunnel, or an empty collection if the store is
     *     unavailable. Never null -- the administrative view degrades to a
     *     replica-local listing rather than failing.
     */
    Collection<TunnelRegistration> listTunnels();
```

In `NoOpClusterStore.java`:

```java
    @Override
    public Collection<TunnelRegistration> listTunnels() {
        return Collections.<TunnelRegistration>emptyList();
    }
```

Add `import java.util.Collections;` there.

- [ ] **Step 4: Implement it**

In `RedisClusterStore.java`, add `import io.lettuce.core.Range;` if not already imported by the `countTunnels` code, plus `import java.util.ArrayList;` and `import java.util.Map;` if absent:

```java
    @Override
    public Collection<TunnelRegistration> listTunnels() {

        List<TunnelRegistration> tunnels = new ArrayList<TunnelRegistration>();

        try {

            RedisCommands<String, String> commands = commands();
            long cutoff = serverTimeMillis(commands) - staleWindowMs;

            // Score-filtered, so members left behind by a dead replica are
            // never listed even before anything prunes them
            List<String> seatTokens = commands.zrangebyscore(ClusterKeys.ALL_INDEX,
                    Range.from(Range.Boundary.excluding((double) cutoff),
                            Range.Boundary.unbounded()));

            for (String seatToken : seatTokens) {

                Map<String, String> record = commands.hgetall(ClusterKeys.tunnel(seatToken));

                // The hash expires on its own TTL, so a member can outlive it
                if (record.isEmpty())
                    continue;

                String endpointKey = record.get("guacdEndpoint");
                String startTime = record.get("startTime");

                tunnels.add(new TunnelRegistration(
                        seatToken,
                        record.get("nodeId"),
                        record.get("guacdConnectionId"),
                        endpointKey != null ? GuacdEndpoint.fromKey(endpointKey) : null,
                        record.get("connIdentifier"),
                        record.get("groupIdentifier"),
                        record.get("sharingProfileId"),
                        record.get("username"),
                        record.get("remoteHost"),
                        startTime != null ? Long.parseLong(startTime) : 0L,
                        record.get("recordUuid")));

            }

            available = true;

        }

        // The administrative view degrades to replica-local rather than
        // failing outright (spec 6.1)
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            logger.warn("Unable to list cluster tunnels. The active connection "
                    + "view will show only this replica's sessions.", e);
        }

        return tunnels;

    }
```

- [ ] **Step 5: Run it to verify it passes**

```bash
mvn -q -pl guacamole-cluster test -Dtest=RemoteListingTest
```

Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add guacamole-cluster/src
git commit -m "GUACAMOLE-283: List every live tunnel in the cluster

Score-filtered against the stale window, so a member left behind by a
dead replica is never listed even before the seat script prunes it, and
entries whose hash has already expired are skipped."
```

---

## Task 3: Merge remote sessions into the administrative listing

**Files:**
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/activeconnection/ActiveConnectionService.java`

**Interfaces:**
- Consumes: `ClusterStore.listTunnels()` (Task 2), `TrackedActiveConnection` setters.
- Produces: no new API. `getIdentifiers` and `retrieveObjects` now include sessions owned by other replicas.

- [ ] **Step 1: Inject the cluster store**

Add the imports and field to `ActiveConnectionService`:

```java
import org.apache.guacamole.cluster.ClusterStore;
import org.apache.guacamole.cluster.TunnelRegistration;
```

```java
    /**
     * Cluster-wide view of active tunnels. Empty when clustering is disabled,
     * in which case this service behaves exactly as upstream.
     */
    @Inject
    private ClusterStore clusterStore;
```

- [ ] **Step 2: Add a helper that builds a tracked connection from a remote entry**

A remote session has no tunnel, which `deleteObject` already null-checks, and `TrackedActiveConnection` exposes setters for every field the view needs:

```java
    /**
     * Builds an administrative view of a session owned by another replica.
     *
     * @param user
     *     The user requesting the listing.
     *
     * @param registration
     *     The remote tunnel, as published to the cluster.
     *
     * @param hasPrivilegedAccess
     *     Whether sensitive fields should be populated.
     *
     * @return
     *     A tracked connection describing the remote session, with no tunnel.
     */
    private TrackedActiveConnection remoteActiveConnection(ModeledAuthenticatedUser user,
            TunnelRegistration registration, boolean hasPrivilegedAccess) {

        TrackedActiveConnection activeConnection = trackedActiveConnectionProvider.get();
        activeConnection.init(user, null, false, false);

        activeConnection.setIdentifier(registration.getRecordUuid());
        activeConnection.setConnectionIdentifier(registration.getConnectionIdentifier());
        activeConnection.setSharingProfileIdentifier(registration.getSharingProfileIdentifier());
        activeConnection.setStartDate(new Date(registration.getStartTime()));

        // A session on another replica cannot be joined from here -- that needs
        // the share-key work in P3b -- so it is never marked connectable, and
        // its tunnel stays null
        activeConnection.setTunnel(null);

        if (hasPrivilegedAccess) {
            activeConnection.setUsername(registration.getUsername());
            activeConnection.setRemoteHost(registration.getRemoteHost());
        }

        return activeConnection;

    }
```

`TrackedActiveConnection.init` dereferences its record argument, so it cannot be called with null as written. Modify `init` to tolerate it — this is the one change needed in that class:

```java
    public void init(ModeledAuthenticatedUser currentUser,
            ActiveConnectionRecord activeConnectionRecord,
            boolean includeSensitiveInformation,
            boolean connectable) {

        super.init(currentUser);
        this.connectionRecord = activeConnectionRecord;
        this.connectable      = connectable;

        // A remote session has no local record; every field is supplied by the
        // caller through the setters below
        if (activeConnectionRecord == null)
            return;

        // Copy all non-sensitive data from given record
        this.connection               = activeConnectionRecord.getConnection();
        ...unchanged...
```

Add `import java.util.Date;` to `ActiveConnectionService`.

- [ ] **Step 3: Include remote sessions in `getIdentifiers`**

Replace the body's identifier loop tail:

```java
        // Build list of identifiers
        Set<String> identifiers = new HashSet<String>(records.size());
        for (ActiveConnectionRecord record : records)
            identifiers.add(record.getUUID().toString());

        // Include sessions owned by other replicas. Privileged users see the
        // whole cluster; unprivileged users see only their own.
        for (TunnelRegistration registration : clusterStore.listTunnels()) {

            if (registration.getRecordUuid() == null)
                continue;

            if (user.isPrivileged()
                    || user.getIdentifier().equals(registration.getUsername()))
                identifiers.add(registration.getRecordUuid());

        }

        return identifiers;
```

Because the local records are also published to the cluster, a locally-owned session appears in both sources; `Set` collapses the duplicate.

- [ ] **Step 4: Include remote sessions in `retrieveObjects`**

After the existing loop over local records, and before `return activeConnections;`:

```java
        // Add sessions owned by other replicas, skipping any already covered
        // by a local record
        Set<String> localIdentifiers = new HashSet<String>();
        for (TrackedActiveConnection local : activeConnections)
            localIdentifiers.add(local.getIdentifier());

        for (TunnelRegistration registration : clusterStore.listTunnels()) {

            String recordUuid = registration.getRecordUuid();
            if (recordUuid == null || localIdentifiers.contains(recordUuid))
                continue;

            if (!identifierSet.contains(recordUuid))
                continue;

            boolean hasPrivilegedAccess =
                    isPrivileged || username.equals(registration.getUsername());

            if (hasPrivilegedAccess || isPrivileged)
                activeConnections.add(
                        remoteActiveConnection(user, registration, hasPrivilegedAccess));

        }
```

- [ ] **Step 5: Verify the extension builds**

```bash
mvn -q -pl extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base -am install -DskipTests
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add extensions/guacamole-auth-jdbc
git commit -m "GUACAMOLE-283: Show sessions from every replica in the active connection view

Remote entries are built from their published cluster state with no
tunnel, which deleteObject already null-checks. A locally-owned session
appears in both sources and is de-duplicated by record UUID.

With clustering disabled listTunnels() returns empty, so the view is
byte-for-byte what upstream produces."
```

---

## Task 4: Publish and receive kill requests

**Files:**
- Create: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterKillHandler.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/redis/RedisClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/NoOpClusterStore.java`
- Test: `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/KillDispatchTest.java`

**Interfaces:**
- Consumes: `ClusterKeys.KILL_CHANNEL` (Task 1).
- Produces:
  - `interface ClusterKillHandler { void killLocalTunnel(String recordUuid); }`
  - `ClusterStore.onKillRequest(ClusterKillHandler handler)`
  - `ClusterStore.requestKill(String recordUuid)`

- [ ] **Step 1: Write the failing test** (ASF header first)

```java
package org.apache.guacamole.cluster.redis;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.guacamole.cluster.ClusterKillHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KillDispatchTest {

    private static final long STALE_WINDOW_MS = 30000L;

    @BeforeAll
    public static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available; skipping Redis-backed tests.");
    }

    private RedisClusterStore publisher;
    private RedisClusterStore subscriber;

    @BeforeEach
    public void setUp() {
        publisher = new RedisClusterStore(RedisTestSupport.redisUri(), STALE_WINDOW_MS, "node-1");
        subscriber = new RedisClusterStore(RedisTestSupport.redisUri(), STALE_WINDOW_MS, "node-2");
    }

    @AfterEach
    public void tearDown() {
        publisher.shutdown();
        subscriber.shutdown();
    }

    @Test
    public void killRequestReachesAnotherReplica() throws Exception {

        final CountDownLatch received = new CountDownLatch(1);
        final AtomicReference<String> seen = new AtomicReference<String>();

        subscriber.onKillRequest(new ClusterKillHandler() {

            @Override
            public void killLocalTunnel(String recordUuid) {
                seen.set(recordUuid);
                received.countDown();
            }

        });

        publisher.requestKill("record-uuid-1");

        assertTrue(received.await(10, TimeUnit.SECONDS), "kill request never arrived");
        assertEquals("record-uuid-1", seen.get());

    }

    @Test
    public void everyReplicaIsNotifiedIncludingThePublisher() throws Exception {

        // The owner may be the replica that issued the kill. Delivery is
        // broadcast; deciding who owns the tunnel is the handler's job.
        final CountDownLatch received = new CountDownLatch(1);

        publisher.onKillRequest(new ClusterKillHandler() {

            @Override
            public void killLocalTunnel(String recordUuid) {
                received.countDown();
            }

        });

        publisher.requestKill("record-uuid-2");
        assertTrue(received.await(10, TimeUnit.SECONDS), "publisher did not receive its own request");

    }

}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl guacamole-cluster test -Dtest=KillDispatchTest
```

Expected: FAIL — `ClusterKillHandler`, `onKillRequest` and `requestKill` do not exist.

- [ ] **Step 3: Create the handler seam** (ASF header first)

```java
package org.apache.guacamole.cluster;

/**
 * Receives a request to close a tunnel which may or may not be owned by this
 * replica. Requests are broadcast to every replica; an implementation closes
 * the tunnel only if it owns it, and otherwise does nothing.
 */
public interface ClusterKillHandler {

    /**
     * Closes the tunnel with the given history record UUID, if this replica
     * owns it.
     *
     * @param recordUuid
     *     The UUID of the history record identifying the tunnel to close.
     */
    void killLocalTunnel(String recordUuid);

}
```

- [ ] **Step 4: Declare the two methods**

In `ClusterStore.java`:

```java
    /**
     * Registers the handler invoked when any replica requests a kill.
     *
     * @param handler
     *     The handler to invoke for every kill request received.
     */
    void onKillRequest(ClusterKillHandler handler);

    /**
     * Asks every replica to close the tunnel with the given history record
     * UUID. Only the owning replica will act.
     *
     * @param recordUuid
     *     The UUID of the history record identifying the tunnel to close.
     *
     * @throws GuacamoleException
     *     If the request cannot be published.
     */
    void requestKill(String recordUuid) throws GuacamoleException;
```

In `NoOpClusterStore.java`:

```java
    @Override
    public void onKillRequest(ClusterKillHandler handler) {
        // Nothing is broadcast when clustering is disabled
    }

    @Override
    public void requestKill(String recordUuid) throws GuacamoleException {
        // Every tunnel is local when clustering is disabled
    }
```

- [ ] **Step 5: Implement publish and subscribe**

In `RedisClusterStore.java`, add the imports:

```java
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.apache.guacamole.cluster.ClusterKillHandler;
```

Add the field:

```java
    /**
     * Dedicated connection for kill broadcasts. Pub/sub cannot share the
     * command connection: a subscribed Redis connection accepts only
     * subscription commands.
     */
    private volatile StatefulRedisPubSubConnection<String, String> pubSubConnection;
```

Then:

```java
    @Override
    public void onKillRequest(final ClusterKillHandler handler) {

        try {

            StatefulRedisPubSubConnection<String, String> pubSub = client.connectPubSub();

            pubSub.addListener(new RedisPubSubAdapter<String, String>() {

                @Override
                public void message(String channel, String recordUuid) {
                    try {
                        handler.killLocalTunnel(recordUuid);
                    }

                    // A handler that throws must not kill the subscriber
                    catch (Throwable e) {
                        logger.warn("Kill request for \"{}\" could not be handled.",
                                recordUuid, e);
                    }
                }

            });

            pubSub.sync().subscribe(ClusterKeys.KILL_CHANNEL);
            pubSubConnection = pubSub;

        }

        // Without a subscription this replica simply never honours remote
        // kills; it must still serve connections
        catch (RedisException e) {
            logger.error("Unable to subscribe to cluster kill requests. Sessions "
                    + "on this replica cannot be terminated from another one.", e);
        }

    }

    @Override
    public void requestKill(String recordUuid) throws GuacamoleException {

        try {
            commands().publish(ClusterKeys.KILL_CHANNEL, recordUuid);
            available = true;
        }

        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            throw new GuacamoleServerException("Unable to request cluster kill.", e);
        }

    }
```

Close it in `shutdown()`, before the client is shut down:

```java
        StatefulRedisPubSubConnection<String, String> pubSub = pubSubConnection;
        if (pubSub != null)
            pubSub.close();
```

- [ ] **Step 6: Run it to verify it passes**

```bash
mvn -q -pl guacamole-cluster test -Dtest=KillDispatchTest
```

Expected: PASS, 2 tests.

- [ ] **Step 7: Commit**

```bash
git add guacamole-cluster/src
git commit -m "GUACAMOLE-283: Broadcast kill requests across the cluster

Kill requests go to every replica and each decides whether it owns the
tunnel, which avoids a directory of replica addresses and works whatever
the ingress does.

Pub/sub uses its own connection because a subscribed Redis connection
accepts only subscription commands. Failing to subscribe is logged and
survived: the replica serves connections, it just cannot be asked to
terminate one remotely."
```

---

## Task 5: Route a remote kill, and wait for it to land

**Files:**
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/tunnel/AbstractGuacamoleTunnelService.java`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/activeconnection/ActiveConnectionService.java`

**Interfaces:**
- Consumes: `requestKill`, `onKillRequest`, `ClusterKillHandler` (Task 4), `lookupSeatToken` (Task 1).
- Produces: `AbstractGuacamoleTunnelService.closeLocalTunnel(String recordUuid)` returning `boolean` — true when this replica owned the tunnel and closed it.

- [ ] **Step 1: Expose local closure and register the handler**

In `AbstractGuacamoleTunnelService`, add:

```java
    /**
     * Closes the tunnel with the given history record UUID, if this replica
     * owns it.
     *
     * @param recordUuid
     *     The UUID of the history record identifying the tunnel.
     *
     * @return
     *     true if this replica owned the tunnel and closed it, false if the
     *     tunnel is not here.
     */
    public boolean closeLocalTunnel(String recordUuid) {

        ActiveConnectionRecord record = activeTunnels.get(recordUuid);
        if (record == null)
            return false;

        GuacamoleTunnel tunnel = record.getTunnel();
        if (tunnel == null || !tunnel.isOpen())
            return false;

        try {
            tunnel.close();
            return true;
        }

        catch (GuacamoleException e) {
            logger.warn("Unable to close tunnel \"{}\" on behalf of the cluster.",
                    recordUuid, e);
            return false;
        }

    }
```

`activeTunnels` is keyed by exactly this UUID (`activeTunnels.put(activeConnection.getUUID().toString(), ...)`), which is what makes a broadcast sufficient.

Register the handler once, in the existing `@Inject`-annotated setup. Add a method annotated for Guice to call after injection:

```java
    /**
     * Subscribes this replica to cluster kill requests. Invoked by Guice once
     * this service has been constructed.
     */
    @Inject
    public void registerClusterKillHandler() {
        clusterStore.onKillRequest(new ClusterKillHandler() {

            @Override
            public void killLocalTunnel(String recordUuid) {
                closeLocalTunnel(recordUuid);
            }

        });
    }
```

Add `import org.apache.guacamole.cluster.ClusterKillHandler;`.

- [ ] **Step 2: Route the kill in `ActiveConnectionService.deleteObject`**

Replace the closure block:

```java
        if (hasObjectPermissions(user, identifier, ObjectPermission.Type.DELETE)) {

            // Close connection if not already closed
            GuacamoleTunnel tunnel = activeConnection.getTunnel();
            if (tunnel != null && tunnel.isOpen()) {
                tunnel.close();
                return;
            }

            // A remote session has no tunnel here. Ask every replica to close
            // it, then wait for the cluster entry to disappear so this call
            // does not report success for a kill that never landed.
            String seatToken = clusterStore.lookupSeatToken(identifier);
            if (seatToken == null)
                return;

            clusterStore.requestKill(identifier);

            long deadline = System.currentTimeMillis() + KILL_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {

                if (!clusterStore.isTunnelLive(seatToken))
                    return;

                try {
                    Thread.sleep(KILL_POLL_INTERVAL_MS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

            }

            throw new GuacamoleServerException("The replica owning this "
                    + "connection did not close it in time.");

        }
```

Add the constants to `ActiveConnectionService`:

```java
    /**
     * Milliseconds to wait for another replica to honour a kill request.
     */
    private static final long KILL_TIMEOUT_MS = 2000L;

    /**
     * Milliseconds between checks for the cluster entry disappearing.
     */
    private static final long KILL_POLL_INTERVAL_MS = 100L;
```

Add `import org.apache.guacamole.GuacamoleServerException;`.

- [ ] **Step 3: Add the liveness check the wait depends on**

In `ClusterStore.java`:

```java
    /**
     * @param seatToken
     *     The seat token identifying a tunnel.
     *
     * @return
     *     true if that tunnel is still published to the cluster, false if it
     *     has gone or the store is unavailable.
     */
    boolean isTunnelLive(String seatToken);
```

In `NoOpClusterStore.java`:

```java
    @Override
    public boolean isTunnelLive(String seatToken) {
        return false;
    }
```

In `RedisClusterStore.java`:

```java
    @Override
    public boolean isTunnelLive(String seatToken) {

        try {
            boolean live = commands().exists(ClusterKeys.tunnel(seatToken)) > 0;
            available = true;
            return live;
        }

        // Reporting "gone" would claim a kill landed when that is unknown;
        // reporting "live" makes the caller wait out its own timeout and
        // report failure, which is the honest answer
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            return true;
        }

    }
```

- [ ] **Step 4: Verify the extension builds and the module suite passes**

```bash
mvn -q -pl extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base -am install -DskipTests
mvn -q -pl guacamole-cluster test
```

Expected: BUILD SUCCESS and all tests passing.

- [ ] **Step 5: Commit**

```bash
git add guacamole-cluster/src extensions/guacamole-auth-jdbc
git commit -m "GUACAMOLE-283: Kill sessions owned by another replica

A local tunnel is closed directly, as before. A remote one is broadcast
and then waited on, bounded at two seconds, until its cluster entry
disappears -- so the API never reports success for a kill that did not
land.

If Redis is unreachable the liveness check reports the tunnel as still
live, which makes the caller wait out its timeout and report failure.
Claiming the kill succeeded would be worse than saying it timed out."
```

---

## Task 6: Verify on the live two-replica deployment

The unit tests cover dispatch and listing. Only a deployment proves the wiring, because the thing being built — one replica terminating another replica's session — cannot occur in a single JVM.

The devqa stack from P2 is still running (`remote-access` namespace, image `1.6.1-p2d`). It includes a `tester` pod with `curl`; **drive the API from inside the cluster**. Port-forwards died repeatedly under load during P2 testing and produced several misleading readings.

**Files:** none. Produces `docs/superpowers/deploy/README.md` section 8.

- [ ] **Step 1: Build, push and deploy**

```bash
docker build -t guacamole-cluster:1.6.1-p3a --build-arg MAVEN_ARGUMENTS=-DskipTests .
# tag and push to 738928754249.dkr.ecr.us-east-2.amazonaws.com/guacamole/guacamole-cluster
kubectl -n remote-access set image deploy/guacamole guacamole=<pushed image>
kubectl -n remote-access rollout status deploy/guacamole
```

Docker Desktop ran out of disk during P2. If the build fails with "no space left on device", run `docker builder prune -af`.

- [ ] **Step 2: Open a session on replica A**

Get both pod IPs, authenticate against A, and open a tunnel, holding it with read requests that carry the `Guacamole-Tunnel-Token` header returned by the connect response.

- [ ] **Step 3: Confirm replica B lists it**

```bash
curl -s "http://<B>:8080/api/session/data/postgresql/activeConnections?token=<B token>"
```

**Expected: the session appears.** Before P3a this returned `{}` — measured during P2 and recorded in README §2. That difference is the whole point of this phase.

- [ ] **Step 4: Kill it from replica B**

```bash
curl -s -X DELETE "http://<B>:8080/api/session/data/postgresql/activeConnections/<record uuid>?token=<B token>"
```

**Expected:** the call returns success, A's session closes, and the entry disappears:

```bash
kubectl -n remote-access exec deploy/redis -- redis-cli zcard guac:idx:all
```

Confirm in A's log that it closed the tunnel, proving the broadcast reached the owner rather than B silently doing nothing.

- [ ] **Step 5: Confirm the bounded wait reports failure honestly**

With the session open on A, scale Redis to zero and attempt the same delete from B.

**Expected:** the call fails within roughly two seconds rather than hanging or falsely reporting success. Restore with `kubectl -n remote-access scale deploy/redis --replicas=1`.

- [ ] **Step 6: Confirm the disabled path is untouched**

Set `CLUSTER_ENABLED=false` in the ConfigMap, restart, and confirm the active-connection view shows only local sessions and behaves exactly as upstream. Restore afterwards.

- [ ] **Step 7: Document the measurements**

Add section 8 to `docs/superpowers/deploy/README.md` in the style of sections 4-7: record what was actually observed, including the numbers, not what was expected.

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/deploy/README.md
git commit -m "GUACAMOLE-283: Record cluster-wide listing and kill verification"
```

---

## What P3a does NOT do

- **Cross-replica join still does not work.** A session on another replica is listed but not connectable; `setTunnel(null)` and `connectable=false` say so explicitly. That needs P3b.
- **Share keys are still replica-local.** `HashSharedConnectionMap` remains bound. P3b.
- **Auth tokens are still replica-local**, so a replica death forces re-login. P4.
- **Brute-force ban counts are still per-replica.** P4.
- **A kill is broadcast to every replica**, not addressed to the owner. With a handful of replicas this is far simpler than maintaining a directory of replica addresses, and it is correct regardless of ingress behaviour. If the replica count ever grows enough for the broadcast to matter, address it then — the `nodeId` needed to do so is already on every tunnel hash.
