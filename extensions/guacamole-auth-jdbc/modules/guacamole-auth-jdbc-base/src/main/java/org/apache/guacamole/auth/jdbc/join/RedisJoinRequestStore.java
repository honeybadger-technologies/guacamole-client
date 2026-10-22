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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.cluster.ClusterStore;

/**
 * JoinRequestStore shared by every web application instance via the cluster
 * store.
 */
public class RedisJoinRequestStore implements JoinRequestStore {

    private final ClusterStore clusterStore;
    private final LongSupplier clock;

    /**
     * Creates a store using the system clock.
     *
     * @param clusterStore
     *     The cluster store holding requests.
     */
    @Inject
    public RedisJoinRequestStore(ClusterStore clusterStore) {
        this(clusterStore, System::currentTimeMillis);
    }

    /**
     * Creates a store using the given clock.
     *
     * @param clusterStore
     *     The cluster store holding requests.
     *
     * @param clock
     *     Supplies the current time, in milliseconds since the epoch.
     */
    public RedisJoinRequestStore(ClusterStore clusterStore, LongSupplier clock) {
        this.clusterStore = clusterStore;
        this.clock = clock;
    }

    @Override
    public void put(JoinRequest request) throws GuacamoleException {
        Map<String, String> fields = new HashMap<String, String>();
        fields.put("connectionIdentifier", request.getConnectionIdentifier());
        fields.put("recordUuids", String.join(",", request.getRecordUuids()));
        fields.put("requester", request.getRequester());
        fields.put("expires", Long.toString(request.getExpires()));
        fields.put("status", request.getStatus().name());
        if (request.getShareKey() != null)
            fields.put("shareKey", request.getShareKey());

        long remainingMs = request.getExpires() - clock.getAsLong();
        int ttlSeconds = (int) Math.max(1, (remainingMs + 999) / 1000);
        clusterStore.putJoinRequest(request.getId(), fields, request.getRecordUuids(), ttlSeconds);
    }

    @Override
    public JoinRequest get(String id) throws GuacamoleException {
        Map<String, String> fields = clusterStore.getJoinRequest(id);
        if (fields == null)
            return null;
        JoinRequest request = new JoinRequest(id,
                fields.get("connectionIdentifier"),
                Arrays.asList(fields.get("recordUuids").split(",")),
                fields.get("requester"),
                Long.parseLong(fields.get("expires")),
                JoinRequest.Status.valueOf(fields.get("status")),
                fields.get("shareKey"));
        return request.isExpired(clock.getAsLong()) ? null : request;
    }

    @Override
    public List<JoinRequest> listFor(String recordUuid) throws GuacamoleException {
        List<JoinRequest> matches = new ArrayList<JoinRequest>();
        for (String id : clusterStore.listJoinRequests(recordUuid)) {
            JoinRequest request = get(id);
            if (request != null)
                matches.add(request);
        }
        return matches;
    }

    @Override
    public boolean resolve(String id, JoinRequest.Status status, String shareKey)
            throws GuacamoleException {
        if (get(id) == null)
            return false;
        return clusterStore.resolveJoinRequest(id, status.name(), shareKey);
    }

    @Override
    public void remove(String id) throws GuacamoleException {
        clusterStore.removeJoinRequest(id);
    }

}
