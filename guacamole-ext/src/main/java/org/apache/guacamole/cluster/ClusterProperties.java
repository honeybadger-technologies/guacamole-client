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

package org.apache.guacamole.cluster;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.properties.BooleanGuacamoleProperty;
import org.apache.guacamole.properties.IntegerGuacamoleProperty;
import org.apache.guacamole.properties.LongGuacamoleProperty;
import org.apache.guacamole.properties.StringGuacamoleProperty;

/**
 * Every guacamole.properties key read by the cluster module.
 */
public class ClusterProperties {

    private ClusterProperties() {}

    /**
     * Whether cluster coordination is active. When false, every code path
     * behaves exactly as unmodified upstream Guacamole.
     */
    public static final BooleanGuacamoleProperty CLUSTER_ENABLED =
            new BooleanGuacamoleProperty() {

        @Override
        public String getName() {
            return "cluster-enabled";
        }

    };

    /**
     * Lettuce URI of the Redis server, for example "redis://host:6379" or
     * "rediss://host:6379" for TLS.
     */
    public static final StringGuacamoleProperty CLUSTER_REDIS_URI =
            new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "cluster-redis-uri";
        }

    };

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

    /**
     * Identity of this replica. Defaults to the HOSTNAME environment variable
     * plus a random suffix.
     */
    public static final StringGuacamoleProperty CLUSTER_NODE_ID =
            new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "cluster-node-id";
        }

    };

    /**
     * Milliseconds between heartbeats.
     */
    public static final LongGuacamoleProperty CLUSTER_HEARTBEAT_INTERVAL =
            new LongGuacamoleProperty() {

        @Override
        public String getName() {
            return "cluster-heartbeat-interval";
        }

    };

    /**
     * Milliseconds after which an unrefreshed cluster entry is considered dead.
     */
    public static final LongGuacamoleProperty CLUSTER_STALE_WINDOW =
            new LongGuacamoleProperty() {

        @Override
        public String getName() {
            return "cluster-stale-window";
        }

    };

    /**
     * Comma-separated list of "host" or "host:port" guacd instances.
     */
    public static final StringGuacamoleProperty GUACD_CLUSTER_HOSTS =
            new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "guacd-cluster-hosts";
        }

    };

    /**
     * Hostname resolving to every available guacd instance, such as a
     * Kubernetes headless Service.
     */
    public static final StringGuacamoleProperty GUACD_CLUSTER_DNS =
            new StringGuacamoleProperty() {

        @Override
        public String getName() {
            return "guacd-cluster-dns";
        }

    };

    /**
     * Port on which pooled guacd instances listen.
     */
    public static final IntegerGuacamoleProperty GUACD_CLUSTER_PORT =
            new IntegerGuacamoleProperty() {

        @Override
        public String getName() {
            return "guacd-cluster-port";
        }

    };

    /**
     * Milliseconds a guacd instance is skipped after a failed connection.
     */
    public static final LongGuacamoleProperty GUACD_CIRCUIT_BREAK_DURATION =
            new LongGuacamoleProperty() {

        @Override
        public String getName() {
            return "guacd-circuit-break-duration";
        }

    };


    /**
     * Returns whether cluster coordination is enabled.
     *
     * This lives here rather than on ClusterModule so that it can be read
     * without Guice on the classpath. ClusterModule extends AbstractModule, so
     * merely naming that class drags Guice into any extension that asks the
     * question -- guacamole-auth-ban among them.
     *
     * @param environment
     *     The Guacamole server environment.
     *
     * @return
     *     true if cluster coordination is enabled.
     *
     * @throws GuacamoleException
     *     If the property cannot be read.
     */
    public static boolean isEnabled(Environment environment) throws GuacamoleException {
        return environment.getProperty(CLUSTER_ENABLED, false);
    }

}
