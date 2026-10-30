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

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.apache.guacamole.cluster.ClusterKeys;
import org.apache.guacamole.cluster.SeatRequestBuilder;
import org.apache.guacamole.cluster.SeatResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that the atomic seat acquisition script of
 * {@link RedisClusterStore} prunes index members left behind by dead
 * replicas, and that such members never count against a seat limit. Runs
 * against a real Redis started by Testcontainers, and is skipped when Docker
 * is unavailable.
 */
public class SeatPruningTest {

    /**
     * The stale window given to the store, in milliseconds. Index members
     * whose score is older than this are treated as left behind by a dead
     * replica.
     */
    private static final long STALE_WINDOW_MS = 30000L;

    /**
     * Skips this class rather than failing it when no Docker daemon is
     * reachable. The image build runs the full test suite inside a container
     * that has no daemon of its own, and a Redis-backed test cannot be
     * meaningfully run there.
     */
    @BeforeAll
    public static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available; skipping Redis-backed tests.");
    }

    /**
     * The raw Lettuce client used to inspect and manipulate Redis directly,
     * independently of the store under test.
     */
    private RedisClient client;

    /**
     * The raw connection used to inspect and manipulate Redis directly,
     * independently of the store under test.
     */
    private StatefulRedisConnection<String, String> connection;

    /**
     * The store under test, acting as replica "node-1".
     */
    private RedisClusterStore store;

    /**
     * Opens a raw connection to the shared Redis container, flushes it so
     * that no state leaks between tests, and creates a fresh store under
     * test.
     */
    @BeforeEach
    public void setUp() {
        client = RedisClient.create(RedisTestSupport.redisUri());
        connection = client.connect();
        connection.sync().flushall();
        store = new RedisClusterStore(RedisTestSupport.redisUri(), STALE_WINDOW_MS, "node-1");
    }

    /**
     * Shuts down the store under test and closes the raw connection and
     * client, releasing every connection opened by {@link #setUp()}.
     */
    @AfterEach
    public void tearDown() {
        store.shutdown();
        connection.close();
        client.shutdown();
    }

    /**
     * Verifies that acquiring a seat physically removes every member of the
     * seat's sorted set whose score is outside the stale window, so dead
     * entries do not accumulate in Redis forever.
     *
     * @throws Exception
     *     If the seat cannot be acquired.
     */
    @Test
    public void acquiringASeatPrunesTombstonesLeftByDeadReplicas() throws Exception {

        String key = ClusterKeys.connectionIndex("conn-1");

        // Two tunnels whose replica died without ever releasing them. Nothing
        // else removes these from the connection index; only the seat script
        // does.
        long ancient = System.currentTimeMillis() - (STALE_WINDOW_MS * 10);
        connection.sync().zadd(key, (double) ancient, "dead-tunnel-1");
        connection.sync().zadd(key, (double) ancient, "dead-tunnel-2");
        assertEquals(2L, connection.sync().zcard(key));

        assertEquals(SeatResult.SUCCESS, store.acquireSeats(
                SeatRequestBuilder.forConnection("live-1", "alice", "conn-1", 5, 0)));

        // Both tombstones physically gone, only the live seat remains
        assertEquals(1L, connection.sync().zcard(key));

    }

    /**
     * Verifies that stale members are pruned before the seat limit is
     * checked, so that a connection is never refused because of seats held
     * by replicas that no longer exist.
     *
     * @throws Exception
     *     If a seat cannot be acquired.
     */
    @Test
    public void tombstonesDoNotConsumeCapacity() throws Exception {

        String key = ClusterKeys.connectionIndex("conn-1");

        // Three dead members against a limit of 2 -- if they counted, the next
        // acquire would be refused
        long ancient = System.currentTimeMillis() - (STALE_WINDOW_MS * 10);
        connection.sync().zadd(key, (double) ancient, "dead-1");
        connection.sync().zadd(key, (double) ancient, "dead-2");
        connection.sync().zadd(key, (double) ancient, "dead-3");

        assertEquals(SeatResult.SUCCESS, store.acquireSeats(
                SeatRequestBuilder.forConnection("live-1", "alice", "conn-1", 2, 0)));
        assertEquals(SeatResult.SUCCESS, store.acquireSeats(
                SeatRequestBuilder.forConnection("live-2", "bob", "conn-1", 2, 0)));

    }

}
