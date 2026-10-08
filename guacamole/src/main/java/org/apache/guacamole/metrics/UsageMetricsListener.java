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

package org.apache.guacamole.metrics;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import org.apache.guacamole.cluster.metrics.ClusterMetrics;
import org.apache.guacamole.net.GuacamoleTunnel;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.auth.credentials.GuacamoleInsufficientCredentialsException;
import org.apache.guacamole.net.event.AuthenticationFailureEvent;
import org.apache.guacamole.net.event.AuthenticationSuccessEvent;
import org.apache.guacamole.net.event.TunnelCloseEvent;
import org.apache.guacamole.net.event.TunnelConnectEvent;
import org.apache.guacamole.net.event.listener.Listener;

/**
 * Records usage metrics for this replica: logins, wrong-credential attempts,
 * open connections, connected users, disconnects and connection duration.
 * Every value is per replica; dashboards sum across replicas.
 */
public class UsageMetricsListener implements Listener {

    /**
     * Connection duration bucket upper bounds, in seconds: 1 min to 8 h.
     */
    static final double[] DURATION_BUCKETS =
            { 60, 300, 900, 1800, 3600, 7200, 14400, 28800 };

    private final LongSupplier clock;

    /**
     * Open tunnels on this replica, by tunnel UUID.
     */
    private final ConcurrentMap<String, OpenTunnel> open =
            new ConcurrentHashMap<String, OpenTunnel>();

    /**
     * Creates a listener timing connections with the system clock.
     */
    public UsageMetricsListener() {
        this(System::currentTimeMillis);
    }

    /**
     * Creates a listener timing connections with the given clock.
     *
     * @param clock
     *     Supplies the current time in milliseconds.
     */
    UsageMetricsListener(LongSupplier clock) {
        this.clock = clock;
        ClusterMetrics.gaugeFunction("guacamole_active_connections", open::size);
        ClusterMetrics.gaugeFunction("guacamole_connected_users", this::connectedUsers);
    }

    private int connectedUsers() {
        Set<String> users = new HashSet<String>();
        for (OpenTunnel tunnel : open.values())
            users.add(tunnel.user);
        return users.size();
    }

    private static String key(GuacamoleTunnel tunnel) {
        if (tunnel == null)
            return null;
        UUID uuid = tunnel.getUUID();
        return uuid == null ? null : uuid.toString();
    }

    private static String identifier(AuthenticatedUser user) {
        if (user == null || user.getIdentifier() == null)
            return "";
        return user.getIdentifier();
    }

    /**
     * Same rule as EventLoggingListener's WARN line: a named user rejected for
     * any reason other than an SSO/MFA request for more credentials.
     */
    private static boolean isWrongCredentials(AuthenticationFailureEvent event) {
        Credentials credentials = event.getCredentials();
        if (credentials == null || credentials.isEmpty())
            return false;
        String username = credentials.getUsername();
        if (username == null || username.isEmpty())
            return false;
        return !(event.getFailure() instanceof GuacamoleInsufficientCredentialsException);
    }

    @Override
    public void handleEvent(@Nonnull Object event) {

        if (event instanceof AuthenticationFailureEvent) {
            if (isWrongCredentials((AuthenticationFailureEvent) event))
                ClusterMetrics.counter("guacamole_auth_failures_total");
        }

        else if (event instanceof AuthenticationSuccessEvent) {
            if (!((AuthenticationSuccessEvent) event).isExistingSession())
                ClusterMetrics.counter("guacamole_logins_total");
        }

        else if (event instanceof TunnelConnectEvent) {
            TunnelConnectEvent connect = (TunnelConnectEvent) event;
            String key = key(connect.getTunnel());
            if (key != null)
                open.put(key, new OpenTunnel(
                        identifier(connect.getAuthenticatedUser()), clock.getAsLong()));
        }

        else if (event instanceof TunnelCloseEvent) {
            String key = key(((TunnelCloseEvent) event).getTunnel());
            OpenTunnel closed = key == null ? null : open.remove(key);
            // Close can fire more than once for one tunnel; count the first only
            if (closed != null) {
                ClusterMetrics.counter("guacamole_connections_closed_total");
                ClusterMetrics.histogram("guacamole_connection_duration_seconds",
                        DURATION_BUCKETS, (clock.getAsLong() - closed.openedAt) / 1000.0);
            }
        }

    }

    /**
     * An open tunnel's owner and start time.
     */
    private static class OpenTunnel {

        private final String user;

        private final long openedAt;

        OpenTunnel(String user, long openedAt) {
            this.user = user;
            this.openedAt = openedAt;
        }

    }

}
