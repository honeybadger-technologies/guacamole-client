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
import org.apache.guacamole.cluster.ClusterKillHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KillDispatchTest {

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

    private RedisClusterStore publisher;
    private RedisClusterStore subscriber;

    @BeforeEach
    public void setUp() {
        publisher = new RedisClusterStore(RedisTestSupport.redisUri(), STALE_WINDOW_MS, "node-1");
        subscriber = new RedisClusterStore(RedisTestSupport.redisUri(), STALE_WINDOW_MS, "node-2");
    }

    @AfterEach
    public void tearDown() {
        publisher.shutdown();
        subscriber.shutdown();
    }

    @Test
    public void killRequestReachesAnotherReplica() throws Exception {

        final CountDownLatch received = new CountDownLatch(1);
        final AtomicReference<String> seen = new AtomicReference<String>();

        subscriber.onKillRequest(new ClusterKillHandler() {

            @Override
            public void killLocalTunnel(String recordUuid) {
                seen.set(recordUuid);
                received.countDown();
            }

        });

        publisher.requestKill("record-uuid-1");

        assertTrue(received.await(10, TimeUnit.SECONDS), "kill request never arrived");
        assertEquals("record-uuid-1", seen.get());

    }

    @Test
    public void everyReplicaIsNotifiedIncludingThePublisher() throws Exception {

        // The owner may be the replica that issued the kill. Delivery is
        // broadcast; deciding who owns the tunnel is the handler's job.
        final CountDownLatch received = new CountDownLatch(1);

        publisher.onKillRequest(new ClusterKillHandler() {

            @Override
            public void killLocalTunnel(String recordUuid) {
                received.countDown();
            }

        });

        publisher.requestKill("record-uuid-2");
        assertTrue(received.await(10, TimeUnit.SECONDS),
                "publisher did not receive its own request");

    }

}
