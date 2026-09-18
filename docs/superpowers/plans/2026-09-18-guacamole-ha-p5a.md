# Guacamole HA Clustering — P5a Implementation Plan (security hardening)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the clustered deployment refuse to run insecurely, say so loudly when it cannot, and stop leaking credentials and resources — so that the controls P4b made necessary are enforced by the software rather than by whoever writes the manifest.

**Architecture:** Security work first, operability second. P5 is split: this plan (P5a) covers the threat model, fail-safe configuration, a startup self-check of the Redis permission surface, credential hygiene, resource lifecycle, supply-chain gates and live abuse-case verification. P5b covers Prometheus metrics, Helm packaging and the Sentinel decision, and depends on nothing here except the Helm chart inheriting P5a's secure defaults.

**Tech Stack:** Java 8, Maven, Lettuce 6.3.2.RELEASE, Redis 7.x, JUnit 5.14.4, Testcontainers 1.21.3, GitHub Actions, Docker.

**Spec:** `docs/superpowers/specs/2026-09-06-guacamole-ha-clustering-design.md` (§5.2, §5.7, §6) and `docs/superpowers/HA-CLUSTERING-STATUS.md`

**Prior phases:** P0/P1, P2, P3a, P3b, P4a, P4b — all merged to `main` as of 2026-09-18.

## Global Constraints

- **Java 8 source and target.** `pom.xml:259-260`. No `var`, no records, no `List.of`.
- **Apache RAT is active.** Every new file needs the ASF licence header. `.md` is excluded.
- **Commit convention is `GUACAMOLE-283: Capitalized summary`.**
- **`cluster-enabled` defaults to `false`.** Nothing in this plan may change behaviour when clustering is off.
- **Never build a Redis key inline.** All keys come from `ClusterKeys`.
- **A Redis outage degrades, never blocks** (spec §6.1). Nothing here may turn an outage into an outage for users — the new failures introduced are *configuration* failures, at startup, not runtime ones.
- **Existing suite must stay green:** `mvn test -Dexec.skip=true` (153 tests as of P4b). `-Dexec.skip=true` is required: `generate-license-files` fails on a stale npm license cache, reproducibly, on `main` too.

---

## Why security comes before metrics and Helm

P4b changed what Redis holds. Before it, the keyspace was routing and counting
state: losing it degraded the cluster and leaked little. After it, the keyspace
holds session identity, and a token hash plus a username is enough to learn who
is logged in and from where.

Every control that makes that safe is currently **advisory**. Authentication,
TLS, the ACL, the NetworkPolicy — all of them live in a YAML file and a README
section. A deployment that omits every one of them starts cleanly, works
perfectly, and says nothing. That is the gap this plan closes, and it is worth
closing before adding a metrics endpoint that is one more thing to secure.

The session that produced P4b also demonstrated the failure mode twice:

- A password reached the pod log on the first deployment that had one, because
  the redaction existed in the source but not in the running image.
- An ACL missing one command disabled clustering **silently** — every seat
  acquisition failed, P2's degradation caught it, and logins, connections and
  sessions all kept working while the cluster quietly stopped being a cluster.

Both are configuration mistakes that the software could have refused or
reported. Tasks 2 and 3 make it do so.

---

## File Structure

**New**

| File | Responsibility |
|---|---|
| `docs/superpowers/specs/2026-09-18-guacamole-ha-threat-model.md` | Assets, trust boundaries, the threats considered, and which control answers each |
| `guacamole-ext/src/main/java/org/apache/guacamole/cluster/ClusterSecurityPolicy.java` | Decides whether a given Redis URI is acceptable, and refuses at startup if not |
| `guacamole-ext/src/test/java/org/apache/guacamole/cluster/ClusterSecurityPolicyTest.java` | The refusal rules |
| `guacamole-ext/src/test/java/org/apache/guacamole/cluster/redis/SelfCheckTest.java` | Self-check against a real Redis, with and without a restrictive ACL |
| `.github/workflows/security.yml` | Dependency and image scanning, SBOM |

**Modified**

| File | Change |
|---|---|
| `.../cluster/ClusterProperties.java` | `cluster-allow-insecure-redis` |
| `.../cluster/ClusterKeys.java` + `ClusterStore` + `NoOpClusterStore` + `RedisClusterStore` | `selfCheck()` and its probe key |
| `.../auth/jdbc/cluster/ClusterModule.java` | Enforce the policy; run the self-check; shut the store down |
| `.../rest/auth/AuthenticationService.java` | Enforce the policy at the webapp's own store |
| `.../GuacamoleServletContextListener.java` | Release cluster resources on undeploy |
| `docs/superpowers/deploy/README.md` | Section 12: the abuse cases and their results |

---

## Task 1: Write the threat model, and derive the controls from it

No code. This exists because the remaining tasks should implement decisions that
were reasoned about once, in one place, rather than defended individually in six
commit messages. It is also the artefact a reviewer needs in order to judge
whether the controls are the right ones.

**Files:**
- Create: `docs/superpowers/specs/2026-09-18-guacamole-ha-threat-model.md`

**Interfaces:**
- Consumes: nothing.
- Produces: a numbered control list, `C1` through `C7`, referenced by every later task in this plan.

- [ ] **Step 1: Enumerate the assets and where they live**

Write the section from what the code actually stores, not from memory. The
keyspace is documented in the design spec §3.2 and the token record in
`RedisClusterStore.putToken`. At minimum:

| Asset | Where | Why it matters |
|---|---|---|
| Session identity (`guac:token:*`) | Redis | Username + address + provider. Reveals who is logged in and from where. |
| Share keys (`guac:share:*`) | Redis | A share key is a bearer credential for a live desktop session. |
| Tunnel registry and indexes (`guac:tunnel:*`, `guac:idx:*`) | Redis | Reveals which users hold which connections, and to which hosts. |
| Seat counters (`guac:seat:*`) | Redis | Integrity only: corrupting them defeats concurrency limits. |
| Auth failure counters (`guac:authfail:*`) | Redis | Integrity only: clearing them defeats brute-force banning. |
| The Redis credential | Env var, Secret, process memory | Grants all of the above. |

- [ ] **Step 2: State the trust boundaries**

Three, and they are what the controls hang off:

1. **Guacamole replica → Redis.** Crosses the pod boundary. Unauthenticated and
   unencrypted by default today.
2. **Anything else in the namespace → Redis.** The NetworkPolicy is the only
   control, and it is optional.
3. **Operator → logs.** Anyone who can read pod logs or a log aggregator. This
   boundary is why redaction exists, and it is wider than the set who can read
   the Secret.

- [ ] **Step 3: Write the threats and the control that answers each**

One row per threat. Keep it to what is real for this deployment — a table of
generic STRIDE categories helps nobody. The controls below are the ones the rest
of this plan implements, and the numbering is load-bearing:

| # | Threat | Control | Task |
|---|---|---|---|
| C1 | Token store deployed against an unauthenticated or unencrypted Redis | Refuse to start unless the URI is authenticated, or insecure operation is explicitly opted into | 2 |
| C2 | An over-tight or wrong ACL disables clustering silently | Exercise the permission surface at startup and report exactly what is denied | 3 |
| C3 | Credentials reach logs or exception messages | Redact at every site that prints a URI, and verify the client library does the same | 4 |
| C4 | Credentials and connections outlive a redeploy | Release cluster resources on undeploy | 5 |
| C5 | A vulnerable dependency ships unnoticed | Scan dependencies and the image in CI, with a failing gate | 6 |
| C6 | Anything in the namespace can read the keyspace | NetworkPolicy, already written in `deploy/redis-hardened.yaml`; verified as an abuse case | 7 |
| C7 | An operator cannot tell whether the controls are active | Startup states the security posture in one line | 2 |

- [ ] **Step 4: Record what is accepted, and why**

A threat model that lists no accepted risk is not finished. At least these:

- **Session identity is readable by anyone holding the Redis credential.** Not
  encrypted at rest. Accepted: encrypting it would require a key the replicas
  share, which moves the problem rather than solving it, and the data is
  identity rather than secrets.
- **A tunnel already opened from a revoked share key survives on another
  replica** until its session ends (P3b). Accepted, and recorded there.
- **`ban-max-addresses` no longer bounds memory** when clustering is on (P4a).
  Accepted, with ingress rate limiting as the outer defence.
- **A remote kill during a Redis outage returns 404 rather than the designed
  timeout** (P3a). Accepted: it never reports success falsely.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-09-18-guacamole-ha-threat-model.md
git commit -m "GUACAMOLE-283: Add a threat model for the clustered deployment

Written before the controls rather than after, so that the controls in
P5a can be judged against a stated model instead of defended one commit
at a time. Names seven controls, C1 through C7, and records the risks
being accepted rather than mitigated."
```

---

## Task 2: Refuse an insecure Redis, and say so either way

Implements **C1** and **C7**.

**Files:**
- Create: `guacamole-ext/src/main/java/org/apache/guacamole/cluster/ClusterSecurityPolicy.java`
- Modify: `guacamole-ext/src/main/java/org/apache/guacamole/cluster/ClusterProperties.java`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/cluster/ClusterModule.java`
- Modify: `guacamole/src/main/java/org/apache/guacamole/rest/auth/AuthenticationService.java`
- Test: `guacamole-ext/src/test/java/org/apache/guacamole/cluster/ClusterSecurityPolicyTest.java`

**Interfaces:**
- Consumes: `RedisUris.redact` (P4b), `ClusterProperties.CLUSTER_REDIS_URI`.
- Produces:
  - `ClusterProperties.CLUSTER_ALLOW_INSECURE_REDIS` — `BooleanGuacamoleProperty`, name `cluster-allow-insecure-redis`
  - `ClusterSecurityPolicy.check(String uri, boolean allowInsecure)` returning `String` (a one-line posture description) and throwing `GuacamoleServerException` when the URI is unacceptable

**The default is refuse.** An operator who wants the devqa arrangement sets
`cluster-allow-insecure-redis=true` and gets a warning on every start. An
operator who forgets gets a startup failure naming the property, rather than a
cluster that works and quietly exposes session identity.

**This is the one place in the programme where failing closed is correct.**
Everywhere else a Redis problem degrades, because the alternative is denying
service to users over a dependency they do not control. Here the failure is a
*configuration* error, visible only at startup, and the deployment that results
from proceeding is one nobody would approve if asked.

- [ ] **Step 1: Write the failing test** (ASF header first)

```java
package org.apache.guacamole.cluster;

import org.apache.guacamole.GuacamoleException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClusterSecurityPolicyTest {

    @Test
    public void anAuthenticatedEncryptedUriIsAccepted() throws Exception {
        String posture = ClusterSecurityPolicy.check(
                "rediss://guacamole:secret@redis:6379", false);
        assertTrue(posture.contains("authenticated"));
        assertTrue(posture.contains("encrypted"));
    }

    @Test
    public void anUnauthenticatedUriIsRefused() {
        GuacamoleException e = assertThrows(GuacamoleException.class,
                () -> ClusterSecurityPolicy.check("redis://redis:6379", false));
        assertTrue(e.getMessage().contains("cluster-allow-insecure-redis"),
                "the refusal must name the property that permits it");
    }

    @Test
    public void anUnencryptedButAuthenticatedUriIsRefused() {

        // Authentication without TLS sends the password in clear text, and the
        // session identity behind it in clear text too
        assertThrows(GuacamoleException.class,
                () -> ClusterSecurityPolicy.check(
                        "redis://guacamole:secret@redis:6379", false));

    }

    @Test
    public void insecureIsAllowedWhenExplicitlyOptedInto() throws Exception {
        String posture = ClusterSecurityPolicy.check("redis://redis:6379", true);
        assertTrue(posture.contains("UNAUTHENTICATED"));
        assertFalse(posture.contains("secret"));
    }

    @Test
    public void thePostureNeverContainsTheCredential() throws Exception {

        String posture = ClusterSecurityPolicy.check(
                "rediss://guacamole:hunter2@redis:6379", false);

        assertFalse(posture.contains("hunter2"),
                "the posture line is logged, so it must be redacted");

    }

    @Test
    public void anUnparseableUriIsRefusedRatherThanAssumedSafe() {
        assertThrows(GuacamoleException.class,
                () -> ClusterSecurityPolicy.check("not-a-uri", false));
    }

}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl guacamole-ext test -Dtest=ClusterSecurityPolicyTest -Dexec.skip=true
```

Expected: FAIL — `ClusterSecurityPolicy` does not exist.

- [ ] **Step 3: Add the property**

In `ClusterProperties.java`, beside the existing properties:

```java
    /**
     * Whether cluster state may be held in a Redis which is unauthenticated,
     * unencrypted, or both. Defaults to false.
     *
     * Since P4b the keyspace holds session identity, so an open Redis exposes
     * who is logged in and from where to anything that can reach the port.
     * Setting this to true is appropriate for a private test namespace and
     * nowhere else.
     */
    public static final BooleanGuacamoleProperty CLUSTER_ALLOW_INSECURE_REDIS =
            new BooleanGuacamoleProperty() {

        @Override
        public String getName() {
            return "cluster-allow-insecure-redis";
        }

    };
```

- [ ] **Step 4: Write the policy** (ASF header first)

```java
package org.apache.guacamole.cluster;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether cluster state may be held in the Redis named by a given URI.
 */
public class ClusterSecurityPolicy {

    private static final Logger logger =
            LoggerFactory.getLogger(ClusterSecurityPolicy.class);

    private ClusterSecurityPolicy() {}

    /**
     * Checks the given Redis URI, returning a one-line description of the
     * resulting security posture.
     *
     * @param uri
     *     The Redis URI cluster state will be held in.
     *
     * @param allowInsecure
     *     Whether an unauthenticated or unencrypted Redis has been explicitly
     *     opted into.
     *
     * @return
     *     A redacted, one-line description of the security posture, suitable
     *     for logging.
     *
     * @throws GuacamoleException
     *     If the URI is unacceptable and insecure operation was not opted into.
     */
    public static String check(String uri, boolean allowInsecure)
            throws GuacamoleException {

        boolean encrypted = uri != null && uri.startsWith("rediss://");
        boolean authenticated = hasCredentials(uri);
        boolean parseable = uri != null && uri.contains("://");

        if (parseable && encrypted && authenticated)
            return "Cluster state is held in an authenticated, encrypted Redis ("
                    + RedisUris.redact(uri) + ").";

        if (!allowInsecure)
            throw new GuacamoleServerException("Refusing to hold cluster state "
                    + "in \"" + RedisUris.redact(uri) + "\": it is "
                    + describeShortcoming(parseable, encrypted, authenticated)
                    + ". Since session identity is held in this keyspace, an "
                    + "open Redis exposes who is logged in and from where. Use "
                    + "a \"rediss://user:password@host\" URI, or set "
                    + "\"cluster-allow-insecure-redis\" to true to accept this "
                    + "deliberately.");

        logger.warn("Cluster state is held in an INSECURE Redis ({}): it is {}. "
                + "Session identity in this keyspace is readable by anything "
                + "that can reach the port. This was permitted by "
                + "\"cluster-allow-insecure-redis\".",
                RedisUris.redact(uri),
                describeShortcoming(parseable, encrypted, authenticated));

        return "Cluster state is held in an UNAUTHENTICATED or unencrypted Redis ("
                + RedisUris.redact(uri) + ").";

    }

    /**
     * Returns whether the given URI carries credentials.
     *
     * @param uri
     *     The URI to examine, which may be null.
     *
     * @return
     *     true if the URI carries credentials, false otherwise.
     */
    private static boolean hasCredentials(String uri) {

        if (uri == null)
            return false;

        int schemeEnd = uri.indexOf("://");
        if (schemeEnd < 0)
            return false;

        return uri.indexOf('@', schemeEnd + 3) >= 0;

    }

    /**
     * Returns a human description of what is wrong with a URI.
     *
     * @param parseable
     *     Whether the URI could be understood at all.
     *
     * @param encrypted
     *     Whether the URI selects TLS.
     *
     * @param authenticated
     *     Whether the URI carries credentials.
     *
     * @return
     *     A description suitable for inclusion in a log line or an exception.
     */
    private static String describeShortcoming(boolean parseable,
            boolean encrypted, boolean authenticated) {

        if (!parseable)
            return "not a URI this policy can evaluate";

        if (!authenticated && !encrypted)
            return "neither authenticated nor encrypted";

        if (!authenticated)
            return "not authenticated";

        return "not encrypted";

    }

}
```

- [ ] **Step 5: Run it to verify it passes**

```bash
mvn -q -pl guacamole-ext test -Dtest=ClusterSecurityPolicyTest -Dexec.skip=true
```

Expected: PASS, 6 tests.

- [ ] **Step 6: Enforce it at both store construction sites**

There are exactly two, because the webapp and the JDBC extension each build
their own store. In `ClusterModule`, replace the existing log line:

```java
            boolean allowInsecure = environment.getProperty(
                    ClusterProperties.CLUSTER_ALLOW_INSECURE_REDIS, false);

            logger.info("Cluster coordination is ENABLED. {}",
                    ClusterSecurityPolicy.check(uri, allowInsecure));
```

and the same in `AuthenticationService.createClusterStore()`, replacing its
`logger.info("Session tokens will be shared ...")` call. `ClusterModule.configure()`
already catches `GuacamoleException` and calls `addError`, which fails the
injector — that is the intended outcome. `createClusterStore()` currently
catches and degrades to a no-op store; change it to rethrow as an
`IllegalStateException` so that a refused configuration stops the webapp rather
than silently disabling the token store while the extension fails separately.

- [ ] **Step 7: Verify the whole suite**

```bash
mvn test -Dexec.skip=true
```

Expected: BUILD SUCCESS, 159 tests.

- [ ] **Step 8: Commit**

```bash
git add guacamole-ext extensions guacamole
git commit -m "GUACAMOLE-283: Refuse to hold cluster state in an insecure Redis

Since P4b the keyspace holds session identity, so an open Redis exposes
who is logged in and from where. Every control that prevented this lived
in a YAML file, and a deployment that omitted all of them started
cleanly and said nothing.

Startup now refuses unless the URI is both authenticated and encrypted,
or cluster-allow-insecure-redis is set, which warns on every start. This
is the one place in the programme where failing closed is right: the
failure is a configuration error, caught at startup, not a dependency
outage that would deny service to users."
```

---

## Task 3: Turn a silent ACL failure into a loud one

Implements **C2**.

**Files:**
- Modify: `guacamole-ext/src/main/java/org/apache/guacamole/cluster/ClusterKeys.java`
- Modify: `guacamole-ext/src/main/java/org/apache/guacamole/cluster/ClusterStore.java`
- Modify: `guacamole-ext/src/main/java/org/apache/guacamole/cluster/NoOpClusterStore.java`
- Modify: `guacamole-ext/src/main/java/org/apache/guacamole/cluster/redis/RedisClusterStore.java`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/cluster/ClusterModule.java`
- Test: `guacamole-ext/src/test/java/org/apache/guacamole/cluster/redis/SelfCheckTest.java`

**Interfaces:**
- Consumes: `ClusterKeys`, the store's existing private `commands()`.
- Produces:
  - `ClusterKeys.selfCheckProbe()` returning `String`
  - `ClusterStore.selfCheck()` returning `List<String>` — one description per denied or failed operation, empty when the permission surface is complete

**The check lives on the store, not beside it.** A separate class would need
access to the Lettuce commands, which are private for good reason: nothing
outside the store should speak Redis directly. Adding one method to the
interface keeps that boundary and gives `NoOpClusterStore` somewhere honest to
return "nothing to check".

**This control exists because the failure it catches actually happened.** An ACL
missing `+time` broke every seat acquisition, and P2\'s degradation absorbed it
so completely that logins, connections and sessions all still worked. The
cluster had stopped coordinating and nothing said so. Testing the ACL with
`redis-cli` did not find it either: `TIME` is issued from inside a Lua script and
from a code path whose failure is caught and degraded.

- [ ] **Step 1: Write the failing test** (ASF header first)

```java
package org.apache.guacamole.cluster.redis;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SelfCheckTest {

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
    public void anUnrestrictedRedisPassesEveryCheck() {
        List<String> failures = store.selfCheck();
        assertTrue(failures.isEmpty(), "unexpected failures: " + failures);
    }

    @Test
    public void theCheckLeavesNothingBehind() {

        store.selfCheck();

        // A self-check runs on every replica start. If it left its probe key
        // behind, every restart would add one more key nothing owns.
        assertEquals(0L, store.probeKeyCountForTesting());

    }

    @Test
    public void anUnreachableRedisReportsEveryProbeRatherThanThrowing() {

        RedisClusterStore unreachable = new RedisClusterStore(
                "redis://127.0.0.1:1", STALE_WINDOW_MS, "node-1");

        try {

            List<String> failures = unreachable.selfCheck();

            // Startup must survive a Redis that is simply down: the check
            // reports, it does not throw
            assertEquals(7, failures.size(), "every probe should be reported");

        }

        finally {
            unreachable.shutdown();
        }

    }

}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl guacamole-ext test -Dtest=SelfCheckTest -Dexec.skip=true
```

Expected: FAIL — `selfCheck()` does not exist.

- [ ] **Step 3: Add the probe key**

In `ClusterKeys.java`, beside the other key builders:

```java
    public static String selfCheckProbe() {
        return "guac:selfcheck";
    }
```

It sits under `guac:` deliberately, so that an ACL scoped to `~guac:*` covers it
without a special case.

- [ ] **Step 4: Declare the operation**

On `ClusterStore`:

```java
    /**
     * Exercises every Redis operation this store depends on, returning one
     * description per operation that failed.
     *
     * Intended to be run once at startup. A restrictive ACL otherwise surfaces
     * as a feature that quietly stopped working, because the failure of any
     * single operation is caught and degraded by design.
     *
     * @return
     *     A description of each failed operation, empty when every operation
     *     succeeded.
     */
    List<String> selfCheck();
```

and on `NoOpClusterStore`:

```java
    @Override
    public List<String> selfCheck() {
        // Nothing to check: no cluster, no permissions, no failures
        return Collections.emptyList();
    }
```

- [ ] **Step 5: Implement it on the Redis store**

In `RedisClusterStore`, one probe per command family the store uses. Each catches
separately, so that one denied command does not hide the next:

```java
    @Override
    public List<String> selfCheck() {

        List<String> failures = new ArrayList<String>();
        String key = ClusterKeys.selfCheckProbe();

        // TIME is the one that has actually been missing in practice. It
        // belongs to Redis's @fast class, which none of the data-type classes
        // include, and both the seat script and countTunnels depend on it.
        try {
            commands().time();
        }
        catch (RedisException e) {
            failures.add("server clock (TIME): " + e.getMessage());
        }

        try {
            commands().set(key, "probe");
            commands().get(key);
        }
        catch (RedisException e) {
            failures.add("string read/write (SET, GET): " + e.getMessage());
        }

        try {
            commands().expire(key, 60);
            commands().ttl(key);
        }
        catch (RedisException e) {
            failures.add("expiry (EXPIRE, TTL): " + e.getMessage());
        }

        try {
            commands().hset(key + ":h", "field", "value");
            commands().hgetall(key + ":h");
        }
        catch (RedisException e) {
            failures.add("hash read/write (HSET, HGETALL): " + e.getMessage());
        }

        try {
            commands().zadd(key + ":z", 1.0, "member");
            commands().zcount(key + ":z", Range.create(0, 2));
            commands().zrem(key + ":z", "member");
        }
        catch (RedisException e) {
            failures.add("sorted sets (ZADD, ZCOUNT, ZREM): " + e.getMessage());
        }

        try {
            commands().publish(ClusterKeys.KILL_CHANNEL, "selfcheck");
        }
        catch (RedisException e) {
            failures.add("publish (PUBLISH): " + e.getMessage());
        }

        // Exercises SCRIPT LOAD and EVALSHA together, and the key pattern the
        // ACL applies to script keys
        try {
            acquireSeats.eval(commands(), new String[] { key + ":z" },
                    new String[] { "selfcheck", "1000", "1" });
        }
        catch (RedisException e) {
            failures.add("scripting (SCRIPT LOAD, EVALSHA): " + e.getMessage());
        }

        try {
            commands().del(key, key + ":h", key + ":z");
        }
        catch (RedisException e) {
            failures.add("delete (DEL): " + e.getMessage());
        }

        return failures;

    }

    /**
     * Returns how many of this check\'s probe keys remain. Intended only for
     * tests.
     *
     * @return
     *     The number of probe keys still present.
     */
    public long probeKeyCountForTesting() {
        String key = ClusterKeys.selfCheckProbe();
        return commands().exists(key, key + ":h", key + ":z");
    }
```

**Check `Range` and the `zcount` signature against Lettuce 6.3 before writing
this.** The rest of the store uses `zcount` already, so copy the call shape from
`countTunnels` rather than the sketch above — guessing a Lettuce signature is
the class of error that cost build cycles in P3a, P3b and P4b.

The eight probes produce seven failure descriptions when Redis is unreachable
plus one for `DEL`, which is why the test above expects 7 for the connection
failure case and must be adjusted to 8 if `DEL` is counted. **Run the test and
take the real number rather than trusting this paragraph.**

- [ ] **Step 6: Run it to verify it passes**

```bash
mvn -q -pl guacamole-ext test -Dtest=SelfCheckTest -Dexec.skip=true
```

Expected: PASS, 3 tests.

- [ ] **Step 7: Run it at startup, and say what failed**

In `ClusterModule`, immediately after the store is constructed and the security
policy has passed:

```java
                List<String> failures = store.selfCheck();
                if (!failures.isEmpty())
                    logger.error("Cluster coordination is enabled but Redis "
                            + "denied or failed {} of the operations it depends "
                            + "on: {}. Clustering will appear to work while "
                            + "silently falling back to per-replica behaviour. "
                            + "See deploy/redis-acl.md for the required "
                            + "permissions.", failures.size(), failures);
```

**Log at ERROR and continue, rather than refusing to start.** A permission
problem is recoverable by fixing the ACL without a redeploy, and refusing here
would turn a degraded cluster into an outage — the opposite of the design\'s
stance everywhere except configuration. The point is that it is impossible to
miss, not that it is fatal.

- [ ] **Step 8: Commit**

```bash
git add guacamole-ext extensions
git commit -m "GUACAMOLE-283: Report a restrictive Redis ACL at startup

An ACL missing one command disabled clustering silently: every seat
acquisition failed, the degradation designed for a Redis outage absorbed
it, and logins, connections and sessions all kept working while the
cluster stopped coordinating. Testing the ACL with redis-cli did not find
it either, because TIME is issued from inside a Lua script and from a
path whose failure is caught.

Startup now exercises each command family the store uses and logs at
ERROR naming exactly what was denied. It does not refuse to start: a
permission problem is fixable without a redeploy, and refusing would turn
a degraded cluster into an outage."
```

---

## Task 4: Keep credentials out of logs and exceptions

Implements **C3**. P4b redacted the two application log sites; this closes what
it did not cover.

**Files:**
- Test: `guacamole-ext/src/test/java/org/apache/guacamole/cluster/redis/CredentialLeakTest.java`
- Modify: `docs/superpowers/deploy/README.md`

**Interfaces:**
- Consumes: `RedisUris.redact` (P4b), `RedisClusterStore`.
- Produces: nothing consumed by a later task.

**The outstanding question from P4b.** The application no longer prints the URI.
Whether Lettuce does — in exception messages, or at DEBUG — was never checked,
because devqa's Redis had no password to leak. It has one now.

- [ ] **Step 1: Write the failing test** (ASF header first)

```java
package org.apache.guacamole.cluster.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class CredentialLeakTest {

    private static final String PASSWORD = "hunter2-not-in-any-message";

    @Test
    public void aConnectionFailureDoesNotNameThePassword() {

        // Port 1 is reserved and nothing listens there, so this fails to
        // connect without needing Docker
        RedisClusterStore store = new RedisClusterStore(
                "redis://guacamole:" + PASSWORD + "@127.0.0.1:1", 30000L, "node-1");

        try {

            // Any command forces the lazy connect, and therefore the failure
            store.getAuthenticationFailures("probe");

        }

        catch (RuntimeException e) {
            assertFalse(describe(e).contains(PASSWORD),
                    "the password reached an exception message: " + describe(e));
        }

        finally {
            store.shutdown();
        }

    }

    /**
     * Returns the full text of an exception and its causes.
     *
     * @param t
     *     The throwable to describe.
     *
     * @return
     *     Every message in the causal chain, concatenated.
     */
    private static String describe(Throwable t) {

        StringBuilder text = new StringBuilder();
        for (Throwable cause = t; cause != null; cause = cause.getCause())
            text.append(cause.toString()).append(' ');

        return text.toString();

    }

}
```

- [ ] **Step 2: Run it**

```bash
mvn -q -pl guacamole-ext test -Dtest=CredentialLeakTest -Dexec.skip=true
```

**This test may pass immediately.** That is a legitimate outcome and not a reason
to skip the task: it converts an unverified assumption into a regression guard.
If it fails, the store must catch and rewrap the exception with a redacted
message before it escapes — do that rather than weakening the test.

- [ ] **Step 3: Check what Lettuce logs at DEBUG against a real credential**

This cannot be asserted in a unit test, because it depends on the log
configuration of the deployment. Run it once, on devqa, which now has an
authenticated Redis:

```bash
kubectl --context devqa -n remote-access set env deploy/guacamole LOG_LEVEL=debug
kubectl --context devqa -n remote-access rollout status deploy/guacamole
PW=$(kubectl --context devqa -n remote-access get secret guacamole-redis \
        -o jsonpath='{.data.password}' | base64 -d)
kubectl --context devqa -n remote-access logs deploy/guacamole --since=5m \
    | grep -cF "$PW"
kubectl --context devqa -n remote-access set env deploy/guacamole LOG_LEVEL-
```

**Expected: 0.** If it is not zero, the finding is that DEBUG logging must never
be enabled against an authenticated Redis, and that belongs in the README in
bold rather than in a commit message.

- [ ] **Step 4: Record the result**

Add the count, and the command that produced it, to `deploy/README.md` section
11, replacing the paragraph that currently says the check is outstanding.

- [ ] **Step 5: Commit**

```bash
git add guacamole-ext docs/superpowers/deploy/README.md
git commit -m "GUACAMOLE-283: Verify credentials do not escape through the client

P4b redacted the two application log sites and left an open question:
whether Lettuce prints the URI in its own exception messages or at DEBUG.
There was no way to answer it then, because devqa's Redis had no password
to leak.

The unit test guards the exception path as a regression. The DEBUG
question is answered by measurement against the now-authenticated devqa
Redis, and the result is recorded rather than assumed."
```

---

## Task 5: Release cluster resources on undeploy

Implements **C4**.

**Files:**
- Modify: `guacamole/src/main/java/org/apache/guacamole/GuacamoleServletContextListener.java:282`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/cluster/ClusterModule.java`

**Interfaces:**
- Consumes: `ClusterStore.shutdown()`, `ClusterHeartbeat` (both exist).
- Produces: nothing consumed by a later task.

**Nothing calls `shutdown()` today.** `ClusterModule.configure()` starts the
heartbeat as a side effect of injector construction, the heartbeat thread is a
daemon, and the Lettuce client is never closed. A Tomcat redeploy therefore
leaks, per cycle: one Lettuce client and its Netty event loops from the
extension, one more from the webapp since P4b, and one heartbeat executor.

The security reading matters as much as the resource one: a leaked client holds
an authenticated connection and the credential that opened it, in the memory of
a webapp that is supposed to be gone.

- [ ] **Step 1: Hold a reference to what must be closed**

`ClusterModule` currently constructs the store and heartbeat inside
`configure()` and keeps no reference. Store both on the module, and expose a
`shutdown()` that closes the heartbeat first and the store second — closing the
store first would leave the heartbeat writing to a closed connection for up to
one interval.

- [ ] **Step 2: Call it from the servlet lifecycle**

`GuacamoleServletContextListener.contextDestroyed` (`:282`) already shuts the
`TokenSessionMap` down. Add the cluster teardown alongside it, for the webapp's
own store, and give the extension a path to its module's `shutdown()`.

**Verify how the extension is reachable from the listener before writing this.**
Extensions are loaded through `ExtensionModule` and their injectors are not
obviously reachable from `contextDestroyed`. If they are not, the honest
implementation is a JVM shutdown hook registered by `ClusterModule` itself, and
the limitation — a Tomcat redeploy without a JVM restart still leaks the
extension's client — should be recorded rather than papered over.

- [ ] **Step 3: Verify by redeploying twice**

There is no unit test for this; a leak is only observable across a container
lifecycle. Measure it:

```bash
kubectl --context devqa -n remote-access exec deploy/redis -- \
    redis-cli --user guacamole INFO clients | grep connected_clients
```

Record the count, restart the Guacamole deployment twice, and compare. Redis
counts every client connection, so a leak shows as a count that climbs and never
returns.

- [ ] **Step 4: Commit**

```bash
git add guacamole extensions
git commit -m "GUACAMOLE-283: Release cluster resources on undeploy

Nothing called shutdown(). The heartbeat thread is a daemon and the
Lettuce client was never closed, so each Tomcat redeploy leaked a client
and its Netty event loops -- two since P4b, because the web application
now has a store of its own.

A leaked client also holds an authenticated connection and the credential
that opened it, in the memory of a web application that is supposed to
have gone away."
```

---

## Task 6: Gate the supply chain in CI

Implements **C5**.

**Files:**
- Create: `.github/workflows/security.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed by a later task.

**CI today is one `docker build` and nothing else** — no dependency scanning, no
image scanning, no SBOM. This fork pulls in Lettuce, Netty and Reactor, none of
which the upstream project shipped, and it ships them inside an extension jar
where a scanner looking only at `pom.xml` would not see them.

- [ ] **Step 1: Scan dependencies on pull requests**

Add a job running OWASP Dependency-Check against the Maven reactor, failing on
CVSS 7 or above, with any suppression carrying a written justification and an
expiry date in `.github/dependency-check-suppressions.xml`. A suppression without
an expiry is a permanent silent acceptance, which is the failure mode this job
exists to prevent.

- [ ] **Step 2: Scan the built image**

The nested-jar packaging means image scanning is not redundant with dependency
scanning: the extension jars carry copies of libraries that a reactor-level scan
attributes to a different module. Add a Trivy scan of the image the build already
produces, failing on HIGH and CRITICAL.

- [ ] **Step 3: Produce an SBOM and attach it to the build**

CycloneDX, generated from the reactor, uploaded as a workflow artefact. The value
is answering "are we affected" on the day a CVE lands, without rebuilding
anything.

- [ ] **Step 4: Pin the base image by digest**

The `Dockerfile` pulls tags. Pin the digest so that a rebuild of an older commit
produces the same image, and record the tag it corresponds to in a comment.

- [ ] **Step 5: Verify the gate actually fails**

Open a throwaway pull request that adds a dependency with a known critical CVE,
confirm the job fails, and close it. **A gate nobody has seen fail is a gate
nobody knows works** — this is the same argument as the clustering-disabled
control case in every previous phase.

- [ ] **Step 6: Commit**

```bash
git add .github
git commit -m "GUACAMOLE-283: Scan dependencies and the image in CI

CI was one docker build. This fork adds Lettuce, Netty and Reactor, and
ships them inside extension jars where a scan of the reactor alone would
attribute them elsewhere, so both scans are needed rather than either.

Suppressions require a justification and an expiry: a suppression without
one is a permanent silent acceptance, which is what the gate exists to
prevent."
```

---

## Task 7: Verify the controls by attacking the deployment

Implements **C6**, and proves C1 through C5 rather than asserting them.

**Files:** none. Produces `docs/superpowers/deploy/README.md` section 12.

Each case below states the expectation before it is run. Record what actually
happened, including anything that failed.

- [ ] **Step 1: An insecure URI is refused**

Set `CLUSTER_REDIS_URI` to `redis://redis:6379` with
`cluster-allow-insecure-redis` unset. **Expected: the deployment does not serve
traffic, and the log names `cluster-allow-insecure-redis`.** Restore afterwards.

- [ ] **Step 2: The opt-out works, and is loud**

Set `CLUSTER_ALLOW_INSECURE_REDIS=true` with the same URI. **Expected: it
starts, and every start logs a warning naming the exposure.**

- [ ] **Step 3: A restrictive ACL is reported**

Remove `+time` from the ACL, reload it, restart a replica. **Expected: an ERROR
at startup naming the denied operation**, where before this plan the same
misconfiguration produced three warnings that read like noise. Restore
afterwards, and confirm the ERROR disappears.

- [ ] **Step 4: The wrong password fails safely**

Point Guacamole at the right host with the wrong password. **Expected: users can
still log in and connect** — the cluster degrades to per-replica behaviour — and
the self-check reports every operation as denied. This is the case that
distinguishes "fails closed on configuration" from "fails closed on dependency",
and both behaviours must hold at once.

- [ ] **Step 5: The NetworkPolicy blocks a bystander**

From a pod that is not Guacamole, attempt to reach Redis. **Expected: the
connection does not establish.** The `pytester` pod already in the namespace is
exactly the bystander this control is aimed at.

- [ ] **Step 6: Document the results**

Add section 12 to `deploy/README.md`, in the style of sections 4-11, with the
commands and the observed output.

- [ ] **Step 7: Commit**

```bash
git add docs/superpowers/deploy/README.md
git commit -m "GUACAMOLE-283: Record security control verification"
```

---

## What P5a does NOT do

- **Prometheus metrics and Helm packaging** are P5b. The chart must default to
  the secure configuration this plan makes mandatory, which is why it follows
  rather than precedes.
- **Sentinel is not implemented, and may never be.** `redis-sentinel-ha.yaml`
  exists and is unvalidated. If Redis moves to a managed service with a stable
  primary endpoint, the work disappears instead of being done. Decide that before
  P5b rather than inside it.
- **Encryption at rest for session identity** is out of scope, and recorded as
  an accepted risk in the threat model rather than silently omitted.
- **Guacamole's own authentication surface** — SAML, TOTP, the login flow — is
  unchanged by this programme and is not reviewed here. This plan covers the
  attack surface clustering added.
