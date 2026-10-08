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

package org.apache.guacamole.auth.jdbc.cluster;

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.guacamole.cluster.metrics.ClusterMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the user and connection inventory gauges.
 */
public class InventoryMetricsTest {

    private long now;

    private final AtomicInteger userQueries = new AtomicInteger();

    private int users;

    @BeforeEach
    public void setUp() {
        ClusterMetrics.reset();
        now = 0;
        users = 12;
        userQueries.set(0);
    }

    private InventoryMetrics inventory(int connections) {
        return new InventoryMetrics(() -> {
            userQueries.incrementAndGet();
            return users;
        }, () -> connections, () -> now);
    }

    @Test
    public void reportsUserAndConnectionCounts() {
        inventory(607);

        String text = ClusterMetrics.render();
        assertTrue(text.contains("guacamole_users 12"), text);
        assertTrue(text.contains("guacamole_connections 607"), text);
    }

    @Test
    public void queriesTheDatabaseAtMostOncePerMinute() {
        inventory(607);

        ClusterMetrics.render();
        users = 13;
        now = InventoryMetrics.CACHE_MILLIS - 1;
        String text = ClusterMetrics.render();
        assertEquals(1, userQueries.get());
        assertTrue(text.contains("guacamole_users 12"), text);

        now = InventoryMetrics.CACHE_MILLIS;
        text = ClusterMetrics.render();
        assertEquals(2, userQueries.get());
        assertTrue(text.contains("guacamole_users 13"), text);
    }

    @Test
    public void omitsACountTheDatabaseCannotSupply() {
        new InventoryMetrics(() -> {
            throw new IllegalStateException("database unreachable");
        }, () -> 607, () -> now);

        String text = ClusterMetrics.render();
        assertFalse(text.contains("guacamole_users"), text);
        assertTrue(text.contains("guacamole_connections 607"), text);
    }

}
