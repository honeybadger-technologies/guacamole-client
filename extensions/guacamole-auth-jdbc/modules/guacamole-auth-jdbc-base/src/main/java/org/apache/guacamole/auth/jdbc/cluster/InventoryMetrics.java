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

import com.google.inject.Inject;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.apache.guacamole.auth.jdbc.connection.ConnectionMapper;
import org.apache.guacamole.auth.jdbc.user.UserMapper;
import org.apache.guacamole.cluster.metrics.ClusterMetrics;

/**
 * Exposes how many users and connections exist in the database, as the
 * guacamole_users and guacamole_connections gauges. Every replica reports the
 * same value; dashboards take max() across replicas.
 */
public class InventoryMetrics {

    /**
     * How long a count is reused before the database is queried again.
     */
    static final long CACHE_MILLIS = 60000L;

    @Inject
    public InventoryMetrics(UserMapper userMapper, ConnectionMapper connectionMapper) {
        this(() -> userMapper.selectIdentifiers().size(),
                () -> connectionMapper.selectIdentifiers().size(),
                System::currentTimeMillis);
    }

    InventoryMetrics(IntSupplier users, IntSupplier connections, LongSupplier clock) {
        ClusterMetrics.gaugeFunction("guacamole_users", new CachedCount(users, clock)::get);
        ClusterMetrics.gaugeFunction("guacamole_connections",
                new CachedCount(connections, clock)::get);
    }

    /**
     * A count fetched at most once per CACHE_MILLIS. A failed fetch throws,
     * so the gauge is omitted rather than reported stale.
     */
    private static class CachedCount {

        private final IntSupplier source;

        private final LongSupplier clock;

        private Integer value;

        private long fetchedAt;

        CachedCount(IntSupplier source, LongSupplier clock) {
            this.source = source;
            this.clock = clock;
        }

        synchronized Integer get() {
            long now = clock.getAsLong();
            if (value == null || now - fetchedAt >= CACHE_MILLIS) {
                value = source.getAsInt();
                fetchedAt = now;
            }
            return value;
        }

    }

}
