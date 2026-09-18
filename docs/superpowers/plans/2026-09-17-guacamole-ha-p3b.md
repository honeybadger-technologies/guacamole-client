> **Status: delivered.** Merged in `a2cbc7c18`. Verified live — see `../deploy/README.md` section 9. Two defects older than this phase were fixed here: a share-key join carried no cluster seat token (P2), and every join failed closed when clustering was disabled (P1).
>
> See `../HA-CLUSTERING-STATUS.md` for the programme-level summary.

# Guacamole HA Clustering — P3b Implementation Plan (cross-replica share keys)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a share key work on any replica, so a link shared by a user on replica A can be redeemed by someone whose request lands on replica B.

**Architecture:** `SharedConnectionDefinition` currently holds a live `ActiveConnectionRecord`, which only exists on the replica owning the shared session. It is widened to carry the four facts a redemption actually needs — the connection, the guacd connection ID, who shared it, and the sharing profile — so it can be rebuilt from cluster state on any replica. A Redis-backed `SharedConnectionMap` stores those facts by share key, and P1's existing route lookup sends the resulting join to the guacd already hosting the session.

**Tech Stack:** Java 8, Maven, Guice 5.1.0, Lettuce 6.3.2.RELEASE, Redis 7.x, JUnit 5.14.4, Testcontainers 1.21.3, Docker.

**Spec:** `docs/superpowers/specs/2026-09-06-guacamole-ha-clustering-design.md` (§4.3 share key redemption across replicas)

**Prior phases:** P0/P1, P2, and P3a — `docs/superpowers/plans/2026-09-17-guacamole-ha-p3a.md`

## Global Constraints

- **Java 8 source and target.** `pom.xml:259-260`. No `var`, no records, no `List.of`.
- **Apache RAT is active.** Every new file needs the ASF licence header. `.md` is excluded.
- **Commit convention is `GUACAMOLE-283: Capitalized summary`.**
- **`cluster-enabled` defaults to `false`.** With clustering off, `HashSharedConnectionMap` stays bound and behaviour is exactly upstream.
- **Never build a Redis key inline.** All keys come from `ClusterKeys`.
- **A Redis outage degrades, never blocks** (spec §6.1). A share key that cannot be read is simply invalid, which is how an expired key already behaves — it must not throw.
- **Existing suite must stay green:** `mvn -pl guacamole-cluster test` (61 tests as of P3a).

---

## Why this was called the largest diff, and why it is smaller than it looks

The spec (§4.3) describes this as "the largest single diff in the design", because
`SharedConnectionDefinition.java:48` holds a live `ActiveConnectionRecord` and the join
path builds a new record from it. Reading the code, the coupling is narrower than that
suggests. The constructor used for a share-key join needs exactly two things from the
parent record:

```java
// ActiveConnectionRecord.java:298-304
public ActiveConnectionRecord(SharedConnectionMap connectionMap,
        RemoteAuthenticatedUser user,
        ActiveConnectionRecord activeConnection,
        ModeledSharingProfile sharingProfile) {
    this(connectionMap, user, null, activeConnection.getConnection(), sharingProfile);
    this.connectionID = activeConnection.getConnectionID();
}
```

The `ModeledConnection` and the guacd connection ID. Both are available without the
parent record: the connection lives in the database every replica shares, and the guacd
connection ID is already on the tunnel hash P1 writes.

`SharedConnection` dereferences the record three more times — for the connection name
(`SharedConnection.java:95`), the primary connection (`:117`), and the identifier of the
user who shared it (`:139`). That is the full extent of the coupling: **four facts**.

So this plan widens `SharedConnectionDefinition` to expose those four directly, rather
than adding a remote subclass. Local definitions resolve them from the record exactly as
today; remote ones carry them as fields. No polymorphism, and the call sites change from
`definition.getActiveConnection().getConnection()` to `definition.getConnection()`.

**What makes the join actually land on the right guacd is already built.** P1's
`GuacdSelector.selectForJoin` looks up `guac:route:{guacdConnectionId}` and fails closed
if it is gone. This phase supplies the connection ID; the routing is done.

---

## File Structure

**New**

| File | Responsibility |
|---|---|
| `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/SharedConnectionEntry.java` | The four facts a share key resolves to, as cluster state |
| `.../jdbc/sharing/RedisSharedConnectionMap.java` | `SharedConnectionMap` backed by the cluster |
| `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/ShareKeyTest.java` | Tests for share-key storage, liveness and revocation |

**Modified**

| File | Change |
|---|---|
| `.../cluster/ClusterKeys.java` | Share key and revoke channel |
| `.../cluster/ClusterStore.java` + `RedisClusterStore` + `NoOpClusterStore` | Share entry put/get/remove and revoke subscription |
| `.../jdbc/sharing/connection/SharedConnectionDefinition.java` | Carry the four facts; second constructor for remote definitions |
| `.../jdbc/sharing/connection/SharedConnection.java` | Read the four facts from the definition rather than through the record |
| `.../jdbc/tunnel/ActiveConnectionRecord.java` | Constructor taking the connection and guacd connection ID directly |
| `.../jdbc/tunnel/AbstractGuacamoleTunnelService.java` | Build the join record from the definition's facts |
| `.../jdbc/JDBCAuthenticationProviderModule.java` | Bind the Redis map when clustering is enabled |

---

## Task 1: Let a definition answer without a live record

Pure widening. No cluster code yet, and behaviour is unchanged, so this is reviewable on its own.

**Files:**
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/sharing/connection/SharedConnectionDefinition.java`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/sharing/connection/SharedConnection.java`

**Interfaces:**
- Consumes: nothing.
- Produces on `SharedConnectionDefinition`:
  - `getConnection()` returning `ModeledConnection`
  - `getGuacdConnectionId()` returning `String`
  - `getSharedBy()` returning `String`
  - a second constructor `SharedConnectionDefinition(ModeledConnection, String guacdConnectionId, String sharedBy, ModeledSharingProfile, String shareKey)`

- [ ] **Step 1: Add the fields and the remote constructor**

In `SharedConnectionDefinition`, alongside the existing fields:

```java
    /**
     * The connection being shared. Held directly so that a definition
     * reconstructed from cluster state, whose originating session lives on
     * another replica, can answer without a local record.
     */
    private final ModeledConnection connection;

    /**
     * The connection ID issued by guacd for the session being shared.
     */
    private final String guacdConnectionId;

    /**
     * Identifier of the user who shared the connection.
     */
    private final String sharedBy;
```

Set them in the existing constructor from the record, and add the remote one:

```java
    public SharedConnectionDefinition(ActiveConnectionRecord activeConnection,
            ModeledSharingProfile sharingProfile, String shareKey) {
        this.activeConnection = activeConnection;
        this.sharingProfile = sharingProfile;
        this.shareKey = shareKey;
        this.connection = activeConnection.getConnection();
        this.guacdConnectionId = activeConnection.getConnectionID();
        this.sharedBy = activeConnection.getUser().getIdentifier();
    }

    /**
     * Creates a definition for a session owned by another replica, described
     * entirely by cluster state.
     *
     * @param connection
     *     The connection being shared.
     *
     * @param guacdConnectionId
     *     The connection ID issued by guacd for the session being shared.
     *
     * @param sharedBy
     *     Identifier of the user who shared the connection.
     *
     * @param sharingProfile
     *     The sharing profile in use, or null if the connection was shared
     *     without one.
     *
     * @param shareKey
     *     The share key identifying this definition.
     */
    public SharedConnectionDefinition(ModeledConnection connection,
            String guacdConnectionId, String sharedBy,
            ModeledSharingProfile sharingProfile, String shareKey) {
        this.activeConnection = null;
        this.sharingProfile = sharingProfile;
        this.shareKey = shareKey;
        this.connection = connection;
        this.guacdConnectionId = guacdConnectionId;
        this.sharedBy = sharedBy;
    }
```

Add the accessors, and note on `getActiveConnection()` that it can now be null:

```java
    /**
     * @return
     *     The record of the session being shared, or null when that session is
     *     owned by another replica. Callers wanting the connection, the guacd
     *     connection ID or the sharing user must use the accessors below, which
     *     answer in both cases.
     */
    public ActiveConnectionRecord getActiveConnection() {
        return activeConnection;
    }

    public ModeledConnection getConnection() {
        return connection;
    }

    public String getGuacdConnectionId() {
        return guacdConnectionId;
    }

    public String getSharedBy() {
        return sharedBy;
    }
```

Add `import org.apache.guacamole.auth.jdbc.connection.ModeledConnection;`.

- [ ] **Step 2: Point `SharedConnection` at the new accessors**

Three call sites, at `SharedConnection.java:95`, `:117` and `:139`:

```java
        return definition.getConnection().getName();
```
```java
        Connection primaryConnection = definition.getConnection();
```
```java
        String sharedBy = definition.getSharedBy();
```

- [ ] **Step 3: Verify the extension builds**

```bash
mvn -q -pl extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base -am install -DskipTests
```

Expected: BUILD SUCCESS. Any remaining compile error naming `getActiveConnection()` is a
call site that still assumes a live record; route it through the new accessors.

- [ ] **Step 4: Commit**

```bash
git add extensions/guacamole-auth-jdbc
git commit -m "GUACAMOLE-283: Let a shared connection definition answer without a live record

A definition needs four facts from the session it shares: the connection,
the guacd connection ID, who shared it, and the sharing profile. Holding
those directly is what allows one to be rebuilt from cluster state on a
replica that does not own the session.

Behaviour is unchanged -- a locally-created definition populates them
from its record exactly as before."
```

---

## Task 2: Build the join record without a parent record

**Files:**
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/tunnel/ActiveConnectionRecord.java`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/tunnel/AbstractGuacamoleTunnelService.java`

**Interfaces:**
- Consumes: `SharedConnectionDefinition.getConnection()`, `.getGuacdConnectionId()` (Task 1).
- Produces: `ActiveConnectionRecord(SharedConnectionMap, RemoteAuthenticatedUser, ModeledConnection, String guacdConnectionId, ModeledSharingProfile)`.

- [ ] **Step 1: Add the constructor**

In `ActiveConnectionRecord`, beside the one taking a parent record:

```java
    /**
     * Creates a record describing a join of an existing session, identified by
     * the connection ID guacd issued for it rather than by a local record of
     * that session. This is what allows a share key to be redeemed on a replica
     * which does not own the session being shared.
     *
     * @param connectionMap
     *     The SharedConnectionMap instance tracking all active shared
     *     connections.
     *
     * @param user
     *     The user joining the connection.
     *
     * @param connection
     *     The connection being joined.
     *
     * @param guacdConnectionId
     *     The connection ID issued by guacd for the session being joined.
     *
     * @param sharingProfile
     *     The sharing profile being used, or null if none.
     */
    public ActiveConnectionRecord(SharedConnectionMap connectionMap,
            RemoteAuthenticatedUser user,
            ModeledConnection connection,
            String guacdConnectionId,
            ModeledSharingProfile sharingProfile) {
        this(connectionMap, user, null, connection, sharingProfile);
        this.connectionID = guacdConnectionId;
    }
```

- [ ] **Step 2: Use it in the share-key join**

At `AbstractGuacamoleTunnelService.java`, in `getGuacamoleTunnel(RemoteAuthenticatedUser, SharedConnectionDefinition, ...)`:

```java
        // Create a connection record which describes the shared connection.
        // Built from the definition's own facts rather than from a live record,
        // so a key shared on another replica can be redeemed here.
        ActiveConnectionRecord connectionRecord = new ActiveConnectionRecord(connectionMap,
                user, definition.getConnection(), definition.getGuacdConnectionId(),
                definition.getSharingProfile());
```

The resulting record has a non-null `getConnectionID()`, so `selectGuacdEndpoint` takes
its join branch and `GuacdSelector.selectForJoin` routes to the guacd hosting the
session — including when that guacd was chosen by a different replica.

- [ ] **Step 3: Verify the extension builds**

```bash
mvn -q -pl extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base -am install -DskipTests
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add extensions/guacamole-auth-jdbc
git commit -m "GUACAMOLE-283: Build a share-key join from the definition's own facts

The join record previously required a live record of the shared session,
which exists only on the replica owning it. It is now built from the
connection and guacd connection ID the definition carries, so the join
routes through the P1 route table wherever it is redeemed."
```

---

## Task 3: Store share keys in the cluster

**Files:**
- Create: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/SharedConnectionEntry.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterKeys.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/redis/RedisClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/NoOpClusterStore.java`
- Test: `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/ShareKeyTest.java`

**Interfaces:**
- Consumes: `ClusterKeys`, `isTunnelLive` (P3a).
- Produces:
  - `SharedConnectionEntry(String seatToken, String guacdConnectionId, String connectionIdentifier, String sharingProfileIdentifier, String sharedBy)` with a getter per field
  - `ClusterStore.putShareKey(String shareKey, SharedConnectionEntry entry)`
  - `ClusterStore.getShareKey(String shareKey)` returning `SharedConnectionEntry` or null
  - `ClusterStore.removeShareKey(String shareKey)`

- [ ] **Step 1: Write the failing test** (ASF header first)

```java
package org.apache.guacamole.cluster.redis;

import org.apache.guacamole.cluster.SharedConnectionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ShareKeyTest {

    private static final long STALE_WINDOW_MS = 30000L;

    @BeforeAll
    public static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available; skipping Redis-backed tests.");
    }

    private RedisClusterStore store;

    @BeforeEach
    public void setUp() {
        store = new RedisClusterStore(RedisTestSupport.redisUri(), STALE_WINDOW_MS, "node-1");
        store.flushForTesting();
    }

    @AfterEach
    public void tearDown() {
        store.shutdown();
    }

    @Test
    public void storedKeyRoundTrips() throws Exception {

        store.putShareKey("key-1", new SharedConnectionEntry("seat-1", "$abc",
                "conn-1", "profile-1", "alice"));

        SharedConnectionEntry entry = store.getShareKey("key-1");
        assertEquals("seat-1", entry.getSeatToken());
        assertEquals("$abc", entry.getGuacdConnectionId());
        assertEquals("conn-1", entry.getConnectionIdentifier());
        assertEquals("profile-1", entry.getSharingProfileIdentifier());
        assertEquals("alice", entry.getSharedBy());

    }

    @Test
    public void unknownKeyIsNull() throws Exception {
        assertNull(store.getShareKey("never-issued"));
    }

    @Test
    public void keyWithoutASharingProfileRoundTrips() throws Exception {

        // Sharing without a profile is legal; the field is simply absent
        store.putShareKey("key-2", new SharedConnectionEntry("seat-2", "$def",
                "conn-1", null, "alice"));

        assertNull(store.getShareKey("key-2").getSharingProfileIdentifier());

    }

    @Test
    public void removedKeyIsGone() throws Exception {

        store.putShareKey("key-3", new SharedConnectionEntry("seat-3", "$ghi",
                "conn-1", null, "alice"));
        store.removeShareKey("key-3");

        assertNull(store.getShareKey("key-3"));

    }

}
```

`flushForTesting()` does not exist; add it to `RedisClusterStore` in Step 4 rather than
having each test build its own Lettuce client, which the earlier suites do only because
they assert on raw keys.

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl guacamole-cluster test -Dtest=ShareKeyTest
```

Expected: FAIL — `SharedConnectionEntry` does not exist.

- [ ] **Step 3: Create the value object** (ASF header first)

```java
package org.apache.guacamole.cluster;

/**
 * Everything a share key resolves to: enough to rebuild a shared connection
 * definition on a replica which does not own the session being shared.
 */
public class SharedConnectionEntry {

    private final String seatToken;
    private final String guacdConnectionId;
    private final String connectionIdentifier;
    private final String sharingProfileIdentifier;
    private final String sharedBy;

    /**
     * @param seatToken
     *     Identifies the shared session's cluster state, used to check that it
     *     is still live.
     *
     * @param guacdConnectionId
     *     The connection ID issued by guacd, which routes the join.
     *
     * @param connectionIdentifier
     *     The connection being shared, as known to the database.
     *
     * @param sharingProfileIdentifier
     *     The sharing profile in use, or null if none.
     *
     * @param sharedBy
     *     Identifier of the user who shared the connection.
     */
    public SharedConnectionEntry(String seatToken, String guacdConnectionId,
            String connectionIdentifier, String sharingProfileIdentifier,
            String sharedBy) {
        this.seatToken = seatToken;
        this.guacdConnectionId = guacdConnectionId;
        this.connectionIdentifier = connectionIdentifier;
        this.sharingProfileIdentifier = sharingProfileIdentifier;
        this.sharedBy = sharedBy;
    }

    public String getSeatToken() { return seatToken; }
    public String getGuacdConnectionId() { return guacdConnectionId; }
    public String getConnectionIdentifier() { return connectionIdentifier; }
    public String getSharingProfileIdentifier() { return sharingProfileIdentifier; }
    public String getSharedBy() { return sharedBy; }

}
```

- [ ] **Step 4: Add the key, the three operations and the test helper**

In `ClusterKeys.java`:

```java
    /**
     * Channel on which share key revocations are published.
     */
    public static final String SHARE_REVOKE_CHANNEL = "guac:share:revoke";

    public static String shareKey(String shareKey) {
        return "guac:share:" + escape(shareKey);
    }
```

In `RedisClusterStore.java`:

```java
    @Override
    public void putShareKey(String shareKey, SharedConnectionEntry entry) {

        try {

            Map<String, String> record = new HashMap<String, String>();
            record.put("seatToken", entry.getSeatToken());
            record.put("guacdConnectionId", entry.getGuacdConnectionId());
            record.put("connIdentifier", entry.getConnectionIdentifier());
            record.put("sharedBy", entry.getSharedBy());

            if (entry.getSharingProfileIdentifier() != null)
                record.put("sharingProfileId", entry.getSharingProfileIdentifier());

            commands().hset(ClusterKeys.shareKey(shareKey), record);
            available = true;

        }

        // A share key that cannot be stored simply never works, which is how an
        // expired key already behaves. It must not fail the share request.
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            logger.warn("Unable to publish share key to the cluster. It will "
                    + "work only on this replica.", e);
        }

    }

    @Override
    public SharedConnectionEntry getShareKey(String shareKey) {

        try {

            Map<String, String> record = commands().hgetall(ClusterKeys.shareKey(shareKey));
            available = true;

            if (record.isEmpty())
                return null;

            return new SharedConnectionEntry(
                    record.get("seatToken"),
                    record.get("guacdConnectionId"),
                    record.get("connIdentifier"),
                    record.get("sharingProfileId"),
                    record.get("sharedBy"));

        }

        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            return null;
        }

    }

    @Override
    public void removeShareKey(String shareKey) {

        try {
            commands().del(ClusterKeys.shareKey(shareKey));
            available = true;
        }

        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            logger.warn("Unable to remove share key from the cluster. It will "
                    + "stop working when its session ends.", e);
        }

    }

    /**
     * Clears the entire keyspace. Intended only for tests.
     */
    public void flushForTesting() {
        commands().flushall();
    }
```

Declare the three on `ClusterStore` with Javadoc matching the Interfaces block above, and
implement them on `NoOpClusterStore` as no-ops returning null for `getShareKey`.

- [ ] **Step 5: Run it to verify it passes**

```bash
mvn -q -pl guacamole-cluster test -Dtest=ShareKeyTest
```

Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add guacamole-cluster/src
git commit -m "GUACAMOLE-283: Store share keys in the cluster

A share key resolves to the four facts a definition needs plus the seat
token of the session it shares, which is what lets a redemption check the
session is still live.

Failing to publish a key is logged rather than thrown: the key then works
only on the replica that issued it, which is the pre-cluster behaviour."
```

---

## Task 4: Resolve share keys through the cluster

**Files:**
- Create: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/sharing/RedisSharedConnectionMap.java`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/JDBCAuthenticationProviderModule.java`

**Interfaces:**
- Consumes: `ClusterStore` share operations (Task 3), `SharedConnectionDefinition`'s remote constructor (Task 1), `ClusterStore.isTunnelLive` (P3a).
- Produces: `RedisSharedConnectionMap implements SharedConnectionMap`.

- [ ] **Step 1: Write the map** (ASF header first)

A local delegate still serves keys issued here, because a locally-issued definition owns
live tunnel state — `registerTunnel` and `invalidate` — that Redis cannot hold.

```java
package org.apache.guacamole.auth.jdbc.sharing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.auth.jdbc.connection.ConnectionService;
import org.apache.guacamole.auth.jdbc.connection.ModeledConnection;
import org.apache.guacamole.auth.jdbc.sharing.connection.SharedConnectionDefinition;
import org.apache.guacamole.auth.jdbc.sharingprofile.ModeledSharingProfile;
import org.apache.guacamole.auth.jdbc.sharingprofile.SharingProfileService;
import org.apache.guacamole.auth.jdbc.user.ModeledAuthenticatedUser;
import org.apache.guacamole.cluster.ClusterStore;
import org.apache.guacamole.cluster.SharedConnectionEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SharedConnectionMap which publishes every share key to the cluster, so a key
 * issued on one replica can be redeemed on any other.
 *
 * Keys issued locally are also kept in memory: a local definition owns live
 * tunnel state, registering tunnels and invalidating them on revocation, which
 * cluster state cannot hold.
 */
@Singleton
public class RedisSharedConnectionMap implements SharedConnectionMap {

    private static final Logger logger =
            LoggerFactory.getLogger(RedisSharedConnectionMap.class);

    @Inject
    private ClusterStore clusterStore;

    @Inject
    private ConnectionService connectionService;

    @Inject
    private SharingProfileService sharingProfileService;

    /**
     * Definitions issued by this replica.
     */
    private final HashSharedConnectionMap local = new HashSharedConnectionMap();

    @Override
    public void add(SharedConnectionDefinition definition) {

        local.add(definition);

        clusterStore.putShareKey(definition.getShareKey(), new SharedConnectionEntry(
                definition.getActiveConnection() != null
                        ? definition.getActiveConnection().getClusterSeatToken() : null,
                definition.getGuacdConnectionId(),
                definition.getConnection().getIdentifier(),
                definition.getSharingProfile() != null
                        ? definition.getSharingProfile().getIdentifier() : null,
                definition.getSharedBy()));

    }

    @Override
    public SharedConnectionDefinition get(String key) {

        // A locally-issued key keeps its live definition
        SharedConnectionDefinition definition = local.get(key);
        if (definition != null)
            return definition;

        SharedConnectionEntry entry = clusterStore.getShareKey(key);
        if (entry == null)
            return null;

        // The session may have ended on its owning replica, in which case the
        // key is dead and is cleaned up here rather than lingering
        if (entry.getSeatToken() == null
                || !clusterStore.isTunnelLive(entry.getSeatToken())) {
            clusterStore.removeShareKey(key);
            return null;
        }

        return buildRemoteDefinition(key, entry);

    }

    @Override
    public SharedConnectionDefinition remove(String key) {

        clusterStore.removeShareKey(key);
        return local.remove(key);

    }

    /**
     * Rebuilds a definition for a session owned by another replica.
     *
     * @param key
     *     The share key being redeemed.
     *
     * @param entry
     *     The cluster state that key resolves to.
     *
     * @return
     *     A definition describing the shared session, or null if the connection
     *     or sharing profile behind it can no longer be read.
     */
    private SharedConnectionDefinition buildRemoteDefinition(String key,
            SharedConnectionEntry entry) {

        try {

            // Both live in the database, which every replica shares
            ModeledConnection connection = connectionService.retrieveObject(
                    getPrivilegedUser(), entry.getConnectionIdentifier());

            if (connection == null)
                return null;

            ModeledSharingProfile sharingProfile = null;
            if (entry.getSharingProfileIdentifier() != null)
                sharingProfile = sharingProfileService.retrieveObject(
                        getPrivilegedUser(), entry.getSharingProfileIdentifier());

            return new SharedConnectionDefinition(connection,
                    entry.getGuacdConnectionId(), entry.getSharedBy(),
                    sharingProfile, key);

        }

        catch (GuacamoleException e) {
            logger.warn("Share key \"{}\" resolves to a connection which cannot "
                    + "be read on this replica.", key, e);
            return null;
        }

    }

}
```

`getPrivilegedUser()` does not exist and must not be invented: reading the connection
behind a share key is exactly the access check the share key itself represents, and the
redeeming user has no permission on the connection. Resolve this in Step 2 before
continuing.

- [ ] **Step 2: Resolve how the connection is read**

`ConnectionService.retrieveObject` takes a `ModeledAuthenticatedUser` and enforces that
user's permissions. A user redeeming a share key legitimately has no permission on the
underlying connection — that is the entire point of sharing.

Check how the existing local path obtains the connection without a permission check: it
does not read it at all, because `SharedConnectionDefinition` already holds the
`ModeledConnection` captured when the **sharing** user created the definition.

Therefore the remote path must not go through `ConnectionService`. Read the model
directly through the mapper, which performs no permission check, and wrap it using the
same pattern `ConnectionService.getObjectInstance` uses (`ConnectionService.java:118-123`).

The mapper's select takes a **collection** of identifiers and a case-sensitivity setting;
there is no single-identifier variant:

```java
    @Inject
    private ConnectionMapper connectionMapper;

    @Inject
    private Provider<ModeledConnection> connectionProvider;

    @Inject
    private SharingProfileMapper sharingProfileMapper;

    @Inject
    private Provider<ModeledSharingProfile> sharingProfileProvider;

    @Inject
    private JDBCEnvironment environment;
```

```java
            Collection<ConnectionModel> models = connectionMapper.select(
                    Collections.singleton(entry.getConnectionIdentifier()),
                    environment.getCaseSensitivity());

            if (models.isEmpty())
                return null;

            ModeledConnection connection = connectionProvider.get();
            connection.init(user, models.iterator().next());
```

`init` takes the current user, so pass the user redeeming the key. The object is used
only as a carrier for the connection's identifier, name and proxy configuration, none of
which consult that user for permission. Build the sharing profile the same way via
`SharingProfileMapper` when `getSharingProfileIdentifier()` is non-null.

`buildRemoteDefinition` therefore needs the redeeming user as a parameter; thread it
through from `get(String key)`, which means `SharedConnectionMap.get` must also carry it
— check whether the interface already provides one, and if not, resolve it from the
`SharedConnectionDirectory` call site at `SharedConnectionDirectory.java:103` rather than
widening the interface.

**Do not skip this step's verification.** Guessing this API is exactly the class of error
that cost a build/deploy cycle in P3a, where the spec's claim about
`TrackedActiveConnection` setters turned out to be false.

- [ ] **Step 3: Bind it when clustering is enabled**

In `JDBCAuthenticationProviderModule.configure()`, replace the unconditional binding:

```java
        // Share keys cross replicas only when clustering is on
        if (ClusterModule.isEnabled(environment))
            bind(SharedConnectionMap.class).to(RedisSharedConnectionMap.class).in(Scopes.SINGLETON);
        else
            bind(SharedConnectionMap.class).to(HashSharedConnectionMap.class).in(Scopes.SINGLETON);
```

`configure()` cannot throw `GuacamoleException`; `ClusterModule.isEnabled` declares it.
Wrap in a try/catch that falls back to `HashSharedConnectionMap` and calls `addError`, so
a misconfigured property degrades to single-replica sharing rather than failing the
module — the same shape `ClusterModule.configure()` already uses.

- [ ] **Step 4: Verify the extension builds**

```bash
mvn -q -pl extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base -am install -DskipTests
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add extensions/guacamole-auth-jdbc
git commit -m "GUACAMOLE-283: Resolve share keys through the cluster

A key issued on any replica now resolves anywhere. Locally-issued keys
keep their in-memory definition, which owns live tunnel state that
cluster state cannot hold; remote ones are rebuilt from the four facts
the cluster stores.

A key whose session has ended is removed on read rather than left to
linger, using the seat token to check liveness."
```

---

## Task 5: Revoke a share key across replicas

**Files:**
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/redis/RedisClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/NoOpClusterStore.java`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/sharing/RedisSharedConnectionMap.java`
- Test: `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/ShareKeyTest.java`

**Interfaces:**
- Consumes: the pub/sub machinery added in P3a.
- Produces:
  - `ClusterStore.onShareRevoked(ClusterShareRevocationHandler handler)`
  - `ClusterStore.publishShareRevocation(String shareKey)`
  - `interface ClusterShareRevocationHandler { void shareRevoked(String shareKey); }`

- [ ] **Step 1: Write the failing test**

Add to `ShareKeyTest`:

```java
    @Test
    public void revocationReachesAnotherReplica() throws Exception {

        RedisClusterStore other = new RedisClusterStore(RedisTestSupport.redisUri(),
                STALE_WINDOW_MS, "node-2");

        try {

            final java.util.concurrent.CountDownLatch revoked =
                    new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicReference<String> seen =
                    new java.util.concurrent.atomic.AtomicReference<String>();

            other.onShareRevoked(new org.apache.guacamole.cluster.ClusterShareRevocationHandler() {

                @Override
                public void shareRevoked(String shareKey) {
                    seen.set(shareKey);
                    revoked.countDown();
                }

            });

            store.publishShareRevocation("key-revoked");

            assertEquals(true, revoked.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "revocation never arrived");
            assertEquals("key-revoked", seen.get());

        }

        finally {
            other.shutdown();
        }

    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl guacamole-cluster test -Dtest=ShareKeyTest
```

Expected: FAIL — `ClusterShareRevocationHandler` does not exist.

- [ ] **Step 3: Create the handler** (ASF header first)

```java
package org.apache.guacamole.cluster;

/**
 * Receives notification that a share key has been revoked anywhere in the
 * cluster, so that tunnels opened from it on this replica can be closed.
 */
public interface ClusterShareRevocationHandler {

    /**
     * Invoked when the given share key is revoked.
     *
     * @param shareKey
     *     The share key which is no longer valid.
     */
    void shareRevoked(String shareKey);

}
```

- [ ] **Step 4: Publish and subscribe**

`RedisClusterStore` already holds one pub/sub connection for kills. Reuse it by
subscribing to both channels and dispatching on the channel name, rather than opening a
second connection:

```java
    @Override
    public void onShareRevoked(final ClusterShareRevocationHandler handler) {
        this.shareRevocationHandler = handler;
        subscribeIfNeeded();
    }
```

Refactor the P3a `onKillRequest` and this method onto one `subscribeIfNeeded()` which
opens the connection once, subscribes to both `ClusterKeys.KILL_CHANNEL` and
`ClusterKeys.SHARE_REVOKE_CHANNEL`, and routes each message:

```java
                @Override
                public void message(String channel, String payload) {
                    try {
                        if (ClusterKeys.KILL_CHANNEL.equals(channel)) {
                            ClusterKillHandler killHandler = RedisClusterStore.this.killHandler;
                            if (killHandler != null)
                                killHandler.killLocalTunnel(payload);
                        }
                        else if (ClusterKeys.SHARE_REVOKE_CHANNEL.equals(channel)) {
                            ClusterShareRevocationHandler revocationHandler =
                                    RedisClusterStore.this.shareRevocationHandler;
                            if (revocationHandler != null)
                                revocationHandler.shareRevoked(payload);
                        }
                    }

                    // A handler that throws must not kill the subscriber
                    catch (Throwable e) {
                        logger.warn("Cluster message on \"{}\" could not be handled.",
                                channel, e);
                    }
                }
```

`publishShareRevocation` mirrors `requestKill`, publishing to
`ClusterKeys.SHARE_REVOKE_CHANNEL`.

- [ ] **Step 5: Revoke on removal**

In `RedisSharedConnectionMap.remove`, publish so other replicas drop tunnels opened from
the key, and register a handler that invalidates the local definition:

```java
    @Override
    public SharedConnectionDefinition remove(String key) {

        clusterStore.removeShareKey(key);
        clusterStore.publishShareRevocation(key);
        return local.remove(key);

    }

    /**
     * Subscribes this replica to revocations issued elsewhere. Invoked by Guice
     * once this map has been constructed.
     */
    @Inject
    public void registerRevocationHandler() {
        clusterStore.onShareRevoked(new ClusterShareRevocationHandler() {

            @Override
            public void shareRevoked(String shareKey) {
                // Closes any tunnels this replica opened from the key
                local.remove(shareKey);
            }

        });
    }
```

`HashSharedConnectionMap.remove` already calls `definition.invalidate()`, which closes
those tunnels, so the local delegate does the work.

**A tunnel opened here from a remote key is not registered with any definition this
replica holds**, because `buildRemoteDefinition` creates a fresh definition per
redemption and `local` never sees it. Note this in the commit and in *What P3b does NOT
do*: revoking a key stops further redemptions everywhere and closes tunnels on the
issuing replica, but a tunnel already opened from that key on another replica survives
until its session ends. Closing those needs the tunnel registry from P3a to carry the
share key, which is out of scope here.

- [ ] **Step 6: Run the tests**

```bash
mvn -q -pl guacamole-cluster test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add guacamole-cluster/src extensions/guacamole-auth-jdbc
git commit -m "GUACAMOLE-283: Revoke share keys across replicas

Revocation is published on its own channel, sharing the pub/sub
connection already opened for kills rather than opening a second.

Revoking stops further redemption everywhere and closes tunnels on the
replica that issued the key. A tunnel already opened from that key on
another replica survives until its session ends -- closing those needs
the tunnel registry to carry the share key, which is not in this phase."
```

---

## Task 6: Verify on the live two-replica deployment

The unit tests cover storage and revocation. Only a deployment proves redemption, because
the thing being built — a key issued on one replica working on another — cannot happen in
a single JVM.

The devqa stack is running (`remote-access`, image `1.6.1-p3b`) with a `tester` pod
carrying `curl`. **Drive the API from inside the cluster**; port-forwards died repeatedly
under load in earlier phases and produced misleading readings.

**Files:** none. Produces `docs/superpowers/deploy/README.md` section 9.

- [ ] **Step 1: Build, push and deploy**

```bash
docker build -t guacamole-cluster:1.6.1-p3c --build-arg MAVEN_ARGUMENTS=-DskipTests .
```

If the build fails with "no space left on device", run `docker builder prune -af`.

- [ ] **Step 2: Open a session on replica A and share it**

Open a tunnel on A and hold it. Then obtain a share key through the API:

```bash
curl -s -X POST "http://<A>:8080/api/session/data/postgresql/activeConnections/<record uuid>/sharingProfiles/<profile id>?token=<A token>"
```

If no sharing profile exists, create one against the test connection first.

- [ ] **Step 3: Redeem the key on replica B**

Use the share key as a connection identifier against B's shared data source, and open a
tunnel.

**Expected: it connects, and joins the existing session rather than starting a new one.**
Confirm by checking that guacd reports no new client for the connection, and that the
route used matches the session on A:

```bash
kubectl -n remote-access exec deploy/redis -- redis-cli get 'guac:route:$<guacd connection id>'
```

- [ ] **Step 4: Confirm a revoked key stops working**

Revoke it on A, then attempt redemption on B again.

**Expected: refused.**

- [ ] **Step 5: Confirm a key whose session has ended is refused**

Close the session on A, then redeem on B.

**Expected: refused, and `guac:share:<key>` is gone** — the liveness check removes it on
read.

- [ ] **Step 6: Confirm the disabled path is unchanged**

Set `CLUSTER_ENABLED=false`, restart, and confirm sharing still works within a single
replica and that `HashSharedConnectionMap` is bound.

- [ ] **Step 7: Document the measurements**

Add section 9 to `docs/superpowers/deploy/README.md` in the style of sections 4-8,
recording what was actually observed, including anything that failed.

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/deploy/README.md
git commit -m "GUACAMOLE-283: Record cross-replica share key verification"
```

---

## What P3b does NOT do

- **A tunnel already opened from a revoked key on another replica is not closed.**
  Revocation stops further redemption everywhere and closes tunnels on the issuing
  replica. Closing the rest needs the cluster tunnel registry to carry the share key.
- **Share keys have no independent expiry.** They are removed when their session ends
  (checked on read via the seat token) or when explicitly revoked. A key whose session
  ended and which is never read again leaves one small hash behind, the same trade-off
  P2 documented for index tombstones.
- **Auth tokens are still replica-local**, so a replica death forces re-login. P4.
- **Brute-force ban counts are still per-replica.** P4.
