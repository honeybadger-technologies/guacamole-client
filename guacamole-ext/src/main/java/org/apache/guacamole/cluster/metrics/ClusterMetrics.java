/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.apache.guacamole.cluster.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.Supplier;

/**
 * Every metric the cluster implementation publishes.
 *
 * Static rather than injected on purpose: the web application and the JDBC
 * extension build separate ClusterStore instances, and an injected registry
 * would give each of them a copy reporting half the truth. This class lives in
 * guacamole-ext, which every extension takes as "provided" and which therefore
 * exists exactly once in the deployed WAR, defined by a single classloader.
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

    /**
     * Gauges whose value is computed each time the metrics are rendered.
     */
    private static final ConcurrentMap<String, Supplier<? extends Number>> gaugeFunctions =
            new ConcurrentHashMap<String, Supplier<? extends Number>>();

    /**
     * Histograms, by metric name.
     */
    private static final ConcurrentMap<String, Histogram> histograms =
            new ConcurrentHashMap<String, Histogram>();

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
    /**
     * Registers a gauge whose value is read from the given function each time
     * the metrics are rendered. A function that throws is omitted from that
     * render rather than failing the whole response.
     *
     * @param name
     *     The metric name.
     *
     * @param function
     *     Supplies the current value.
     */
    public static void gaugeFunction(String name, Supplier<? extends Number> function) {
        gaugeFunctions.put(name, function);
    }

    /**
     * Records one observation in a histogram, creating it with the given
     * bucket upper bounds on first use. Later calls reuse the original bounds.
     *
     * @param name
     *     The metric name, without the _bucket/_sum/_count suffix.
     *
     * @param buckets
     *     Ascending bucket upper bounds; +Inf is implicit.
     *
     * @param value
     *     The observed value.
     */
    public static void histogram(String name, double[] buckets, double value) {
        Histogram histogram = histograms.get(name);
        if (histogram == null) {
            histograms.putIfAbsent(name, new Histogram(buckets));
            histogram = histograms.get(name);
        }
        histogram.observe(value);
    }

    public static void reset() {
        counters.clear();
        gauges.clear();
        names.clear();
        gaugeFunctions.clear();
        histograms.clear();
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

        List<String> functionNames = new ArrayList<String>(gaugeFunctions.keySet());
        Collections.sort(functionNames);
        for (String name : functionNames) {
            Number value;
            try {
                value = gaugeFunctions.get(name).get();
            }
            catch (RuntimeException e) {
                continue;
            }
            if (value == null)
                continue;
            text.append("# TYPE ").append(name).append(" gauge\n");
            text.append(name).append(' ').append(format(value.doubleValue())).append('\n');
        }

        List<String> histogramNames = new ArrayList<String>(histograms.keySet());
        Collections.sort(histogramNames);
        for (String name : histogramNames)
            histograms.get(name).render(name, text);

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

    /**
     * A fixed-bucket histogram in the Prometheus exposition format.
     */
    private static class Histogram {

        private final double[] bounds;

        /**
         * Observations per bucket, not cumulative; the last slot is +Inf.
         */
        private final AtomicLongArray counts;

        private final DoubleAdder sum = new DoubleAdder();

        private final AtomicLong count = new AtomicLong();

        Histogram(double[] bounds) {
            this.bounds = bounds.clone();
            this.counts = new AtomicLongArray(bounds.length + 1);
        }

        void observe(double value) {
            int slot = bounds.length;
            for (int i = 0; i < bounds.length; i++) {
                if (value <= bounds[i]) {
                    slot = i;
                    break;
                }
            }
            counts.incrementAndGet(slot);
            sum.add(value);
            count.incrementAndGet();
        }

        void render(String name, StringBuilder text) {
            text.append("# TYPE ").append(name).append(" histogram\n");
            long cumulative = 0;
            for (int i = 0; i < bounds.length; i++) {
                cumulative += counts.get(i);
                text.append(name).append("_bucket{le=\"").append(format(bounds[i]))
                        .append("\"} ").append(cumulative).append('\n');
            }
            cumulative += counts.get(bounds.length);
            text.append(name).append("_bucket{le=\"+Inf\"} ").append(cumulative).append('\n');
            text.append(name).append("_sum ").append(format(sum.sum())).append('\n');
            text.append(name).append("_count ").append(count.get()).append('\n');
        }

    }

}
