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
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.Collection;
import java.util.List;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.cluster.ClusterStore;
import org.apache.guacamole.cluster.SeatKey;
import org.apache.guacamole.cluster.SeatRequest;
import org.apache.guacamole.cluster.SeatResult;
import org.apache.guacamole.cluster.TunnelRegistration;
import org.apache.guacamole.cluster.guacd.GuacdEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis-backed implementation of ClusterStore, using Lettuce.
 */
public class RedisClusterStore implements ClusterStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisClusterStore.class);

    private static final String ACQUIRE_SEATS_SCRIPT =
            "/org/apache/guacamole/cluster/redis/acquire-seats.lua";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final LuaScript acquireSeats = LuaScript.load(ACQUIRE_SEATS_SCRIPT);
    private final long staleWindowMs;
    private final String nodeId;

    private volatile boolean available = true;

    /**
     * @param redisUri
     *     Lettuce URI of the Redis server or Sentinel set.
     *
     * @param staleWindowMs
     *     Milliseconds after which an unrefreshed member is considered dead.
     *
     * @param nodeId
     *     Identity of this replica.
     */
    public RedisClusterStore(String redisUri, long staleWindowMs, String nodeId) {
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
        this.staleWindowMs = staleWindowMs;
        this.nodeId = nodeId;
    }

    private RedisCommands<String, String> commands() {
        return connection.sync();
    }

    @Override
    public SeatResult acquireSeats(SeatRequest request) throws GuacamoleException {

        List<SeatKey> seatKeys = request.getKeys();

        String[] keys = new String[seatKeys.size()];
        String[] args = new String[seatKeys.size() + 2];

        args[0] = request.getTunnelUuid();
        args[1] = Long.toString(staleWindowMs);

        for (int i = 0; i < seatKeys.size(); i++) {
            keys[i] = seatKeys.get(i).getRedisKey();
            args[i + 2] = Integer.toString(seatKeys.get(i).getLimit());
        }

        try {

            long result = acquireSeats.eval(commands(), keys, args);
            available = true;

            if (result == 0)
                return SeatResult.SUCCESS;

            return seatKeys.get((int) result - 1).getFailureResult();

        }
        catch (RedisException e) {
            available = false;
            throw new GuacamoleServerException("Unable to acquire cluster seats.", e);
        }

    }

    @Override
    public void releaseSeats(SeatRequest request) throws GuacamoleException {

        try {

            RedisCommands<String, String> commands = commands();
            for (SeatKey key : request.getKeys())
                commands.zrem(key.getRedisKey(), request.getTunnelUuid());

            available = true;

        }
        catch (RedisException e) {
            available = false;
            throw new GuacamoleServerException("Unable to release cluster seats.", e);
        }

    }

    @Override
    public void registerTunnel(TunnelRegistration registration) throws GuacamoleException {
        throw new UnsupportedOperationException("Implemented in Task 5.");
    }

    @Override
    public void unregisterTunnel(TunnelRegistration registration) throws GuacamoleException {
        throw new UnsupportedOperationException("Implemented in Task 5.");
    }

    @Override
    public void heartbeat(Collection<TunnelRegistration> registrations) throws GuacamoleException {
        throw new UnsupportedOperationException("Implemented in Task 5.");
    }

    @Override
    public GuacdEndpoint lookupRoute(String guacdConnectionId) throws GuacamoleException {
        throw new UnsupportedOperationException("Implemented in Task 5.");
    }

    @Override
    public long countTunnels(GuacdEndpoint endpoint) {
        throw new UnsupportedOperationException("Implemented in Task 5.");
    }

    @Override
    public String getNodeId() {
        return nodeId;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public void shutdown() {
        connection.close();
        client.shutdown();
    }

}
