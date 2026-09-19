# Guacamole HA Clustering — P5b Implementation Plan (operability and packaging)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a clustered deployment observable and reproducible — every degradation P5a made loud in a log becomes a number Prometheus can alert on, and the deployment that produces it comes from the Terraform module rather than from hand-applied YAML.

**Architecture:** Two repositories, in order. First `guacamole-client`: a metrics registry in `guacamole-ext` (which is loaded exactly once in the WAR, so one registry serves both the web application's store and the extension's), incremented at the degradation sites, exposed by a token-guarded servlet. Then `devops-k8s-infra`: the existing `addons/guacamole` Terraform module gains the fork image, a hardened Redis, the cluster properties, a headless guacd Service, a ServiceMonitor, alerts and a dashboard — all default-off, because that module is live in three environments including production.

**Tech Stack:** Java 8, Maven, Lettuce 6.3.2.RELEASE, Guice ServletModule, Prometheus text exposition format 0.0.4, Terraform, Helm (third-party chart), kube-prometheus-stack.

**Spec:** `docs/superpowers/specs/2026-09-06-guacamole-ha-clustering-design.md` (§6 operations), `docs/superpowers/specs/2026-09-18-guacamole-ha-threat-model.md` (C1, C6, C7), and `docs/superpowers/HA-CLUSTERING-STATUS.md`

**Prior phases:** P0/P1, P2, P3a, P3b, P4a, P4b, P5a — all merged to `main` as of 2026-09-18.

## Global Constraints

- **Java 8 source and target.** `pom.xml:259-260`. No `var`, no records, no `List.of`.
- **Apache RAT is active.** Every new file needs the ASF licence header. `.md` is excluded; a YAML or properties file is not — P5a's `.trivyignore.yaml` failed the build for exactly this.
- **Commit convention is `GUACAMOLE-283: Capitalized summary`** in `guacamole-client`. The infrastructure repository follows its own convention — read `git log` there before the first commit.
- **`cluster-enabled` defaults to `false`.** Nothing in this plan may change behaviour when clustering is off.
- **Never build a Redis key inline.** All keys come from `ClusterKeys`.
- **A Redis outage degrades, never blocks** (spec §6.1). A metrics failure must degrade further still: no counter, no servlet and no scrape may ever affect a user request.
- **Existing suite must stay green:** `mvn test -Dexec.skip=true` (165 tests as of P5a). `-Dexec.skip=true` is required: `generate-license-files` fails on a stale npm license cache, reproducibly, on `main` too.
- **No new runtime dependency in `guacamole-client`.** P5a's supply-chain gate found a real CVE in the one library this fork already added. The Prometheus text format is roughly forty lines of string building; Micrometer is not worth a second attack surface and a second thing to scan.
- **The Terraform module is live in `devqa`, `prod/us-east-2` and `smartpark-us/us-east-1`.** Production currently runs `replicas = 1`, `hpa_enabled = false`. Every variable this plan adds defaults to the current behaviour, and a `terraform plan` on an unchanged input must produce no diff.

---

## Why metrics, and why these metrics

P5a made every failure loud **in a log**. That is a real improvement over P4b, where an ACL missing one command stopped the cluster coordinating while logins, connections and sessions all kept working. But a log line is only loud if somebody is reading it, and nobody reads the logs of a system that appears to work.

Each metric below exists because a specific failure in this programme was invisible while it was happening:

| Metric | The failure it would have caught |
|---|---|
| `guacamole_cluster_store_available` | The 180-second hang, and every later Redis outage |
| `guacamole_cluster_selfcheck_denied_operations` | The ACL missing `+time` — three warnings that read like noise |
| `guacamole_cluster_operation_failures_total` | Every silent degradation: each `catch (RedisException)` that returns a fallback |
| `guacamole_cluster_redis_insecure` | A deployment running on the `cluster-allow-insecure-redis` opt-out and nobody remembering |
| `guacamole_guacd_selections_total` | guacd distribution, measured by hand in P0/P1 and never since |
| `guacamole_cluster_seat_acquisitions_total` | Concurrency limits failing open under a degraded store |

The last two are the ones that make the difference between "the cluster is up" and "the cluster is doing its job". A cluster that has silently fallen back to per-replica behaviour reports `available=1` on every replica and still serves every request.

## Why the endpoint needs a token

The metrics endpoint cannot require a Guacamole session — Prometheus does not have one. It also cannot be open, because the ALB Ingress routes `/*` to this Service and there is no per-path deny. So it is guarded by a bearer token, and **metrics are off unless a token is configured**: enabling them without one is refused at startup, in the same shape and for the same reason as P5a's `ClusterSecurityPolicy`.

The metrics themselves carry no usernames, no connection names and no addresses. That is a cardinality decision and a privacy decision at once, and it is why `guacd` endpoint is the only label with a variable value.

---

## File Structure

### Repository 1: `guacamole-client`

**New**

| File | Responsibility |
|---|---|
| `guacamole-ext/src/main/java/org/apache/guacamole/cluster/metrics/ClusterMetrics.java` | The registry: counters, gauges, and rendering to the Prometheus text format |
| `guacamole-ext/src/test/java/org/apache/guacamole/cluster/metrics/ClusterMetricsTest.java` | Format correctness, label escaping, concurrent increment |
| `guacamole/src/main/java/org/apache/guacamole/metrics/MetricsServlet.java` | Serves the registry over HTTP, guarded by a bearer token |
| `guacamole/src/main/java/org/apache/guacamole/metrics/MetricsModule.java` | `ServletModule` binding `/metrics` |
| `guacamole/src/test/java/org/apache/guacamole/metrics/MetricsServletTest.java` | Authorisation behaviour |

**Modified**

| File | Change |
|---|---|
| `.../cluster/ClusterProperties.java` | `cluster-metrics-enabled`, `cluster-metrics-token` |
| `.../cluster/redis/RedisClusterStore.java` | Increment a failure counter at each degradation site; count seat outcomes; trust a private CA through SslOptions |
| `.../cluster/guacd/GuacdSelector.java` | Count selections per endpoint |
| `.../auth/jdbc/cluster/ClusterModule.java` | Publish the startup gauges |
| `.../GuacamoleServletContextListener.java` | Install `MetricsModule` |
| `docs/superpowers/deploy/README.md` | Section 13: the metric list and what each one is for |

### Repository 2: `devops-k8s-infra`

**New**

| File | Responsibility |
|---|---|
| `infra-aws/modules/addons/guacamole/redis.tf` | Hardened Redis StatefulSet, Service, ACL Secret, NetworkPolicy |
| `infra-aws/modules/addons/guacamole/cluster.tf` | Headless guacd Service, cluster properties, metrics Secret |
| `infra-aws/modules/addons/guacamole/monitoring.tf` | ServiceMonitor, PrometheusRule |
| `infra-aws/modules/addons/prometheus/dashboards/guacamole-cluster.json` | Grafana dashboard |

**Modified**

| File | Change |
|---|---|
| `infra-aws/modules/addons/guacamole/variables.tf` | `cluster_enabled`, `image`, `redis_*`, `metrics_*` — all defaulting to today's behaviour |
| `infra-aws/modules/addons/guacamole/main.tf` | Image override; cluster properties and env; the `guacd_replicas` guard |
| `infra-aws/modules/addons/guacamole/README.md` | What clustering requires, and what it costs |

---

## Task 1: A metrics registry that cannot break a request

**Files:**
- Create: `guacamole-ext/src/main/java/org/apache/guacamole/cluster/metrics/ClusterMetrics.java`
- Test: `guacamole-ext/src/test/java/org/apache/guacamole/cluster/metrics/ClusterMetricsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `ClusterMetrics.counter(String name, String labelName, String labelValue)` returning `void` — increments by one, creating the series on first use
  - `ClusterMetrics.counter(String name)` returning `void`
  - `ClusterMetrics.gauge(String name, double value)` returning `void`
  - `ClusterMetrics.render()` returning `String` — the whole registry in Prometheus text exposition format
  - `ClusterMetrics.reset()` returning `void` — tests only

**This is static state, deliberately.** The web application and the JDBC extension build separate `ClusterStore` instances, and a metric that existed twice would report half the truth in each copy. `guacamole-ext` is taken as `provided` by every extension and lives exactly once in the WAR — the same property that forced the P4b relocation is what makes one registry reachable from both. Do not make this an injectable singleton: Guice would give the extension's injector and the web application's injector one instance each.

- [ ] **Step 1: Write the failing test** (ASF header first — copy the 19-line block from `ClusterKeys.java`)

```java
package org.apache.guacamole.cluster.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClusterMetricsTest {

    @BeforeEach
    public void setUp() {
        ClusterMetrics.reset();
    }

    @Test
    public void rendersACounterWithItsTypeAndValue() {

        ClusterMetrics.counter("guacamole_cluster_operation_failures_total",
                "operation", "acquireSeats");
        ClusterMetrics.counter("guacamole_cluster_operation_failures_total",
                "operation", "acquireSeats");

        String text = ClusterMetrics.render();

        assertTrue(text.contains("# TYPE guacamole_cluster_operation_failures_total counter"), text);
        assertTrue(text.contains(
                "guacamole_cluster_operation_failures_total{operation=\"acquireSeats\"} 2"), text);

    }

    @Test
    public void rendersAGaugeAsItsLatestValue() {

        ClusterMetrics.gauge("guacamole_cluster_store_available", 1);
        ClusterMetrics.gauge("guacamole_cluster_store_available", 0);

        String text = ClusterMetrics.render();

        assertTrue(text.contains("# TYPE guacamole_cluster_store_available gauge"), text);
        assertTrue(text.contains("guacamole_cluster_store_available 0"), text);
        assertFalse(text.contains("guacamole_cluster_store_available 1"), text);

    }

    @Test
    public void escapesLabelValues() {

        // A guacd endpoint label is built from a hostname, and the exposition
        // format would be corrupted by a quote or a backslash in one
        ClusterMetrics.counter("guacamole_guacd_selections_total",
                "endpoint", "host\"with\\quote");

        assertTrue(ClusterMetrics.render().contains(
                "endpoint=\"host\\\"with\\\\quote\""), ClusterMetrics.render());

    }

    @Test
    public void countsEveryIncrementUnderConcurrency() throws Exception {

        final int threads = 8;
        final int perThread = 1000;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);

        List<Thread> workers = new ArrayList<Thread>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(new Runnable() {

                @Override
                public void run() {
                    try {
                        start.await();
                        for (int j = 0; j < perThread; j++)
                            ClusterMetrics.counter("guacamole_cluster_seat_acquisitions_total",
                                    "result", "SUCCESS");
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    finally {
                        done.countDown();
                    }
                }

            });
            workers.add(worker);
            worker.start();
        }

        start.countDown();
        done.await();

        assertTrue(ClusterMetrics.render().contains(
                "guacamole_cluster_seat_acquisitions_total{result=\"SUCCESS\"} "
                        + (threads * perThread)), ClusterMetrics.render());

    }

    @Test
    public void rendersNothingWhenNothingHasBeenRecorded() {
        assertEquals("", ClusterMetrics.render());
    }

}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl guacamole-ext test -Dtest=ClusterMetricsTest -Dexec.skip=true
```

Expected: FAIL — `ClusterMetrics` does not exist.

- [ ] **Step 3: Write the registry** (ASF header first)

```java
package org.apache.guacamole.cluster.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every metric the cluster implementation publishes.
 *
 * Static rather than injected on purpose: the web application and the JDBC
 * extension build separate ClusterStore instances, and an injected registry
 * would give each of them a copy reporting half the truth. This class lives in
 * guacamole-ext, which every extension takes as "provided" and which therefore
 * exists exactly once in the deployed WAR.
 */
public class ClusterMetrics {

    /**
     * Counter series, keyed by their fully rendered identity, for example
     * "guacamole_cluster_operation_failures_total{operation=\"getToken\"}".
     */
    private static final ConcurrentMap<String, AtomicLong> counters =
            new ConcurrentHashMap<String, AtomicLong>();

    /**
     * Gauge values, keyed the same way.
     */
    private static final ConcurrentMap<String, Double> gauges =
            new ConcurrentHashMap<String, Double>();

    /**
     * The metric name of each series, keyed by that series' identity, so that
     * the TYPE line can be emitted once per name.
     */
    private static final ConcurrentMap<String, String> names =
            new ConcurrentHashMap<String, String>();

    private ClusterMetrics() {}

    /**
     * Increments an unlabelled counter.
     *
     * @param name
     *     The metric name.
     */
    public static void counter(String name) {
        increment(name, name);
    }

    /**
     * Increments a counter carrying one label.
     *
     * @param name
     *     The metric name.
     *
     * @param labelName
     *     The label's name.
     *
     * @param labelValue
     *     The label's value, which may contain characters needing escaping.
     */
    public static void counter(String name, String labelName, String labelValue) {
        increment(name, name + "{" + labelName + "=\"" + escape(labelValue) + "\"}");
    }

    /**
     * Records the current value of an unlabelled gauge.
     *
     * @param name
     *     The metric name.
     *
     * @param value
     *     The value as of now.
     */
    public static void gauge(String name, double value) {
        names.put(name, name);
        gauges.put(name, Double.valueOf(value));
    }

    /**
     * Discards every recorded series. Intended only for tests.
     */
    public static void reset() {
        counters.clear();
        gauges.clear();
        names.clear();
    }

    /**
     * Renders every recorded series in the Prometheus text exposition format.
     *
     * @return
     *     The whole registry, or the empty string when nothing has been
     *     recorded.
     */
    public static String render() {

        StringBuilder text = new StringBuilder();

        List<String> counterKeys = new ArrayList<String>(counters.keySet());
        Collections.sort(counterKeys);

        List<String> gaugeKeys = new ArrayList<String>(gauges.keySet());
        Collections.sort(gaugeKeys);

        String lastName = null;
        for (String key : counterKeys) {
            String name = names.get(key);
            if (!name.equals(lastName)) {
                text.append("# TYPE ").append(name).append(" counter\n");
                lastName = name;
            }
            text.append(key).append(' ').append(counters.get(key).get()).append('\n');
        }

        lastName = null;
        for (String key : gaugeKeys) {
            String name = names.get(key);
            if (!name.equals(lastName)) {
                text.append("# TYPE ").append(name).append(" gauge\n");
                lastName = name;
            }
            text.append(key).append(' ')
                    .append(format(gauges.get(key).doubleValue())).append('\n');
        }

        return text.toString();

    }

    /**
     * Increments the counter with the given identity, creating it if this is
     * its first occurrence.
     *
     * @param name
     *     The metric name, used for the TYPE line.
     *
     * @param key
     *     The fully rendered series identity.
     */
    private static void increment(String name, String key) {

        AtomicLong counter = counters.get(key);
        if (counter == null) {
            counters.putIfAbsent(key, new AtomicLong());
            names.put(key, name);
            counter = counters.get(key);
        }

        counter.incrementAndGet();

    }

    /**
     * Escapes a label value for the text exposition format.
     *
     * @param value
     *     The raw label value.
     *
     * @return
     *     The value with backslashes, quotes and newlines escaped.
     */
    private static String escape(String value) {
        if (value == null)
            return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * Renders a gauge value without a trailing ".0" for whole numbers, which
     * keeps a 0/1 gauge readable.
     *
     * @param value
     *     The value to render.
     *
     * @return
     *     The value as text.
     */
    private static String format(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value))
            return Long.toString((long) value);
        return Double.toString(value);
    }

}
```

- [ ] **Step 4: Run it to verify it passes**

```bash
mvn -q -pl guacamole-ext test -Dtest=ClusterMetricsTest -Dexec.skip=true
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add guacamole-ext
git commit -m "GUACAMOLE-283: Add a cluster metrics registry

Static rather than injected, because the web application and the JDBC
extension hold separate stores and an injected registry would give each a
copy reporting half the truth. It lives in guacamole-ext for the same
reason the rest of the cluster package does: that module is taken as
provided by every extension and exists exactly once in the WAR.

No new dependency. The text exposition format is a few dozen lines of
string building, and P5a's supply-chain gate found a real CVE in the one
library this fork has already added."
```

---

## Task 2: Record what currently degrades in silence

**Files:**
- Modify: `guacamole-ext/src/main/java/org/apache/guacamole/cluster/redis/RedisClusterStore.java`
- Modify: `guacamole-ext/src/main/java/org/apache/guacamole/cluster/guacd/GuacdSelector.java`
- Test: `guacamole-ext/src/test/java/org/apache/guacamole/cluster/redis/MetricsRecordedTest.java`

**Interfaces:**
- Consumes: `ClusterMetrics.counter`, `ClusterMetrics.gauge` (Task 1).
- Produces: the series names Task 5 alerts on and Task 6 charts —
  - `guacamole_cluster_operation_failures_total{operation}` counter
  - `guacamole_cluster_seat_acquisitions_total{result}` counter
  - `guacamole_guacd_selections_total{endpoint}` counter
  - `guacamole_cluster_store_available` gauge

**Every `catch (RedisException e)` in the store is a place the cluster quietly stopped working.** There are roughly fourteen. Each already logs; a log nobody reads is what this task replaces. Add exactly one `ClusterMetrics.counter` call per catch block, named for the operation, alongside the existing `available = false` assignment.

- [ ] **Step 1: Write the failing test** (ASF header first)

```java
package org.apache.guacamole.cluster.redis;

import org.apache.guacamole.cluster.metrics.ClusterMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that a degraded operation is counted, not merely logged. Needs no
 * Docker: an unreachable Redis is what produces the degradation.
 */
public class MetricsRecordedTest {

    private static final String UNREACHABLE = "redis://127.0.0.1:1";

    @BeforeEach
    public void setUp() {
        ClusterMetrics.reset();
    }

    @Test
    public void aDegradedReadIsCounted() {

        RedisClusterStore store = new RedisClusterStore(UNREACHABLE, 30000L, "node-1");

        try {

            store.getAuthenticationFailures("10.0.0.1");

            String text = ClusterMetrics.render();
            assertTrue(text.contains(
                    "guacamole_cluster_operation_failures_total"
                            + "{operation=\"getAuthenticationFailures\"} 1"), text);

        }

        finally {
            store.shutdown();
        }

    }

    @Test
    public void availabilityIsPublishedAsAGauge() {

        RedisClusterStore store = new RedisClusterStore(UNREACHABLE, 30000L, "node-1");

        try {

            store.getAuthenticationFailures("10.0.0.1");
            store.publishMetrics();

            assertTrue(ClusterMetrics.render().contains(
                    "guacamole_cluster_store_available 0"), ClusterMetrics.render());

        }

        finally {
            store.shutdown();
        }

    }

}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl guacamole-ext test -Dtest=MetricsRecordedTest -Dexec.skip=true
```

Expected: FAIL — no counter is recorded, and `publishMetrics` does not exist.

- [ ] **Step 3: Count each degradation**

In `RedisClusterStore`, in every `catch (RedisException e)` block, add one line beside the existing `available = false`:

```java
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            ClusterMetrics.counter("guacamole_cluster_operation_failures_total",
                    "operation", "getAuthenticationFailures");
            return -1;
        }
```

**The label value is the enclosing method's name, spelled exactly.** Work through the file method by method rather than with a regular expression — several methods contain more than one catch block, and a copied label pointing at the wrong method is worse than no metric, because it sends the reader to the wrong code.

Add the seat outcome counter in `acquireSeats`, on the success path, where the result is already known:

```java
            long result = acquireSeats.eval(commands(), keys, args);
            available = true;

            if (result == 0) {
                ClusterMetrics.counter("guacamole_cluster_seat_acquisitions_total",
                        "result", "SUCCESS");
                return SeatResult.SUCCESS;
            }

            SeatResult failure = seatKeys.get((int) result - 1).getFailureResult();
            ClusterMetrics.counter("guacamole_cluster_seat_acquisitions_total",
                    "result", failure.name());
            return failure;
```

- [ ] **Step 4: Publish the gauges**

A gauge is a sample of state, so it is written when read rather than on every operation. Add to `RedisClusterStore`:

```java
    /**
     * Publishes this store's current state as gauges. Called immediately
     * before the metrics endpoint renders, so that a gauge is never staler
     * than the scrape that reads it.
     */
    @Override
    public void publishMetrics() {
        ClusterMetrics.gauge("guacamole_cluster_store_available", available ? 1 : 0);
        ClusterMetrics.gauge("guacamole_cluster_enabled", 1);
    }
```

and to `ClusterStore`:

```java
    /**
     * Publishes this store's current state as gauges, immediately before a
     * scrape reads them.
     */
    void publishMetrics();
```

and to `NoOpClusterStore`:

```java
    @Override
    public void publishMetrics() {
        // A deployment with clustering off still publishes the fact, so that
        // "is this replica clustered" is answerable from Prometheus alone
        ClusterMetrics.gauge("guacamole_cluster_enabled", 0);
    }
```

- [ ] **Step 5: Publish the two configuration-fault gauges**

These are the two Task 6 alerts on, and neither has a natural home in the
store, because both describe how the deployment was *configured* rather than
how Redis is behaving. Each is published at the point the fact becomes known.

In `ClusterSecurityPolicy.check`, on all three exits — accepted, tolerated, and
the throw:

```java
        // Published from here because this is the only place that knows
        // whether the URI was accepted or merely tolerated
        ClusterMetrics.gauge("guacamole_cluster_redis_insecure",
                parseable && encrypted && authenticated ? 0 : 1);
```

Place it before the first `return`, so that it is reached on every path
including the refusal — a refused start still publishes 1, which is correct for
the few seconds the process lives.

In `ClusterModule.configure()`, where P5a already calls `store.selfCheck()`:

```java
                List<String> denied = store.selfCheck();
                ClusterMetrics.gauge(
                        "guacamole_cluster_selfcheck_denied_operations", denied.size());
```

Add the assertions to the tests that already cover those two classes, rather
than new test classes — `ClusterSecurityPolicyTest` and `SelfCheckTest`:

```java
    @Test
    public void publishesTheInsecureGauge() throws Exception {

        ClusterMetrics.reset();
        ClusterSecurityPolicy.check("rediss://guacamole:secret@redis:6379", false);
        assertTrue(ClusterMetrics.render().contains(
                "guacamole_cluster_redis_insecure 0"), ClusterMetrics.render());

        ClusterMetrics.reset();
        ClusterSecurityPolicy.check("redis://redis:6379", true);
        assertTrue(ClusterMetrics.render().contains(
                "guacamole_cluster_redis_insecure 1"), ClusterMetrics.render());

    }
```

- [ ] **Step 6: Count guacd selections**

In `GuacdSelector`, where an endpoint is chosen and returned, add:

```java
        ClusterMetrics.counter("guacamole_guacd_selections_total",
                "endpoint", selected.toKey());
```

**Read the method before writing this.** The selector has an early return for the single-endpoint case and a circuit-breaker skip path; the counter belongs on the one line every successful selection passes through, which is the return of the chosen endpoint — not inside the loop that evaluates candidates.

- [ ] **Step 7: Run it to verify it passes**

```bash
mvn -q -pl guacamole-ext test -Dtest=MetricsRecordedTest -Dexec.skip=true
mvn test -Dexec.skip=true
```

Expected: PASS; then BUILD SUCCESS with 173 tests.

- [ ] **Step 8: Commit**

```bash
git add guacamole-ext
git commit -m "GUACAMOLE-283: Count every silent degradation

Each catch block in the store is a place the cluster stops coordinating
while every user request keeps succeeding. P5a made those loud in a log;
this makes them countable, because a log line is only loud if somebody is
reading it, and nobody reads the logs of a system that appears to work.

Seat outcomes and guacd selections are counted too. Those two are the
difference between knowing the cluster is up and knowing it is doing its
job: a cluster that has fallen back to per-replica behaviour reports
available=1 on every replica and still serves every request."
```

---

## Task 3: Serve the metrics, and refuse to serve them unguarded

**Files:**
- Create: `guacamole/src/main/java/org/apache/guacamole/metrics/MetricsServlet.java`
- Create: `guacamole/src/main/java/org/apache/guacamole/metrics/MetricsModule.java`
- Modify: `guacamole-ext/src/main/java/org/apache/guacamole/cluster/ClusterProperties.java`
- Modify: `guacamole/src/main/java/org/apache/guacamole/GuacamoleServletContextListener.java`
- Test: `guacamole/src/test/java/org/apache/guacamole/metrics/MetricsServletTest.java`

**Interfaces:**
- Consumes: `ClusterMetrics.render()` (Task 1), `ClusterStore.publishMetrics()` (Task 2).
- Produces:
  - `ClusterProperties.CLUSTER_METRICS_ENABLED` — `BooleanGuacamoleProperty`, name `cluster-metrics-enabled`
  - `ClusterProperties.CLUSTER_METRICS_TOKEN` — `StringGuacamoleProperty`, name `cluster-metrics-token`
  - `GET /metrics` returning `text/plain; version=0.0.4`, 200 with a correct bearer token, 401 without, 404 when disabled

**Off by default, and unusable without a token.** `cluster-metrics-enabled=true` with no `cluster-metrics-token` is refused at startup — the same stance as `ClusterSecurityPolicy`, for the same reason: the endpoint is reachable through the ALB Ingress, which routes `/*` to this Service and offers no per-path deny.

**404 rather than 403 when disabled**, so that a disabled endpoint is indistinguishable from one that was never built.

- [ ] **Step 1: Write the failing test** (ASF header first)

```java
package org.apache.guacamole.metrics;

import java.io.PrintWriter;
import java.io.StringWriter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.guacamole.cluster.metrics.ClusterMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MetricsServletTest {

    private static final String TOKEN = "a-long-random-scrape-token";

    private StringWriter body;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    public void setUp() throws Exception {

        ClusterMetrics.reset();
        ClusterMetrics.gauge("guacamole_cluster_enabled", 1);

        body = new StringWriter();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

    }

    @Test
    public void servesTheRegistryWithACorrectToken() throws Exception {

        when(request.getHeader("Authorization")).thenReturn("Bearer " + TOKEN);

        new MetricsServlet(true, TOKEN, null).doGet(request, response);

        verify(response).setContentType("text/plain; version=0.0.4; charset=utf-8");
        assertTrue(body.toString().contains("guacamole_cluster_enabled 1"), body.toString());

    }

    @Test
    public void refusesAMissingToken() throws Exception {

        when(request.getHeader("Authorization")).thenReturn(null);

        new MetricsServlet(true, TOKEN, null).doGet(request, response);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertEquals("", body.toString());

    }

    @Test
    public void refusesAWrongToken() throws Exception {

        when(request.getHeader("Authorization")).thenReturn("Bearer not-the-token");

        new MetricsServlet(true, TOKEN, null).doGet(request, response);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertEquals("", body.toString());

    }

    @Test
    public void looksLikeItWasNeverBuiltWhenDisabled() throws Exception {

        when(request.getHeader("Authorization")).thenReturn("Bearer " + TOKEN);

        new MetricsServlet(false, TOKEN, null).doGet(request, response);

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        assertEquals("", body.toString());

    }

}
```

**Check `mockito-core` is already a test dependency of the `guacamole` module before writing this.** Run `grep -n mockito guacamole/pom.xml pom.xml`. If it is absent, do not add it — rewrite the four tests against small hand-written stubs implementing `HttpServletRequest` and `HttpServletResponse`, because a new test dependency is still a dependency the supply-chain gate has to carry.

- [ ] **Step 2: Run it to verify it fails**

```bash
mvn -q -pl guacamole test -Dtest=MetricsServletTest -Dexec.skip=true
```

Expected: FAIL — `MetricsServlet` does not exist.

- [ ] **Step 3: Add the properties**

In `ClusterProperties.java`, beside `CLUSTER_ALLOW_INSECURE_REDIS`:

```java
    /**
     * Whether the Prometheus metrics endpoint is served. Defaults to false.
     */
    public static final BooleanGuacamoleProperty CLUSTER_METRICS_ENABLED =
            new BooleanGuacamoleProperty() {

        @Override
        public String getName() {
            return "cluster-metrics-enabled";
        }

    };

    /**
     * Bearer token required by the metrics endpoint.
     *
     * The endpoint cannot require a Guacamole session, because a scraper does
     * not have one, and it cannot be open, because the Ingress routes every
     * path to this application. Enabling metrics without a token is refused at
     * startup.
     */
    public static final StringGuacamoleProperty CLUSTER_METRICS_TOKEN =
            new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "cluster-metrics-token";
        }

    };
```

- [ ] **Step 4: Write the servlet** (ASF header first)

```java
package org.apache.guacamole.metrics;

import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.guacamole.cluster.ClusterStore;
import org.apache.guacamole.cluster.metrics.ClusterMetrics;

/**
 * Serves the cluster metrics registry in the Prometheus text exposition
 * format, guarded by a bearer token.
 */
public class MetricsServlet extends HttpServlet {

    private final boolean enabled;
    private final String token;
    private final ClusterStore store;

    /**
     * Creates a servlet serving the given store's metrics.
     *
     * @param enabled
     *     Whether the endpoint is served at all.
     *
     * @param token
     *     The bearer token a request must carry.
     *
     * @param store
     *     The store whose gauges are sampled before each render, which may be
     *     null in tests.
     */
    public MetricsServlet(boolean enabled, String token, ClusterStore store) {
        this.enabled = enabled;
        this.token = token;
        this.store = store;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        // A disabled endpoint is indistinguishable from one that was never
        // built
        if (!enabled) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        if (!authorized(request.getHeader("Authorization"))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        if (store != null)
            store.publishMetrics();

        response.setContentType("text/plain; version=0.0.4; charset=utf-8");
        response.getWriter().write(ClusterMetrics.render());

    }

    /**
     * Returns whether the given Authorization header carries the configured
     * token, compared in constant time.
     *
     * @param header
     *     The Authorization header, which may be null.
     *
     * @return
     *     true if the header authorises the request.
     */
    private boolean authorized(String header) {

        if (header == null || token == null || !header.startsWith("Bearer "))
            return false;

        String presented = header.substring("Bearer ".length());
        if (presented.length() != token.length())
            return false;

        // Constant time in the length that matters: a timing oracle on a
        // scrape token is cheap to remove and awkward to explain later
        int difference = 0;
        for (int i = 0; i < token.length(); i++)
            difference |= presented.charAt(i) ^ token.charAt(i);

        return difference == 0;

    }

}
```

- [ ] **Step 5: Bind it, and refuse an enabled endpoint with no token** (ASF header first)

```java
package org.apache.guacamole.metrics;

import com.google.inject.servlet.ServletModule;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.cluster.ClusterProperties;
import org.apache.guacamole.cluster.ClusterStore;
import org.apache.guacamole.environment.Environment;

/**
 * Binds the metrics endpoint, when it is configured.
 */
public class MetricsModule extends ServletModule {

    private final Environment environment;
    private final ClusterStore store;

    /**
     * Creates a module serving the given store's metrics.
     *
     * @param environment
     *     The Guacamole server environment.
     *
     * @param store
     *     The store whose gauges are sampled on each scrape.
     */
    public MetricsModule(Environment environment, ClusterStore store) {
        this.environment = environment;
        this.store = store;
    }

    @Override
    protected void configureServlets() {

        try {

            boolean enabled = environment.getProperty(
                    ClusterProperties.CLUSTER_METRICS_ENABLED, false);

            String token = environment.getProperty(
                    ClusterProperties.CLUSTER_METRICS_TOKEN);

            if (enabled && (token == null || token.isEmpty()))
                throw new GuacamoleServerException("\"cluster-metrics-enabled\" "
                        + "is true but \"cluster-metrics-token\" is unset. The "
                        + "metrics endpoint is reachable through the same "
                        + "Ingress as the application, so it is never served "
                        + "unguarded.");

            serve("/metrics").with(new MetricsServlet(enabled, token, store));

        }

        catch (GuacamoleException e) {
            addError(e);
        }

    }

}
```

Install it in `GuacamoleServletContextListener.getInjector()`, in the same `createChildInjector` call that already installs `RESTServiceModule` and `TunnelModule`.

**The store the module needs is the web application's own, and it must be reached through a getter on `AuthenticationService` — not by naming `RedisClusterStore` again.**

```java
    /**
     * Returns the cluster store this replica holds session identity in.
     *
     * @return
     *     The web application's cluster store.
     */
    public ClusterStore getClusterStore() {
        return clusterStore;
    }
```

The reason is not style. `AuthenticationService.createClusterStore()` contains the only place
the web application names the implementation class, and that single line is what a later split
of the cluster package into its own artifact has to remove. Adding a second direct reference
here would double that work for no benefit. A getter costs the same to write and leaves the
count at one.

Do not construct a second store to satisfy this — it would double the connections P5a
accounted for. If the getter proves unreachable from the module, pass `null`: the counters
still render and only the two gauges go stale.

- [ ] **Step 6: Run it to verify it passes**

```bash
mvn -q -pl guacamole test -Dtest=MetricsServletTest -Dexec.skip=true
mvn test -Dexec.skip=true
```

Expected: PASS, 4 tests; then BUILD SUCCESS with 177 tests.

- [ ] **Step 7: Commit**

```bash
git add guacamole guacamole-ext
git commit -m "GUACAMOLE-283: Serve cluster metrics behind a bearer token

The endpoint cannot require a Guacamole session, because Prometheus does
not have one, and it cannot be open, because the Ingress routes every
path to this application and offers no per-path deny. So it is off by
default and refuses to start when enabled without a token, in the same
shape and for the same reason as the Redis URI policy in P5a.

A disabled endpoint returns 404 rather than 403, so that it is
indistinguishable from one that was never built. The token comparison is
constant time in its length: cheap to write now, awkward to explain
later."
```

---

## Task 4: Point the Terraform module at the fork, and give it a Redis

**Repository:** `devops-k8s-infra`

**Files:**
- Create: `infra-aws/modules/addons/guacamole/redis.tf`
- Modify: `infra-aws/modules/addons/guacamole/variables.tf`
- Modify: `infra-aws/modules/addons/guacamole/main.tf`

**Interfaces:**
- Consumes: the images produced by `guacamole-client`, and the manifests already written in `guacamole-client/docs/superpowers/deploy/redis-hardened.yaml` and `redis-acl.md`.
- Produces: variables later tasks read — `var.cluster_enabled`, `var.guacamole_image`, `local.redis_uri_secret_name`, `local.namespace`.

**The module currently deploys upstream Guacamole.** `helm_values.guacamole` sets no `image`, so the chart's default is used, and the chart's default contains none of this programme's code. Everything else in this plan is inert until that changes.

**Three environments run this module — `devqa`, `prod/us-east-2`, `smartpark-us/us-east-1` — and production runs `replicas = 1`, `hpa_enabled = false`.** Every variable added here defaults to current behaviour, and the acceptance test for this task is a `terraform plan` that produces no diff on an unchanged input.

- [ ] **Step 1: Read the repository's conventions first**

```bash
cd ~/Workspace/tiba-parking/devops-k8s-infra
git log --oneline -20
git log -3 --format='%B' -- infra-aws/modules/addons/guacamole
```

Match the commit message convention and the variable documentation style already in `variables.tf` — every variable there carries a `description`, and several carry a `validation` block with a message written as a sentence.

- [ ] **Step 2: Add the variables**

In `variables.tf`:

```hcl
variable "cluster_enabled" {
  description = <<-EOT
    Enable HA clustering. Requires a Guacamole image built from the
    honeybadger-technologies/guacamole-client fork: the upstream image contains
    none of the clustering code, and setting this against it does nothing.
    Deploys a dedicated Redis holding session identity, guacd routing and
    concurrency state.
  EOT
  type        = bool
  default     = false
}

variable "guacamole_image" {
  description = <<-EOT
    Guacamole image, as repository and tag. Leave null to use the chart default,
    which is upstream Guacamole. Must be set to a fork build when
    cluster_enabled is true.
  EOT
  type = object({
    repository = string
    tag        = string
  })
  default = null

  validation {
    condition     = var.guacamole_image == null || try(var.guacamole_image.tag, "") != ""
    error_message = "guacamole_image.tag must be set when guacamole_image is given."
  }
}

variable "redis_storage_size" {
  description = "Persistent volume size for the clustering Redis. Only used when cluster_enabled is true."
  type        = string
  default     = "8Gi"
}

variable "redis_memory_limit" {
  description = <<-EOT
    Memory limit for the clustering Redis. Redis runs with maxmemory-policy
    noeviction: evicting a member of guac:idx:* or guac:seat:* would corrupt
    concurrency limits and guacd routing silently, so it must fail writes
    instead.
  EOT
  type        = string
  default     = "512Mi"
}
```

Add the precondition that catches the combination nothing else would:

```hcl
  lifecycle {
    precondition {
      condition     = !var.cluster_enabled || var.guacamole_image != null
      error_message = "cluster_enabled requires guacamole_image: the upstream image contains no clustering code, so the cluster would start, spread nothing, and report nothing."
    }
  }
```

on the `helm_release.guacamole` resource, beside the existing `hpa_max_replicas` precondition.

- [ ] **Step 3: Wire the image through**

In `main.tf`, inside `helm_values.guacamole`, add:

```hcl
      image = var.guacamole_image == null ? null : {
        repository = var.guacamole_image.repository
        tag        = var.guacamole_image.tag
      }
```

`yamlencode` renders a null as an explicit null, which some charts read as "set to nothing" rather than "unset". **Verify against this chart before trusting it:** run `helm template` with and without the key and compare the rendered image, or use a `merge()` that omits the key entirely when the variable is null. The second is safer, and matches how `local.guacamole_hpa` already merges optional keys.

- [ ] **Step 4: Write the Redis**

`redis.tf` carries a `kubernetes_stateful_set_v1`, a headless `kubernetes_service_v1`, a `kubernetes_secret_v1` for the ACL and the URI, a `kubernetes_config_map_v1` for `redis.conf`, and a `kubernetes_network_policy_v1` — every one gated on `count = var.cluster_enabled ? 1 : 0`.

Translate `guacamole-client/docs/superpowers/deploy/redis-hardened.yaml` resource by resource rather than writing it fresh; it already carries the decisions that matter, each for a recorded reason:

- `maxmemory-policy noeviction` — a cache evicts, and evicting one member of `guac:idx:*` or `guac:seat:*` corrupts concurrency limits and guacd routing silently.
- AOF persistence — a restart that loses the keyspace loses every live session's identity.
- The ACL from `redis-acl.md`, including `+time`. **`+time` is load-bearing and is the single thing most likely to be dropped as redundant:** `TIME` belongs to Redis's `@fast` class, which none of the data-type classes include, and both the seat script and `countTunnels` depend on it. Omitting it disables clustering silently — that is P4b's defect, and P5a's self-check exists to catch it.
- `user default off` — the ACL grants nothing by default, which is also why no administrative command works from inside the pod, as P5a's verification found when it could not run `INFO`.

Generate the password with `random_password` and assemble the URI in a `local`, so the plaintext never appears in a `.tf` file:

```hcl
resource "random_password" "redis" {
  count   = var.cluster_enabled ? 1 : 0
  length  = 32
  special = false
}
```

`special = false` is deliberate: the password is embedded in a URI, and a `@` or `/` in it changes where the URI's authority ends.

- [ ] **Step 5: Verify the no-op**

```bash
cd ~/Workspace/tiba-parking/devops-k8s-infra/infra-aws/live/prod/us-east-2/addons/guacamole
terragrunt plan
```

**Expected: "No changes."** This is the acceptance test for the whole task. A diff here means a live production deployment would be altered by a change that is supposed to be inert until `cluster_enabled` is set.

- [ ] **Step 6: Commit**

Follow the repository's own convention, established in Step 1. The message must state that the change is inert by default and that the production plan was run and was empty.

---

## Task 5: Configure clustering, and make guacd scalable only when it is safe

**Repository:** `devops-k8s-infra`

**Files:**
- Create: `infra-aws/modules/addons/guacamole/cluster.tf`
- Modify: `infra-aws/modules/addons/guacamole/variables.tf`
- Modify: `infra-aws/modules/addons/guacamole/main.tf`

**Interfaces:**
- Consumes: `var.cluster_enabled`, the Redis Secret from Task 4, and the properties defined in Tasks 3 and P5a.
- Produces: `local.cluster_properties`, `local.cluster_env_vars`, and the headless Service name `guacamole-guacd-headless`.

**`guacd_replicas` is currently documented as "keep at 1", and that documentation is correct for the deployment it describes and wrong for a clustered one.** Its stated reason — guacd holds tunnel state in process memory, so a load-balanced Service round-robins a join to the wrong pod and it fails with "No such connection" — is precisely the problem P0/P1 solved, by having Guacamole choose the guacd endpoint itself and record the route in `guac:route:*`. That only works when Guacamole addresses guacd pods **individually**, which a ClusterIP Service prevents. So the guard is relaxed only in the presence of both clustering and a headless Service, and the variable's documentation must say so rather than simply dropping the warning.

- [ ] **Step 1: Confirm what the chart creates**

```bash
kubectl --context devqa -n <namespace> get svc -l app.kubernetes.io/name=guacamole -o wide
kubectl --context devqa -n <namespace> get svc guacamole-guacd -o jsonpath='{.spec.clusterIP}{"\n"}'
```

A value other than `None` confirms the Service load-balances, which is what makes a second guacd pod unsafe today. **Do not attempt to make the chart's Service headless** — it is a third-party chart and the field may not be exposed. Add a second, headless Service of our own selecting the same pods; that is additive, reversible, and is what the hand-applied `guacd-headless-service.yaml` already does.

- [ ] **Step 2: Add the headless Service**

In `cluster.tf`, gated on `count = var.cluster_enabled ? 1 : 0`, a `kubernetes_service_v1` named `guacamole-guacd-headless` with `spec.cluster_ip = "None"`, port 4822, and a selector copied from the chart's own guacd Service — read it with the command in Step 1 rather than guessing the labels.

- [ ] **Step 3: Relax the guacd guard, narrowly**

Replace the `guacd_replicas` validation with one that permits more than one replica only when clustering is on:

```hcl
  validation {
    condition     = var.guacd_replicas == 1 || var.cluster_enabled
    error_message = "guacd_replicas > 1 requires cluster_enabled: without clustering, Guacamole reaches guacd through a load-balanced Service and a shared-connection join is round-robined to a pod that does not hold the tunnel, which fails with \"No such connection\"."
  }
```

**Terraform variable validation cannot reference another variable in older versions.** Check the version in the repository's `required_version`; if cross-variable validation is unavailable, move this to a `lifecycle.precondition` on `helm_release.guacamole`, which can. Rewrite the variable's description to state the new rule rather than leaving the old "keep at 1" text in place — a stale comment that contradicts the code is worse than no comment.

- [ ] **Step 4: Assemble the properties**

Non-secret settings go in the existing `guacamole.properties` ConfigMap through `local.guacamole_properties`:

```
cluster-enabled: true
cluster-heartbeat-interval: 10000
cluster-stale-window: 30000
guacd-cluster-dns: guacamole-guacd-headless
guacd-cluster-port: 4822
cluster-metrics-enabled: true
```

The two secret-bearing settings go in as environment variables, from Secrets, because the image's entrypoint generates properties from environment variables and appends the mounted file:

```hcl
  cluster_env_vars = var.cluster_enabled ? [
    {
      name = "CLUSTER_REDIS_URI"
      valueFrom = {
        secretKeyRef = {
          name = kubernetes_secret_v1.redis[0].metadata[0].name
          key  = "uri"
        }
      }
    },
    {
      name = "CLUSTER_METRICS_TOKEN"
      valueFrom = {
        secretKeyRef = {
          name = kubernetes_secret_v1.metrics[0].metadata[0].name
          key  = "token"
        }
      }
    },
  ] : []
```

**Never put `cluster-redis-uri` in the ConfigMap.** It carries the password, and a ConfigMap is readable by anything with `get configmaps` — a wider set than can read a Secret, and exactly the boundary the threat model calls trust boundary 3.

`cluster-allow-insecure-redis` is deliberately **not** exposed as a variable. The in-cluster Redis this module deploys has no TLS, so clustering would be refused by P5a's policy — which is correct, and the resolution is to give the Redis a certificate rather than to make the opt-out one `terraform apply` away. **This is a real blocker for Task 8 and must be resolved there, not worked around here.**

- [ ] **Step 5: Verify the no-op, then the enabled path**

```bash
cd ~/Workspace/tiba-parking/devops-k8s-infra/infra-aws/live/prod/us-east-2/addons/guacamole
terragrunt plan     # expected: No changes

cd ../../../../devqa/us-east-2/addons/guacamole
# set cluster_enabled = true and guacamole_image in this environment's terragrunt.hcl
terragrunt plan     # expected: the Redis, the headless Service, the Secrets, and the changed ConfigMap
```

- [ ] **Step 6: Commit**

Following the repository's convention, recording that production plans clean and that the guacd guard is relaxed only in the presence of both clustering and the headless Service.

---

## Task 6: Scrape, alert, and chart

**Repository:** `devops-k8s-infra`

**Files:**
- Create: `infra-aws/modules/addons/guacamole/monitoring.tf`
- Create: `infra-aws/modules/addons/prometheus/dashboards/guacamole-cluster.json`

**Interfaces:**
- Consumes: the series names from Task 2, the endpoint from Task 3, `var.cluster_enabled`.
- Produces: nothing consumed by a later task.

**The scrape needs no Prometheus-side change.** The stack is configured with `serviceMonitorSelectorNilUsesHelmValues = false` (`modules/addons/prometheus/main.tf:281`), so a `ServiceMonitor` in any namespace is picked up. Confirm that is still true before relying on it.

- [ ] **Step 1: Add the ServiceMonitor**

A `kubernetes_manifest` for `monitoring.coreos.com/v1 ServiceMonitor`, gated on `var.cluster_enabled`, selecting the Guacamole Service, scraping `/metrics` on the web port, with the bearer token supplied from the Secret:

```yaml
  endpoints:
    - port: http
      path: /metrics
      interval: 30s
      bearerTokenSecret:
        name: guacamole-metrics
        key: token
```

**`bearerTokenSecret` reads the Secret from the ServiceMonitor's own namespace**, so the Secret must live beside the workload, which it does. Confirm the port's *name* with `kubectl get svc guacamole -o jsonpath='{.spec.ports[*].name}'` — a ServiceMonitor referencing a port name that does not exist fails silently, producing no targets and no error.

- [ ] **Step 2: Alert on the failures this programme actually had**

A `PrometheusRule` with four rules, each written against a failure that happened:

```yaml
    - alert: GuacamoleClusterStoreUnavailable
      expr: guacamole_cluster_store_available == 0
      for: 5m
      annotations:
        summary: A Guacamole replica cannot reach Redis
        description: >-
          Sessions on this replica will not survive its loss, concurrency
          limits fall back to per-replica, and cross-replica kill and share
          stop working. Users are unaffected until a replica dies.

    - alert: GuacamoleClusterPermissionsDenied
      expr: guacamole_cluster_selfcheck_denied_operations > 0
      for: 1m
      annotations:
        summary: Redis denied operations Guacamole depends on
        description: >-
          Clustering will appear to work while silently falling back to
          per-replica behaviour. Check the Redis ACL against
          deploy/redis-acl.md; a missing "+time" produces exactly this.

    - alert: GuacamoleClusterInsecureRedis
      expr: guacamole_cluster_redis_insecure == 1
      for: 15m
      annotations:
        summary: Guacamole is holding session identity in an insecure Redis
        description: >-
          Running on the cluster-allow-insecure-redis opt-out. The keyspace
          holds usernames and source addresses, readable by anything that can
          reach the port.

    - alert: GuacamoleClusterDegrading
      expr: sum(rate(guacamole_cluster_operation_failures_total[5m])) > 0
      for: 10m
      annotations:
        summary: Guacamole cluster operations are failing
        description: >-
          Each failure is absorbed by design, so no user request fails. The
          cluster is not coordinating.
```

**Every series above is emitted by Task 2 — check that before writing the rules.** `guacamole_cluster_redis_insecure` and `guacamole_cluster_selfcheck_denied_operations` come from Task 2 Step 5; the other two from Steps 3 and 4. An alert on a series no code emits never fires, and reads in review exactly like one that does, so confirm each with a Prometheus query in Step 4 rather than trusting the name.

- [ ] **Step 3: Chart it**

A dashboard JSON in the Prometheus addon's `dashboards/` directory, matching the shape of the files already there — read one first. Five panels, in this order, because it is the order an operator debugs in:

1. `guacamole_cluster_enabled` and `guacamole_cluster_store_available` per replica — is the cluster there at all
2. `sum by (result) (rate(guacamole_cluster_seat_acquisitions_total[5m]))` — is it enforcing limits
3. `sum by (endpoint) (rate(guacamole_guacd_selections_total[5m]))` — is guacd load actually spread, which P0/P1 measured by hand and nothing has measured since
4. `sum by (operation) (rate(guacamole_cluster_operation_failures_total[5m]))` — what is degrading
5. `guacamole_cluster_selfcheck_denied_operations` and `guacamole_cluster_redis_insecure` — the two configuration faults

- [ ] **Step 4: Verify targets and rules are live**

```bash
kubectl --context devqa -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090 &
curl -s localhost:9090/api/v1/targets | python3 -c "import sys,json;print([t['health'] for t in json.load(sys.stdin)['data']['activeTargets'] if 'guacamole' in t['labels'].get('job','')])"
curl -s 'localhost:9090/api/v1/query?query=guacamole_cluster_enabled' | python3 -m json.tool | head -20
```

**Expected: one `up` target per Guacamole replica, and a value for the query.** An empty target list means the ServiceMonitor's selector or port name is wrong — the most common failure here, and a silent one.

- [ ] **Step 5: Commit**

---

## Task 7: Decide Sentinel by measuring, not by preference

**Files:**
- Create: `guacamole-client/docs/superpowers/specs/2026-09-19-redis-redundancy-decision.md`
- Modify or delete: `guacamole-client/docs/superpowers/deploy/redis-sentinel-ha.yaml`

**Interfaces:**
- Consumes: the deployment from Tasks 4 and 5.
- Produces: a decision, and the removal of an unvalidated file if the decision goes that way.

**The question is not "is Sentinel better".** It is whether the degraded window during a single-Redis restart is long enough, and harmful enough, to be worth a component whose behaviour under this client is unverified. Two facts already constrain it: Redis Cluster mode cannot be used at all (`RedisClusterStore` builds a `RedisClient`, and `acquireSeats` passes one key per limit-bearing index to a single `EVAL`, which spans hash slots and is rejected with `CROSSSLOT`), and Guacamole already degrades rather than fails during an outage — so Sentinel shortens a degraded window rather than preventing an outage.

- [ ] **Step 1: Measure the window that actually exists**

On devqa, with clustering on and a user logged in with a live connection:

```bash
kubectl --context devqa -n remote-access delete pod redis-0
# and, in another terminal, from the moment of deletion:
#  - can an existing session still use its connection?
#  - can a new user log in?
#  - how long until guacamole_cluster_store_available returns to 1?
```

Record all three. The third is the number the decision turns on; the first two are what determines whether the number matters.

- [ ] **Step 2: Answer the open Lettuce question, or record that it is still open**

`redis-sentinel-ha.yaml` has never been deployed, and whether Lettuce applies a `redis-sentinel://user:password@...` URI's credentials to the Sentinels as well as to the primary is unverified. If Sentinel is to be recommended, this must be answered by deploying it on devqa and watching a failover, not by reading the documentation. If it is not, say so and stop.

- [ ] **Step 3: Write the decision**

State the recommendation, the measurement behind it, and the trigger that would reverse it. The most likely outcome, given the measurement and the fact that a managed Redis with a stable primary endpoint needs **no code change at all** — Lettuce simply connects to it — is that the work disappears rather than being done. If that is the conclusion, delete `redis-sentinel-ha.yaml` rather than leaving an unvalidated manifest for somebody to apply in an incident.

- [ ] **Step 4: Commit**

---

## Task 8: Resolve TLS to Redis, then verify the whole thing on devqa

**Files:**
- Modify: `infra-aws/modules/addons/guacamole/redis.tf`
- Modify: `guacamole-client/docs/superpowers/deploy/README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: `deploy/README.md` section 14.

**Resolved on 2026-09-19: a self-signed certificate authority, generated in Terraform, in every environment.** The blocker is therefore closed before Task 4 builds the Redis, and `cluster-allow-insecure-redis` is never exposed as a module variable.

What was rejected, and why, so it is not revisited:

- **The ACM certificate the module already holds** cannot be used. `data "aws_acm_certificate" "ingress"` hands an ARN to the ALB, which works because AWS holds the private key and terminates TLS itself. ACM public certificates are not exportable, and Redis needs `tls-cert-file` and `tls-key-file` on disk. This is a dead end, not a configuration gap.
- **AWS Private CA** would work — it exports a key — but costs roughly $400 per month per CA plus issuance, for a certificate nothing outside the namespace will ever validate.
- **cert-manager** is not installed: `kubectl get crd` returns no `cert-manager.io` resources on devqa, and `infra-aws/modules/addons` contains only `external-secrets` and `sealed-secrets`. That route would add a cluster-wide addon first.
- **ElastiCache with in-transit encryption** remains the option that would remove both the certificate plumbing and the Sentinel question at once, and needs no application change. It is recorded as a future option for production rather than chosen now. If it is ever adopted it must be **cluster mode disabled** — `RedisClusterStore` builds a `RedisClient`, and `acquireSeats` passes one key per limit-bearing index to a single `EVAL`, which spans hash slots and is rejected with `CROSSSLOT`.

**The trap this avoids.** The obvious way to make Guacamole trust a private CA is `JAVA_OPTS=-Djavax.net.ssl.trustStore=...`, and it is wrong: that *replaces* the default truststore, which also governs SAML identity provider metadata retrieval and every other outbound HTTPS call. Login would break, and it would look like a Redis change. Trust is therefore scoped to the Redis client through Lettuce's `SslOptions.trustManager(File)`, which accepts a PEM directly — verified against the Lettuce 6.3.2 jar rather than assumed — and is wired to the `cluster-redis-ca-cert` property.

- [ ] **Step 1: Confirm the certificate chain reaches the pods**

The certificates are generated in Task 4. Confirm here that Redis is serving TLS and that Guacamole verifies it rather than skipping verification:

```bash
kubectl --context devqa -n <namespace> exec redis-0 -- \
    redis-cli --tls --cacert /etc/redis/tls/ca.crt --user guacamole ping
kubectl --context devqa -n <namespace> logs deploy/guacamole | grep "authenticated, encrypted"
```

**Expected:** `PONG`, and the P5a posture line reporting an authenticated, encrypted Redis — which is only reachable when `ClusterSecurityPolicy` accepted the URI without the opt-out.

- [ ] **Step 2: Deploy to devqa through Terraform, not by hand**

Every previous phase was verified on a hand-applied stack. This is the first one where the module produces it, and the point of the task is that the two agree.

- [ ] **Step 3: Re-run the P5a abuse cases that were never run**

`deploy/README.md` §12.4 and §12.5 — the removed `+time` and the wrong password — are still unrun, and both are now easier, because Terraform owns the Secret:

- **§12.4:** remove `+time` from the ACL in `redis.tf`, apply, restart a replica. **Expected: an ERROR naming `server clock (TIME)`, and `guacamole_cluster_selfcheck_denied_operations` above zero**, which is also the first end-to-end proof that the alert fires.
- **§12.5:** point Guacamole at the right host with the wrong password. **Expected: users can still log in and connect, and the self-check reports every operation denied.** Both must hold at once.

- [ ] **Step 4: Verify the clustered guacd path**

Set `guacd_replicas = 2` with clustering on, open a shared connection, and join it from the other replica. **Expected: one guacd connection, two users** — the P3b measurement, now against a pool the module built rather than a hand-applied headless Service. This is the assertion that the relaxed guard in Task 5 is actually safe.

- [ ] **Step 5: Write section 14 of `deploy/README.md`**

In the style of sections 4 through 12: the commands, the observed output, and anything that failed.

- [ ] **Step 6: Commit both repositories**

---

## Deferred: splitting the cluster package into its own artifact

Recorded here so that the decision is findable rather than remembered.

Asked on 2026-09-19 whether the cluster package should move to its own repository and be bound
as a Guacamole extension, the answer was to ship first: **working code, then a usage analysis,
then a refactor if the analysis warrants one.** P5b is therefore executed against the current
layout, and nothing in it assumes the split will or will not happen.

What the split would and would not be allowed to move is already settled by the classloader
rule, and does not need revisiting when the time comes:

- **Must stay in `guacamole-ext`** (a `provided` artifact, defined once by the web application's
  loader): every type that appears in a signature both sides implement or call —
  `ClusterStore`, `NoOpClusterStore`, `RehydratableAuthenticationProvider`, `TokenIdentity`, the
  handler interfaces, the `Seat*`/`Shared*`/`Tunnel*` value types, `ClusterProperties`,
  `ClusterSecurityPolicy`, `ClusterKeys`, and `ClusterMetrics` — the last because a shared
  registry is the whole point of it.
- **Could move** to a separately released implementation artifact: `RedisClusterStore`, the Lua
  scripts, Lettuce, `GuacdPool`, `GuacdSelector`, `ClusterHeartbeat`.
- **The one thing standing in the way** is that the web application names `RedisClusterStore`
  directly, in `AuthenticationService.createClusterStore()`. Task 3 above is written so as not
  to add a second such reference.

**What step 2 should measure**, so the refactor is decided on evidence: whether Lettuce and
Netty living inside the WAR actually costs anything in practice — upstream merge friction
against `apache/guacamole-client`, and the supply-chain scan surface P5a's gate now reports on.
If neither hurts, the split buys tidiness at the price of the P4b failure class, and should not
be done.

## What P5b does NOT do

- **It does not change how sessions, tunnels or share keys behave.** Every change here is observation and packaging. If a user-visible behaviour changes, something is wrong.
- **It does not enable clustering anywhere but devqa.** Production rollout is a separate decision, made with the dashboard from Task 6 in front of whoever makes it.
- **It does not fix the NetworkPolicy enforcement gap.** That is cluster-wide CNI configuration affecting every namespace and every team, recorded in the threat model and in §12.6. It does constrain Task 8's options, which is why it is named there.
- **It does not add authentication metrics or user-level telemetry.** No metric carries a username, a connection name or an address — a cardinality decision and a privacy decision at the same time.
