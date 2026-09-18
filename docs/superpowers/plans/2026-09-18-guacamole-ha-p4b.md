> **Status: delivered.** Merged in `b6786c3f8`. Verified live — see `../deploy/README.md` section 11. The plan underestimated the packaging problem: cluster classes had to move into `guacamole-ext` before any of this could load, and four defects surfaced only on a deployment. Redis became security-sensitive at this phase.
>
> See `../HA-CLUSTERING-STATUS.md` for the programme-level summary.

# Guacamole HA Clustering — P4b Implementation Plan (auth token store and session recovery)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user whose replica dies land on a healthy replica still logged in, instead of being bounced to the login screen.

**Architecture:** Redis holds identity only — username, authenticating provider, remote address and hostname, authentication time — keyed by the SHA-256 of the auth token rather than the token itself. On a local session miss the webapp looks the token up, and if its provider implements a new `RehydratableAuthenticationProvider` SPI, rebuilds the `AuthenticatedUser` and runs the existing `getUserContext()` path, which re-reads permissions from the database. Providers not implementing that SPI are denied, so SSO and second-factor sessions fall through to a real login.

**Tech Stack:** Java 8, Maven, Guice 5.1.0, Lettuce 6.3.2.RELEASE, Redis 7.x, JUnit 5.14.4, Testcontainers 1.21.3, Docker.

**Spec:** `docs/superpowers/specs/2026-09-06-guacamole-ha-clustering-design.md` (§5.1 through §5.6)

**Prior phases:** P0/P1, P2, P3a, P3b, P4a — all merged to `main` as of 2026-09-18.

## Global Constraints

- **Java 8 source and target.** `pom.xml:259-260`. No `var`, no records, no `List.of`.
- **Apache RAT is active.** Every new file needs the ASF licence header. `.md` is excluded.
- **Commit convention is `GUACAMOLE-283: Capitalized summary`.**
- **`cluster-enabled` defaults to `false`.** With clustering off, `HashTokenSessionMap` is used directly and behaviour is exactly upstream.
- **Never build a Redis key inline.** All keys come from `ClusterKeys`.
- **A Redis outage degrades, never blocks** (spec §6.1). A token that cannot be looked up is simply not rehydratable, which is how an expired token already behaves.
- **Existing suite must stay green:** `mvn -pl guacamole-cluster test` (72 tests as of P4a).

---

## The four security properties this phase must not break

These are the reasons the spec rejects the simpler designs. Every one of them is a
way to turn "the user logs in again after a replica restart" into "an attacker
holding a stale token gets a fresh session".

1. **No credentials in Redis.** Storing the password to replay `authenticateUser`
   would put it in Redis for the life of the session. The store holds identity only.
2. **No authorization in Redis.** Serializing the `UserContext` or a permission set
   would cache authorization, so revoking a permission or disabling an account would
   not take effect until token expiry. Permissions are always re-derived.
   **Verified:** `JDBCAuthenticationProviderService.getUserContext` gates on
   `user != null && !user.isDisabled()`, and that method runs on the rehydration
   path, so a disabled account is caught.
3. **The key is the SHA-256 of the token, never the token.** A Guacamole auth token
   is a bearer credential. Storing it in plaintext means a Redis dump, a backup, or
   an operator running `KEYS guac:token:*` yields a set of live, usable sessions.
   Hashing preserves the only operation needed — exact-match lookup — while making
   the store useless for impersonation.
4. **Default-deny on the provider.** Only providers implementing
   `RehydratableAuthenticationProvider` are rebuilt. This is load-bearing:
   `guacamole-auth-totp` records second-factor success in memory, so rebuilding from
   a username alone is a **2FA bypass**; SSO providers carry assertion state, so
   rebuilding fabricates an authentication the identity provider never issued.

**Redis must run with authentication and TLS once this is deployed**, with the
Guacamole ACL user scoped to the `guac:` prefix. Before P4b the store held routing
and counting state; after it, it holds session identity. The devqa stack runs Redis
with neither, which is acceptable for a test namespace and is not acceptable for
anything else. Task 5 Step 7 records this in the deployment README.

---

## File Structure

**New**

| File | Responsibility |
|---|---|
| `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/RehydratableAuthenticationProvider.java` | The SPI a provider implements to declare its sessions rebuildable |
| `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/TokenIdentity.java` | The identity fields a token resolves to |
| `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterLogoutHandler.java` | Callback for a logout issued on another replica |
| `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/TokenStoreTest.java` | Storage, hashing, expiry and cross-replica logout |

**Modified**

| File | Change |
|---|---|
| `.../cluster/ClusterKeys.java` | `token(String tokenHash)`, `LOGOUT_CHANNEL` |
| `.../cluster/ClusterStore.java` + `RedisClusterStore` + `NoOpClusterStore` | Token put/get/remove/touch, logout publish/subscribe |
| `guacamole/pom.xml` | Depend on `guacamole-cluster` |
| `.../rest/auth/AuthenticationService.java:461` | Rehydrate on local miss |
| `.../rest/auth/AuthenticationService.java:404,491` | Publish the token on login, withdraw it on logout |
| `.../GuacamoleServletContextListener.java:204` | Register the logout subscription |
| `.../auth/jdbc/InjectedAuthenticationProvider.java` + `AuthenticationProviderService` + `JDBCAuthenticationProviderService` | Implement `RehydratableAuthenticationProvider` |

**Why rehydration lives in `AuthenticationService` rather than in a `TokenSessionMap`
implementation.** Rebuilding a session needs the `AuthenticationProvider` list and
the decoration service, both of which `AuthenticationService` already holds and a
map does not. `HashTokenSessionMap` is also constructed by hand at
`GuacamoleServletContextListener.java:204`, before the Guice injector exists, so it
cannot be given injected collaborators. The map stays exactly as upstream and every
cluster concern sits in the service that already owns the flow.

---

## Task 1: Store token identity in the cluster

**Files:**
- Create: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/TokenIdentity.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterKeys.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/redis/RedisClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/NoOpClusterStore.java`
- Test: `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/TokenStoreTest.java`

**Interfaces:**
- Consumes: `ClusterKeys`, `LuaScript` (not needed here — plain commands suffice).
- Produces:
  - `TokenIdentity(String username, String authProviderIdentifier, String remoteAddress, String remoteHostname, long authenticatedTime)` with a getter per field
  - `ClusterKeys.token(String tokenHash)` returning `String`
  - `ClusterStore.putToken(String tokenHash, TokenIdentity identity, int timeoutSeconds)`
  - `ClusterStore.getToken(String tokenHash)` returning `TokenIdentity` or null
  - `ClusterStore.removeToken(String tokenHash)`
  - `ClusterStore.touchToken(String tokenHash, int timeoutSeconds)` — refreshes idle expiry

**The store never sees the token itself.** Every method takes an already-hashed
value. Hashing happens in Task 3, at the single point where the token is known, so
that no later change can accidentally pass a raw token to a method whose name says
hash.

- [ ] **Step 1: Write the failing test** (ASF header first)

```java
package org.apache.guacamole.cluster.redis;

import org.apache.guacamole.cluster.TokenIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TokenStoreTest {

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
    public void identityRoundTrips() throws Exception {

        store.putToken("hash-1", new TokenIdentity("alice", "postgresql",
                "10.0.0.1", "client.example.com", 1700000000000L), 3600);

        TokenIdentity identity = store.getToken("hash-1");
        assertEquals("alice", identity.getUsername());
        assertEquals("postgresql", identity.getAuthProviderIdentifier());
        assertEquals("10.0.0.1", identity.getRemoteAddress());
        assertEquals("client.example.com", identity.getRemoteHostname());
        assertEquals(1700000000000L, identity.getAuthenticatedTime());

    }

    @Test
    public void unknownTokenIsNull() throws Exception {
        assertNull(store.getToken("never-issued"));
    }

    @Test
    public void identityWithoutAHostnameRoundTrips() throws Exception {

        // The remote hostname is frequently unavailable
        store.putToken("hash-2", new TokenIdentity("bob", "postgresql",
                "10.0.0.2", null, 1700000000000L), 3600);

        assertNull(store.getToken("hash-2").getRemoteHostname());

    }

    @Test
    public void removedTokenIsGone() throws Exception {

        store.putToken("hash-3", new TokenIdentity("carol", "postgresql",
                "10.0.0.3", null, 1700000000000L), 3600);
        store.removeToken("hash-3");

        assertNull(store.getToken("hash-3"));

    }

    @Test
    public void tokenIsVisibleToAnotherReplica() throws Exception {

        RedisClusterStore other = new RedisClusterStore(RedisTestSupport.redisUri(),
                STALE_WINDOW_MS, "node-2");

        try {
            store.putToken("hash-4", new TokenIdentity("dave", "postgresql",
                    "10.0.0.4", null, 1700000000000L), 3600);
            assertEquals("dave", other.getToken("hash-4").getUsername());
        }

        finally {
            other.shutdown();
        }

    }

    @Test
    public void idleExpiryIsRefreshedByTouch() throws Exception {

        store.putToken("hash-5", new TokenIdentity("erin", "postgresql",
                "10.0.0.5", null, 1700000000000L), 2);

        Thread.sleep(1100);
        store.touchToken("hash-5", 60);
        assertTrue(store.tokenTtlForTesting("hash-5") > 2,
                "touch must extend the idle window");

    }

    @Test
    public void anExpiredTokenIsGone() throws Exception {

        store.putToken("hash-6", new TokenIdentity("frank", "postgresql",
                "10.0.0.6", null, 1700000000000L), 1);

        Thread.sleep(1100);
        assertNull(store.getToken("hash-6"));

    }

}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl guacamole-cluster test -Dtest=TokenStoreTest
```

Expected: FAIL — `TokenIdentity` does not exist.

- [ ] **Step 3: Create the value object** (ASF header first)

```java
package org.apache.guacamole.cluster;

/**
 * The identity a session token resolves to. Identity only: no credentials and
 * no permissions. Permissions are always re-derived from the database when a
 * session is rebuilt, so that revoking access takes effect immediately rather
 * than at token expiry.
 */
public class TokenIdentity {

    private final String username;
    private final String authProviderIdentifier;
    private final String remoteAddress;
    private final String remoteHostname;
    private final long authenticatedTime;

    /**
     * Creates a new TokenIdentity.
     *
     * @param username
     *     The identifier of the authenticated user.
     *
     * @param authProviderIdentifier
     *     The identifier of the authentication provider which authenticated
     *     them. Rehydration is allowed only for providers which declare
     *     themselves rehydratable.
     *
     * @param remoteAddress
     *     The address the session was authenticated from.
     *
     * @param remoteHostname
     *     The hostname the session was authenticated from, or null if unknown.
     *
     * @param authenticatedTime
     *     The time authentication occurred, in milliseconds since the epoch.
     */
    public TokenIdentity(String username, String authProviderIdentifier,
            String remoteAddress, String remoteHostname, long authenticatedTime) {
        this.username = username;
        this.authProviderIdentifier = authProviderIdentifier;
        this.remoteAddress = remoteAddress;
        this.remoteHostname = remoteHostname;
        this.authenticatedTime = authenticatedTime;
    }

    public String getUsername() { return username; }
    public String getAuthProviderIdentifier() { return authProviderIdentifier; }
    public String getRemoteAddress() { return remoteAddress; }
    public String getRemoteHostname() { return remoteHostname; }
    public long getAuthenticatedTime() { return authenticatedTime; }

}
```

- [ ] **Step 4: Add the key and the four operations**

In `ClusterKeys.java`:

```java
    /**
     * Channel on which logouts are published.
     */
    public static final String LOGOUT_CHANNEL = "guac:logout";

    public static String token(String tokenHash) {
        return "guac:token:" + escape(tokenHash);
    }
```

In `RedisClusterStore.java`, following the shape of the share-key operations added
in P3b:

```java
    @Override
    public void putToken(String tokenHash, TokenIdentity identity, int timeoutSeconds) {

        try {

            Map<String, String> record = new HashMap<String, String>();
            record.put("username", identity.getUsername());
            record.put("authProvider", identity.getAuthProviderIdentifier());
            record.put("remoteAddress", identity.getRemoteAddress());
            record.put("authenticatedTime",
                    Long.toString(identity.getAuthenticatedTime()));

            if (identity.getRemoteHostname() != null)
                record.put("remoteHostname", identity.getRemoteHostname());

            String key = ClusterKeys.token(tokenHash);
            commands().hset(key, record);
            commands().expire(key, timeoutSeconds);
            available = true;

        }

        // A token that cannot be published simply does not survive a replica
        // death, which is the pre-cluster behaviour
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            logger.warn("Unable to publish session token to the cluster. This "
                    + "session will not survive the loss of this replica.", e);
        }

    }

    @Override
    public TokenIdentity getToken(String tokenHash) {

        try {

            Map<String, String> record = commands().hgetall(ClusterKeys.token(tokenHash));
            available = true;

            if (record.isEmpty())
                return null;

            return new TokenIdentity(
                    record.get("username"),
                    record.get("authProvider"),
                    record.get("remoteAddress"),
                    record.get("remoteHostname"),
                    Long.parseLong(record.get("authenticatedTime")));

        }

        // An unreadable token is simply not rehydratable, which is how an
        // expired token already behaves
        catch (RedisException | NumberFormatException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            return null;
        }

    }

    @Override
    public void removeToken(String tokenHash) {

        try {
            commands().del(ClusterKeys.token(tokenHash));
            available = true;
        }

        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            logger.warn("Unable to remove session token from the cluster. It "
                    + "will expire on its own.", e);
        }

    }

    @Override
    public void touchToken(String tokenHash, int timeoutSeconds) {

        try {
            commands().expire(ClusterKeys.token(tokenHash), timeoutSeconds);
            available = true;
        }

        // Losing one refresh only shortens the idle window for this session
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
        }

    }

    /**
     * Returns the remaining lifetime of a token, in seconds. Intended only for
     * tests.
     *
     * @param tokenHash
     *     The hash of the token to examine.
     *
     * @return
     *     The remaining lifetime of the token, in seconds.
     */
    public long tokenTtlForTesting(String tokenHash) {
        return commands().ttl(ClusterKeys.token(tokenHash));
    }
```

Declare all four on `ClusterStore` with Javadoc, and implement them on
`NoOpClusterStore` as no-ops, `getToken` returning null.

- [ ] **Step 5: Run it to verify it passes**

```bash
mvn -q -pl guacamole-cluster test -Dtest=TokenStoreTest
```

Expected: PASS, 7 tests.

- [ ] **Step 6: Commit**

```bash
git add guacamole-cluster/src
git commit -m "GUACAMOLE-283: Store session token identity in the cluster

Identity only -- username, provider, remote address and hostname,
authentication time. No credentials, because storing them would put a
password in Redis for the life of a session, and no permissions, because
caching authorization would delay a revocation until token expiry.

Keys are taken already hashed. The hashing happens at the one point where
the token is known, so that no later change can pass a raw token to a
method whose parameter is named for a hash."
```

---

## Task 2: Declare which providers may be rebuilt

**Files:**
- Create: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/RehydratableAuthenticationProvider.java`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/InjectedAuthenticationProvider.java`
- Modify: `.../auth/jdbc/AuthenticationProviderService.java`
- Modify: `.../auth/jdbc/JDBCAuthenticationProviderService.java`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/user/UserService.java`

**Interfaces:**
- Consumes: `UserService.getObjectInstance`, `UserMapper.selectOne`, `ModeledAuthenticatedUser`.
- Produces:
  - `RehydratableAuthenticationProvider.rehydrate(String username, Credentials skeleton)` returning `AuthenticatedUser` or null
  - `UserService.retrieveRehydratedUser(AuthenticationProvider, String username, Credentials)` returning `ModeledAuthenticatedUser` or null

- [ ] **Step 1: Create the SPI** (ASF header first)

```java
package org.apache.guacamole.cluster;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.Credentials;

/**
 * An AuthenticationProvider whose sessions may be rebuilt on another replica
 * from a username alone.
 *
 * Implementing this interface is a security assertion, and the default is to
 * deny. A provider may implement it only if a session of its own carries no
 * state beyond the username that authentication established. Providers that
 * fail this test, and must never implement it:
 *
 *   - Second-factor providers such as guacamole-auth-totp, which record that a
 *     session passed its second factor in memory. Rebuilding from a username
 *     alone would issue an authenticated session that never saw a TOTP code.
 *   - SSO providers, which carry assertion state from the identity provider.
 *     Rebuilding would fabricate an authentication the identity provider never
 *     issued.
 *   - Providers holding session-scoped secrets, such as guacamole-vault.
 */
public interface RehydratableAuthenticationProvider {

    /**
     * Rebuilds the AuthenticatedUser for a session which was authenticated
     * against this provider on another replica.
     *
     * Implementations MUST re-check that the account still exists and is still
     * permitted to log in. They MUST NOT accept a username that no longer
     * resolves to an account.
     *
     * @param username
     *     The identifier of the user whose session is being rebuilt.
     *
     * @param skeleton
     *     Credentials carrying the request's remote address and hostname, and
     *     no secret. A rehydrated session has no password to present.
     *
     * @return
     *     The rebuilt AuthenticatedUser, or null if the session may not be
     *     rebuilt.
     *
     * @throws GuacamoleException
     *     If an error prevents the user from being rebuilt.
     */
    AuthenticatedUser rehydrate(String username, Credentials skeleton)
            throws GuacamoleException;

}
```

- [ ] **Step 2: Add the database lookup**

In `UserService.java`, beside `retrieveAuthenticatedUser` (`UserService.java:396`),
which this deliberately mirrors minus the password check:

```java
    /**
     * Retrieves the user with the given username, without verifying any
     * password, for the purpose of rebuilding a session authenticated on
     * another replica.
     *
     * The absent password check is the entire point: the session token is the
     * credential that was verified, and it was verified when the session was
     * created. Authorization is not granted here -- it is re-derived by
     * getUserContext(), which also rejects a disabled account.
     *
     * @param authenticationProvider
     *     The AuthenticationProvider on behalf of which the user is being
     *     retrieved.
     *
     * @param username
     *     The identifier of the user whose session is being rebuilt.
     *
     * @param credentials
     *     Credentials carrying the request's remote address and hostname.
     *
     * @return
     *     An AuthenticatedUser for the named user, or null if no such user
     *     exists.
     *
     * @throws GuacamoleException
     *     If the user cannot be retrieved.
     */
    public ModeledAuthenticatedUser retrieveRehydratedUser(
            AuthenticationProvider authenticationProvider, String username,
            Credentials credentials) throws GuacamoleException {

        UserModel userModel = userMapper.selectOne(username, getCaseSensitivity());
        if (userModel == null)
            return null;

        ModeledUser user = getObjectInstance(null, userModel);
        user.setCurrentUser(new ModeledAuthenticatedUser(authenticationProvider,
                user, credentials));

        return user.getCurrentUser();

    }
```

- [ ] **Step 3: Implement the SPI on the JDBC provider**

**The provider has no injector field to reach through.**
`InjectedAuthenticationProvider.java:64-69` takes an injector in its constructor,
pulls one `AuthenticationProviderService` out of it, and lets the injector go. Every
provider method delegates to that service. Rehydration follows the same path.

Add to the `AuthenticationProviderService` interface, as a Java 8 default so that
only the JDBC service has to implement it:

```java
    /**
     * Rebuilds the AuthenticatedUser of a session authenticated against this
     * provider on another replica. Returns null by default, which denies
     * rebuilding.
     *
     * @param authenticationProvider
     *     The AuthenticationProvider on behalf of which the user is rebuilt.
     *
     * @param username
     *     The identifier of the user whose session is being rebuilt.
     *
     * @param skeleton
     *     Credentials carrying the request's remote address and hostname.
     *
     * @return
     *     The rebuilt AuthenticatedUser, or null if the session may not be
     *     rebuilt.
     *
     * @throws GuacamoleException
     *     If an error prevents the user from being rebuilt.
     */
    default AuthenticatedUser rehydrate(AuthenticationProvider authenticationProvider,
            String username, Credentials skeleton) throws GuacamoleException {
        return null;
    }
```

`JDBCAuthenticationProviderService` overrides it:

```java
    @Override
    @Transactional
    public AuthenticatedUser rehydrate(AuthenticationProvider authenticationProvider,
            String username, Credentials skeleton) throws GuacamoleException {
        return userService.retrieveRehydratedUser(authenticationProvider, username,
                skeleton);
    }
```

and `InjectedAuthenticationProvider` implements the SPI by delegating, exactly as its
other methods do:

```java
public abstract class InjectedAuthenticationProvider
        extends AbstractAuthenticationProvider
        implements RehydratableAuthenticationProvider {

    @Override
    public AuthenticatedUser rehydrate(String username, Credentials skeleton)
            throws GuacamoleException {
        return authProviderService.rehydrate(this, username, skeleton);
    }
```

**The shared-connection providers inherit this and must keep denying.**
`PostgreSQLSharedAuthenticationProvider` and its siblings also extend
`InjectedAuthenticationProvider`, so they too will advertise the SPI — but their
service is `SharedAuthenticationProviderService`
(`.../auth/jdbc/sharing/SharedAuthenticationProviderService.java`), which does not
override the default and therefore returns null. A share key is its own credential
and already works cluster-wide from P3b; rebuilding one from a username would be
wrong. **Verified:** `AuthenticationProviderService` is an interface
(`AuthenticationProviderService.java:33`), so a Java 8 default method is legal, and
the shared service has no `rehydrate` of its own.

This is also why the webapp treats a null return as "not rebuildable" rather than
assuming that `instanceof RehydratableAuthenticationProvider` alone is permission.

- [ ] **Step 4: Verify both modules build**

```bash
mvn -q -pl extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base -am install -DskipTests
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add guacamole-cluster/src extensions/guacamole-auth-jdbc
git commit -m "GUACAMOLE-283: Let the JDBC provider rebuild a session from a username

Implementing RehydratableAuthenticationProvider is a security assertion,
and the default is to deny: a second-factor provider rebuilt from a
username alone would issue a session that never saw a second factor, and
an SSO provider would fabricate an assertion its identity provider never
made.

The JDBC lookup is retrieveAuthenticatedUser minus the password check.
The token was the credential and it was verified when the session was
created. Authorization is still re-derived by getUserContext(), which
also rejects a disabled account."
```

---

## Task 3: Rehydrate on a local session miss

**Files:**
- Modify: `guacamole/pom.xml`
- Modify: `guacamole/src/main/java/org/apache/guacamole/rest/auth/AuthenticationService.java`

**Interfaces:**
- Consumes: `ClusterStore` token operations (Task 1), `RehydratableAuthenticationProvider` (Task 2).
- Produces: nothing consumed by a later task.

- [ ] **Step 1: Add the dependency**

In `guacamole/pom.xml`, beside the `guacamole-ext` dependency:

```xml
        <dependency>
            <groupId>org.apache.guacamole</groupId>
            <artifactId>guacamole-cluster</artifactId>
            <version>${revision}</version>
            <scope>compile</scope>
        </dependency>
```

This is a new coupling: P0 through P4a never needed `guacamole-cluster` in the
webapp, only in extensions. It also means a **second Lettuce client per replica** —
the webapp's, alongside the JDBC extension's, which cannot be shared because
extensions load in their own classloaders. Two clients against one Redis is
acceptable; note it in the commit so it is a known cost rather than a surprise.

- [ ] **Step 2: Hash the token**

In `AuthenticationService`, as a private static helper. This is the only place a
token is hashed, and the only place a raw token is passed to anything cluster-related:

```java
    /**
     * Returns the SHA-256 of the given auth token, hex-encoded.
     *
     * The token is a bearer credential, so the cluster stores its hash rather
     * than the token. A Redis dump or an operator listing keys then yields
     * nothing usable for impersonation, while exact-match lookup -- the only
     * operation needed -- still works.
     *
     * @param authToken
     *     The token to hash.
     *
     * @return
     *     The hex-encoded SHA-256 of the given token.
     */
    private static String hashToken(String authToken) {

        try {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(authToken.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash)
                hex.append(String.format("%02x", b));

            return hex.toString();

        }

        // SHA-256 is required of every Java implementation
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }

    }
```

- [ ] **Step 3: Publish the token on login**

In `authenticate`, at the point a new token is minted (`AuthenticationService.java:404`):

```java
            // If no existing session, generate a new token/session pair
            else {
                authToken = authTokenGenerator.getToken();
                tokenSessionMap.put(authToken, new GuacamoleSession(listenerService, authenticatedUser, userContexts));
                publishToken(authToken, authenticatedUser);
            }
```

with:

```java
    /**
     * Publishes the identity behind a newly-issued token to the cluster, so
     * that the session can be rebuilt on another replica.
     *
     * @param authToken
     *     The token which was issued.
     *
     * @param authenticatedUser
     *     The user the token identifies.
     */
    private void publishToken(String authToken, AuthenticatedUser authenticatedUser) {

        Credentials credentials = authenticatedUser.getCredentials();
        clusterStore.putToken(hashToken(authToken), new TokenIdentity(
                authenticatedUser.getIdentifier(),
                authenticatedUser.getAuthenticationProvider().getIdentifier(),
                credentials != null ? credentials.getRemoteAddress() : null,
                credentials != null ? credentials.getRemoteHostname() : null,
                System.currentTimeMillis()),
                sessionTimeoutSeconds());

    }
```

**Read `api-session-timeout` the same way `HashTokenSessionMap` does** — it is
declared there as a private property at `HashTokenSessionMap.java:59-66` with
`getName()` returning `"api-session-timeout"` and is in minutes. Do not invent a
second property; re-read the same one and convert to seconds.

- [ ] **Step 4: Rehydrate on miss**

`getGuacamoleSession` (`AuthenticationService.java:461`) becomes:

```java
    public GuacamoleSession getGuacamoleSession(String authToken)
            throws GuacamoleException {

        // Try to get the session from the map of logged in users.
        GuacamoleSession session = tokenSessionMap.get(authToken);

        // A session this replica does not hold may still be live elsewhere in
        // the cluster
        if (session == null)
            session = rehydrateSession(authToken);

        // Authentication failed.
        if (session == null)
            throw new GuacamoleUnauthorizedException("Permission Denied.");

        // Keep the cluster's idle expiry in step with local access
        clusterStore.touchToken(hashToken(authToken), sessionTimeoutSeconds());

        return session;

    }

    /**
     * Rebuilds the session behind a token which this replica does not hold.
     *
     * @param authToken
     *     The token whose session should be rebuilt.
     *
     * @return
     *     The rebuilt session, or null if the token is unknown to the cluster
     *     or its provider does not permit rebuilding.
     */
    private GuacamoleSession rehydrateSession(String authToken) {

        TokenIdentity identity = clusterStore.getToken(hashToken(authToken));
        if (identity == null)
            return null;

        // Default deny: only a provider which declares itself rehydratable may
        // have its sessions rebuilt
        AuthenticationProvider authProvider = null;
        for (AuthenticationProvider candidate : authProviders) {
            if (candidate.getIdentifier().equals(identity.getAuthProviderIdentifier())) {
                authProvider = candidate;
                break;
            }
        }

        if (!(authProvider instanceof RehydratableAuthenticationProvider)) {
            logger.debug("Session for user \"{}\" will not be rebuilt: provider "
                    + "\"{}\" is not rehydratable.", identity.getUsername(),
                    identity.getAuthProviderIdentifier());
            return null;
        }

        try {

            // The cast is required: Credentials has both a
            // (String, String, HttpServletRequest) and a
            // (String, String, RequestDetails) constructor, so a bare null is
            // ambiguous and will not compile
            Credentials skeleton = new Credentials(null, null,
                    (HttpServletRequest) null);
            skeleton.setRemoteAddress(identity.getRemoteAddress());
            skeleton.setRemoteHostname(identity.getRemoteHostname());

            AuthenticatedUser authenticatedUser =
                    ((RehydratableAuthenticationProvider) authProvider)
                            .rehydrate(identity.getUsername(), skeleton);

            if (authenticatedUser == null)
                return null;

            // Permissions are re-read here, so an account disabled or
            // de-permissioned since login is caught
            List<DecoratedUserContext> userContexts =
                    getUserContexts(null, authenticatedUser, skeleton);

            GuacamoleSession session = new GuacamoleSession(listenerService,
                    authenticatedUser, userContexts);
            tokenSessionMap.put(authToken, session);

            logger.info("Session for user \"{}\" rebuilt on this replica.",
                    identity.getUsername());
            return session;

        }

        // A session that cannot be rebuilt is simply not rebuilt; the user
        // authenticates again, which is the pre-cluster behaviour
        catch (GuacamoleException | GuacamoleAuthenticationProcessException e) {
            logger.warn("Unable to rebuild session for user \"{}\".",
                    identity.getUsername(), e);
            return null;
        }

    }
```

**No `AuthenticationSuccessEvent` is fired here, deliberately.** That event drives
login accounting and `guacamole-auth-ban`, and a replica restart must not present as
a burst of logins — which, with P4a in place, would now also count toward
cluster-wide ban thresholds.

**Verified:** `Credentials` declares both
`(String, String, HttpServletRequest)` (`Credentials.java:76`) and
`(String, String, RequestDetails)` (`:95`), so the third argument must be cast or the
call is ambiguous. `setRemoteAddress` (`:321`) and `setRemoteHostname` (`:367`) both
exist.

- [ ] **Step 5: Withdraw the token on logout**

`destroyGuacamoleSession` becomes:

```java
    public boolean destroyGuacamoleSession(String authToken) {

        String tokenHash = hashToken(authToken);

        // Withdraw from the cluster first, so that no replica can rehydrate
        // the token after this point
        clusterStore.removeToken(tokenHash);
        clusterStore.publishLogout(tokenHash);

        // Remove corresponding GuacamoleSession if the token is valid
        GuacamoleSession session = tokenSessionMap.remove(authToken);
        if (session == null)
            return false;

        // Invalidate the removed session
        session.invalidate();
        return true;

    }
```

The ordering matters: removing from Redis before the local map closes the window in
which another replica could rehydrate a token that is being logged out.

- [ ] **Step 6: Verify the webapp builds**

```bash
mvn -q -pl guacamole -am install -DskipTests
```

Expected: BUILD SUCCESS. If `generate-license-files` fails, that failure is
pre-existing and unrelated — it reproduces on `main` — so confirm the compile
succeeded and move on.

- [ ] **Step 7: Commit**

```bash
git add guacamole/pom.xml guacamole/src
git commit -m "GUACAMOLE-283: Rebuild a session whose replica is gone

A request whose token this replica does not hold now looks the token up
in the cluster and rebuilds the session, so losing a replica costs a user
their tunnels but not their login.

Only providers implementing RehydratableAuthenticationProvider are
rebuilt. Everything else falls through to a real login.

No AuthenticationSuccessEvent is fired on a rebuild. That event drives
login accounting and brute-force banning, and a replica restart must not
present as a burst of logins.

This adds guacamole-cluster to the webapp, which is a new coupling: every
prior phase needed it only in extensions. It also means a second Lettuce
client per replica, since extensions load in their own classloaders and
cannot share the webapp's."
```

---

## Task 4: Log out everywhere at once

**Files:**
- Create: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterLogoutHandler.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/redis/RedisClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/NoOpClusterStore.java`
- Modify: `guacamole/src/main/java/org/apache/guacamole/rest/auth/AuthenticationService.java`
- Test: `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/TokenStoreTest.java`

**Interfaces:**
- Consumes: the single pub/sub connection built in P3a and extended in P3b.
- Produces:
  - `interface ClusterLogoutHandler { void loggedOut(String tokenHash); }`
  - `ClusterStore.onLogout(ClusterLogoutHandler handler)`
  - `ClusterStore.publishLogout(String tokenHash)`

**Without this, logging out on one replica leaves a fully usable session in another
replica's local map until idle timeout** — a worse security property than the
single-node behaviour this phase started from.

- [ ] **Step 1: Write the failing test**

Add to `TokenStoreTest`:

```java
    @Test
    public void logoutReachesAnotherReplica() throws Exception {

        RedisClusterStore other = new RedisClusterStore(RedisTestSupport.redisUri(),
                STALE_WINDOW_MS, "node-2");

        try {

            final java.util.concurrent.CountDownLatch seen =
                    new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicReference<String> hash =
                    new java.util.concurrent.atomic.AtomicReference<String>();

            other.onLogout(new org.apache.guacamole.cluster.ClusterLogoutHandler() {

                @Override
                public void loggedOut(String tokenHash) {
                    hash.set(tokenHash);
                    seen.countDown();
                }

            });

            store.publishLogout("hash-logged-out");

            assertTrue(seen.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "logout never arrived");
            assertEquals("hash-logged-out", hash.get());

        }

        finally {
            other.shutdown();
        }

    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl guacamole-cluster test -Dtest=TokenStoreTest
```

Expected: FAIL — `ClusterLogoutHandler` does not exist.

- [ ] **Step 3: Add the handler and the channel**

`ClusterLogoutHandler` mirrors `ClusterShareRevocationHandler` exactly:

```java
package org.apache.guacamole.cluster;

/**
 * Receives notification that a session token has been logged out anywhere in
 * the cluster, so that any session rebuilt from it on this replica can be
 * dropped.
 */
public interface ClusterLogoutHandler {

    /**
     * Invoked when the session behind the given token hash is logged out.
     *
     * @param tokenHash
     *     The hash of the token which is no longer valid.
     */
    void loggedOut(String tokenHash);

}
```

In `RedisClusterStore`, add a `volatile ClusterLogoutHandler logoutHandler` field,
`onLogout` setting it and calling `subscribeIfNeeded()`, and a third branch in the
existing `message(String channel, String payload)` dispatcher for
`ClusterKeys.LOGOUT_CHANNEL`. `subscribeIfNeeded()` already opens one connection and
subscribes to every channel; add `LOGOUT_CHANNEL` to its `subscribe(...)` call.
`publishLogout` mirrors `publishShareRevocation`.

- [ ] **Step 4: Drop the local session on a remote logout**

`AuthenticationService` cannot easily be given a startup hook, but it is a singleton
created by Guice. Register from its constructor or an `@Inject` method, following the
pattern P3a used for the kill handler and P3b for revocation:

```java
    /**
     * Subscribes this replica to logouts issued elsewhere. Invoked by Guice
     * once this service has been constructed.
     */
    @Inject
    public void registerLogoutHandler() {
        clusterStore.onLogout(new ClusterLogoutHandler() {

            @Override
            public void loggedOut(String tokenHash) {
                invalidateRehydratedSession(tokenHash);
            }

        });
    }
```

**The local map is keyed by the raw token and the cluster message carries only its
hash**, so this replica cannot look the session up directly. Maintain a
`ConcurrentHashMap<String, String>` from token hash to raw token, populated whenever
this replica publishes or rehydrates a token and cleared on logout, and use it here.
That map never leaves the JVM, so it introduces no new exposure beyond the raw tokens
the session map already holds.

```java
    /**
     * Drops the session behind the given token hash, if this replica holds one.
     *
     * @param tokenHash
     *     The hash of the token which has been logged out.
     */
    private void invalidateRehydratedSession(String tokenHash) {

        String authToken = localTokensByHash.remove(tokenHash);
        if (authToken == null)
            return;

        GuacamoleSession session = tokenSessionMap.remove(authToken);
        if (session != null) {
            session.invalidate();
            logger.debug("Dropped session logged out on another replica.");
        }

    }
```

- [ ] **Step 5: Run the tests**

```bash
mvn -pl guacamole-cluster test
mvn -q -pl guacamole -am install -DskipTests
```

Expected: PASS, 80 tests in `guacamole-cluster`; webapp compiles.

- [ ] **Step 6: Commit**

```bash
git add guacamole-cluster/src guacamole/src
git commit -m "GUACAMOLE-283: Log out across every replica

Logout withdraws the token from the cluster and announces it on its own
channel, sharing the pub/sub connection already opened for kills and share
key revocations.

Without the announcement, logging out on one replica would leave a fully
usable session in another replica's local map until idle timeout, which
is a worse property than the single-node behaviour this started from."
```

---

## Task 5: Verify on the live two-replica deployment

The unit tests prove storage and the logout fan-out. Only a deployment proves the
thing the phase exists for: that a user survives the loss of the replica they
authenticated against.

**Files:** none. Produces `docs/superpowers/deploy/README.md` section 11.

- [ ] **Step 1: Build, push and deploy**

```bash
docker build -t guacamole-cluster:1.6.1-p4b --build-arg MAVEN_ARGUMENTS=-DskipTests .
docker tag guacamole-cluster:1.6.1-p4b 738928754249.dkr.ecr.us-east-2.amazonaws.com/guacamole/guacamole-cluster:1.6.1-p4b
aws ecr get-login-password --region us-east-2 --profile tiba-dev-staging | docker login --username AWS --password-stdin 738928754249.dkr.ecr.us-east-2.amazonaws.com
docker push 738928754249.dkr.ecr.us-east-2.amazonaws.com/guacamole/guacamole-cluster:1.6.1-p4b
kubectl --context devqa -n remote-access set image deploy/guacamole guacamole=738928754249.dkr.ecr.us-east-2.amazonaws.com/guacamole/guacamole-cluster:1.6.1-p4b
kubectl --context devqa -n remote-access rollout status deploy/guacamole
```

- [ ] **Step 2: Authenticate against A, use the token on B**

```bash
read A B <<< $(kubectl --context devqa -n remote-access get pods -l app=guacamole \
    --no-headers -o wide | awk '$3=="Running"{print $6}' | tr '\n' ' ')

kubectl --context devqa -n remote-access exec pytester -- python3 -u -c "
import json, sys, urllib.error, urllib.parse, urllib.request
A, B = sys.argv[1], sys.argv[2]
body = urllib.parse.urlencode({'username':'guacadmin','password':'guacadmin'}).encode()
tok = json.load(urllib.request.urlopen('http://%s:8080/api/tokens' % A, data=body))['authToken']
print('token from A:', tok[:12])
for host, name in ((A,'A'), (B,'B')):
    try:
        r = urllib.request.urlopen('http://%s:8080/api/session/data/postgresql/connections?token=%s' % (host, tok), timeout=15)
        print('%s -> %d' % (name, r.status))
    except urllib.error.HTTPError as e:
        print('%s -> %d' % (name, e.code))
" $A $B
```

**Expected: both 200.** Before P4b, B returns 403 — the token exists only in A's map.
Confirm B's log carries `Session for user "guacadmin" rebuilt on this replica.`

- [ ] **Step 3: Confirm the key holds no token and no secrets**

```bash
kubectl --context devqa -n remote-access exec deploy/redis -- redis-cli --scan --pattern 'guac:token:*'
kubectl --context devqa -n remote-access exec deploy/redis -- redis-cli hgetall "guac:token:<hash>"
```

**Expected:** the key name is a 64-character hex hash, not the token, and the hash
contains `username`, `authProvider`, `remoteAddress`, `authenticatedTime` and no
password. Grep the whole keyspace for the token string and expect no match:

```bash
kubectl --context devqa -n remote-access exec deploy/redis -- redis-cli --scan | grep -F "<token>" || echo "token does not appear in any key"
```

- [ ] **Step 4: Kill the authenticating replica**

Authenticate against A, delete A's pod with `--grace-period=0`, then use the token
against B.

**Expected: 200.** This is the phase's whole purpose — the login survives, though any
tunnels A held are gone.

- [ ] **Step 5: Confirm logout is cluster-wide**

Authenticate against A, use the token on B so B holds a rebuilt session, then
`DELETE /api/tokens/<token>` against A. Immediately use the token against B.

**Expected: 403 from B**, and `guac:token:<hash>` gone from Redis. Without the
logout publish, B would keep serving the session from its local map.

- [ ] **Step 6: Confirm the disabled path is unchanged**

Set `CLUSTER_ENABLED=false`, restart, authenticate against A and use the token
against B. **Expected: 403** — the upstream behaviour. Restore afterwards.

- [ ] **Step 7: Document the measurements**

Add section 11 to `docs/superpowers/deploy/README.md` in the style of sections 4-10,
recording what was observed including anything that failed. **State plainly that the
devqa Redis runs without authentication or TLS, that this is acceptable only for a
test namespace, and that any real deployment of the token store requires
`requirepass` or ACL authentication and TLS, with the Guacamole user scoped to the
`guac:` prefix.**

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/deploy/README.md
git commit -m "GUACAMOLE-283: Record token store and session recovery verification"
```

---

## What P4b does NOT do

- **Tunnels do not migrate.** A replica death still drops the sessions it hosted. The
  user lands on a healthy replica already logged in and reconnects.
- **Only JDBC-authenticated sessions rehydrate.** SSO and second-factor sessions fall
  through to a real login, by design. In a deployment running both, the two behaviours
  coexist.
- **Idle expiry is refreshed on access, not continuously.** A session accessed on one
  replica refreshes the cluster's TTL; one sitting idle everywhere expires.
- **The token store does not replace the local map.** Each replica still holds its own
  sessions in memory; Redis holds only what is needed to rebuild them.
- **Redis is now security-sensitive.** Before this phase it held routing and counting
  state. It now holds session identity, and requires authentication and TLS.
