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

package org.apache.guacamole.auth.jdbc.join;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleSecurityException;
import org.apache.guacamole.auth.jdbc.connection.ConnectionService;
import org.apache.guacamole.auth.jdbc.connection.ModeledConnection;
import org.apache.guacamole.auth.jdbc.sharing.ConnectionSharingService;
import org.apache.guacamole.auth.jdbc.sharingprofile.ModeledSharingProfile;
import org.apache.guacamole.auth.jdbc.sharingprofile.SharingProfileService;
import org.apache.guacamole.auth.jdbc.tunnel.ActiveConnectionRecord;
import org.apache.guacamole.auth.jdbc.tunnel.GuacamoleTunnelService;
import org.apache.guacamole.auth.jdbc.user.ModeledAuthenticatedUser;
import org.apache.guacamole.cluster.ClusterStore;
import org.apache.guacamole.cluster.TunnelRegistration;
import org.apache.guacamole.net.GuacamoleTunnel;

/**
 * JoinContext backed by the JDBC services and the cluster store.
 */
public class JdbcJoinContext implements JoinContext {

    private final ModeledAuthenticatedUser user;
    private final GuacamoleTunnelService tunnelService;
    private final ConnectionService connectionService;
    private final SharingProfileService sharingProfileService;
    private final ConnectionSharingService connectionSharingService;
    private final ClusterStore clusterStore;

    /**
     * Local records seen by getPrimarySessions(), by record UUID.
     */
    private final Map<String, ActiveConnectionRecord> localRecords =
            new HashMap<String, ActiveConnectionRecord>();

    /**
     * Creates a JdbcJoinContext acting as the given user.
     *
     * @param user
     *     The calling user.
     *
     * @param tunnelService
     *     Source of this node's active connections.
     *
     * @param connectionService
     *     Used to check READ permission on connections.
     *
     * @param sharingProfileService
     *     Used to list sharing profiles the user may use.
     *
     * @param connectionSharingService
     *     Used to share the user's sessions.
     *
     * @param clusterStore
     *     Source of other nodes' sessions, and route for ending them.
     */
    JdbcJoinContext(ModeledAuthenticatedUser user, GuacamoleTunnelService tunnelService,
            ConnectionService connectionService, SharingProfileService sharingProfileService,
            ConnectionSharingService connectionSharingService, ClusterStore clusterStore) {
        this.user = user;
        this.tunnelService = tunnelService;
        this.connectionService = connectionService;
        this.sharingProfileService = sharingProfileService;
        this.connectionSharingService = connectionSharingService;
        this.clusterStore = clusterStore;
    }

    @Override
    public String getUsername() {
        return user.getIdentifier();
    }

    @Override
    public boolean canReadConnection(String connectionIdentifier) throws GuacamoleException {
        return connectionService.retrieveObject(user, connectionIdentifier) != null;
    }

    @Override
    public List<PrimarySession> getPrimarySessions(String connectionIdentifier)
            throws GuacamoleException {

        List<PrimarySession> sessions = new ArrayList<PrimarySession>();
        Set<String> seen = new HashSet<String>();

        ModeledConnection connection = connectionService.retrieveObject(user, connectionIdentifier);
        if (connection == null)
            return sessions;

        for (ActiveConnectionRecord record : tunnelService.getActiveConnections(connection)) {
            if (!record.isPrimaryConnection())
                continue;
            String uuid = record.getUUID().toString();
            localRecords.put(uuid, record);
            seen.add(uuid);
            sessions.add(new PrimarySession(uuid, record.getUsername()));
        }

        for (TunnelRegistration registration : clusterStore.listTunnels()) {
            String uuid = registration.getRecordUuid();
            if (uuid == null || seen.contains(uuid)
                    || registration.getSharingProfileIdentifier() != null
                    || !connectionIdentifier.equals(registration.getConnectionIdentifier()))
                continue;
            seen.add(uuid);
            sessions.add(new PrimarySession(uuid, registration.getUsername()));
        }

        return sessions;
    }

    /**
     * Returns one of the caller's own primary sessions on this node.
     *
     * @param recordUuid
     *     The session's record UUID.
     *
     * @return
     *     The session's record.
     *
     * @throws GuacamoleException
     *     If the session is not one of the caller's own on this node.
     */
    private ActiveConnectionRecord ownRecord(String recordUuid) throws GuacamoleException {
        for (ActiveConnectionRecord record : tunnelService.getActiveConnections(user)) {
            if (record.isPrimaryConnection()
                    && getUsername().equals(record.getUsername())
                    && record.getUUID().toString().equals(recordUuid))
                return record;
        }
        throw new GuacamoleSecurityException("Permission denied.");
    }

    @Override
    public Set<String> getOwnPrimaryRecordUuids() throws GuacamoleException {
        Set<String> uuids = new HashSet<String>();
        for (ActiveConnectionRecord record : tunnelService.getActiveConnections(user))
            if (record.isPrimaryConnection() && getUsername().equals(record.getUsername()))
                uuids.add(record.getUUID().toString());
        return uuids;
    }

    @Override
    public List<SharingOption> getSharingOptions(String recordUuid) throws GuacamoleException {
        ModeledConnection connection = ownRecord(recordUuid).getConnection();
        List<SharingOption> options = new ArrayList<SharingOption>();
        for (ModeledSharingProfile profile : sharingProfileService.retrieveObjects(
                user, connection.getSharingProfileIdentifiers()))
            options.add(new SharingOption(profile.getIdentifier(), profile.getName()));
        return options;
    }

    @Override
    public String share(String recordUuid, String sharingProfileIdentifier)
            throws GuacamoleException {
        return connectionSharingService.shareConnection(user, ownRecord(recordUuid),
                sharingProfileIdentifier).getShareKey();
    }

    @Override
    public void endSession(PrimarySession session) throws GuacamoleException {
        ActiveConnectionRecord local = localRecords.get(session.getRecordUuid());
        if (local != null) {
            GuacamoleTunnel tunnel = local.getTunnel();
            if (tunnel != null && tunnel.isOpen())
                tunnel.close();
            return;
        }
        clusterStore.requestKill(session.getRecordUuid());
    }

}
