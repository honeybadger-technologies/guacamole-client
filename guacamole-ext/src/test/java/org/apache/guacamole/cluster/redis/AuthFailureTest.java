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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AuthFailureTest {

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
    public void failuresAccumulate() throws Exception {

        assertEquals(1, store.recordAuthenticationFailure("10.0.0.1", 300));
        assertEquals(2, store.recordAuthenticationFailure("10.0.0.1", 300));
        assertEquals(3, store.recordAuthenticationFailure("10.0.0.1", 300));

    }

    @Test
    public void addressesAreCountedIndependently() throws Exception {

        store.recordAuthenticationFailure("10.0.0.1", 300);
        store.recordAuthenticationFailure("10.0.0.1", 300);
        store.recordAuthenticationFailure("10.0.0.2", 300);

        assertEquals(2, store.getAuthenticationFailures("10.0.0.1"));
        assertEquals(1, store.getAuthenticationFailures("10.0.0.2"));

    }

    @Test
    public void unknownAddressHasNoFailures() throws Exception {
        assertEquals(0, store.getAuthenticationFailures("10.0.0.99"));
    }

    @Test
    public void countIsVisibleToAnotherReplica() throws Exception {

        // The entire point of the phase: two replicas share one count
        RedisClusterStore other = new RedisClusterStore(RedisTestSupport.redisUri(),
                STALE_WINDOW_MS, "node-2");

        try {
            store.recordAuthenticationFailure("10.0.0.5", 300);
            store.recordAuthenticationFailure("10.0.0.5", 300);
            assertEquals(3, other.recordAuthenticationFailure("10.0.0.5", 300));
        }

        finally {
            other.shutdown();
        }

    }

    @Test
    public void theWindowIsRefreshedByEachFailure() throws Exception {

        // A one-second ban duration, re-armed by a second failure
        store.recordAuthenticationFailure("10.0.0.6", 1);
        long firstTtl = store.authFailureTtlForTesting("10.0.0.6");
        assertTrue(firstTtl > 0, "the counter must carry an expiry");

        Thread.sleep(1100);
        assertEquals(0, store.getAuthenticationFailures("10.0.0.6"),
                "the counter must age out once the ban duration passes");

        assertEquals(1, store.recordAuthenticationFailure("10.0.0.6", 1),
                "a failure after expiry starts a fresh count");

    }

}
