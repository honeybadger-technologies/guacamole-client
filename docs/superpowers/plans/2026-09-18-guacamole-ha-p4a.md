# Guacamole HA Clustering — P4a Implementation Plan (cluster-wide brute-force bans)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Count and enforce brute-force authentication bans across every replica, so an attacker behind a load balancer no longer receives N times the configured `max-attempts` before being banned.

**Architecture:** `guacamole-auth-ban` tracks failures in a per-JVM Caffeine cache. A Redis-backed `AuthenticationFailureTracker` replaces it at the single construction site when clustering is enabled. One counter key per client address, incremented and expired by one Lua script so that concurrent attempts landing on different replicas cannot both observe a count below the limit. When Redis is unreachable the tracker falls back to the existing in-memory implementation, so an outage degrades to per-replica banning rather than to no banning at all.

**Tech Stack:** Java 8, Maven, Lettuce 6.3.2.RELEASE, Redis 7.x, JUnit 5.14.4, Testcontainers 1.21.3, Docker.

**Spec:** `docs/superpowers/specs/2026-09-06-guacamole-ha-clustering-design.md` (§2.6 and §5.7)

**Prior phases:** P0/P1, P2, P3a, P3b — all merged to `main` as of 2026-09-18.

## Global Constraints

- **Java 8 source and target.** `pom.xml:259-260`. No `var`, no records, no `List.of`.
- **Apache RAT is active.** Every new file needs the ASF licence header. `.md` is excluded.
- **Every new module needs an empty `.ratignore`.** No new module here, so nothing to add.
- **Commit convention is `GUACAMOLE-283: Capitalized summary`.**
- **`cluster-enabled` defaults to `false`.** With clustering off, `InMemoryAuthenticationFailureTracker` stays bound and behaviour is exactly upstream.
- **Never build a Redis key inline.** All keys come from `ClusterKeys`.
- **A Redis outage degrades, never blocks** (spec §6.1).
- **Existing suite must stay green:** `mvn -pl guacamole-cluster test` (67 tests as of P3b).

---

## What the spec gets wrong, and what this plan does instead

§5.7 specifies three operations, and one of them does not match the behaviour it
claims to mirror:

> **Success** — `DEL guac:authfail:{address}`.

The in-memory tracker does **not** clear failures on success.
`notifyAuthenticationSuccess` calls `notifyAuthenticationStatus(credentials, false)`
(`InMemoryAuthenticationFailureTracker.java:167-170`), which is the identical call
`notifyAuthenticationRequestReceived` makes. Failures are only ever removed by ageing
out:

```java
// AuthenticationFailureStatus.java
public boolean isValid()   { return System.nanoTime() - lastFailure <= duration; }
public boolean isBlocked() { return isValid() && failureCount.get() >= maxAttempts; }
```

Deleting on success would also be a weakening rather than a neutral change: an
attacker who guesses one valid account among many would clear their own address's
counter and resume. **This plan implements parity — success reads the counter and
blocks if it is at the limit, exactly as a received request does — and stores no
delete operation at all.**

The Redis model maps cleanly onto the in-memory one:

| In-memory | Redis |
|---|---|
| `failureCount` | the integer value at `guac:authfail:{address}` |
| `lastFailure` refreshed on each failure | `EXPIRE` reset to `banDuration` on each failure |
| `isValid()` false | key absent |
| `isBlocked()` | value `>= maxAttempts` while the key exists |
| Caffeine `maximumSize(maxAddresses)` | **no equivalent — see below** |

**`max-addresses` stops bounding anything when clustering is on.** Redis has no
per-keyspace size cap, so memory scales with the number of distinct addresses failing
authentication within one ban window. Each key is a small counter with a TTL and
drains on its own, but it is an attacker-influenced allocation. Do **not** mitigate
with an LRU `maxmemory-policy` on this Redis: eviction would silently discard tunnel
index and seat state, turning a nuisance into a correctness failure. Rate limiting at
the ingress is the correct outer defence. Task 3 logs this at startup so an operator
is told rather than left to assume.

---

## File Structure

**New**

| File | Responsibility |
|---|---|
| `guacamole-cluster/src/main/resources/org/apache/guacamole/cluster/redis/record-auth-failure.lua` | Atomic increment-and-expire, returning the new count |
| `.../guacamole-auth-ban/src/main/java/org/apache/guacamole/auth/ban/status/RedisAuthenticationFailureTracker.java` | `AuthenticationFailureTracker` reading and writing cluster state, falling back to the in-memory tracker when Redis is down |
| `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/AuthFailureTest.java` | Tests for counting, expiry and cross-replica visibility |

**Modified**

| File | Change |
|---|---|
| `.../cluster/ClusterKeys.java` | `authFailure(String address)` |
| `.../cluster/ClusterStore.java` + `RedisClusterStore` + `NoOpClusterStore` | `recordAuthenticationFailure` / `getAuthenticationFailures` |
| `extensions/guacamole-auth-ban/pom.xml` | Depend on `guacamole-cluster` |
| `.../auth/ban/BanningAuthenticationListener.java:155` | Select the Redis tracker when clustering is enabled |

**Why the Redis code lives in `guacamole-cluster` rather than in the ban extension.**
Connection handling, the command timeout, the `REJECT_COMMANDS` option and the
availability re-probe are all already solved in `RedisClusterStore`, and P2 proved
that getting them wrong turns a Redis outage into an outage. The ban extension gets
its own `RedisClusterStore` instance — extensions are loaded in separate
classloaders with their dependencies embedded as nested jars, so it cannot share the
JDBC extension's instance — but it does not get its own copy of that logic.

---

## Task 1: Count authentication failures in the cluster

**Files:**
- Create: `guacamole-cluster/src/main/resources/org/apache/guacamole/cluster/redis/record-auth-failure.lua`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterKeys.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/ClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/redis/RedisClusterStore.java`
- Modify: `guacamole-cluster/src/main/java/org/apache/guacamole/cluster/NoOpClusterStore.java`
- Test: `guacamole-cluster/src/test/java/org/apache/guacamole/cluster/redis/AuthFailureTest.java`

**Interfaces:**
- Consumes: `ClusterKeys.escape` (private), `LuaScript.load`, `LuaScript.eval`.
- Produces:
  - `ClusterKeys.authFailure(String address)` returning `String`
  - `ClusterStore.recordAuthenticationFailure(String address, int banDurationSeconds)` returning `int` — the failure count after the increment, or `-1` if the cluster could not be reached
  - `ClusterStore.getAuthenticationFailures(String address)` returning `int` — the current count, `0` if none, or `-1` if the cluster could not be reached

**`-1` means "unknown", and every caller must treat it as such.** Returning `0` on a
Redis failure would silently unban every address the moment Redis hiccups; returning
a large number would lock every user out. Task 2 routes `-1` to the in-memory
fallback.

- [ ] **Step 1: Write the failing test** (ASF header first)

```java
package org.apache.guacamole.cluster.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AuthFailureTest {

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
    public void failuresAccumulate() throws Exception {

        assertEquals(1, store.recordAuthenticationFailure("10.0.0.1", 300));
        assertEquals(2, store.recordAuthenticationFailure("10.0.0.1", 300));
        assertEquals(3, store.recordAuthenticationFailure("10.0.0.1", 300));

    }

    @Test
    public void addressesAreCountedIndependently() throws Exception {

        store.recordAuthenticationFailure("10.0.0.1", 300);
        store.recordAuthenticationFailure("10.0.0.1", 300);
        store.recordAuthenticationFailure("10.0.0.2", 300);

        assertEquals(2, store.getAuthenticationFailures("10.0.0.1"));
        assertEquals(1, store.getAuthenticationFailures("10.0.0.2"));

    }

    @Test
    public void unknownAddressHasNoFailures() throws Exception {
        assertEquals(0, store.getAuthenticationFailures("10.0.0.99"));
    }

    @Test
    public void countIsVisibleToAnotherReplica() throws Exception {

        // The entire point of the phase: two replicas share one count
        RedisClusterStore other = new RedisClusterStore(RedisTestSupport.redisUri(),
                STALE_WINDOW_MS, "node-2");

        try {
            store.recordAuthenticationFailure("10.0.0.5", 300);
            store.recordAuthenticationFailure("10.0.0.5", 300);
            assertEquals(3, other.recordAuthenticationFailure("10.0.0.5", 300));
        }

        finally {
            other.shutdown();
        }

    }

    @Test
    public void theWindowIsRefreshedByEachFailure() throws Exception {

        // A one-second ban duration, re-armed by a second failure
        store.recordAuthenticationFailure("10.0.0.6", 1);
        long firstTtl = store.authFailureTtlForTesting("10.0.0.6");

        Thread.sleep(1100);
        assertEquals(0, store.getAuthenticationFailures("10.0.0.6"),
                "the counter must age out once the ban duration passes");

        assertEquals(1, store.recordAuthenticationFailure("10.0.0.6", 1),
                "a failure after expiry starts a fresh count");
        assertEquals(true, firstTtl > 0);

    }

}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl guacamole-cluster test -Dtest=AuthFailureTest
```

Expected: FAIL — `recordAuthenticationFailure` does not exist.

- [ ] **Step 3: Write the Lua script** (ASF header first, as a Lua `--` comment block)

`record-auth-failure.lua`:

```lua
-- KEYS[1] : the per-address failure counter
-- ARGV[1] : ban duration, in seconds
--
-- Increments the counter and re-arms its expiry, mirroring the in-memory
-- tracker, where each failure both increments failureCount and resets
-- lastFailure. Both operations must be one script: an INCR whose EXPIRE is
-- lost leaves an address banned permanently.
local count = redis.call('INCR', KEYS[1])
redis.call('EXPIRE', KEYS[1], ARGV[1])
return count
```

- [ ] **Step 4: Add the key and the two operations**

In `ClusterKeys.java`, beside the other key builders:

```java
    public static String authFailure(String address) {
        return "guac:authfail:" + escape(address);
    }
```

In `RedisClusterStore.java`, beside the other script fields:

```java
    private static final LuaScript RECORD_AUTH_FAILURE =
            LuaScript.load("/org/apache/guacamole/cluster/redis/record-auth-failure.lua");
```

and the operations:

```java
    @Override
    public int recordAuthenticationFailure(String address, int banDurationSeconds) {

        try {
            long count = RECORD_AUTH_FAILURE.eval(commands(),
                    new String[] { ClusterKeys.authFailure(address) },
                    new String[] { Integer.toString(banDurationSeconds) });
            available = true;
            return (int) count;
        }

        // -1 is "unknown", never "none": reporting zero here would unban every
        // address for as long as Redis is unreachable
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            return -1;
        }

    }

    @Override
    public int getAuthenticationFailures(String address) {

        try {
            String value = commands().get(ClusterKeys.authFailure(address));
            available = true;
            return value == null ? 0 : Integer.parseInt(value);
        }

        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            return -1;
        }

        // A counter that is not an integer is corrupt rather than absent;
        // treating it as unknown routes the caller to its fallback
        catch (NumberFormatException e) {
            logger.warn("Authentication failure counter for \"{}\" is not a "
                    + "number. Falling back to replica-local tracking.", address);
            return -1;
        }

    }

    /**
     * Returns the remaining lifetime of an address's failure counter, in
     * seconds. Intended only for tests.
     */
    public long authFailureTtlForTesting(String address) {
        return commands().ttl(ClusterKeys.authFailure(address));
    }
```

Declare both on `ClusterStore` with Javadoc stating the `-1` contract, and implement
them on `NoOpClusterStore` returning `-1` — with clustering disabled there is no
cluster to consult, and `-1` sends every caller to the in-memory path, which is
exactly upstream behaviour.

- [ ] **Step 5: Run it to verify it passes**

```bash
mvn -q -pl guacamole-cluster test -Dtest=AuthFailureTest
```

Expected: PASS, 5 tests.

- [ ] **Step 6: Run the whole cluster suite**

```bash
mvn -pl guacamole-cluster test
```

Expected: PASS, 72 tests.

- [ ] **Step 7: Commit**

```bash
git add guacamole-cluster/src
git commit -m "GUACAMOLE-283: Count authentication failures in the cluster

One counter per client address, incremented and re-armed by a single
script so that two replicas cannot both observe a count below the limit.

A cluster that cannot be reached reports -1 rather than 0. Reporting zero
would unban every address for the duration of a Redis outage."
```

---

## Task 2: A cluster-backed failure tracker

**Files:**
- Modify: `extensions/guacamole-auth-ban/pom.xml`
- Create: `extensions/guacamole-auth-ban/src/main/java/org/apache/guacamole/auth/ban/status/RedisAuthenticationFailureTracker.java`

**Interfaces:**
- Consumes: `ClusterStore.recordAuthenticationFailure`, `ClusterStore.getAuthenticationFailures` (Task 1); `AuthenticationFailureTracker` (`AuthenticationFailureTracker.java:29`).
- Produces: `RedisAuthenticationFailureTracker(String redisUri, int maxAttempts, int banDuration, long maxAddresses)`.

- [ ] **Step 1: Add the dependency**

In `extensions/guacamole-auth-ban/pom.xml`, beside the `guacamole-ext` dependency:

```xml
        <dependency>
            <groupId>org.apache.guacamole</groupId>
            <artifactId>guacamole-cluster</artifactId>
            <version>${revision}</version>
            <scope>compile</scope>
        </dependency>
```

`compile` scope is deliberate and is what puts `guacamole-cluster` and
`lettuce-core` inside the built extension jar as nested jars — verified on the
deployed image, where `guacamole-auth-jdbc-postgresql.jar` carries 24 of them
including `lettuce-core-6.3.2.RELEASE.jar`. Guacamole loads each extension in its own
classloader, so this extension cannot borrow the JDBC extension's copy.

- [ ] **Step 2: Write the tracker** (ASF header first)

```java
package org.apache.guacamole.auth.ban.status;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.cluster.ClusterStore;
import org.apache.guacamole.cluster.redis.RedisClusterStore;
import org.apache.guacamole.language.TranslatableGuacamoleClientTooManyException;
import org.apache.guacamole.net.auth.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AuthenticationFailureTracker which counts failures across every replica, so
 * that an attacker behind a load balancer is limited to maxAttempts in total
 * rather than to maxAttempts per replica.
 *
 * Failures are never cleared by a successful login. That matches
 * InMemoryAuthenticationFailureTracker, where success and a received request
 * take the identical code path and only the passage of time removes a count.
 */
public class RedisAuthenticationFailureTracker implements AuthenticationFailureTracker {

    private static final Logger logger =
            LoggerFactory.getLogger(RedisAuthenticationFailureTracker.class);

    /**
     * Stale window of the cluster store. Irrelevant to failure counting, which
     * expires its own keys, but required by the constructor.
     */
    private static final long UNUSED_STALE_WINDOW_MS = 30000L;

    private final ClusterStore clusterStore;

    private final int maxAttempts;

    private final int banDuration;

    /**
     * Tracker used whenever the cluster cannot be reached. A Redis outage must
     * degrade to replica-local banning, never to no banning.
     */
    private final AuthenticationFailureTracker fallback;

    /**
     * Creates a new RedisAuthenticationFailureTracker.
     *
     * @param redisUri
     *     The URI of the Redis service holding cluster state.
     *
     * @param maxAttempts
     *     The number of failures after which an address is blocked.
     *
     * @param banDuration
     *     The number of seconds an address remains blocked.
     *
     * @param maxAddresses
     *     The maximum number of addresses the fallback tracker will hold. This
     *     bounds the fallback only; the cluster counter has no such bound.
     */
    public RedisAuthenticationFailureTracker(String redisUri, int maxAttempts,
            int banDuration, long maxAddresses) {
        this.clusterStore = new RedisClusterStore(redisUri, UNUSED_STALE_WINDOW_MS,
                "auth-ban");
        this.maxAttempts = maxAttempts;
        this.banDuration = banDuration;
        this.fallback = new InMemoryAuthenticationFailureTracker(maxAttempts,
                banDuration, maxAddresses);
    }

    /**
     * Blocks the request if the given count has reached the limit.
     *
     * @param address
     *     The address the request originated from.
     *
     * @param failures
     *     The number of failures recorded for that address.
     *
     * @throws GuacamoleException
     *     If the address has reached the configured limit.
     */
    private void blockIfBanned(String address, int failures)
            throws GuacamoleException {

        if (failures < maxAttempts)
            return;

        logger.warn("Blocking authentication attempt from address \"{}\" due to "
                + "number of authentication failures across the cluster.", address);
        throw new TranslatableGuacamoleClientTooManyException("Too "
                + "many failed authentication attempts.",
                "LOGIN.ERROR_TOO_MANY_ATTEMPTS");

    }

    /**
     * Applies the cluster-wide rules to one authentication request.
     *
     * @param credentials
     *     The credentials of the request.
     *
     * @param failed
     *     Whether the request is known to have failed.
     *
     * @throws GuacamoleException
     *     If the request is blocked by brute-force prevention rules.
     */
    private void notifyAuthenticationStatus(Credentials credentials, boolean failed)
            throws GuacamoleException {

        // Ignore requests that do not contain explicit parameters of any kind
        if (credentials.isEmpty())
            return;

        String address = credentials.getRemoteAddress();
        if (address == null)
            throw new GuacamoleServerException("Source address cannot be determined.");

        int failures;
        if (failed)
            failures = clusterStore.recordAuthenticationFailure(address, banDuration);
        else
            failures = clusterStore.getAuthenticationFailures(address);

        // The cluster could not answer: fall back to this replica's own view
        // rather than allowing every attempt through
        if (failures < 0) {
            if (failed)
                fallback.notifyAuthenticationFailed(credentials);
            else
                fallback.notifyAuthenticationRequestReceived(credentials);
            return;
        }

        if (failed)
            logger.info("Authentication has failed for address \"{}\" (current "
                    + "total failures across the cluster: {}/{}).", address,
                    failures, maxAttempts);

        blockIfBanned(address, failures);

    }

    @Override
    public void notifyAuthenticationRequestReceived(Credentials credentials)
            throws GuacamoleException {
        notifyAuthenticationStatus(credentials, false);
    }

    @Override
    public void notifyAuthenticationSuccess(Credentials credentials)
            throws GuacamoleException {
        notifyAuthenticationStatus(credentials, false);
    }

    @Override
    public void notifyAuthenticationFailed(Credentials credentials)
            throws GuacamoleException {
        notifyAuthenticationStatus(credentials, true);
    }

}
```

- [ ] **Step 3: Verify the extension builds**

```bash
mvn -q -pl extensions/guacamole-auth-ban -am install -DskipTests
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Confirm the dependency really lands in the jar**

```bash
unzip -l extensions/guacamole-auth-ban/target/guacamole-auth-ban-1.6.1.jar | grep -E "lettuce|guacamole-cluster"
```

Expected: both nested jars listed. **Do not skip this.** If they are absent the
extension compiles and then fails at runtime with `NoClassDefFoundError`, which
surfaces only as "the relevant authentication provider could not be loaded" — the
same symptom that cost a full build/deploy cycle in P2.

- [ ] **Step 5: Commit**

```bash
git add extensions/guacamole-auth-ban
git commit -m "GUACAMOLE-283: Add a cluster-wide authentication failure tracker

Counts failures in Redis so that N replicas no longer grant an attacker N
times max-attempts before banning.

Success does not clear the count, matching the in-memory tracker, where
success and a received request take the identical path and only the
passage of time removes a count. Clearing on success would also let an
attacker who guesses one valid account reset their own address.

A Redis outage falls back to the in-memory tracker, degrading to
replica-local banning rather than to no banning."
```

---

## Task 3: Select the tracker when clustering is enabled

**Files:**
- Modify: `extensions/guacamole-auth-ban/src/main/java/org/apache/guacamole/auth/ban/BanningAuthenticationListener.java:126-162`

**Interfaces:**
- Consumes: `RedisAuthenticationFailureTracker` (Task 2), `ClusterModule.isEnabled(Environment)`, `ClusterProperties.CLUSTER_REDIS_URI`.
- Produces: nothing consumed by a later task.

- [ ] **Step 1: Replace the construction site**

The existing `else` branch at `BanningAuthenticationListener.java:155` becomes:

```java
        else if (isClusterEnabled(environment)) {
            String redisUri = environment.getProperty(ClusterProperties.CLUSTER_REDIS_URI,
                    "redis://localhost:6379");
            this.tracker = new RedisAuthenticationFailureTracker(redisUri,
                    maxAttempts, banDuration, maxAddresses);
            logger.info("Addresses will be automatically banned for {} seconds "
                    + "after {} failed authentication attempts, counted across "
                    + "the whole cluster. The \"{}\" property no longer bounds "
                    + "the number of tracked addresses -- it applies only to "
                    + "the fallback used while Redis is unreachable.",
                    banDuration, maxAttempts, MAX_ADDRESSES.getName());
        }
        else {
            this.tracker = new InMemoryAuthenticationFailureTracker(maxAttempts, banDuration, maxAddresses);
            logger.info("Addresses will be automatically banned for {} "
                    + "seconds after {} failed authentication attempts. Up "
                    + "to {} unique addresses will be tracked/banned at any "
                    + "given time.", banDuration, maxAttempts, maxAddresses);
        }
```

and a helper beside the constructor:

```java
    /**
     * Returns whether clustering is enabled, treating an unreadable property as
     * disabled. A misconfigured cluster property must not stop this extension
     * from banning anything.
     *
     * @param environment
     *     The environment to read the property from.
     *
     * @return
     *     true if cluster coordination is enabled, false otherwise.
     */
    private boolean isClusterEnabled(Environment environment) {

        try {
            return ClusterModule.isEnabled(environment);
        }

        catch (GuacamoleException e) {
            logger.warn("Unable to determine whether clustering is enabled. "
                    + "Authentication failures will be tracked per-replica.", e);
            return false;
        }

    }
```

Add the imports `org.apache.guacamole.cluster.ClusterModule`,
`org.apache.guacamole.cluster.ClusterProperties`, and
`org.apache.guacamole.GuacamoleException` if not already present.

**The ordering of the branches matters.** `maxAttempts <= 0`, `banDuration <= 0` and
`maxAddresses <= 0` must keep disabling banning entirely before the cluster branch is
reached, so that an operator who has turned banning off does not have it turned back
on by enabling clustering.

- [ ] **Step 2: Verify the extension builds**

```bash
mvn -q -pl extensions/guacamole-auth-ban -am install -DskipTests
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Run the full suite**

```bash
mvn test -rf :guacamole-cluster
```

Expected: BUILD SUCCESS. The root `mvn test` fails in the `guacamole` webapp module
on `generate-license-files` for reasons unrelated to this work — reproduced on `main`
with none of this work applied — so the reactor is resumed past it.

- [ ] **Step 4: Commit**

```bash
git add extensions/guacamole-auth-ban
git commit -m "GUACAMOLE-283: Track authentication failures cluster-wide when clustering is on

Selected at the single construction site, after the three branches that
disable banning entirely, so enabling clustering never re-enables banning
an operator has switched off.

Startup now states that max-addresses no longer bounds the number of
tracked addresses, since Redis has no equivalent of Caffeine's
maximumSize and memory instead scales with the number of distinct
failing addresses within one ban window."
```

---

## Task 4: Verify on the live two-replica deployment

The unit tests prove the counter. Only a deployment proves the thing this phase
exists for: that failures against *different replicas* are counted together.

The devqa stack is running in namespace `remote-access` with two web-app replicas and
a `pytester` pod carrying Python. **Drive the API from inside the cluster** —
port-forwards died repeatedly under load in earlier phases and produced misleading
readings.

**Files:** none. Produces `docs/superpowers/deploy/README.md` section 10.

- [ ] **Step 1: Build, push and deploy**

```bash
docker build -t guacamole-cluster:1.6.1-p4a --build-arg MAVEN_ARGUMENTS=-DskipTests .
docker tag guacamole-cluster:1.6.1-p4a 738928754249.dkr.ecr.us-east-2.amazonaws.com/guacamole/guacamole-cluster:1.6.1-p4a
aws ecr get-login-password --region us-east-2 --profile tiba-dev-staging | docker login --username AWS --password-stdin 738928754249.dkr.ecr.us-east-2.amazonaws.com
docker push 738928754249.dkr.ecr.us-east-2.amazonaws.com/guacamole/guacamole-cluster:1.6.1-p4a
kubectl --context devqa -n remote-access set image deploy/guacamole guacamole=738928754249.dkr.ecr.us-east-2.amazonaws.com/guacamole/guacamole-cluster:1.6.1-p4a
kubectl --context devqa -n remote-access rollout status deploy/guacamole
```

If the build fails with "no space left on device", run `docker builder prune -af`.

- [ ] **Step 2: Lower the threshold so the test is short**

```bash
kubectl --context devqa -n remote-access set env deploy/guacamole BAN_MAX_INVALID_ATTEMPTS=4 BAN_ADDRESS_DURATION=120
kubectl --context devqa -n remote-access rollout status deploy/guacamole
```

Confirm the startup line names the cluster:

```bash
kubectl --context devqa -n remote-access logs deploy/guacamole | grep "counted across"
```

- [ ] **Step 3: Split failed logins across both replicas**

Two failures against A, then two against B, from the same source address. With
`BAN_MAX_INVALID_ATTEMPTS=4` the fourth must be the one that trips the ban, and the fifth
request — against **either** replica — must be refused.

```bash
read A B <<< $(kubectl --context devqa -n remote-access get pods -l app=guacamole \
    --no-headers -o wide | awk '$3=="Running"{print $6}' | tr '\n' ' ')

kubectl --context devqa -n remote-access exec pytester -- python3 -u -c "
import sys, urllib.error, urllib.parse, urllib.request
A, B = sys.argv[1], sys.argv[2]
def attempt(host):
    body = urllib.parse.urlencode({'username': 'guacadmin', 'password': 'wrong'}).encode()
    try:
        urllib.request.urlopen('http://%s:8080/api/tokens' % host, data=body, timeout=15)
        return 200
    except urllib.error.HTTPError as e:
        return e.code
for i, host in enumerate([A, A, B, B, B, A], start=1):
    print('attempt %d against %s -> %d' % (i, 'A' if host == A else 'B', attempt(host)))
" $A $B
```

**Expected: 403 for the first four, then 429 for attempts 5 and 6**, with attempt 5
landing on B and attempt 6 on A. Before this phase every replica counted separately,
so four attempts split 2/2 would have left both replicas at 2 and banned nothing.

Record the actual output, including the status codes, in Step 6.

- [ ] **Step 4: Confirm the counter in Redis**

```bash
ADDR=$(kubectl --context devqa -n remote-access get pod pytester -o jsonpath='{.status.podIP}')
kubectl --context devqa -n remote-access exec deploy/redis -- redis-cli --scan --pattern 'guac:authfail:*'
kubectl --context devqa -n remote-access exec deploy/redis -- redis-cli get "guac:authfail:$ADDR"
kubectl --context devqa -n remote-access exec deploy/redis -- redis-cli ttl "guac:authfail:$ADDR"
```

Expected: one key for the tester's address, a value at or above 4, and a TTL at or
below 120 that was re-armed by the most recent failure.

- [ ] **Step 5: Confirm a successful login does not clear the ban**

Wait for the TTL to expire, authenticate correctly once, then fail twice and check
the counter is 2 rather than reset. This is the spec deviation and it should be shown
working rather than asserted.

- [ ] **Step 6: Confirm the disabled path is unchanged**

```bash
kubectl --context devqa -n remote-access set env deploy/guacamole CLUSTER_ENABLED=false
kubectl --context devqa -n remote-access rollout status deploy/guacamole
```

Repeat Step 3. **Expected: no ban** — 2 failures on each replica leaves each below the
threshold of 4, which is exactly the upstream weakness this phase removes and is the
clearest possible demonstration that the fix is the cluster counter and not something
else. Then restore `CLUSTER_ENABLED=true`.

- [ ] **Step 7: Document the measurements**

Add section 10 to `docs/superpowers/deploy/README.md` in the style of sections 4-9,
recording what was actually observed, including anything that failed.

- [ ] **Step 8: Commit**

```bash
git add docs/superpowers/deploy/README.md
git commit -m "GUACAMOLE-283: Record cluster-wide ban verification"
```

---

## What P4a does NOT do

- **Auth tokens are still replica-local.** A replica death still forces re-login. That
  is P4b, which carries the token store, the rehydration SPI and cluster-wide logout.
- **`max-addresses` no longer bounds anything while clustering is on.** Stated in §5.7
  and logged at startup. The outer defence is ingress rate limiting.
- **Ban state is not shared with the in-memory fallback.** Failures counted during a
  Redis outage live only on the replica that saw them, and are not merged back into
  the cluster counter when Redis returns. The counts diverge for at most one ban
  window.
- **There is no unban API.** As upstream, a ban ends when its window expires. An
  operator can clear one by hand with `DEL guac:authfail:{address}`.
