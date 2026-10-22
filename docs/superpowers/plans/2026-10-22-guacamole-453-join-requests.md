# GUACAMOLE-453 Join Requests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a user opens a connection that another user is connected to, ask the connected user to admit them via a sharing profile, or let the newcomer Enforce, which ends the existing session.

**Architecture:**
- **Server:** a mailbox of join requests (in-memory, or Redis when clustered) behind `JoinRequestStore`. The rules live in `JoinRequestService`, which talks to the JDBC world only through a per-caller `JoinContext` interface, so it can be tested without Guice or a database. A JAX-RS resource under `/api/session/ext/<ds>/join/` exposes it.
- **UI:** a JDBC-extension AngularJS module (`js/joinRequests.js`). It intercepts `/client/:id` route changes for the newcomer and polls `incoming` for the connected user.

**Tech Stack:** Java 8 (warnings are errors), JUnit 5 with hand-written fakes (no Mockito), Guice, JAX-RS (Jersey and Jackson), Lettuce with Lua scripts for Redis, Testcontainers Redis, and AngularJS 1.x extension JS.

**Spec:** `docs/superpowers/specs/2026-10-22-guacamole-453-join-requests-design.md` (see Task 7 for the deviations this plan makes).

## Global Constraints

- Java source and target are 1.8, and `failOnWarning` is true. No `var`, no `List.of`, no raw types, and no unchecked warnings.
- Every new `.java` and `.js` file starts with the ASF license header, copied verbatim from `guacamole-ext/src/main/java/org/apache/guacamole/cluster/ClusterKeys.java` lines 1-18. Every new `.lua` file uses the `--` form from `guacamole-ext/src/main/resources/org/apache/guacamole/cluster/redis/record-auth-failure.lua` lines 1-16. Apache RAT fails the build without it.
- Property names: `join-request-enabled` (boolean, default `false`) and `join-request-timeout` (seconds, default `120`).
- REST base: `api/session/ext/<data-source>/join/`. Every endpoint returns 404 (`GuacamoleResourceNotFoundException`) when the feature is disabled.
- Commit messages: `GUACAMOLE-453: <Capitalized summary>.` This repo is public. Never mention internal ticket keys, company names or environment names.
- Redis keys: `guac:join:<id>` (hash) and `guac:join:idx:<recordUuid>` (set). Both carry a TTL equal to the request timeout.
- JDBC base path, `B` = `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc`. Test path, `T` = the same path with `src/test/java`.
- Commands:
  - jdbc-base tests: `cd extensions/guacamole-auth-jdbc && mvn -q test -pl modules/guacamole-auth-jdbc-base -am -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`
  - guacamole-ext tests: `mvn -q -pl guacamole-ext test -Dtest=<Class> -Dexec.skip=true`
  - Never `mvn install`: worktrees share `~/.m2`.

---

## File Structure

| File | Responsibility |
|---|---|
| `B/join/JoinRequestProperties.java` | Property definitions and the default timeout |
| `B/join/JoinRequest.java` | Immutable request value plus its `Status` enum |
| `B/join/JoinRequestStore.java` | Mailbox interface |
| `B/join/InMemoryJoinRequestStore.java` | Non-clustered mailbox |
| `B/join/RedisJoinRequestStore.java` | Clustered mailbox on top of `ClusterStore` |
| `B/join/PrimarySession.java` | A primary (non-shared) session on a connection: record UUID and username |
| `B/join/SharingOption.java` | A sharing profile that can be offered: identifier and name |
| `B/join/JoinContext.java` | Per-caller view of sessions, permissions, sharing and kill |
| `B/join/JdbcJoinContext.java` | Production `JoinContext` (tunnel service, cluster store, sharing service) |
| `B/join/JoinContextFactory.java` | Builds a `JdbcJoinContext` for a `ModeledAuthenticatedUser` |
| `B/join/JoinRequestService.java` | All rules: status, create, poll, cancel, incoming, decide, enforce |
| `B/join/JoinRequestResource.java` | JAX-RS endpoints |
| `B/join/JoinRequestCreation.java`, `B/join/JoinDecision.java` | JSON request bodies |
| `B/user/JDBCUserContextResource.java` | The single per-session extension resource, mounting `password-reset` and `join` |
| `B/user/ModeledUserContext.java` (modify) | `getResource()` returns the combined resource |
| `B/JDBCAuthenticationProviderModule.java` (modify) | Store and service bindings |
| `guacamole-ext/.../cluster/ClusterStore.java`, `NoOpClusterStore.java`, `ClusterKeys.java`, `redis/RedisClusterStore.java` (modify) | Join-request storage primitives |
| `guacamole-ext/src/main/resources/org/apache/guacamole/cluster/redis/put-join-request.lua`, `resolve-join-request.lua` | Atomic Redis writes |
| `…/guacamole-auth-jdbc-base/src/main/resources/js/joinRequests.js` | UI for both sides |
| `…/guacamole-auth-jdbc-base/src/main/resources/translations/en.json` (modify) | `JOIN.*` strings |
| `…/guacamole-auth-jdbc-{postgresql,mysql,sqlserver}/src/main/resource-templates/guac-manifest.json` (modify) | Register `js/joinRequests.js` |

---

### Task 1: Request model, properties and in-memory store

**Files:**
- Create: `B/join/JoinRequestProperties.java`, `B/join/JoinRequest.java`, `B/join/JoinRequestStore.java`, `B/join/InMemoryJoinRequestStore.java`, `B/join/package-info.java`
- Test: `T/join/InMemoryJoinRequestStoreTest.java`

**Interfaces:**
- Produces:
  - `JoinRequest(String id, String connectionIdentifier, List<String> recordUuids, String requester, long expires, JoinRequest.Status status, String shareKey)`, with getters `getId()`, `getConnectionIdentifier()`, `getRecordUuids()`, `getRequester()`, `getExpires()`, `getStatus()` and `getShareKey()`, plus `withResolution(Status, String)` and `isExpired(long now)`.
  - `enum JoinRequest.Status { PENDING, ADMITTED, DENIED, CANCELLED }`
  - `JoinRequestStore`: `void put(JoinRequest)`, `JoinRequest get(String id)` (null if absent or expired), `List<JoinRequest> listFor(String recordUuid)`, `boolean resolve(String id, JoinRequest.Status status, String shareKey)` (true only if the request was `PENDING`), `void remove(String id)`. Every method `throws GuacamoleException`.
  - `InMemoryJoinRequestStore()` uses the wall clock; `InMemoryJoinRequestStore(LongSupplier clock)` is for tests.
  - `JoinRequestProperties.JOIN_REQUEST_ENABLED`, `JOIN_REQUEST_TIMEOUT`, `DEFAULT_TIMEOUT = 120`

- [ ] **Step 1: Write the failing test** `T/join/InMemoryJoinRequestStoreTest.java`

```java
package org.apache.guacamole.auth.jdbc.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class InMemoryJoinRequestStoreTest {

    private final AtomicLong now = new AtomicLong(1000000L);
    private InMemoryJoinRequestStore store;

    @BeforeEach
    public void setUp() {
        store = new InMemoryJoinRequestStore(now::get);
    }

    private JoinRequest request(String id, long expires, String... uuids) {
        return new JoinRequest(id, "7", Arrays.asList(uuids), "bob", expires,
                JoinRequest.Status.PENDING, null);
    }

    @Test
    public void putThenGetReturnsRequest() throws Exception {
        store.put(request("r1", now.get() + 5000, "u1"));
        JoinRequest found = store.get("r1");
        assertEquals("bob", found.getRequester());
        assertEquals(JoinRequest.Status.PENDING, found.getStatus());
    }

    @Test
    public void expiredRequestIsGone() throws Exception {
        store.put(request("r1", now.get() + 5000, "u1"));
        now.addAndGet(5001);
        assertNull(store.get("r1"));
        assertTrue(store.listFor("u1").isEmpty());
    }

    @Test
    public void listForReturnsOnlyRequestsTargetingThatSession() throws Exception {
        store.put(request("r1", now.get() + 5000, "u1", "u2"));
        store.put(request("r2", now.get() + 5000, "u3"));
        assertEquals(1, store.listFor("u2").size());
        assertEquals("r1", store.listFor("u2").get(0).getId());
        assertTrue(store.listFor("u9").isEmpty());
    }

    @Test
    public void firstResolutionWins() throws Exception {
        store.put(request("r1", now.get() + 5000, "u1"));
        assertTrue(store.resolve("r1", JoinRequest.Status.ADMITTED, "key-1"));
        assertFalse(store.resolve("r1", JoinRequest.Status.DENIED, null));
        assertEquals(JoinRequest.Status.ADMITTED, store.get("r1").getStatus());
        assertEquals("key-1", store.get("r1").getShareKey());
    }

    @Test
    public void resolvingMissingOrExpiredRequestFails() throws Exception {
        assertFalse(store.resolve("nope", JoinRequest.Status.DENIED, null));
        store.put(request("r1", now.get() + 5000, "u1"));
        now.addAndGet(6000);
        assertFalse(store.resolve("r1", JoinRequest.Status.DENIED, null));
    }

    @Test
    public void removeDeletesRequest() throws Exception {
        store.put(request("r1", now.get() + 5000, "u1"));
        store.remove("r1");
        assertNull(store.get("r1"));
    }

}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd extensions/guacamole-auth-jdbc && mvn -q test -pl modules/guacamole-auth-jdbc-base -am -Dtest=InMemoryJoinRequestStoreTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, because `InMemoryJoinRequestStore` and `JoinRequest` do not exist yet.

- [ ] **Step 3: Implement**

`B/join/package-info.java`:
```java
/**
 * Requests to join a connection that another user is already connected to.
 */
package org.apache.guacamole.auth.jdbc.join;
```

`B/join/JoinRequestProperties.java`:
```java
package org.apache.guacamole.auth.jdbc.join;

import org.apache.guacamole.properties.BooleanGuacamoleProperty;
import org.apache.guacamole.properties.IntegerGuacamoleProperty;

/**
 * Properties controlling join requests.
 */
public class JoinRequestProperties {

    private JoinRequestProperties() {}

    /**
     * The number of seconds an unanswered join request remains valid if
     * join-request-timeout is not set.
     */
    public static final int DEFAULT_TIMEOUT = 120;

    /**
     * Whether opening a connection that another user is connected to asks
     * that user to admit the newcomer. Disabled by default.
     */
    public static final BooleanGuacamoleProperty JOIN_REQUEST_ENABLED =
            new BooleanGuacamoleProperty() {
        @Override
        public String getName() { return "join-request-enabled"; }
    };

    /**
     * The number of seconds an unanswered join request remains valid.
     */
    public static final IntegerGuacamoleProperty JOIN_REQUEST_TIMEOUT =
            new IntegerGuacamoleProperty() {
        @Override
        public String getName() { return "join-request-timeout"; }
    };

}
```

`B/join/JoinRequest.java`:
```java
package org.apache.guacamole.auth.jdbc.join;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A request by one user to join a connection that other users hold.
 */
public class JoinRequest {

    /**
     * The possible states of a join request.
     */
    public enum Status { PENDING, ADMITTED, DENIED, CANCELLED }

    private final String id;
    private final String connectionIdentifier;
    private final List<String> recordUuids;
    private final String requester;
    private final long expires;
    private final Status status;
    private final String shareKey;

    /**
     * Creates a join request.
     *
     * @param id
     *     The unique identifier of this request.
     *
     * @param connectionIdentifier
     *     The identifier of the connection being joined.
     *
     * @param recordUuids
     *     The UUIDs of the primary sessions held by other users when the
     *     request was made.
     *
     * @param requester
     *     The username of the user asking to join.
     *
     * @param expires
     *     When this request expires, in milliseconds since the epoch.
     *
     * @param status
     *     The current status of this request.
     *
     * @param shareKey
     *     The share key granted on admission, or null.
     */
    public JoinRequest(String id, String connectionIdentifier,
            List<String> recordUuids, String requester, long expires,
            Status status, String shareKey) {
        this.id = id;
        this.connectionIdentifier = connectionIdentifier;
        this.recordUuids = Collections.unmodifiableList(new ArrayList<String>(recordUuids));
        this.requester = requester;
        this.expires = expires;
        this.status = status;
        this.shareKey = shareKey;
    }

    public String getId() { return id; }
    public String getConnectionIdentifier() { return connectionIdentifier; }
    public List<String> getRecordUuids() { return recordUuids; }
    public String getRequester() { return requester; }
    public long getExpires() { return expires; }
    public Status getStatus() { return status; }
    public String getShareKey() { return shareKey; }

    /**
     * Returns whether this request has expired.
     *
     * @param now
     *     The current time, in milliseconds since the epoch.
     *
     * @return
     *     true if this request is no longer valid.
     */
    public boolean isExpired(long now) {
        return now >= expires;
    }

    /**
     * Returns a copy of this request with the given resolution.
     *
     * @param newStatus
     *     The new status.
     *
     * @param newShareKey
     *     The share key granted, or null.
     *
     * @return
     *     A resolved copy of this request.
     */
    public JoinRequest withResolution(Status newStatus, String newShareKey) {
        return new JoinRequest(id, connectionIdentifier, recordUuids, requester,
                expires, newStatus, newShareKey);
    }

}
```

`B/join/JoinRequestStore.java`:
```java
package org.apache.guacamole.auth.jdbc.join;

import java.util.List;
import org.apache.guacamole.GuacamoleException;

/**
 * Storage for pending join requests. Expired requests behave as absent.
 */
public interface JoinRequestStore {

    /**
     * Stores the given request until it expires.
     *
     * @param request
     *     The request to store.
     *
     * @throws GuacamoleException
     *     If the request cannot be stored.
     */
    void put(JoinRequest request) throws GuacamoleException;

    /**
     * Returns the request with the given ID.
     *
     * @param id
     *     The ID of the request.
     *
     * @return
     *     The request, or null if it does not exist or has expired.
     *
     * @throws GuacamoleException
     *     If the store cannot be read.
     */
    JoinRequest get(String id) throws GuacamoleException;

    /**
     * Returns every unexpired request targeting the given primary session.
     *
     * @param recordUuid
     *     The UUID of the primary session.
     *
     * @return
     *     The matching requests, in any status. Never null.
     *
     * @throws GuacamoleException
     *     If the store cannot be read.
     */
    List<JoinRequest> listFor(String recordUuid) throws GuacamoleException;

    /**
     * Resolves a pending request. Only the first resolution of a request
     * succeeds.
     *
     * @param id
     *     The ID of the request.
     *
     * @param status
     *     The new status.
     *
     * @param shareKey
     *     The share key granted, or null.
     *
     * @return
     *     true if the request was pending and is now resolved, false
     *     otherwise.
     *
     * @throws GuacamoleException
     *     If the store cannot be written.
     */
    boolean resolve(String id, JoinRequest.Status status, String shareKey)
            throws GuacamoleException;

    /**
     * Removes the request with the given ID, if present.
     *
     * @param id
     *     The ID of the request.
     *
     * @throws GuacamoleException
     *     If the store cannot be written.
     */
    void remove(String id) throws GuacamoleException;

}
```

`B/join/InMemoryJoinRequestStore.java`:
```java
package org.apache.guacamole.auth.jdbc.join;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * JoinRequestStore for a single web application instance.
 */
public class InMemoryJoinRequestStore implements JoinRequestStore {

    private final ConcurrentMap<String, JoinRequest> requests =
            new ConcurrentHashMap<String, JoinRequest>();

    private final LongSupplier clock;

    /**
     * Creates a store using the system clock.
     */
    public InMemoryJoinRequestStore() {
        this(System::currentTimeMillis);
    }

    /**
     * Creates a store using the given clock.
     *
     * @param clock
     *     Supplies the current time, in milliseconds since the epoch.
     */
    public InMemoryJoinRequestStore(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public void put(JoinRequest request) {
        long now = clock.getAsLong();
        Iterator<JoinRequest> iterator = requests.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().isExpired(now))
                iterator.remove();
        }
        requests.put(request.getId(), request);
    }

    @Override
    public JoinRequest get(String id) {
        JoinRequest request = requests.get(id);
        if (request == null)
            return null;
        if (request.isExpired(clock.getAsLong())) {
            requests.remove(id, request);
            return null;
        }
        return request;
    }

    @Override
    public List<JoinRequest> listFor(String recordUuid) {
        long now = clock.getAsLong();
        List<JoinRequest> matches = new ArrayList<JoinRequest>();
        for (JoinRequest request : requests.values()) {
            if (!request.isExpired(now) && request.getRecordUuids().contains(recordUuid))
                matches.add(request);
        }
        return matches;
    }

    @Override
    public boolean resolve(String id, JoinRequest.Status status, String shareKey) {
        JoinRequest current = get(id);
        if (current == null || current.getStatus() != JoinRequest.Status.PENDING)
            return false;
        return requests.replace(id, current, current.withResolution(status, shareKey));
    }

    @Override
    public void remove(String id) {
        requests.remove(id);
    }

}
```

Every file above begins with the ASF header (Global Constraints).

- [ ] **Step 4: Run the test to verify it passes**

Run the Step 2 command. Expected: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src
git commit -m "GUACAMOLE-453: Add join request model and in-memory store."
```

---

### Task 2: JoinRequestService rules

**Files:**
- Create: `B/join/PrimarySession.java`, `B/join/SharingOption.java`, `B/join/JoinContext.java`, `B/join/JoinRequestService.java`
- Test: `T/join/JoinRequestServiceTest.java`, `T/join/FakeJoinContext.java`

**Interfaces:**
- Consumes: Task 1 (`JoinRequest`, `JoinRequestStore`, `InMemoryJoinRequestStore`, `JoinRequestProperties`).
- Produces:
  - `PrimarySession(String recordUuid, String username)`, with `getRecordUuid()` and `getUsername()`
  - `SharingOption(String identifier, String name)`, with `getIdentifier()` and `getName()`
  - `JoinContext` (all methods `throws GuacamoleException` except `getUsername()`):
    - `String getUsername()`
    - `boolean canReadConnection(String connectionIdentifier)`
    - `List<PrimarySession> getPrimarySessions(String connectionIdentifier)`: cluster-wide primary sessions on the connection
    - `Set<String> getOwnPrimaryRecordUuids()`: the caller's own primary sessions served by this node
    - `List<SharingOption> getSharingOptions(String recordUuid)`
    - `String share(String recordUuid, String sharingProfileIdentifier)`: returns a share key
    - `void endSession(PrimarySession session)`
  - `JoinRequestService(JoinRequestStore store, boolean enabled, int timeoutSeconds, LongSupplier clock, Supplier<String> ids)`. The production `@Inject JoinRequestService(JoinRequestStore store, JDBCEnvironment environment)` is added in Task 4.
  - Service methods, each taking a `JoinContext ctx`:
    - `Map<String,Object> status(ctx, String connectionId)` returns `{"state":"free"}` or `{"state":"inUse","users":n}`
    - `JoinRequest create(ctx, String connectionId)`
    - `Map<String,Object> poll(ctx, String id)` returns `{"status":…, "shareKey"?}`
    - `void cancel(ctx, String id)`
    - `List<Map<String,Object>> incoming(ctx)`; each item is `{"id","requester","connectionIdentifier","sharingOptions":[{"identifier","name"}]}`
    - `void decide(ctx, String id, String decision, String sharingProfile)`, where `decision` is `"admit"` or `"deny"`
    - `int enforce(ctx, String connectionId)`

- [ ] **Step 1: Write the fake** `T/join/FakeJoinContext.java`

```java
package org.apache.guacamole.auth.jdbc.join;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JoinContext over a shared, mutable picture of who is connected where.
 */
public class FakeJoinContext implements JoinContext {

    /** Connection ID to primary sessions, shared by every context in a test. */
    private final Map<String, List<PrimarySession>> world;

    private final String username;
    private final Set<String> readable = new HashSet<String>();
    private final List<SharingOption> sharingOptions = new ArrayList<SharingOption>();

    public final List<String> ended = new ArrayList<String>();
    public final List<String> shared = new ArrayList<String>();

    public FakeJoinContext(Map<String, List<PrimarySession>> world, String username) {
        this.world = world;
        this.username = username;
    }

    public FakeJoinContext canRead(String connectionId) {
        readable.add(connectionId);
        return this;
    }

    public FakeJoinContext offers(String identifier, String name) {
        sharingOptions.add(new SharingOption(identifier, name));
        return this;
    }

    @Override
    public String getUsername() { return username; }

    @Override
    public boolean canReadConnection(String connectionIdentifier) {
        return readable.contains(connectionIdentifier);
    }

    @Override
    public List<PrimarySession> getPrimarySessions(String connectionIdentifier) {
        List<PrimarySession> sessions = world.get(connectionIdentifier);
        return sessions == null ? new ArrayList<PrimarySession>()
                : new ArrayList<PrimarySession>(sessions);
    }

    @Override
    public Set<String> getOwnPrimaryRecordUuids() {
        Set<String> own = new HashSet<String>();
        for (List<PrimarySession> sessions : world.values())
            for (PrimarySession session : sessions)
                if (session.getUsername().equals(username))
                    own.add(session.getRecordUuid());
        return own;
    }

    @Override
    public List<SharingOption> getSharingOptions(String recordUuid) {
        return sharingOptions;
    }

    @Override
    public String share(String recordUuid, String sharingProfileIdentifier) {
        shared.add(recordUuid + "/" + sharingProfileIdentifier);
        return "key-" + sharingProfileIdentifier;
    }

    @Override
    public void endSession(PrimarySession session) {
        ended.add(session.getRecordUuid());
        for (List<PrimarySession> sessions : world.values())
            sessions.remove(session);
    }

}
```

- [ ] **Step 2: Write the failing test** `T/join/JoinRequestServiceTest.java`

```java
package org.apache.guacamole.auth.jdbc.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.guacamole.GuacamoleClientException;
import org.apache.guacamole.GuacamoleResourceConflictException;
import org.apache.guacamole.GuacamoleResourceNotFoundException;
import org.apache.guacamole.GuacamoleSecurityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class JoinRequestServiceTest {

    private final AtomicLong now = new AtomicLong(1000000L);
    private final AtomicInteger ids = new AtomicInteger();
    private Map<String, List<PrimarySession>> world;
    private JoinRequestService service;
    private FakeJoinContext alice;   // connected to connection "7"
    private FakeJoinContext bob;     // newcomer

    @BeforeEach
    public void setUp() {
        world = new HashMap<String, List<PrimarySession>>();
        world.put("7", new ArrayList<PrimarySession>(Arrays.asList(
                new PrimarySession("rec-a", "alice"))));
        service = new JoinRequestService(new InMemoryJoinRequestStore(now::get),
                true, 120, now::get, () -> "req-" + ids.incrementAndGet());
        alice = new FakeJoinContext(world, "alice").canRead("7")
                .offers("11", "Allow control").offers("12", "View only");
        bob = new FakeJoinContext(world, "bob").canRead("7");
    }

    @Test
    public void disabledServiceIsNotFound() {
        JoinRequestService off = new JoinRequestService(new InMemoryJoinRequestStore(now::get),
                false, 120, now::get, () -> "x");
        assertThrows(GuacamoleResourceNotFoundException.class, () -> off.status(bob, "7"));
        assertThrows(GuacamoleResourceNotFoundException.class, () -> off.incoming(alice));
    }

    @Test
    public void statusRequiresRead() {
        FakeJoinContext eve = new FakeJoinContext(world, "eve");
        assertThrows(GuacamoleSecurityException.class, () -> service.status(eve, "7"));
        assertThrows(GuacamoleSecurityException.class, () -> service.create(eve, "7"));
        assertThrows(GuacamoleSecurityException.class, () -> service.enforce(eve, "7"));
    }

    @Test
    public void statusIsInUseWhenAnotherUserIsConnected() throws Exception {
        assertEquals("inUse", service.status(bob, "7").get("state"));
        assertEquals(1, service.status(bob, "7").get("users"));
    }

    @Test
    public void statusIsFreeForOwnSessionsAndEmptyConnections() throws Exception {
        assertEquals("free", service.status(alice, "7").get("state"));
        world.get("7").clear();
        assertEquals("free", service.status(bob, "7").get("state"));
    }

    @Test
    public void createOnFreeConnectionConflicts() {
        world.get("7").clear();
        assertThrows(GuacamoleResourceConflictException.class, () -> service.create(bob, "7"));
    }

    @Test
    public void admitDeliversShareKeyOnceToRequesterOnly() throws Exception {
        JoinRequest request = service.create(bob, "7");
        assertEquals("PENDING", service.poll(bob, request.getId()).get("status"));

        List<Map<String, Object>> incoming = service.incoming(alice);
        assertEquals(1, incoming.size());
        assertEquals("bob", incoming.get(0).get("requester"));
        assertEquals(2, ((List<?>) incoming.get(0).get("sharingOptions")).size());

        FakeJoinContext eve = new FakeJoinContext(world, "eve").canRead("7");
        assertThrows(GuacamoleResourceNotFoundException.class,
                () -> service.poll(eve, request.getId()));

        service.decide(alice, request.getId(), "admit", "12");
        assertEquals(Arrays.asList("rec-a/12"), alice.shared);

        Map<String, Object> admitted = service.poll(bob, request.getId());
        assertEquals("ADMITTED", admitted.get("status"));
        assertEquals("key-12", admitted.get("shareKey"));
        assertThrows(GuacamoleResourceNotFoundException.class,
                () -> service.poll(bob, request.getId()));
    }

    @Test
    public void denyIsReportedToRequester() throws Exception {
        JoinRequest request = service.create(bob, "7");
        service.decide(alice, request.getId(), "deny", null);
        assertEquals("DENIED", service.poll(bob, request.getId()).get("status"));
    }

    @Test
    public void firstAnswerWins() throws Exception {
        world.get("7").add(new PrimarySession("rec-c", "carol"));
        FakeJoinContext carol = new FakeJoinContext(world, "carol").canRead("7");
        JoinRequest request = service.create(bob, "7");
        service.decide(carol, request.getId(), "deny", null);
        assertThrows(GuacamoleResourceConflictException.class,
                () -> service.decide(alice, request.getId(), "admit", "11"));
        assertTrue(alice.shared.isEmpty());
    }

    @Test
    public void onlyOwnersMayDecide() throws Exception {
        JoinRequest request = service.create(bob, "7");
        FakeJoinContext eve = new FakeJoinContext(world, "eve").canRead("7");
        assertThrows(GuacamoleSecurityException.class,
                () -> service.decide(eve, request.getId(), "admit", "11"));
        assertTrue(service.incoming(eve).isEmpty());
        assertTrue(service.incoming(bob).isEmpty());
    }

    @Test
    public void admitWithoutProfileOrUnknownDecisionIsRejected() throws Exception {
        JoinRequest request = service.create(bob, "7");
        assertThrows(GuacamoleClientException.class,
                () -> service.decide(alice, request.getId(), "admit", null));
        assertThrows(GuacamoleClientException.class,
                () -> service.decide(alice, request.getId(), "maybe", null));
    }

    @Test
    public void unansweredRequestExpires() throws Exception {
        JoinRequest request = service.create(bob, "7");
        now.addAndGet(120001);
        assertThrows(GuacamoleResourceNotFoundException.class,
                () -> service.poll(bob, request.getId()));
        assertTrue(service.incoming(alice).isEmpty());
    }

    @Test
    public void pollReportsFreeWhenOwnerLeft() throws Exception {
        JoinRequest request = service.create(bob, "7");
        world.get("7").clear();
        assertEquals("FREE", service.poll(bob, request.getId()).get("status"));
    }

    @Test
    public void cancelRemovesRequest() throws Exception {
        JoinRequest request = service.create(bob, "7");
        service.cancel(bob, request.getId());
        assertTrue(service.incoming(alice).isEmpty());
    }

    @Test
    public void enforceEndsOtherUsersSessionsAndCancelsPending() throws Exception {
        world.get("7").add(new PrimarySession("rec-b", "bob"));
        FakeJoinContext dave = new FakeJoinContext(world, "dave").canRead("7");
        JoinRequest pending = service.create(dave, "7");

        assertEquals(1, service.enforce(bob, "7"));
        assertEquals(Arrays.asList("rec-a"), bob.ended);
        assertEquals("CANCELLED", service.poll(dave, pending.getId()).get("status"));
        assertFalse(world.get("7").isEmpty());
    }

}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd extensions/guacamole-auth-jdbc && mvn -q test -pl modules/guacamole-auth-jdbc-base -am -Dtest=JoinRequestServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, because `JoinContext`, `PrimarySession` and `JoinRequestService` are missing.

- [ ] **Step 4: Implement**

`B/join/PrimarySession.java`:
```java
package org.apache.guacamole.auth.jdbc.join;

/**
 * A primary (not shared) session on a connection, wherever it is served.
 */
public class PrimarySession {

    private final String recordUuid;
    private final String username;

    /**
     * Creates a PrimarySession.
     *
     * @param recordUuid
     *     The UUID of the session's active connection record.
     *
     * @param username
     *     The user who opened the session.
     */
    public PrimarySession(String recordUuid, String username) {
        this.recordUuid = recordUuid;
        this.username = username;
    }

    public String getRecordUuid() { return recordUuid; }
    public String getUsername() { return username; }

}
```

`B/join/SharingOption.java`:
```java
package org.apache.guacamole.auth.jdbc.join;

/**
 * A sharing profile the connected user may admit a newcomer with.
 */
public class SharingOption {

    private final String identifier;
    private final String name;

    /**
     * Creates a SharingOption.
     *
     * @param identifier
     *     The sharing profile identifier.
     *
     * @param name
     *     The human-readable sharing profile name.
     */
    public SharingOption(String identifier, String name) {
        this.identifier = identifier;
        this.name = name;
    }

    public String getIdentifier() { return identifier; }
    public String getName() { return name; }

}
```

`B/join/JoinContext.java`:
```java
package org.apache.guacamole.auth.jdbc.join;

import java.util.List;
import java.util.Set;
import org.apache.guacamole.GuacamoleException;

/**
 * Everything JoinRequestService needs to know about, or do on behalf of, the
 * user making a request.
 */
public interface JoinContext {

    /**
     * @return The username of the calling user.
     */
    String getUsername();

    /**
     * @param connectionIdentifier The connection to check.
     * @return true if the caller has READ permission on the connection.
     * @throws GuacamoleException If permissions cannot be read.
     */
    boolean canReadConnection(String connectionIdentifier) throws GuacamoleException;

    /**
     * @param connectionIdentifier The connection to inspect.
     * @return Every primary session on the connection, cluster-wide.
     * @throws GuacamoleException If active sessions cannot be listed.
     */
    List<PrimarySession> getPrimarySessions(String connectionIdentifier)
            throws GuacamoleException;

    /**
     * @return The record UUIDs of primary sessions the caller opened which
     *     are served by this web application instance.
     * @throws GuacamoleException If active sessions cannot be listed.
     */
    Set<String> getOwnPrimaryRecordUuids() throws GuacamoleException;

    /**
     * @param recordUuid One of the caller's own primary sessions.
     * @return The sharing profiles of that session's connection which the
     *     caller may share with.
     * @throws GuacamoleException If the profiles cannot be read.
     */
    List<SharingOption> getSharingOptions(String recordUuid) throws GuacamoleException;

    /**
     * @param recordUuid One of the caller's own primary sessions.
     * @param sharingProfileIdentifier The sharing profile to share with.
     * @return A new share key for the session.
     * @throws GuacamoleException If sharing is not permitted.
     */
    String share(String recordUuid, String sharingProfileIdentifier)
            throws GuacamoleException;

    /**
     * Ends the given session, wherever it is served.
     *
     * @param session The session to end.
     * @throws GuacamoleException If the session cannot be ended.
     */
    void endSession(PrimarySession session) throws GuacamoleException;

}
```

`B/join/JoinRequestService.java`:
```java
package org.apache.guacamole.auth.jdbc.join;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.guacamole.GuacamoleClientException;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleResourceConflictException;
import org.apache.guacamole.GuacamoleResourceNotFoundException;
import org.apache.guacamole.GuacamoleSecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rules for asking to join, admitting into, and taking over connections that
 * other users hold.
 */
public class JoinRequestService {

    private static final Logger logger = LoggerFactory.getLogger(JoinRequestService.class);

    private final JoinRequestStore store;
    private final boolean enabled;
    private final int timeoutSeconds;
    private final LongSupplier clock;
    private final Supplier<String> ids;

    /**
     * Creates a JoinRequestService.
     *
     * @param store Where requests are kept.
     * @param enabled Whether join requests are enabled.
     * @param timeoutSeconds How long an unanswered request remains valid.
     * @param clock Supplies the current time in milliseconds.
     * @param ids Generates unguessable request IDs.
     */
    public JoinRequestService(JoinRequestStore store, boolean enabled,
            int timeoutSeconds, LongSupplier clock, Supplier<String> ids) {
        this.store = store;
        this.enabled = enabled;
        this.timeoutSeconds = timeoutSeconds;
        this.clock = clock;
        this.ids = ids;
    }

    private void requireEnabled() throws GuacamoleException {
        if (!enabled)
            throw new GuacamoleResourceNotFoundException("Join requests are disabled.");
    }

    private void requireRead(JoinContext ctx, String connectionId) throws GuacamoleException {
        if (!ctx.canReadConnection(connectionId))
            throw new GuacamoleSecurityException("Permission denied.");
    }

    private List<PrimarySession> othersOn(JoinContext ctx, String connectionId)
            throws GuacamoleException {
        List<PrimarySession> others = new ArrayList<PrimarySession>();
        for (PrimarySession session : ctx.getPrimarySessions(connectionId))
            if (!session.getUsername().equals(ctx.getUsername()))
                others.add(session);
        return others;
    }

    private JoinRequest requireOwnRequest(JoinContext ctx, String id) throws GuacamoleException {
        JoinRequest request = store.get(id);
        if (request == null || !request.getRequester().equals(ctx.getUsername()))
            throw new GuacamoleResourceNotFoundException("No such join request.");
        return request;
    }

    public Map<String, Object> status(JoinContext ctx, String connectionId)
            throws GuacamoleException {
        requireEnabled();
        requireRead(ctx, connectionId);
        List<PrimarySession> others = othersOn(ctx, connectionId);
        Map<String, Object> result = new HashMap<String, Object>();
        if (others.isEmpty())
            result.put("state", "free");
        else {
            result.put("state", "inUse");
            result.put("users", others.size());
        }
        return result;
    }

    public JoinRequest create(JoinContext ctx, String connectionId) throws GuacamoleException {
        requireEnabled();
        requireRead(ctx, connectionId);
        List<PrimarySession> others = othersOn(ctx, connectionId);
        if (others.isEmpty())
            throw new GuacamoleResourceConflictException("The connection is not in use by another user.");
        List<String> uuids = new ArrayList<String>();
        for (PrimarySession session : others)
            uuids.add(session.getRecordUuid());
        JoinRequest request = new JoinRequest(ids.get(), connectionId, uuids,
                ctx.getUsername(), clock.getAsLong() + timeoutSeconds * 1000L,
                JoinRequest.Status.PENDING, null);
        store.put(request);
        return request;
    }

    public Map<String, Object> poll(JoinContext ctx, String id) throws GuacamoleException {
        requireEnabled();
        JoinRequest request = requireOwnRequest(ctx, id);
        Map<String, Object> result = new HashMap<String, Object>();
        if (request.getStatus() == JoinRequest.Status.ADMITTED) {
            store.remove(id);
            result.put("status", request.getStatus().name());
            result.put("shareKey", request.getShareKey());
            return result;
        }
        if (request.getStatus() == JoinRequest.Status.PENDING
                && othersOn(ctx, request.getConnectionIdentifier()).isEmpty()) {
            store.remove(id);
            result.put("status", "FREE");
            return result;
        }
        result.put("status", request.getStatus().name());
        return result;
    }

    public void cancel(JoinContext ctx, String id) throws GuacamoleException {
        requireEnabled();
        requireOwnRequest(ctx, id);
        store.remove(id);
    }

    public List<Map<String, Object>> incoming(JoinContext ctx) throws GuacamoleException {
        requireEnabled();
        Map<String, Map<String, Object>> found = new LinkedHashMap<String, Map<String, Object>>();
        for (String recordUuid : ctx.getOwnPrimaryRecordUuids()) {
            for (JoinRequest request : store.listFor(recordUuid)) {
                if (request.getStatus() != JoinRequest.Status.PENDING
                        || request.getRequester().equals(ctx.getUsername())
                        || found.containsKey(request.getId()))
                    continue;
                List<Map<String, String>> options = new ArrayList<Map<String, String>>();
                for (SharingOption option : ctx.getSharingOptions(recordUuid)) {
                    Map<String, String> entry = new HashMap<String, String>();
                    entry.put("identifier", option.getIdentifier());
                    entry.put("name", option.getName());
                    options.add(entry);
                }
                Map<String, Object> item = new HashMap<String, Object>();
                item.put("id", request.getId());
                item.put("requester", request.getRequester());
                item.put("connectionIdentifier", request.getConnectionIdentifier());
                item.put("sharingOptions", options);
                found.put(request.getId(), item);
            }
        }
        return new ArrayList<Map<String, Object>>(found.values());
    }

    public void decide(JoinContext ctx, String id, String decision, String sharingProfile)
            throws GuacamoleException {
        requireEnabled();
        JoinRequest request = store.get(id);
        if (request == null)
            throw new GuacamoleResourceNotFoundException("No such join request.");

        String ownedUuid = null;
        Set<String> own = ctx.getOwnPrimaryRecordUuids();
        for (String recordUuid : request.getRecordUuids())
            if (own.contains(recordUuid)) { ownedUuid = recordUuid; break; }
        if (ownedUuid == null)
            throw new GuacamoleSecurityException("Permission denied.");

        if (request.getStatus() != JoinRequest.Status.PENDING)
            throw new GuacamoleResourceConflictException("The join request was already answered.");

        if ("deny".equals(decision)) {
            if (!store.resolve(id, JoinRequest.Status.DENIED, null))
                throw new GuacamoleResourceConflictException("The join request was already answered.");
            return;
        }

        if (!"admit".equals(decision))
            throw new GuacamoleClientException("Unknown decision.");
        if (sharingProfile == null)
            throw new GuacamoleClientException("A sharing profile is required.");

        String shareKey = ctx.share(ownedUuid, sharingProfile);
        if (!store.resolve(id, JoinRequest.Status.ADMITTED, shareKey))
            throw new GuacamoleResourceConflictException("The join request was already answered.");
    }

    public int enforce(JoinContext ctx, String connectionId) throws GuacamoleException {
        requireEnabled();
        requireRead(ctx, connectionId);
        List<PrimarySession> others = othersOn(ctx, connectionId);
        for (PrimarySession session : others) {
            ctx.endSession(session);
            for (JoinRequest request : store.listFor(session.getRecordUuid()))
                if (request.getStatus() == JoinRequest.Status.PENDING)
                    store.resolve(request.getId(), JoinRequest.Status.CANCELLED, null);
        }
        if (!others.isEmpty())
            logger.info("User \"{}\" enforced connection \"{}\", ending {} session(s).",
                    ctx.getUsername(), connectionId, others.size());
        return others.size();
    }

}
```

Add a one-line Javadoc to each public method of `JoinRequestService`, in the style of `JoinRequestStore`, before running the build. `-Xlint:all` does not require it, but the codebase documents every public method.

- [ ] **Step 5: Run the test to verify it passes**

Run the Step 3 command. Expected: `Tests run: 14, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src
git commit -m "GUACAMOLE-453: Add join request rules."
```

---

### Task 3: Cluster store primitives for join requests (fork-only)

**Files:**
- Modify: `guacamole-ext/src/main/java/org/apache/guacamole/cluster/ClusterStore.java`, `NoOpClusterStore.java`, `ClusterKeys.java`, `redis/RedisClusterStore.java`
- Create: `guacamole-ext/src/main/resources/org/apache/guacamole/cluster/redis/put-join-request.lua`, `resolve-join-request.lua`
- Test: `guacamole-ext/src/test/java/org/apache/guacamole/cluster/redis/JoinRequestStorageTest.java`, and modify `guacamole-ext/src/test/java/org/apache/guacamole/cluster/NoOpClusterStoreTest.java`

**Interfaces:**
- Produces, on `ClusterStore` (all `throws GuacamoleException`):
  - `void putJoinRequest(String id, Map<String, String> fields, Collection<String> recordUuids, int ttlSeconds)`
  - `Map<String, String> getJoinRequest(String id)`: null if absent
  - `Collection<String> listJoinRequests(String recordUuid)`: IDs, which may include expired ones
  - `boolean resolveJoinRequest(String id, String status, String shareKey)`: sets `status` and `shareKey` only if the current `status` is `PENDING`. A `shareKey` of `null` stores none.
  - `void removeJoinRequest(String id)`
- `ClusterKeys.joinRequest(String id)` returns `"guac:join:" + escape(id)`, and `ClusterKeys.joinRequestIndex(String recordUuid)` returns `"guac:join:idx:" + escape(recordUuid)`.
- Redis failures throw `GuacamoleServerBusyException`, which gives HTTP 503.

- [ ] **Step 1: Write the failing Redis test** `.../cluster/redis/JoinRequestStorageTest.java`

```java
package org.apache.guacamole.cluster.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

public class JoinRequestStorageTest {

    private static final long STALE_WINDOW_MS = 30000;
    private RedisClusterStore store;

    @BeforeAll
    public static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available; skipping Redis-backed tests.");
    }

    @BeforeEach
    public void setUp() {
        store = new RedisClusterStore(RedisTestSupport.redisUri(), STALE_WINDOW_MS, "node-1");
        store.flushForTesting();
    }

    @AfterEach
    public void tearDown() {
        store.shutdown();
    }

    private Map<String, String> fields() {
        Map<String, String> fields = new HashMap<String, String>();
        fields.put("requester", "bob");
        fields.put("status", "PENDING");
        return fields;
    }

    @Test
    public void putThenGetAndList() throws Exception {
        store.putJoinRequest("r1", fields(), Arrays.asList("u1", "u2"), 60);
        assertEquals("bob", store.getJoinRequest("r1").get("requester"));
        assertTrue(store.listJoinRequests("u2").contains("r1"));
        assertTrue(store.listJoinRequests("u9").isEmpty());
        assertTrue(store.joinRequestTtlForTesting("r1") > 0);
    }

    @Test
    public void resolveOnlyOnce() throws Exception {
        store.putJoinRequest("r1", fields(), Arrays.asList("u1"), 60);
        assertTrue(store.resolveJoinRequest("r1", "ADMITTED", "key-1"));
        assertFalse(store.resolveJoinRequest("r1", "DENIED", null));
        assertEquals("ADMITTED", store.getJoinRequest("r1").get("status"));
        assertEquals("key-1", store.getJoinRequest("r1").get("shareKey"));
    }

    @Test
    public void missingRequest() throws Exception {
        assertNull(store.getJoinRequest("nope"));
        assertFalse(store.resolveJoinRequest("nope", "DENIED", null));
    }

    @Test
    public void removeDeletesRequest() throws Exception {
        store.putJoinRequest("r1", fields(), Arrays.asList("u1"), 60);
        store.removeJoinRequest("r1");
        assertNull(store.getJoinRequest("r1"));
    }

}
```

Add this test to `NoOpClusterStoreTest` (adding `assertThrows` and `assertNull` static imports, plus `java.util.Collections` and `org.apache.guacamole.GuacamoleUnsupportedException`, if missing):

```java
    @Test
    public void joinRequestsAreUnsupportedWithoutClustering() throws Exception {
        NoOpClusterStore store = new NoOpClusterStore();
        assertThrows(GuacamoleUnsupportedException.class, () -> store.putJoinRequest(
                "r1", java.util.Collections.<String, String>emptyMap(),
                java.util.Collections.<String>emptyList(), 60));
        assertNull(store.getJoinRequest("r1"));
        assertTrue(store.listJoinRequests("u1").isEmpty());
        assertFalse(store.resolveJoinRequest("r1", "DENIED", null));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl guacamole-ext test -Dtest='JoinRequestStorageTest,NoOpClusterStoreTest' -Dexec.skip=true`
Expected: compilation failure, because `putJoinRequest` is undefined.

- [ ] **Step 3: Implement**

Add to `ClusterKeys.java`, after `shareKey(...)`:
```java
    public static String joinRequest(String id) {
        return "guac:join:" + escape(id);
    }

    public static String joinRequestIndex(String recordUuid) {
        return "guac:join:idx:" + escape(recordUuid);
    }
```

Add to `ClusterStore.java`, after `publishShareRevocation`. Add `import java.util.Collection; import java.util.Map;` if missing.
```java
    /**
     * Stores a join request until it expires, indexed under each primary
     * session it targets.
     *
     * @param id
     *     The join request ID.
     *
     * @param fields
     *     The request's fields, including "status".
     *
     * @param recordUuids
     *     The record UUIDs of the primary sessions the request targets.
     *
     * @param ttlSeconds
     *     Seconds until the request and its index entries expire.
     *
     * @throws GuacamoleException
     *     If the request cannot be stored.
     */
    void putJoinRequest(String id, Map<String, String> fields,
            Collection<String> recordUuids, int ttlSeconds) throws GuacamoleException;

    /**
     * @param id The join request ID.
     * @return The request's fields, or null if it does not exist.
     * @throws GuacamoleException If the store cannot be read.
     */
    Map<String, String> getJoinRequest(String id) throws GuacamoleException;

    /**
     * @param recordUuid A primary session's record UUID.
     * @return The IDs of join requests indexed under that session. Some may
     *     have expired.
     * @throws GuacamoleException If the store cannot be read.
     */
    Collection<String> listJoinRequests(String recordUuid) throws GuacamoleException;

    /**
     * Atomically resolves a pending join request.
     *
     * @param id The join request ID.
     * @param status The new status.
     * @param shareKey The share key granted, or null.
     * @return true if the request was PENDING and is now resolved.
     * @throws GuacamoleException If the store cannot be written.
     */
    boolean resolveJoinRequest(String id, String status, String shareKey)
            throws GuacamoleException;

    /**
     * @param id The join request ID to remove.
     * @throws GuacamoleException If the store cannot be written.
     */
    void removeJoinRequest(String id) throws GuacamoleException;
```

Add to `NoOpClusterStore.java`. Add the imports `java.util.Collection`, `java.util.Collections`, `java.util.Map`, `org.apache.guacamole.GuacamoleException` and `org.apache.guacamole.GuacamoleUnsupportedException`.
```java
    // Without clustering, join requests use the in-memory store instead
    @Override
    public void putJoinRequest(String id, Map<String, String> fields,
            Collection<String> recordUuids, int ttlSeconds) throws GuacamoleException {
        throw new GuacamoleUnsupportedException("Join requests require clustering.");
    }

    @Override
    public Map<String, String> getJoinRequest(String id) {
        return null;
    }

    @Override
    public Collection<String> listJoinRequests(String recordUuid) {
        return Collections.<String>emptyList();
    }

    @Override
    public boolean resolveJoinRequest(String id, String status, String shareKey) {
        return false;
    }

    @Override
    public void removeJoinRequest(String id) {
    }
```

`put-join-request.lua`, with the ASF `--` header first:
```lua
-- Stores a join request and indexes it under each targeted session.
--
-- KEYS[1]      : the request hash
-- KEYS[2..n]   : one index set per targeted primary session
-- ARGV[1]      : TTL, in seconds
-- ARGV[2]      : the request ID
-- ARGV[3..]    : field, value pairs
--
-- One script, so that no key is ever left without its expiry.
for i = 3, #ARGV, 2 do
    redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
end
redis.call('EXPIRE', KEYS[1], ARGV[1])
for i = 2, #KEYS do
    redis.call('SADD', KEYS[i], ARGV[2])
    redis.call('EXPIRE', KEYS[i], ARGV[1])
end
return 1
```

`resolve-join-request.lua`, with the ASF `--` header first:
```lua
-- Resolves a join request only if it is still pending.
--
-- KEYS[1] : the request hash
-- ARGV[1] : the new status
-- ARGV[2] : the share key, or an empty string for none
if redis.call('HGET', KEYS[1], 'status') ~= 'PENDING' then
    return 0
end
redis.call('HSET', KEYS[1], 'status', ARGV[1])
if ARGV[2] ~= '' then
    redis.call('HSET', KEYS[1], 'shareKey', ARGV[2])
end
return 1
```

Changes to `RedisClusterStore.java`:

Add constants next to `RECORD_AUTH_FAILURE_SCRIPT` (line ~78), and scripts next to `recordAuthFailure` (line ~122):
```java
    private static final String PUT_JOIN_REQUEST_SCRIPT =
            "/org/apache/guacamole/cluster/redis/put-join-request.lua";

    private static final String RESOLVE_JOIN_REQUEST_SCRIPT =
            "/org/apache/guacamole/cluster/redis/resolve-join-request.lua";
```
```java
    private final LuaScript putJoinRequest = LuaScript.load(PUT_JOIN_REQUEST_SCRIPT);
    private final LuaScript resolveJoinRequest = LuaScript.load(RESOLVE_JOIN_REQUEST_SCRIPT);
```

Add the methods after `getShareKey`. Add imports `java.util.ArrayList`, `java.util.Collection`, `org.apache.guacamole.GuacamoleServerBusyException`, `org.apache.guacamole.GuacamoleException` if missing.
```java
    private GuacamoleServerBusyException joinRequestFailure(String operation,
            RedisException e) {
        ClusterMetrics.counter("guacamole_cluster_operation_failures_total",
                "operation", operation);
        available = false;
        unavailableSince = System.currentTimeMillis();
        logger.warn("Join request operation \"{}\" failed.", operation, e);
        return new GuacamoleServerBusyException("Join requests are temporarily unavailable.", e);
    }

    @Override
    public void putJoinRequest(String id, Map<String, String> fields,
            Collection<String> recordUuids, int ttlSeconds) throws GuacamoleException {

        List<String> keys = new ArrayList<String>();
        keys.add(ClusterKeys.joinRequest(id));
        for (String recordUuid : recordUuids)
            keys.add(ClusterKeys.joinRequestIndex(recordUuid));

        List<String> args = new ArrayList<String>();
        args.add(Integer.toString(ttlSeconds));
        args.add(id);
        for (Map.Entry<String, String> field : fields.entrySet()) {
            args.add(field.getKey());
            args.add(field.getValue());
        }

        try {
            putJoinRequest.eval(commands(), keys.toArray(new String[0]),
                    args.toArray(new String[0]));
            available = true;
        }
        catch (RedisException e) {
            throw joinRequestFailure("putJoinRequest", e);
        }

    }

    @Override
    public Map<String, String> getJoinRequest(String id) throws GuacamoleException {
        try {
            Map<String, String> fields = commands().hgetall(ClusterKeys.joinRequest(id));
            available = true;
            return fields.isEmpty() ? null : fields;
        }
        catch (RedisException e) {
            throw joinRequestFailure("getJoinRequest", e);
        }
    }

    @Override
    public Collection<String> listJoinRequests(String recordUuid) throws GuacamoleException {
        try {
            Collection<String> ids = commands().smembers(ClusterKeys.joinRequestIndex(recordUuid));
            available = true;
            return ids;
        }
        catch (RedisException e) {
            throw joinRequestFailure("listJoinRequests", e);
        }
    }

    @Override
    public boolean resolveJoinRequest(String id, String status, String shareKey)
            throws GuacamoleException {
        try {
            long resolved = resolveJoinRequest.eval(commands(),
                    new String[] { ClusterKeys.joinRequest(id) },
                    new String[] { status, shareKey == null ? "" : shareKey });
            available = true;
            return resolved == 1;
        }
        catch (RedisException e) {
            throw joinRequestFailure("resolveJoinRequest", e);
        }
    }

    @Override
    public void removeJoinRequest(String id) throws GuacamoleException {
        try {
            commands().del(ClusterKeys.joinRequest(id));
            available = true;
        }
        catch (RedisException e) {
            throw joinRequestFailure("removeJoinRequest", e);
        }
    }

    /**
     * For tests only: the remaining TTL of a join request, in seconds.
     *
     * @param id The join request ID.
     * @return The TTL, or a negative value if absent or without expiry.
     */
    public long joinRequestTtlForTesting(String id) {
        return commands().ttl(ClusterKeys.joinRequest(id));
    }
```

If `ClusterMetrics.counter(...)` returns a counter object that the existing code increments (check the `putShareKey` catch block at lines ~785-791), match that exact call shape.

The test fakes `ClusterHeartbeatTest.RecordingStore` and `GuacdSelectorTest.FakeStore` extend `NoOpClusterStore`, so they need no change. Confirm with `git grep -n "implements ClusterStore"`: the only hits should be `NoOpClusterStore` and `RedisClusterStore` (plus old plan docs).

- [ ] **Step 4: Run the tests to verify they pass**

Run the Step 2 command. Expected: all tests pass. `JoinRequestStorageTest` is skipped (not failed) if Docker is down, so start Docker and re-run until it shows 4 tests passing.

- [ ] **Step 5: Run the whole guacamole-ext suite** to catch compile breaks in other fakes

Run: `mvn -q -pl guacamole-ext test -Dexec.skip=true`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add guacamole-ext/src
git commit -m "GUACAMOLE-453: Store join requests in the cluster."
```

---

### Task 4: Redis-backed store and Guice wiring

**Files:**
- Create: `B/join/RedisJoinRequestStore.java`
- Modify: `B/join/JoinRequestService.java` (add the `@Inject` constructor), `B/JDBCAuthenticationProviderModule.java:215-237`
- Test: `T/join/RedisJoinRequestStoreTest.java`

**Interfaces:**
- Consumes: Task 1 `JoinRequestStore` and `JoinRequest`; Task 3 `ClusterStore` join methods.
- Produces:
  - `RedisJoinRequestStore(ClusterStore clusterStore, LongSupplier clock)`, plus an `@Inject RedisJoinRequestStore(ClusterStore)` that uses the system clock.
  - `@Inject JoinRequestService(JoinRequestStore store, JDBCEnvironment environment) throws GuacamoleException`
  - Guice: `JoinRequestStore` is bound to Redis or in-memory by `clusterEnabled`, and `JoinRequestService` is a singleton.

- [ ] **Step 1: Write the failing test** `T/join/RedisJoinRequestStoreTest.java`. It uses a map-backed fake `ClusterStore`, so no Docker is needed.

```java
package org.apache.guacamole.auth.jdbc.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.guacamole.cluster.NoOpClusterStore;
import org.junit.jupiter.api.Test;

public class RedisJoinRequestStoreTest {

    /** ClusterStore keeping join requests in maps; ignores TTLs. */
    private static class MapClusterStore extends NoOpClusterStore {
        final Map<String, Map<String, String>> requests = new HashMap<String, Map<String, String>>();
        final Map<String, Set<String>> index = new HashMap<String, Set<String>>();
        int lastTtl;

        @Override
        public void putJoinRequest(String id, Map<String, String> fields,
                Collection<String> recordUuids, int ttlSeconds) {
            requests.put(id, new HashMap<String, String>(fields));
            for (String uuid : recordUuids) {
                if (!index.containsKey(uuid))
                    index.put(uuid, new HashSet<String>());
                index.get(uuid).add(id);
            }
            lastTtl = ttlSeconds;
        }

        @Override
        public Map<String, String> getJoinRequest(String id) {
            return requests.get(id);
        }

        @Override
        public Collection<String> listJoinRequests(String recordUuid) {
            Set<String> ids = index.get(recordUuid);
            return ids == null ? new ArrayList<String>() : ids;
        }

        @Override
        public boolean resolveJoinRequest(String id, String status, String shareKey) {
            Map<String, String> fields = requests.get(id);
            if (fields == null || !"PENDING".equals(fields.get("status")))
                return false;
            fields.put("status", status);
            if (shareKey != null)
                fields.put("shareKey", shareKey);
            return true;
        }

        @Override
        public void removeJoinRequest(String id) {
            requests.remove(id);
        }
    }

    private final AtomicLong now = new AtomicLong(1000000L);

    @Test
    public void roundTripsEveryField() throws Exception {
        MapClusterStore cluster = new MapClusterStore();
        RedisJoinRequestStore store = new RedisJoinRequestStore(cluster, now::get);
        store.put(new JoinRequest("r1", "7", Arrays.asList("u1", "u2"), "bob",
                now.get() + 90500, JoinRequest.Status.PENDING, null));

        assertEquals(91, cluster.lastTtl);
        JoinRequest found = store.get("r1");
        assertEquals("7", found.getConnectionIdentifier());
        assertEquals(Arrays.asList("u1", "u2"), found.getRecordUuids());
        assertEquals("bob", found.getRequester());
        assertEquals(JoinRequest.Status.PENDING, found.getStatus());
        assertNull(found.getShareKey());
        assertEquals(1, store.listFor("u2").size());
    }

    @Test
    public void resolveAndRemove() throws Exception {
        MapClusterStore cluster = new MapClusterStore();
        RedisJoinRequestStore store = new RedisJoinRequestStore(cluster, now::get);
        store.put(new JoinRequest("r1", "7", Arrays.asList("u1"), "bob",
                now.get() + 60000, JoinRequest.Status.PENDING, null));
        assertTrue(store.resolve("r1", JoinRequest.Status.ADMITTED, "k"));
        assertFalse(store.resolve("r1", JoinRequest.Status.DENIED, null));
        assertEquals("k", store.get("r1").getShareKey());
        store.remove("r1");
        assertNull(store.get("r1"));
        assertTrue(store.listFor("u1").isEmpty());
    }

    @Test
    public void expiredEntryIsIgnoredEvenIfStillStored() throws Exception {
        MapClusterStore cluster = new MapClusterStore();
        RedisJoinRequestStore store = new RedisJoinRequestStore(cluster, now::get);
        store.put(new JoinRequest("r1", "7", Arrays.asList("u1"), "bob",
                now.get() + 1000, JoinRequest.Status.PENDING, null));
        now.addAndGet(1001);
        assertNull(store.get("r1"));
    }

}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd extensions/guacamole-auth-jdbc && mvn -q test -pl modules/guacamole-auth-jdbc-base -am -Dtest=RedisJoinRequestStoreTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, because `RedisJoinRequestStore` is missing.

- [ ] **Step 3: Implement** `B/join/RedisJoinRequestStore.java`

```java
package org.apache.guacamole.auth.jdbc.join;

import com.google.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.cluster.ClusterStore;

/**
 * JoinRequestStore shared by every web application instance via the cluster
 * store.
 */
public class RedisJoinRequestStore implements JoinRequestStore {

    private final ClusterStore clusterStore;
    private final LongSupplier clock;

    /**
     * Creates a store using the system clock.
     *
     * @param clusterStore The cluster store holding requests.
     */
    @Inject
    public RedisJoinRequestStore(ClusterStore clusterStore) {
        this(clusterStore, System::currentTimeMillis);
    }

    /**
     * Creates a store using the given clock.
     *
     * @param clusterStore The cluster store holding requests.
     * @param clock Supplies the current time, in milliseconds.
     */
    public RedisJoinRequestStore(ClusterStore clusterStore, LongSupplier clock) {
        this.clusterStore = clusterStore;
        this.clock = clock;
    }

    @Override
    public void put(JoinRequest request) throws GuacamoleException {
        Map<String, String> fields = new HashMap<String, String>();
        fields.put("connectionIdentifier", request.getConnectionIdentifier());
        fields.put("recordUuids", String.join(",", request.getRecordUuids()));
        fields.put("requester", request.getRequester());
        fields.put("expires", Long.toString(request.getExpires()));
        fields.put("status", request.getStatus().name());
        if (request.getShareKey() != null)
            fields.put("shareKey", request.getShareKey());

        long remainingMs = request.getExpires() - clock.getAsLong();
        int ttlSeconds = (int) Math.max(1, (remainingMs + 999) / 1000);
        clusterStore.putJoinRequest(request.getId(), fields, request.getRecordUuids(), ttlSeconds);
    }

    @Override
    public JoinRequest get(String id) throws GuacamoleException {
        Map<String, String> fields = clusterStore.getJoinRequest(id);
        if (fields == null)
            return null;
        JoinRequest request = new JoinRequest(id,
                fields.get("connectionIdentifier"),
                Arrays.asList(fields.get("recordUuids").split(",")),
                fields.get("requester"),
                Long.parseLong(fields.get("expires")),
                JoinRequest.Status.valueOf(fields.get("status")),
                fields.get("shareKey"));
        return request.isExpired(clock.getAsLong()) ? null : request;
    }

    @Override
    public List<JoinRequest> listFor(String recordUuid) throws GuacamoleException {
        List<JoinRequest> matches = new ArrayList<JoinRequest>();
        for (String id : clusterStore.listJoinRequests(recordUuid)) {
            JoinRequest request = get(id);
            if (request != null)
                matches.add(request);
        }
        return matches;
    }

    @Override
    public boolean resolve(String id, JoinRequest.Status status, String shareKey)
            throws GuacamoleException {
        JoinRequest current = get(id);
        if (current == null)
            return false;
        return clusterStore.resolveJoinRequest(id, status.name(), shareKey);
    }

    @Override
    public void remove(String id) throws GuacamoleException {
        clusterStore.removeJoinRequest(id);
    }

}
```

Add the production constructor to `JoinRequestService`. Imports: `com.google.inject.Inject`, `java.security.SecureRandom`, `java.math.BigInteger`, `org.apache.guacamole.auth.jdbc.JDBCEnvironment`.
```java
    /**
     * Creates a JoinRequestService configured from guacamole.properties.
     *
     * @param store Where requests are kept.
     * @param environment The JDBC environment.
     * @throws GuacamoleException If the properties cannot be parsed.
     */
    @Inject
    public JoinRequestService(JoinRequestStore store, JDBCEnvironment environment)
            throws GuacamoleException {
        this(store,
                environment.getProperty(JoinRequestProperties.JOIN_REQUEST_ENABLED, false),
                environment.getProperty(JoinRequestProperties.JOIN_REQUEST_TIMEOUT,
                        JoinRequestProperties.DEFAULT_TIMEOUT),
                System::currentTimeMillis,
                newIdGenerator());
    }

    private static Supplier<String> newIdGenerator() {
        final SecureRandom random = new SecureRandom();
        return () -> new BigInteger(130, random).toString(32);
    }
```

Bind in `JDBCAuthenticationProviderModule.java` directly after the `SharedConnectionMap` if/else (line ~232). Add the imports for `InMemoryJoinRequestStore`, `JoinRequestService`, `JoinRequestStore` and `RedisJoinRequestStore` from `org.apache.guacamole.auth.jdbc.join`.
```java
        if (clusterEnabled)
            bind(JoinRequestStore.class).to(RedisJoinRequestStore.class).in(Scopes.SINGLETON);
        else
            bind(JoinRequestStore.class).to(InMemoryJoinRequestStore.class).in(Scopes.SINGLETON);
        bind(JoinRequestService.class).in(Scopes.SINGLETON);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd extensions/guacamole-auth-jdbc && mvn -q test -pl modules/guacamole-auth-jdbc-base -am -Dtest='RedisJoinRequestStoreTest,JoinRequestServiceTest,InMemoryJoinRequestStoreTest,ClusterModuleTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass. `ClusterModuleTest` proves the module still configures.

- [ ] **Step 5: Commit**

```bash
git add extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src
git commit -m "GUACAMOLE-453: Share join requests across the cluster."
```

---

### Task 5: JDBC context, REST resource and user-context wiring

**Files:**
- Create: `B/join/JdbcJoinContext.java`, `B/join/JoinContextFactory.java`, `B/join/JoinRequestResource.java`, `B/join/JoinRequestCreation.java`, `B/join/JoinDecision.java`, `B/user/JDBCUserContextResource.java`
- Modify: `B/user/ModeledUserContext.java:221-224` (`getResource()`), and add two `@Inject` fields
- Test: `T/join/JoinRequestResourceTest.java`

**Interfaces:**
- Consumes:
  - `JoinRequestService` (Tasks 2 and 4)
  - `GuacamoleTunnelService.getActiveConnections(ModeledAuthenticatedUser)` and `getActiveConnections(Connection)`
  - `ClusterStore.listTunnels()` and `requestKill(String)`
  - `ConnectionService.retrieveObject(user, id)`, which returns `ModeledConnection` or null
  - `SharingProfileService.retrieveObjects(user, Collection<String>)`, which returns `Collection<ModeledSharingProfile>`
  - `ConnectionSharingService.shareConnection(user, record, profileId)`, which returns a `SharedConnectionDefinition` with `getShareKey()`
  - `ActiveConnectionRecord.getUUID()`, `getUsername()`, `isPrimaryConnection()`, `getConnection()` and `getTunnel()`
  - `ModeledConnection.getSharingProfileIdentifiers()`
  - `TunnelRegistration.getConnectionIdentifier()`, `getSharingProfileIdentifier()`, `getUsername()` and `getRecordUuid()`
- Produces:
  - `JoinContextFactory.create(ModeledAuthenticatedUser)`, which returns a `JoinContext`
  - `JoinRequestResource(JoinRequestService, JoinContext)`
  - `JDBCUserContextResource(PasswordResetResource, JoinRequestResource)`

- [ ] **Step 1: Write the failing test** `T/join/JoinRequestResourceTest.java`. It proves the resource delegates and that the JSON shapes are correct.

```java
package org.apache.guacamole.auth.jdbc.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.guacamole.GuacamoleResourceNotFoundException;
import org.junit.jupiter.api.Test;

public class JoinRequestResourceTest {

    private final AtomicLong now = new AtomicLong(1000000L);

    @Test
    public void createReturnsIdAndExpiry() throws Exception {
        Map<String, List<PrimarySession>> world = new HashMap<String, List<PrimarySession>>();
        world.put("7", new ArrayList<PrimarySession>(Arrays.asList(new PrimarySession("rec-a", "alice"))));
        JoinRequestService service = new JoinRequestService(
                new InMemoryJoinRequestStore(now::get), true, 120, now::get, () -> "req-1");
        JoinRequestResource resource = new JoinRequestResource(service,
                new FakeJoinContext(world, "bob").canRead("7"));

        JoinRequestCreation body = new JoinRequestCreation();
        body.setConnectionId("7");
        Map<String, Object> created = resource.createRequest(body);

        assertEquals("req-1", created.get("id"));
        assertEquals(now.get() + 120000L, created.get("expires"));
        assertEquals("PENDING", resource.getRequest("req-1").get("status"));
    }

    @Test
    public void disabledFeatureIsNotFound() {
        JoinRequestService off = new JoinRequestService(
                new InMemoryJoinRequestStore(now::get), false, 120, now::get, () -> "x");
        JoinRequestResource resource = new JoinRequestResource(off,
                new FakeJoinContext(new HashMap<String, List<PrimarySession>>(), "bob"));
        assertThrows(GuacamoleResourceNotFoundException.class, () -> resource.getStatus("7"));
    }

}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd extensions/guacamole-auth-jdbc && mvn -q test -pl modules/guacamole-auth-jdbc-base -am -Dtest=JoinRequestResourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Implement the request bodies**

`B/join/JoinRequestCreation.java`:
```java
package org.apache.guacamole.auth.jdbc.join;

/**
 * JSON body of a request to join a connection.
 */
public class JoinRequestCreation {

    private String connectionId;

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

}
```

`B/join/JoinDecision.java`:
```java
package org.apache.guacamole.auth.jdbc.join;

/**
 * JSON body of an answer to a join request.
 */
public class JoinDecision {

    private String decision;
    private String sharingProfile;

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getSharingProfile() { return sharingProfile; }
    public void setSharingProfile(String sharingProfile) { this.sharingProfile = sharingProfile; }

}
```

- [ ] **Step 4: Implement the resource** `B/join/JoinRequestResource.java`

```java
package org.apache.guacamole.auth.jdbc.join;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import org.apache.guacamole.GuacamoleClientException;
import org.apache.guacamole.GuacamoleException;

/**
 * REST endpoints for join requests, served under
 * api/session/ext/DATA_SOURCE/join/.
 */
@Produces(MediaType.APPLICATION_JSON)
public class JoinRequestResource {

    private final JoinRequestService service;
    private final JoinContext context;

    /**
     * @param service The join request rules.
     * @param context The calling user's context.
     */
    public JoinRequestResource(JoinRequestService service, JoinContext context) {
        this.service = service;
        this.context = context;
    }

    @GET
    @Path("status/{connectionId}")
    public Map<String, Object> getStatus(@PathParam("connectionId") String connectionId)
            throws GuacamoleException {
        return service.status(context, connectionId);
    }

    @POST
    @Path("requests")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> createRequest(JoinRequestCreation body)
            throws GuacamoleException {
        if (body == null || body.getConnectionId() == null)
            throw new GuacamoleClientException("A connection is required.");
        JoinRequest request = service.create(context, body.getConnectionId());
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("id", request.getId());
        result.put("expires", request.getExpires());
        return result;
    }

    @GET
    @Path("requests/{id}")
    public Map<String, Object> getRequest(@PathParam("id") String id)
            throws GuacamoleException {
        return service.poll(context, id);
    }

    @DELETE
    @Path("requests/{id}")
    public void cancelRequest(@PathParam("id") String id) throws GuacamoleException {
        service.cancel(context, id);
    }

    @GET
    @Path("incoming")
    public List<Map<String, Object>> getIncoming() throws GuacamoleException {
        return service.incoming(context);
    }

    @POST
    @Path("incoming/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public void decide(@PathParam("id") String id, JoinDecision body)
            throws GuacamoleException {
        if (body == null)
            throw new GuacamoleClientException("A decision is required.");
        service.decide(context, id, body.getDecision(), body.getSharingProfile());
    }

    @POST
    @Path("enforce/{connectionId}")
    public void enforce(@PathParam("connectionId") String connectionId)
            throws GuacamoleException {
        service.enforce(context, connectionId);
    }

}
```

- [ ] **Step 5: Implement the JDBC context** `B/join/JdbcJoinContext.java` and `B/join/JoinContextFactory.java`

```java
package org.apache.guacamole.auth.jdbc.join;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleSecurityException;
import org.apache.guacamole.auth.jdbc.connection.ConnectionService;
import org.apache.guacamole.auth.jdbc.connection.ModeledConnection;
import org.apache.guacamole.auth.jdbc.sharing.ConnectionSharingService;
import org.apache.guacamole.auth.jdbc.sharingprofile.ModeledSharingProfile;
import org.apache.guacamole.auth.jdbc.sharingprofile.SharingProfileService;
import org.apache.guacamole.auth.jdbc.tunnel.ActiveConnectionRecord;
import org.apache.guacamole.auth.jdbc.tunnel.GuacamoleTunnelService;
import org.apache.guacamole.auth.jdbc.user.ModeledAuthenticatedUser;
import org.apache.guacamole.cluster.ClusterStore;
import org.apache.guacamole.cluster.TunnelRegistration;
import org.apache.guacamole.net.GuacamoleTunnel;

/**
 * JoinContext backed by the JDBC services and the cluster store.
 */
public class JdbcJoinContext implements JoinContext {

    private final ModeledAuthenticatedUser user;
    private final GuacamoleTunnelService tunnelService;
    private final ConnectionService connectionService;
    private final SharingProfileService sharingProfileService;
    private final ConnectionSharingService connectionSharingService;
    private final ClusterStore clusterStore;

    /** Local records seen by getPrimarySessions(), by record UUID. */
    private final Map<String, ActiveConnectionRecord> localRecords =
            new HashMap<String, ActiveConnectionRecord>();

    JdbcJoinContext(ModeledAuthenticatedUser user, GuacamoleTunnelService tunnelService,
            ConnectionService connectionService, SharingProfileService sharingProfileService,
            ConnectionSharingService connectionSharingService, ClusterStore clusterStore) {
        this.user = user;
        this.tunnelService = tunnelService;
        this.connectionService = connectionService;
        this.sharingProfileService = sharingProfileService;
        this.connectionSharingService = connectionSharingService;
        this.clusterStore = clusterStore;
    }

    @Override
    public String getUsername() {
        return user.getIdentifier();
    }

    @Override
    public boolean canReadConnection(String connectionIdentifier) throws GuacamoleException {
        return connectionService.retrieveObject(user, connectionIdentifier) != null;
    }

    @Override
    public List<PrimarySession> getPrimarySessions(String connectionIdentifier)
            throws GuacamoleException {

        List<PrimarySession> sessions = new ArrayList<PrimarySession>();
        Set<String> seen = new HashSet<String>();

        ModeledConnection connection = connectionService.retrieveObject(user, connectionIdentifier);
        if (connection == null)
            return sessions;

        for (ActiveConnectionRecord record : tunnelService.getActiveConnections(connection)) {
            if (!record.isPrimaryConnection())
                continue;
            String uuid = record.getUUID().toString();
            localRecords.put(uuid, record);
            seen.add(uuid);
            sessions.add(new PrimarySession(uuid, record.getUsername()));
        }

        for (TunnelRegistration registration : clusterStore.listTunnels()) {
            String uuid = registration.getRecordUuid();
            if (uuid == null || seen.contains(uuid)
                    || registration.getSharingProfileIdentifier() != null
                    || !connectionIdentifier.equals(registration.getConnectionIdentifier()))
                continue;
            seen.add(uuid);
            sessions.add(new PrimarySession(uuid, registration.getUsername()));
        }

        return sessions;
    }

    private ActiveConnectionRecord ownRecord(String recordUuid) throws GuacamoleException {
        for (ActiveConnectionRecord record : tunnelService.getActiveConnections(user)) {
            if (record.isPrimaryConnection()
                    && getUsername().equals(record.getUsername())
                    && record.getUUID().toString().equals(recordUuid))
                return record;
        }
        throw new GuacamoleSecurityException("Permission denied.");
    }

    @Override
    public Set<String> getOwnPrimaryRecordUuids() throws GuacamoleException {
        Set<String> uuids = new HashSet<String>();
        for (ActiveConnectionRecord record : tunnelService.getActiveConnections(user))
            if (record.isPrimaryConnection() && getUsername().equals(record.getUsername()))
                uuids.add(record.getUUID().toString());
        return uuids;
    }

    @Override
    public List<SharingOption> getSharingOptions(String recordUuid) throws GuacamoleException {
        ModeledConnection connection = ownRecord(recordUuid).getConnection();
        List<SharingOption> options = new ArrayList<SharingOption>();
        for (ModeledSharingProfile profile : sharingProfileService.retrieveObjects(
                user, connection.getSharingProfileIdentifiers()))
            options.add(new SharingOption(profile.getIdentifier(), profile.getName()));
        return options;
    }

    @Override
    public String share(String recordUuid, String sharingProfileIdentifier)
            throws GuacamoleException {
        return connectionSharingService.shareConnection(user, ownRecord(recordUuid),
                sharingProfileIdentifier).getShareKey();
    }

    @Override
    public void endSession(PrimarySession session) throws GuacamoleException {
        ActiveConnectionRecord local = localRecords.get(session.getRecordUuid());
        if (local != null) {
            GuacamoleTunnel tunnel = local.getTunnel();
            if (tunnel != null && tunnel.isOpen())
                tunnel.close();
            return;
        }
        clusterStore.requestKill(session.getRecordUuid());
    }

}
```

```java
package org.apache.guacamole.auth.jdbc.join;

import com.google.inject.Inject;
import org.apache.guacamole.auth.jdbc.connection.ConnectionService;
import org.apache.guacamole.auth.jdbc.sharing.ConnectionSharingService;
import org.apache.guacamole.auth.jdbc.sharingprofile.SharingProfileService;
import org.apache.guacamole.auth.jdbc.tunnel.GuacamoleTunnelService;
import org.apache.guacamole.auth.jdbc.user.ModeledAuthenticatedUser;
import org.apache.guacamole.cluster.ClusterStore;

/**
 * Creates the JoinContext of an authenticated user.
 */
public class JoinContextFactory {

    @Inject private GuacamoleTunnelService tunnelService;
    @Inject private ConnectionService connectionService;
    @Inject private SharingProfileService sharingProfileService;
    @Inject private ConnectionSharingService connectionSharingService;
    @Inject private ClusterStore clusterStore;

    /**
     * @param user The authenticated user.
     * @return A JoinContext acting as that user.
     */
    public JoinContext create(ModeledAuthenticatedUser user) {
        return new JdbcJoinContext(user, tunnelService, connectionService,
                sharingProfileService, connectionSharingService, clusterStore);
    }

}
```

Check the packages used in the imports above against the source before compiling: `git grep -n "^package" -- "B/**/ModeledSharingProfile.java" "B/**/ConnectionService.java" "B/**/ActiveConnectionRecord.java"`. The fact sheet places `ActiveConnectionRecord` and `GuacamoleTunnelService` in `auth/jdbc/tunnel/`. Fix the imports to match what the grep prints.

If `getSharingProfileIdentifiers()` is declared `throws GuacamoleException`, the enclosing method already throws, so nothing changes.

- [ ] **Step 6: Mount the resource.** Create `B/user/JDBCUserContextResource.java`:

```java
package org.apache.guacamole.auth.jdbc.user;

import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.auth.jdbc.join.JoinRequestResource;
import org.apache.guacamole.auth.jdbc.reset.PasswordResetResource;

/**
 * The single extension resource of a JDBC user context, served under
 * api/session/ext/DATA_SOURCE/.
 */
@Produces(MediaType.APPLICATION_JSON)
public class JDBCUserContextResource {

    private final PasswordResetResource passwordReset;
    private final JoinRequestResource joinRequests;

    /**
     * @param passwordReset The password reset endpoints.
     * @param joinRequests The join request endpoints.
     */
    public JDBCUserContextResource(PasswordResetResource passwordReset,
            JoinRequestResource joinRequests) {
        this.passwordReset = passwordReset;
        this.joinRequests = joinRequests;
    }

    @POST
    @Path("password-reset")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public void sendResetLink(@FormParam("username") String username)
            throws GuacamoleException {
        passwordReset.sendResetLink(username);
    }

    @Path("join")
    public JoinRequestResource getJoinRequests() {
        return joinRequests;
    }

}
```

Modify `ModeledUserContext.java`. Add these fields next to `passwordResetService` (lines 78-79):
```java
    @Inject
    private JoinRequestService joinRequestService;

    @Inject
    private JoinContextFactory joinContextFactory;
```
Replace the body of `getResource()` (lines 221-224):
```java
    @Override
    public Object getResource() throws GuacamoleException {
        return new JDBCUserContextResource(
                new PasswordResetResource(getCurrentUser(), passwordResetService),
                new JoinRequestResource(joinRequestService,
                        joinContextFactory.create(getCurrentUser())));
    }
```
Add the imports for `JoinContextFactory`, `JoinRequestResource` and `JoinRequestService`.

- [ ] **Step 7: Run the jdbc-base suite**

Run: `cd extensions/guacamole-auth-jdbc && mvn -q test -pl modules/guacamole-auth-jdbc-base -am`
Expected: BUILD SUCCESS. All tests pass, including the existing password-reset tests, which prove the reset endpoint still resolves.

- [ ] **Step 8: Commit**

```bash
git add extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src
git commit -m "GUACAMOLE-453: Expose join requests over REST."
```

---

### Task 6: Web UI for the newcomer and the connected user

**Files:**
- Create: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/resources/js/joinRequests.js`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/resources/translations/en.json` (add a `JOIN` group after `PASSWORD_RESET`), and `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-{postgresql,mysql,sqlserver}/src/main/resource-templates/guac-manifest.json` (add `"js/joinRequests.js"` to `"js"`)

**Interfaces:**
- Consumes: the REST endpoints from Task 5; the AngularJS services `authenticationService`, `guacClientManager`, `guacNotification`, `ManagedClientState` and `ClientIdentifier` (factory in module `navigation`, with `Types.CONNECTION === 'c'`, `toString` and `fromString`); and the route `/client/:id`.
- Produces: the module `guacJdbcJoinRequests` and the factory `joinRequestService`.

- [ ] **Step 1: Add translations.** In `.../resources/translations/en.json`, add after the closing `}` of `"PASSWORD_RESET"` (followed by a comma):

```json
    "JOIN" : {
        "ACTION_CANCEL"              : "Cancel",
        "ACTION_DENY"                : "Deny",
        "ACTION_ENFORCE"             : "Enforce",
        "ACTION_OK"                  : "OK",
        "ACTION_RETRY"               : "Retry",
        "DIALOG_HEADER_REQUEST"      : "Join Request",
        "DIALOG_HEADER_WAITING"      : "Connection In Use",
        "TEXT_DENIED"                : "Your request to join was declined. You can connect anyway, which ends the current session.",
        "TEXT_EXPIRED"               : "No response from the current user.",
        "TEXT_REQUEST"               : "{USERNAME} wants to join this session.",
        "TEXT_REQUEST_NO_SHARING"    : "{USERNAME} wants to join this session. This connection cannot be shared.",
        "TEXT_WAITING"               : "An active session is running, please wait to be admitted."
    }
```

Run: `python3 -m json.tool extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/resources/translations/en.json > /dev/null && echo ok`
Expected: `ok`

- [ ] **Step 2: Register the script in all three manifests.** In each `guac-manifest.json`, change the `"js"` array to:

```json
    "js" : [
        "js/passwordReset.js",
        "js/joinRequests.js"
    ],
```

Run: `for f in extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-{postgresql,mysql,sqlserver}/src/main/resource-templates/guac-manifest.json; do grep -c joinRequests.js $f; done`
Expected: `1` three times.

- [ ] **Step 3: Write** `.../resources/js/joinRequests.js`, with the ASF header first:

```js
/**
 * Join requests: asks the user connected to a connection to admit a newcomer,
 * or lets the newcomer end the current session.
 */
angular.module('guacJdbcJoinRequests', ['auth', 'client', 'navigation', 'notification', 'rest']);
angular.module('index').requires.push('guacJdbcJoinRequests');

angular.module('guacJdbcJoinRequests').factory('joinRequestService', ['$injector',
        function joinRequestService($injector) {

    var authenticationService = $injector.get('authenticationService');

    var base = function base(dataSource) {
        return 'api/session/ext/' + encodeURIComponent(dataSource) + '/join/';
    };

    return {
        getStatus : function getStatus(dataSource, connectionId) {
            return authenticationService.request({ method : 'GET',
                url : base(dataSource) + 'status/' + encodeURIComponent(connectionId) });
        },
        createRequest : function createRequest(dataSource, connectionId) {
            return authenticationService.request({ method : 'POST',
                url : base(dataSource) + 'requests',
                data : { connectionId : connectionId } });
        },
        getRequest : function getRequest(dataSource, id) {
            return authenticationService.request({ method : 'GET',
                url : base(dataSource) + 'requests/' + encodeURIComponent(id) });
        },
        cancelRequest : function cancelRequest(dataSource, id) {
            return authenticationService.request({ method : 'DELETE',
                url : base(dataSource) + 'requests/' + encodeURIComponent(id) });
        },
        getIncoming : function getIncoming(dataSource) {
            return authenticationService.request({ method : 'GET',
                url : base(dataSource) + 'incoming' });
        },
        decide : function decide(dataSource, id, decision, sharingProfile) {
            return authenticationService.request({ method : 'POST',
                url : base(dataSource) + 'incoming/' + encodeURIComponent(id),
                data : { decision : decision, sharingProfile : sharingProfile } });
        },
        enforce : function enforce(dataSource, connectionId) {
            return authenticationService.request({ method : 'POST',
                url : base(dataSource) + 'enforce/' + encodeURIComponent(connectionId) });
        }
    };

}]);

angular.module('guacJdbcJoinRequests').run(['$injector', function joinRequests($injector) {

    var $interval          = $injector.get('$interval');
    var $location          = $injector.get('$location');
    var $rootScope         = $injector.get('$rootScope');
    var ClientIdentifier   = $injector.get('ClientIdentifier');
    var guacClientManager  = $injector.get('guacClientManager');
    var guacNotification   = $injector.get('guacNotification');
    var joinRequestService = $injector.get('joinRequestService');
    var ManagedClientState = $injector.get('ManagedClientState');

    var JDBC_DATA_SOURCES = ['postgresql', 'mysql', 'sqlserver'];
    var POLL_MS = 3000;

    // Client IDs whose next navigation skips the in-use check
    var cleared = {};

    var isNotFound = function isNotFound(error) {
        return !!error && (error.type === 'NOT_FOUND' || error.statusCode === 404);
    };

    // guacNotification ignores a new status while another is shown
    var show = function show(status) {
        guacNotification.showStatus(false);
        guacNotification.showStatus(status);
    };

    var connect = function connect(id) {
        guacNotification.showStatus(false);
        cleared[id] = true;
        $location.url('/client/' + id);
    };

    var enforce = function enforce(id, identifier) {
        joinRequestService.enforce(identifier.dataSource, identifier.id)
            .then(function enforced() { connect(id); }, guacNotification.SHOW_REQUEST_ERROR);
    };

    var outcome = function outcome(id, identifier, textKey, canRetry) {
        var actions = [];
        if (canRetry)
            actions.push({ name : 'JOIN.ACTION_RETRY',
                callback : function retry() { waitForAdmission(id, identifier); } });
        actions.push({ name : 'JOIN.ACTION_ENFORCE', className : 'danger',
            callback : function forced() { enforce(id, identifier); } });
        actions.push({ name : 'JOIN.ACTION_CANCEL',
            callback : function cancel() { guacNotification.showStatus(false); } });
        show({ title : 'JOIN.DIALOG_HEADER_WAITING', text : { key : textKey }, actions : actions });
    };

    var waitForAdmission = function waitForAdmission(id, identifier) {

        joinRequestService.createRequest(identifier.dataSource, identifier.id)
                .then(function requested(created) {

            var poll = null;
            var stopRouteWatch = null;
            var stop = function stop() {
                if (poll) { $interval.cancel(poll); poll = null; }
                if (stopRouteWatch) { stopRouteWatch(); stopRouteWatch = null; }
            };

            show({
                title   : 'JOIN.DIALOG_HEADER_WAITING',
                text    : { key : 'JOIN.TEXT_WAITING' },
                actions : [
                    { name : 'JOIN.ACTION_ENFORCE', className : 'danger',
                        callback : function forced() { stop(); enforce(id, identifier); } },
                    { name : 'JOIN.ACTION_CANCEL',
                        callback : function cancelled() {
                            stop();
                            joinRequestService.cancelRequest(identifier.dataSource, created.id);
                            guacNotification.showStatus(false);
                        } }
                ]
            });

            stopRouteWatch = $rootScope.$on('$routeChangeSuccess', stop);

            poll = $interval(function check() {
                joinRequestService.getRequest(identifier.dataSource, created.id)
                        .then(function polled(result) {
                    if (result.status === 'ADMITTED') {
                        stop();
                        guacNotification.showStatus(false);
                        var sharedId = ClientIdentifier.toString({
                            dataSource : identifier.dataSource + '-shared',
                            type       : ClientIdentifier.Types.CONNECTION,
                            id         : result.shareKey
                        });
                        $location.path('/client/' + sharedId).search({ key : result.shareKey });
                    }
                    else if (result.status === 'DENIED') {
                        stop();
                        outcome(id, identifier, 'JOIN.TEXT_DENIED', false);
                    }
                    else if (result.status === 'FREE' || result.status === 'CANCELLED') {
                        stop();
                        connect(id);
                    }
                }, function failed(error) {
                    stop();
                    if (isNotFound(error))
                        outcome(id, identifier, 'JOIN.TEXT_EXPIRED', true);
                    else
                        connect(id);
                });
            }, POLL_MS);

        }, function notRequested() {
            // Free by now, or the mailbox is unavailable: never block connecting
            connect(id);
        });

    };

    // Newcomer: check before a connection is opened
    $rootScope.$on('$routeChangeStart', function checkInUse(event, next) {

        if (!next || !next.$$route || next.$$route.originalPath !== '/client/:id')
            return;

        var id = next.params.id;
        if (next.params.key || cleared[id]) {
            delete cleared[id];
            return;
        }

        var identifier = ClientIdentifier.fromString(id);
        if (identifier.type !== ClientIdentifier.Types.CONNECTION
                || JDBC_DATA_SOURCES.indexOf(identifier.dataSource) === -1
                || id in guacClientManager.getManagedClients())
            return;

        event.preventDefault();

        joinRequestService.getStatus(identifier.dataSource, identifier.id)
                .then(function checked(status) {
            if (status.state === 'inUse')
                waitForAdmission(id, identifier);
            else
                connect(id);
        }, function unchecked() {
            connect(id);
        });

    });

    // Connected user: answer requests to join
    var shownRequest = null;

    var prompt = function prompt(dataSource, request) {

        var status = null;
        var done = function done() {
            if (guacNotification.getStatus() === status)
                guacNotification.showStatus(false);
            shownRequest = null;
        };
        var decide = function decide(decision, profile) {
            joinRequestService.decide(dataSource, request.id, decision, profile)
                .then(done, function failed(error) {
                    done();
                    guacNotification.SHOW_REQUEST_ERROR(error);
                });
        };

        var actions = request.sharingOptions.map(function toAction(option) {
            return { name : option.name,
                callback : function admit() { decide('admit', option.identifier); } };
        });
        actions.push(request.sharingOptions.length
            ? { name : 'JOIN.ACTION_DENY', callback : function deny() { decide('deny'); } }
            : { name : 'JOIN.ACTION_OK', callback : function ok() { decide('deny'); } });

        status = {
            title   : 'JOIN.DIALOG_HEADER_REQUEST',
            text    : {
                key       : request.sharingOptions.length ? 'JOIN.TEXT_REQUEST' : 'JOIN.TEXT_REQUEST_NO_SHARING',
                variables : { USERNAME : request.requester }
            },
            actions : actions
        };

        shownRequest = { id : request.id, status : status };
        guacNotification.showStatus(status);

    };

    $interval(function pollIncoming() {

        var dataSources = {};
        var clients = guacClientManager.getManagedClients();
        angular.forEach(clients, function collect(client, id) {
            if (client.clientState.connectionState !== ManagedClientState.ConnectionState.CONNECTED)
                return;
            var dataSource = ClientIdentifier.fromString(id).dataSource;
            if (JDBC_DATA_SOURCES.indexOf(dataSource) !== -1)
                dataSources[dataSource] = true;
        });

        angular.forEach(dataSources, function check(unused, dataSource) {
            joinRequestService.getIncoming(dataSource).then(function received(requests) {

                // Withdraw a prompt whose request is gone (cancelled, expired, answered elsewhere)
                if (shownRequest && !requests.some(function same(r) { return r.id === shownRequest.id; })) {
                    if (guacNotification.getStatus() === shownRequest.status)
                        guacNotification.showStatus(false);
                    shownRequest = null;
                }

                if (requests.length && !shownRequest && !guacNotification.getStatus())
                    prompt(dataSource, requests[0]);

            });
        });

    }, POLL_MS);

}]);
```

- [ ] **Step 4: Syntax-check**

Run: `node --check extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/resources/js/joinRequests.js && echo ok`
Expected: `ok`

- [ ] **Step 5: Build the extension jars** to prove the manifests and resources package

Run: `cd extensions/guacamole-auth-jdbc && mvn -q package -DskipTests -Dexec.skip=true`
Expected: BUILD SUCCESS. Then: `unzip -l modules/guacamole-auth-jdbc-postgresql/target/guacamole-auth-jdbc-postgresql-*.jar | grep joinRequests.js` prints one line.

- [ ] **Step 6: Commit**

```bash
git add extensions/guacamole-auth-jdbc
git commit -m "GUACAMOLE-453: Ask connected users to admit newcomers."
```

---

### Task 7: Spec alignment, full build and manual check

**Files:**
- Modify: `docs/superpowers/specs/2026-10-22-guacamole-453-join-requests-design.md`

- [ ] **Step 1: Record this plan's deviations in the spec.** Append to the spec:

```markdown
## Implementation notes (plan of 2026-10-22)

- **UI lives in the JDBC extension** (`js/joinRequests.js`, registered in the three JDBC manifests), not in the core web app. The feature belongs to the JDBC auth provider, and the core app needs no change.
- **The share key is created server-side.** On admit the connected user posts `{"decision":"admit","sharingProfile":"<id>"}`, and the server calls the existing `ConnectionSharingService.shareConnection` as that user, which is the same path and the same permission checks as `sharingCredentials`. The browser never handles the key on the connected user's side.
- **`ClusterStore` gains five methods:** put, get, list, resolve, remove. Lua scripts make put and resolve atomic.
- **No JavaScript unit tests.** The web app has no AngularJS test harness. The UI is verified manually (see Testing), and all rules sit server-side, where they are unit-tested.
- **Connected users are found per node.** `incoming` lists only requests for sessions served by the node that answers the poll. With sticky load-balancer sessions that is the user's own node. Newcomer status and Enforce are cluster-wide.
```

- [ ] **Step 2: Full build and tests**

Run: `mvn -q verify -Dexec.skip=true -Dskip.npm -Dskip.npx -Dskip.installnodenpm`
Expected: BUILD SUCCESS, with no compiler warnings, the RAT check passing, and all tests green.

- [ ] **Step 3: Manual check on a local or test deployment** with `join-request-enabled: true`, a connection that has two sharing profiles ("Allow control" with no parameters, and "View only" with `read-only=true`), and two users with READ on it:
  1. A connects, then B opens the connection. B sees "Connection In Use / please wait to be admitted", and within 3 s A sees "B wants to join" with "Allow control", "View only" and "Deny".
  2. A picks "View only". B joins the same session and cannot type. A stays connected.
  3. Repeat with "Allow control". B can type.
  4. A picks "Deny". B sees the declined text with Enforce and Cancel.
  5. A ignores the request for 120 s. B sees "No response…" with Retry, Enforce and Cancel. A's prompt disappears.
  6. B presses Enforce. A gets "Disconnected", and B connects normally.
  7. A opens the same connection in a second tab. No dialog appears.
  8. With `join-request-enabled: false`, B connects exactly as before.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-10-22-guacamole-453-join-requests-design.md
git commit -m "GUACAMOLE-453: Record implementation notes in the design."
```
