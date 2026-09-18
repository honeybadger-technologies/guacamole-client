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

package org.apache.guacamole.cluster.redis;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.guacamole.cluster.ClusterLogoutHandler;
import org.apache.guacamole.cluster.TokenIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TokenStoreTest {

    private static final long STALE_WINDOW_MS = 30000L;

    /**
     * Skips this class rather than failing it when no Docker daemon is
     * reachable.
     */
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
    public void identityRoundTrips() throws Exception {

        store.putToken("hash-1", new TokenIdentity("alice", "postgresql",
                "10.0.0.1", "client.example.com", 1700000000000L), 3600);

        TokenIdentity identity = store.getToken("hash-1");
        assertEquals("alice", identity.getUsername());
        assertEquals("postgresql", identity.getAuthProviderIdentifier());
        assertEquals("10.0.0.1", identity.getRemoteAddress());
        assertEquals("client.example.com", identity.getRemoteHostname());
        assertEquals(1700000000000L, identity.getAuthenticatedTime());

    }

    @Test
    public void unknownTokenIsNull() throws Exception {
        assertNull(store.getToken("never-issued"));
    }

    @Test
    public void identityWithoutAHostnameRoundTrips() throws Exception {

        // The remote hostname is frequently unavailable
        store.putToken("hash-2", new TokenIdentity("bob", "postgresql",
                "10.0.0.2", null, 1700000000000L), 3600);

        assertNull(store.getToken("hash-2").getRemoteHostname());

    }

    @Test
    public void removedTokenIsGone() throws Exception {

        store.putToken("hash-3", new TokenIdentity("carol", "postgresql",
                "10.0.0.3", null, 1700000000000L), 3600);
        store.removeToken("hash-3");

        assertNull(store.getToken("hash-3"));

    }

    @Test
    public void tokenIsVisibleToAnotherReplica() throws Exception {

        RedisClusterStore other = new RedisClusterStore(RedisTestSupport.redisUri(),
                STALE_WINDOW_MS, "node-2");

        try {
            store.putToken("hash-4", new TokenIdentity("dave", "postgresql",
                    "10.0.0.4", null, 1700000000000L), 3600);
            assertEquals("dave", other.getToken("hash-4").getUsername());
        }

        finally {
            other.shutdown();
        }

    }

    @Test
    public void idleExpiryIsRefreshedByTouch() throws Exception {

        store.putToken("hash-5", new TokenIdentity("erin", "postgresql",
                "10.0.0.5", null, 1700000000000L), 2);

        Thread.sleep(1100);
        store.touchToken("hash-5", 60);
        assertTrue(store.tokenTtlForTesting("hash-5") > 2,
                "touch must extend the idle window");

    }

    @Test
    public void logoutReachesAnotherReplica() throws Exception {

        RedisClusterStore other = new RedisClusterStore(RedisTestSupport.redisUri(),
                STALE_WINDOW_MS, "node-2");

        try {

            final CountDownLatch seen = new CountDownLatch(1);
            final AtomicReference<String> hash = new AtomicReference<String>();

            other.onLogout(new ClusterLogoutHandler() {

                @Override
                public void loggedOut(String tokenHash) {
                    hash.set(tokenHash);
                    seen.countDown();
                }

            });

            store.publishLogout("hash-logged-out");

            assertTrue(seen.await(10, TimeUnit.SECONDS), "logout never arrived");
            assertEquals("hash-logged-out", hash.get());

        }

        finally {
            other.shutdown();
        }

    }

    @Test
    public void anExpiredTokenIsGone() throws Exception {

        store.putToken("hash-6", new TokenIdentity("frank", "postgresql",
                "10.0.0.6", null, 1700000000000L), 1);

        Thread.sleep(1100);
        assertNull(store.getToken("hash-6"));

    }

}
