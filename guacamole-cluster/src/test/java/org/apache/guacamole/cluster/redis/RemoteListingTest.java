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
import java.util.Collection;
import org.apache.guacamole.cluster.ClusterKeys;
import org.apache.guacamole.cluster.TunnelRegistration;
import org.apache.guacamole.cluster.guacd.GuacdEndpoint;
import org.apache.guacamole.net.auth.GuacamoleProxyConfiguration.EncryptionMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RemoteListingTest {

    private static final long STALE_WINDOW_MS = 30000L;

    private static final GuacdEndpoint GUACD_A =
            new GuacdEndpoint("guacd-a", 4822, EncryptionMethod.NONE);

    /**
     * Skips this class rather than failing it when no Docker daemon is
     * reachable. The image build runs the full test suite inside a container
     * that has no daemon of its own.
     */
    @BeforeAll
    public static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available; skipping Redis-backed tests.");
    }

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisClusterStore store;

    @BeforeEach
    public void setUp() {
        client = RedisClient.create(RedisTestSupport.redisUri());
        connection = client.connect();
        connection.sync().flushall();
        store = new RedisClusterStore(RedisTestSupport.redisUri(), STALE_WINDOW_MS, "node-1");
    }

    @AfterEach
    public void tearDown() {
        store.shutdown();
        connection.close();
        client.shutdown();
    }

    private TunnelRegistration registration(String seat, String user, String recordUuid) {
        return new TunnelRegistration(seat, "node-2", "$" + seat, GUACD_A,
                "conn-1", null, null, user, "10.0.0.5",
                System.currentTimeMillis(), recordUuid);
    }

    @Test
    public void listsEveryLiveTunnel() throws Exception {

        store.registerTunnel(registration("seat-1", "alice", "rec-1"));
        store.registerTunnel(registration("seat-2", "bob", "rec-2"));

        Collection<TunnelRegistration> listed = store.listTunnels();
        assertEquals(2, listed.size());

    }

    @Test
    public void listedEntriesCarryTheFieldsTheAdminViewNeeds() throws Exception {

        store.registerTunnel(registration("seat-1", "alice", "rec-1"));

        TunnelRegistration listed = store.listTunnels().iterator().next();
        assertEquals("seat-1", listed.getTunnelUuid());
        assertEquals("rec-1", listed.getRecordUuid());
        assertEquals("alice", listed.getUsername());
        assertEquals("conn-1", listed.getConnectionIdentifier());
        assertEquals("node-2", listed.getNodeId());
        assertEquals("10.0.0.5", listed.getRemoteHost());

    }

    @Test
    public void omitsStaleTunnelsLeftByDeadReplicas() throws Exception {

        store.registerTunnel(registration("seat-1", "alice", "rec-1"));

        // A tunnel whose replica died: still a member of the index, but its
        // hash has expired and its score is far outside the window
        long ancient = System.currentTimeMillis() - (STALE_WINDOW_MS * 10);
        connection.sync().zadd(ClusterKeys.ALL_INDEX, (double) ancient, "seat-dead");

        Collection<TunnelRegistration> listed = store.listTunnels();
        assertEquals(1, listed.size());
        assertEquals("seat-1", listed.iterator().next().getTunnelUuid());

    }

    @Test
    public void returnsEmptyRatherThanFailingWhenThereIsNothing() throws Exception {
        assertTrue(store.listTunnels().isEmpty());
    }

}
