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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the cluster metrics registry and its rendering.
 */
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
