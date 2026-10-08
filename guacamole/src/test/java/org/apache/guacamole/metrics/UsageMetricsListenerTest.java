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

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.apache.guacamole.cluster.metrics.ClusterMetrics;
import org.apache.guacamole.net.GuacamoleTunnel;
import org.apache.guacamole.net.auth.AuthenticatedUser;
import org.apache.guacamole.net.auth.Credentials;
import org.apache.guacamole.net.auth.credentials.CredentialsInfo;
import org.apache.guacamole.net.auth.credentials.GuacamoleInsufficientCredentialsException;
import org.apache.guacamole.net.auth.credentials.GuacamoleInvalidCredentialsException;
import org.apache.guacamole.net.event.AuthenticationFailureEvent;
import org.apache.guacamole.net.event.AuthenticationSuccessEvent;
import org.apache.guacamole.net.event.TunnelCloseEvent;
import org.apache.guacamole.net.event.TunnelConnectEvent;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the usage metrics recorded from authentication and tunnel events.
 */
public class UsageMetricsListenerTest {

    private long now;

    private UsageMetricsListener listener;

    @Before
    public void setUp() {
        ClusterMetrics.reset();
        now = 0;
        listener = new UsageMetricsListener(() -> now);
    }

    private static HttpServletRequest emptyRequest() {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class },
                (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (Enumeration.class.isAssignableFrom(type))
                        return Collections.emptyEnumeration();
                    if (Map.class.isAssignableFrom(type))
                        return Collections.emptyMap();
                    return null;
                });
    }

    private static Credentials credentials(String username) {
        return new Credentials(username, username == null ? null : "secret",
                emptyRequest());
    }

    private static AuthenticatedUser user(String identifier) {
        return (AuthenticatedUser) Proxy.newProxyInstance(
                AuthenticatedUser.class.getClassLoader(),
                new Class<?>[] { AuthenticatedUser.class },
                (proxy, method, args) ->
                        "getIdentifier".equals(method.getName()) ? identifier : null);
    }

    private static GuacamoleTunnel tunnel(String uuid) {
        return (GuacamoleTunnel) Proxy.newProxyInstance(
                GuacamoleTunnel.class.getClassLoader(),
                new Class<?>[] { GuacamoleTunnel.class },
                (proxy, method, args) ->
                        "getUUID".equals(method.getName())
                                ? java.util.UUID.fromString(uuid) : null);
    }

    private static final String A = "00000000-0000-0000-0000-00000000000a";
    private static final String B = "00000000-0000-0000-0000-00000000000b";
    private static final String C = "00000000-0000-0000-0000-00000000000c";

    private void connect(String identifier, String uuid) throws Exception {
        listener.handleEvent(new TunnelConnectEvent(user(identifier),
                credentials(identifier), tunnel(uuid)));
    }

    private void close(String identifier, String uuid) throws Exception {
        listener.handleEvent(new TunnelCloseEvent(user(identifier),
                credentials(identifier), tunnel(uuid)));
    }

    @Test
    public void countsARejectedLoginForANamedUser() throws Exception {
        listener.handleEvent(new AuthenticationFailureEvent(credentials("alice"),
                new GuacamoleInvalidCredentialsException("Invalid login.",
                        CredentialsInfo.USERNAME_PASSWORD)));

        String text = ClusterMetrics.render();
        Assert.assertTrue(text, text.contains("guacamole_auth_failures_total 1"));
    }

    @Test
    public void ignoresFailuresThatAreNotWrongCredentials() throws Exception {
        // Login screen initialisation, an anonymous attempt, and an SSO
        // provider asking for more credentials are not wrong credentials
        listener.handleEvent(new AuthenticationFailureEvent(credentials(null)));
        listener.handleEvent(new AuthenticationFailureEvent(credentials("")));
        listener.handleEvent(new AuthenticationFailureEvent(credentials("alice"),
                new GuacamoleInsufficientCredentialsException("More needed.",
                        CredentialsInfo.USERNAME_PASSWORD)));

        String text = ClusterMetrics.render();
        Assert.assertFalse(text, text.contains("guacamole_auth_failures_total"));
    }

    @Test
    public void countsNewLoginsButNotSessionReuse() throws Exception {
        listener.handleEvent(new AuthenticationSuccessEvent(user("alice"), false));
        listener.handleEvent(new AuthenticationSuccessEvent(user("alice"), true));

        String text = ClusterMetrics.render();
        Assert.assertTrue(text, text.contains("guacamole_logins_total 1"));
    }

    @Test
    public void tracksActiveConnectionsAndConnectedUsers() throws Exception {
        connect("alice", A);
        connect("alice", B);
        connect("bob", C);

        String text = ClusterMetrics.render();
        Assert.assertTrue(text, text.contains("guacamole_active_connections 3"));
        Assert.assertTrue(text, text.contains("guacamole_connected_users 2"));

        close("alice", A);

        text = ClusterMetrics.render();
        Assert.assertTrue(text, text.contains("guacamole_active_connections 2"));
        Assert.assertTrue(text, text.contains("guacamole_connections_closed_total 1"));
    }

    @Test
    public void reportsZeroActiveConnectionsBeforeAnyConnect() {
        String text = ClusterMetrics.render();
        Assert.assertTrue(text, text.contains("guacamole_active_connections 0"));
        Assert.assertTrue(text, text.contains("guacamole_connected_users 0"));
    }

    @Test
    public void recordsConnectionDuration() throws Exception {
        now = 1_000;
        connect("alice", A);
        now = 1_000 + 125_000;
        close("alice", A);

        String text = ClusterMetrics.render();
        Assert.assertTrue(text, text.contains(
                "guacamole_connection_duration_seconds_bucket{le=\"60\"} 0"));
        Assert.assertTrue(text, text.contains(
                "guacamole_connection_duration_seconds_bucket{le=\"300\"} 1"));
        Assert.assertTrue(text, text.contains("guacamole_connection_duration_seconds_sum 125"));
        Assert.assertTrue(text, text.contains("guacamole_connection_duration_seconds_count 1"));
    }

    @Test
    public void countsATunnelClosedTwiceOnce() throws Exception {
        connect("alice", A);
        close("alice", A);
        close("alice", A);

        String text = ClusterMetrics.render();
        Assert.assertTrue(text, text.contains("guacamole_connections_closed_total 1"));
        Assert.assertTrue(text, text.contains("guacamole_connection_duration_seconds_count 1"));
    }

}
