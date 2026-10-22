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

import com.google.inject.Inject;
import org.apache.guacamole.auth.jdbc.connection.ConnectionService;
import org.apache.guacamole.auth.jdbc.sharing.ConnectionSharingService;
import org.apache.guacamole.auth.jdbc.sharingprofile.SharingProfileService;
import org.apache.guacamole.auth.jdbc.tunnel.GuacamoleTunnelService;
import org.apache.guacamole.auth.jdbc.user.ModeledAuthenticatedUser;
import org.apache.guacamole.cluster.ClusterStore;

/**
 * Creates the JoinContext of an authenticated user.
 */
public class JoinContextFactory {

    @Inject
    private GuacamoleTunnelService tunnelService;

    @Inject
    private ConnectionService connectionService;

    @Inject
    private SharingProfileService sharingProfileService;

    @Inject
    private ConnectionSharingService connectionSharingService;

    @Inject
    private ClusterStore clusterStore;

    /**
     * @param user
     *     The authenticated user.
     *
     * @return
     *     A JoinContext acting as that user.
     */
    public JoinContext create(ModeledAuthenticatedUser user) {
        return new JdbcJoinContext(user, tunnelService, connectionService,
                sharingProfileService, connectionSharingService, clusterStore);
    }

}
