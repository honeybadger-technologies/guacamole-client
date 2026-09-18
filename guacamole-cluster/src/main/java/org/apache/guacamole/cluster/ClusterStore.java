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

import java.util.Collection;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.cluster.guacd.GuacdEndpoint;

/**
 * Cluster-wide state shared by every Guacamole web application replica.
 *
 * Implementations must tolerate the backing store being unreachable. Methods
 * that cannot safely proceed without it throw GuacamoleException; methods whose
 * callers can degrade to replica-local behavior return a neutral value and
 * report unavailability through isAvailable().
 */
public interface ClusterStore {

    /**
     * Atomically prunes stale members, checks every limit, and takes seats.
     *
     * @param request
     *     The seats to acquire.
     *
     * @return
     *     SUCCESS if every limit was satisfied, otherwise the failure result of
     *     the first key whose limit was reached.
     *
     * @throws GuacamoleException
     *     If the store is unreachable.
     */
    SeatResult acquireSeats(SeatRequest request) throws GuacamoleException;

    /**
     * Releases seats previously acquired by acquireSeats(). Safe to call more
     * than once for the same tunnel.
     *
     * @param request
     *     The seats to release. Only the tunnel UUID and the key list are used.
     *
     * @throws GuacamoleException
     *     If the store is unreachable.
     */
    void releaseSeats(SeatRequest request) throws GuacamoleException;

    /**
     * Publishes a live tunnel to the cluster, making it visible to every
     * replica and routable by its guacd connection ID.
     *
     * @param registration
     *     The tunnel to publish.
     *
     * @throws GuacamoleException
     *     If the store is unreachable.
     */
    void registerTunnel(TunnelRegistration registration) throws GuacamoleException;

    /**
     * Removes a tunnel and its route from the cluster. Safe to call for a
     * tunnel that is already absent.
     *
     * @param registration
     *     The tunnel to remove.
     *
     * @throws GuacamoleException
     *     If the store is unreachable.
     */
    void unregisterTunnel(TunnelRegistration registration) throws GuacamoleException;

    /**
     * Refreshes the heartbeat score of every given tunnel, preventing them from
     * ageing out of the cluster indexes.
     *
     * @param registrations
     *     Every tunnel currently owned by this replica.
     *
     * @throws GuacamoleException
     *     If the store is unreachable.
     */
    void heartbeat(Collection<TunnelRegistration> registrations) throws GuacamoleException;

    /**
     * Returns the guacd instance hosting the given guacd connection ID.
     *
     * @param guacdConnectionId
     *     The connection ID issued by guacd in its "ready" instruction.
     *
     * @return
     *     The guacd instance hosting that connection, or null if no live route
     *     exists.
     *
     * @throws GuacamoleException
     *     If the store is unreachable.
     */
    GuacdEndpoint lookupRoute(String guacdConnectionId) throws GuacamoleException;

    /**
     * Returns the number of live tunnels currently assigned to the given guacd
     * instance across the whole cluster.
     *
     * @param endpoint
     *     The guacd instance to count.
     *
     * @return
     *     The number of live tunnels, or 0 if the store is unreachable.
     */
    long countTunnels(GuacdEndpoint endpoint);

    /**
     * Returns every tunnel currently live anywhere in the cluster, including
     * those owned by this replica.
     *
     * @return
     *     Every live tunnel, or an empty collection if the store is
     *     unavailable. Never null -- the administrative view degrades to a
     *     replica-local listing rather than failing.
     */
    Collection<TunnelRegistration> listTunnels();

    /**
     * Returns the seat token of the tunnel whose history record has the given
     * UUID.
     *
     * @param recordUuid
     *     The UUID of the connection history record.
     *
     * @return
     *     The seat token identifying that tunnel's cluster state, or null if
     *     no such tunnel is known or the store is unavailable.
     */
    String lookupSeatToken(String recordUuid);

    /**
     * @return
     *     The identity of this replica, as recorded on every tunnel this
     *     replica owns. Used to decide whether a tunnel can be closed locally
     *     or must be killed through another replica.
     */
    String getNodeId();

    /**
     * @return
     *     true if the backing store was reachable as of the most recent
     *     operation, false if callers should degrade to replica-local behavior.
     */
    boolean isAvailable();

    /**
     * Registers the handler invoked when any replica requests a kill.
     *
     * @param handler
     *     The handler to invoke for every kill request received.
     */
    void onKillRequest(ClusterKillHandler handler);

    /**
     * Asks every replica to close the tunnel with the given history record
     * UUID. Only the owning replica will act.
     *
     * @param recordUuid
     *     The UUID of the history record identifying the tunnel to close.
     *
     * @throws GuacamoleException
     *     If the request cannot be published.
     */
    void requestKill(String recordUuid) throws GuacamoleException;

    /**
     * @param seatToken
     *     The seat token identifying a tunnel.
     *
     * @return
     *     true if that tunnel is still published to the cluster, false if it
     *     has gone or the store is unavailable.
     */
    boolean isTunnelLive(String seatToken);

    /**
     * Releases all resources held by this store.
     */
    /**
     * Publishes the given share key to the cluster, so that it can be redeemed
     * on any replica. Failure to publish is logged rather than thrown: the key
     * then works only on the replica which issued it.
     *
     * @param shareKey
     *     The share key being issued.
     *
     * @param entry
     *     Everything that key resolves to.
     */
    void putShareKey(String shareKey, SharedConnectionEntry entry);

    /**
     * Returns everything the given share key resolves to.
     *
     * @param shareKey
     *     The share key being redeemed.
     *
     * @return
     *     The cluster state behind the given share key, or null if the key is
     *     unknown or cannot be read.
     */
    SharedConnectionEntry getShareKey(String shareKey);

    /**
     * Removes the given share key from the cluster, so that it can no longer
     * be redeemed anywhere.
     *
     * @param shareKey
     *     The share key being removed.
     */
    void removeShareKey(String shareKey);

    void shutdown();

}
