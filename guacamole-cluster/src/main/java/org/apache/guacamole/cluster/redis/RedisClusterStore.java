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

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.util.ArrayList;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.cluster.ClusterKeys;
import org.apache.guacamole.cluster.ClusterKillHandler;
import org.apache.guacamole.cluster.ClusterShareRevocationHandler;
import org.apache.guacamole.cluster.ClusterStore;
import org.apache.guacamole.cluster.SeatKey;
import org.apache.guacamole.cluster.SeatRequest;
import org.apache.guacamole.cluster.SeatResult;
import org.apache.guacamole.cluster.SharedConnectionEntry;
import org.apache.guacamole.cluster.TunnelRegistration;
import org.apache.guacamole.cluster.guacd.GuacdEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis-backed implementation of ClusterStore, using Lettuce.
 */
public class RedisClusterStore implements ClusterStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisClusterStore.class);

    /**
     * Seconds any single Redis command may take before it is abandoned. Seat
     * acquisition sits directly in the connect path, so this bounds how long a
     * user waits when Redis is degraded rather than cleanly down.
     */
    private static final long COMMAND_TIMEOUT_SECONDS = 2L;

    /**
     * Milliseconds before an unavailable store is probed again.
     */
    private static final long RETRY_INTERVAL_MS = 10000L;

    private static final String ACQUIRE_SEATS_SCRIPT =
            "/org/apache/guacamole/cluster/redis/acquire-seats.lua";

    private static final String RECORD_AUTH_FAILURE_SCRIPT =
            "/org/apache/guacamole/cluster/redis/record-auth-failure.lua";

    private final RedisClient client;

    /**
     * Established on first use rather than in the constructor. Connecting
     * eagerly means a replica that starts while Redis is unreachable fails to
     * build its Guice injector at all, which takes the whole authentication
     * provider down with it -- the replica is then dead rather than degraded.
     */
    private volatile StatefulRedisConnection<String, String> connection;

    /**
     * When the store last failed, so an unavailable store is re-probed rather
     * than written off for the lifetime of the replica.
     */
    private volatile long unavailableSince = 0L;

    /**
     * Dedicated connection for kill broadcasts. Pub/sub cannot share the
     * command connection: a subscribed Redis connection accepts only
     * subscription commands.
     */
    private volatile StatefulRedisPubSubConnection<String, String> pubSubConnection;

    /**
     * Handler invoked when a kill is requested anywhere in the cluster, or null
     * if this replica has not registered one.
     */
    private volatile ClusterKillHandler killHandler;

    /**
     * Handler invoked when a share key is revoked anywhere in the cluster, or
     * null if this replica has not registered one.
     */
    private volatile ClusterShareRevocationHandler shareRevocationHandler;
    private final LuaScript acquireSeats = LuaScript.load(ACQUIRE_SEATS_SCRIPT);
    private final LuaScript recordAuthFailure = LuaScript.load(RECORD_AUTH_FAILURE_SCRIPT);
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

        // The timeout belongs on the URI; AbstractRedisClient.setDefaultTimeout
        // is deprecated, and this module compiles with -Werror.
        RedisURI parsedUri = RedisURI.create(redisUri);
        parsedUri.setTimeout(Duration.ofSeconds(COMMAND_TIMEOUT_SECONDS));
        this.client = RedisClient.create(parsedUri);

        // Fail fast rather than queue. Lettuce's default is to buffer commands
        // while the connection is down and wait out a long command timeout, so
        // the first connect attempt after Redis becomes unreachable blocks for
        // minutes instead of degrading -- measured at over 180 seconds on a
        // live cluster. Rejecting immediately is what makes the documented
        // fallback to per-replica limits actually reachable.
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());

        this.staleWindowMs = staleWindowMs;
        this.nodeId = nodeId;
    }

    /**
     * Returns the command interface, connecting if necessary.
     *
     * @return
     *     The synchronous command interface.
     *
     * @throws RedisException
     *     If a connection cannot be established.
     */
    private RedisCommands<String, String> commands() {

        StatefulRedisConnection<String, String> current = connection;
        if (current != null && current.isOpen())
            return current.sync();

        synchronized (this) {

            if (connection != null && connection.isOpen())
                return connection.sync();

            connection = client.connect();
            return connection.sync();

        }

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
            unavailableSince = System.currentTimeMillis();
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
            unavailableSince = System.currentTimeMillis();
            throw new GuacamoleServerException("Unable to release cluster seats.", e);
        }

    }

    /**
     * Returns every sorted set the given tunnel is indexed in. The group index
     * is included only when the tunnel was established through a balancing
     * group.
     *
     * @param registration
     *     The tunnel to index.
     *
     * @return
     *     Every index key for the given tunnel.
     */
    private List<String> indexKeys(TunnelRegistration registration) {

        List<String> keys = new ArrayList<String>(5);
        keys.add(ClusterKeys.ALL_INDEX);
        keys.add(ClusterKeys.userIndex(registration.getUsername()));
        keys.add(ClusterKeys.guacdIndex(registration.getEndpoint().toKey()));
        keys.add(ClusterKeys.connectionIndex(registration.getConnectionIdentifier()));

        if (registration.getGroupIdentifier() != null)
            keys.add(ClusterKeys.groupIndex(registration.getGroupIdentifier()));

        return keys;

    }

    /**
     * @return
     *     The current time according to the Redis server, in milliseconds.
     */
    private long serverTimeMillis(RedisCommands<String, String> commands) {
        List<String> time = commands.time();
        return (Long.parseLong(time.get(0)) * 1000L)
                + (Long.parseLong(time.get(1)) / 1000L);
    }

    @Override
    public void registerTunnel(TunnelRegistration registration) throws GuacamoleException {

        try {

            RedisCommands<String, String> commands = commands();
            long now = serverTimeMillis(commands);

            Map<String, String> record = new HashMap<String, String>();
            record.put("nodeId", registration.getNodeId());
            record.put("guacdConnectionId", registration.getGuacdConnectionId());
            record.put("guacdEndpoint", registration.getEndpoint().toKey());
            record.put("connIdentifier", registration.getConnectionIdentifier());
            record.put("username", registration.getUsername());
            record.put("startTime", Long.toString(registration.getStartTime()));

            if (registration.getGroupIdentifier() != null)
                record.put("groupIdentifier", registration.getGroupIdentifier());

            if (registration.getSharingProfileIdentifier() != null)
                record.put("sharingProfileId", registration.getSharingProfileIdentifier());

            if (registration.getRemoteHost() != null)
                record.put("remoteHost", registration.getRemoteHost());

            if (registration.getRecordUuid() != null)
                record.put("recordUuid", registration.getRecordUuid());

            String tunnelKey = ClusterKeys.tunnel(registration.getTunnelUuid());
            commands.hset(tunnelKey, record);
            commands.pexpire(tunnelKey, staleWindowMs);

            for (String key : indexKeys(registration))
                commands.zadd(key, (double) now, registration.getTunnelUuid());

            if (registration.getGuacdConnectionId() != null) {
                String routeKey = ClusterKeys.route(registration.getGuacdConnectionId());
                commands.psetex(routeKey, staleWindowMs, registration.getEndpoint().toKey());
            }

            // Lets a kill addressed by history record UUID find the cluster
            // state, which is keyed by seat token
            if (registration.getRecordUuid() != null)
                commands.psetex(ClusterKeys.record(registration.getRecordUuid()),
                        staleWindowMs, registration.getTunnelUuid());

            available = true;

        }
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            throw new GuacamoleServerException("Unable to register tunnel with cluster.", e);
        }

    }

    @Override
    public void unregisterTunnel(TunnelRegistration registration) throws GuacamoleException {

        try {

            RedisCommands<String, String> commands = commands();

            for (String key : indexKeys(registration))
                commands.zrem(key, registration.getTunnelUuid());

            commands.del(ClusterKeys.tunnel(registration.getTunnelUuid()));

            if (registration.getGuacdConnectionId() != null)
                commands.del(ClusterKeys.route(registration.getGuacdConnectionId()));

            if (registration.getRecordUuid() != null)
                commands.del(ClusterKeys.record(registration.getRecordUuid()));

            available = true;

        }
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            throw new GuacamoleServerException("Unable to unregister tunnel from cluster.", e);
        }

    }

    @Override
    public void heartbeat(Collection<TunnelRegistration> registrations) throws GuacamoleException {

        if (registrations.isEmpty())
            return;

        try {

            RedisCommands<String, String> commands = commands();
            long now = serverTimeMillis(commands);

            for (TunnelRegistration registration : registrations) {

                for (String key : indexKeys(registration))
                    commands.zadd(key, (double) now, registration.getTunnelUuid());

                commands.pexpire(ClusterKeys.tunnel(registration.getTunnelUuid()), staleWindowMs);

                if (registration.getGuacdConnectionId() != null)
                    commands.pexpire(ClusterKeys.route(registration.getGuacdConnectionId()),
                            staleWindowMs);

                if (registration.getRecordUuid() != null)
                    commands.pexpire(ClusterKeys.record(registration.getRecordUuid()),
                            staleWindowMs);

            }

            available = true;

        }
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            throw new GuacamoleServerException("Unable to refresh cluster heartbeat.", e);
        }

    }

    @Override
    public GuacdEndpoint lookupRoute(String guacdConnectionId) throws GuacamoleException {

        try {

            String value = commands().get(ClusterKeys.route(guacdConnectionId));
            available = true;

            if (value == null)
                return null;

            return GuacdEndpoint.fromKey(value);

        }
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            throw new GuacamoleServerException("Unable to look up guacd route.", e);
        }

    }

    @Override
    public long countTunnels(GuacdEndpoint endpoint) {

        try {

            RedisCommands<String, String> commands = commands();
            long cutoff = serverTimeMillis(commands) - staleWindowMs;

            Long count = commands.zcount(ClusterKeys.guacdIndex(endpoint.toKey()),
                    io.lettuce.core.Range.from(
                            io.lettuce.core.Range.Boundary.excluding((double) cutoff),
                            io.lettuce.core.Range.Boundary.unbounded()));

            available = true;
            return count == null ? 0L : count;

        }
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            logger.warn("Unable to count tunnels for guacd \"{}\". Treating as unloaded.",
                    endpoint, e);
            return 0L;
        }

    }

    @Override
    public Collection<TunnelRegistration> listTunnels() {

        List<TunnelRegistration> tunnels = new ArrayList<TunnelRegistration>();

        try {

            RedisCommands<String, String> commands = commands();
            long cutoff = serverTimeMillis(commands) - staleWindowMs;

            // Score-filtered, so members left behind by a dead replica are
            // never listed even before anything prunes them
            List<String> seatTokens = commands.zrangebyscore(ClusterKeys.ALL_INDEX,
                    io.lettuce.core.Range.from(
                            io.lettuce.core.Range.Boundary.excluding((double) cutoff),
                            io.lettuce.core.Range.Boundary.unbounded()));

            for (String seatToken : seatTokens) {

                Map<String, String> record = commands.hgetall(ClusterKeys.tunnel(seatToken));

                // The hash expires on its own TTL, so a member can outlive it
                if (record.isEmpty())
                    continue;

                String endpointKey = record.get("guacdEndpoint");
                String startTime = record.get("startTime");

                tunnels.add(new TunnelRegistration(
                        seatToken,
                        record.get("nodeId"),
                        record.get("guacdConnectionId"),
                        endpointKey != null ? GuacdEndpoint.fromKey(endpointKey) : null,
                        record.get("connIdentifier"),
                        record.get("groupIdentifier"),
                        record.get("sharingProfileId"),
                        record.get("username"),
                        record.get("remoteHost"),
                        startTime != null ? Long.parseLong(startTime) : 0L,
                        record.get("recordUuid")));

            }

            available = true;

        }

        // The administrative view degrades to replica-local rather than
        // failing outright (spec 6.1)
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            logger.warn("Unable to list cluster tunnels. The active connection "
                    + "view will show only this replica's sessions.", e);
        }

        return tunnels;

    }

    @Override
    public String lookupSeatToken(String recordUuid) {

        try {
            String token = commands().get(ClusterKeys.record(recordUuid));
            available = true;
            return token;
        }

        // The caller degrades to a replica-local view rather than failing
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            logger.warn("Unable to resolve record \"{}\" to a seat token.", recordUuid, e);
            return null;
        }

    }

    @Override
    public String getNodeId() {
        return nodeId;
    }

    @Override
    public boolean isClustered() {
        return true;
    }

    @Override
    public boolean isAvailable() {

        if (available)
            return true;

        // Re-probe periodically. Without this a replica that saw one failure
        // would use replica-local limits forever, even after Redis returned.
        return System.currentTimeMillis() - unavailableSince > RETRY_INTERVAL_MS;

    }

    @Override
    public void onKillRequest(ClusterKillHandler handler) {
        this.killHandler = handler;
        subscribeIfNeeded();
    }

    @Override
    public void onShareRevoked(ClusterShareRevocationHandler handler) {
        this.shareRevocationHandler = handler;
        subscribeIfNeeded();
    }

    /**
     * Opens the single pub/sub connection this replica uses for every cluster
     * message, subscribing to each channel. Both kills and share key
     * revocations arrive here, dispatched by channel name, so registering a
     * second handler does not open a second connection.
     */
    private synchronized void subscribeIfNeeded() {

        if (pubSubConnection != null)
            return;

        try {

            StatefulRedisPubSubConnection<String, String> pubSub = client.connectPubSub();

            pubSub.addListener(new RedisPubSubAdapter<String, String>() {

                @Override
                public void message(String channel, String payload) {
                    try {

                        if (ClusterKeys.KILL_CHANNEL.equals(channel)) {
                            ClusterKillHandler handler = killHandler;
                            if (handler != null)
                                handler.killLocalTunnel(payload);
                        }

                        else if (ClusterKeys.SHARE_REVOKE_CHANNEL.equals(channel)) {
                            ClusterShareRevocationHandler handler = shareRevocationHandler;
                            if (handler != null)
                                handler.shareRevoked(payload);
                        }

                    }

                    // A handler that throws must not kill the subscriber
                    catch (Throwable e) {
                        logger.warn("Cluster message on \"{}\" could not be handled.",
                                channel, e);
                    }
                }

            });

            pubSub.sync().subscribe(ClusterKeys.KILL_CHANNEL,
                    ClusterKeys.SHARE_REVOKE_CHANNEL);
            pubSubConnection = pubSub;

        }

        // Without a subscription this replica simply never honours remote kills
        // or revocations; it must still serve connections
        catch (RedisException e) {
            logger.error("Unable to subscribe to cluster messages. Sessions on "
                    + "this replica cannot be terminated from another one, and "
                    + "share keys revoked elsewhere stay open here.", e);
        }

    }

    @Override
    public void publishShareRevocation(String shareKey) {

        try {
            commands().publish(ClusterKeys.SHARE_REVOKE_CHANNEL, shareKey);
            available = true;
        }

        // The key is already gone from the cluster, so it can no longer be
        // redeemed; only the closing of tunnels already opened from it is lost
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            logger.warn("Unable to announce revocation of a share key. Tunnels "
                    + "opened from it on other replicas will survive until "
                    + "their session ends.", e);
        }

    }

    @Override
    public void requestKill(String recordUuid) throws GuacamoleException {

        try {
            commands().publish(ClusterKeys.KILL_CHANNEL, recordUuid);
            available = true;
        }

        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            throw new GuacamoleServerException("Unable to request cluster kill.", e);
        }

    }

    @Override
    public boolean isTunnelLive(String seatToken) {

        try {
            boolean live = commands().exists(ClusterKeys.tunnel(seatToken)) > 0;
            available = true;
            return live;
        }

        // Reporting "gone" would claim a kill landed when that is unknown;
        // reporting "live" makes the caller wait out its own timeout and
        // report failure, which is the honest answer
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            return true;
        }

    }

    @Override
    public void putShareKey(String shareKey, SharedConnectionEntry entry) {

        try {

            Map<String, String> record = new HashMap<String, String>();
            record.put("seatToken", entry.getSeatToken());
            record.put("guacdConnectionId", entry.getGuacdConnectionId());
            record.put("connIdentifier", entry.getConnectionIdentifier());
            record.put("sharedBy", entry.getSharedBy());

            if (entry.getSharingProfileIdentifier() != null)
                record.put("sharingProfileId", entry.getSharingProfileIdentifier());

            commands().hset(ClusterKeys.shareKey(shareKey), record);
            available = true;

        }

        // A share key that cannot be stored simply never works elsewhere, which
        // is how an expired key already behaves. It must not fail the share.
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            logger.warn("Unable to publish share key to the cluster. It will "
                    + "work only on this replica.", e);
        }

    }

    @Override
    public SharedConnectionEntry getShareKey(String shareKey) {

        try {

            Map<String, String> record = commands().hgetall(ClusterKeys.shareKey(shareKey));
            available = true;

            if (record.isEmpty())
                return null;

            return new SharedConnectionEntry(
                    record.get("seatToken"),
                    record.get("guacdConnectionId"),
                    record.get("connIdentifier"),
                    record.get("sharingProfileId"),
                    record.get("sharedBy"));

        }

        // An unreadable key is an invalid key, which is how an expired key
        // already behaves
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            return null;
        }

    }

    @Override
    public void removeShareKey(String shareKey) {

        try {
            commands().del(ClusterKeys.shareKey(shareKey));
            available = true;
        }

        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            logger.warn("Unable to remove share key from the cluster. It will "
                    + "stop working when its session ends.", e);
        }

    }

    /**
     * Clears the entire keyspace. Intended only for tests.
     */
    public void flushForTesting() {
        commands().flushall();
    }

    @Override
    public int recordAuthenticationFailure(String address, int banDurationSeconds) {

        try {
            long count = recordAuthFailure.eval(commands(),
                    new String[] { ClusterKeys.authFailure(address) },
                    new String[] { Integer.toString(banDurationSeconds) });
            available = true;
            return (int) count;
        }

        // -1 is "unknown", never "none": reporting zero here would unban every
        // address for as long as Redis is unreachable
        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            return -1;
        }

    }

    @Override
    public int getAuthenticationFailures(String address) {

        String value;

        try {
            value = commands().get(ClusterKeys.authFailure(address));
            available = true;
        }

        catch (RedisException e) {
            available = false;
            unavailableSince = System.currentTimeMillis();
            return -1;
        }

        if (value == null)
            return 0;

        try {
            return Integer.parseInt(value);
        }

        // A counter that is not a number is corrupt rather than absent, so the
        // caller is routed to its fallback rather than told there are none
        catch (NumberFormatException e) {
            logger.warn("Authentication failure counter for \"{}\" is not a "
                    + "number. Falling back to replica-local tracking.", address);
            return -1;
        }

    }

    /**
     * Returns the remaining lifetime of an address's failure counter, in
     * seconds. Intended only for tests.
     *
     * @param address
     *     The address whose counter should be examined.
     *
     * @return
     *     The remaining lifetime of the counter, in seconds.
     */
    public long authFailureTtlForTesting(String address) {
        return commands().ttl(ClusterKeys.authFailure(address));
    }

    @Override
    public void shutdown() {

        StatefulRedisPubSubConnection<String, String> pubSub = pubSubConnection;
        if (pubSub != null)
            pubSub.close();

        StatefulRedisConnection<String, String> current = connection;
        if (current != null)
            current.close();

        client.shutdown();

    }

}
