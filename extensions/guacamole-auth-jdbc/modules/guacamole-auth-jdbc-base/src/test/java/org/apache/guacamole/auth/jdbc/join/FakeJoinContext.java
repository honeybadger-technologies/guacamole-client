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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JoinContext over a shared, mutable picture of who is connected where.
 */
public class FakeJoinContext implements JoinContext {

    /**
     * Connection ID to primary sessions, shared by every context in a test.
     */
    private final Map<String, List<PrimarySession>> world;

    private final String username;
    private final Set<String> readable = new HashSet<String>();
    private final List<SharingOption> sharingOptions = new ArrayList<SharingOption>();

    /**
     * Record UUIDs of the sessions this context ended.
     */
    public final List<String> ended = new ArrayList<String>();

    /**
     * "recordUuid/profileId" for each share this context created.
     */
    public final List<String> shared = new ArrayList<String>();

    public FakeJoinContext(Map<String, List<PrimarySession>> world, String username) {
        this.world = world;
        this.username = username;
    }

    public FakeJoinContext canRead(String connectionId) {
        readable.add(connectionId);
        return this;
    }

    public FakeJoinContext offers(String identifier, String name) {
        sharingOptions.add(new SharingOption(identifier, name));
        return this;
    }

    @Override
    public String getUsername() { return username; }

    @Override
    public boolean canReadConnection(String connectionIdentifier) {
        return readable.contains(connectionIdentifier);
    }

    @Override
    public List<PrimarySession> getPrimarySessions(String connectionIdentifier) {
        List<PrimarySession> sessions = world.get(connectionIdentifier);
        return sessions == null ? new ArrayList<PrimarySession>()
                : new ArrayList<PrimarySession>(sessions);
    }

    @Override
    public Set<String> getOwnPrimaryRecordUuids() {
        Set<String> own = new HashSet<String>();
        for (List<PrimarySession> sessions : world.values())
            for (PrimarySession session : sessions)
                if (session.getUsername().equals(username))
                    own.add(session.getRecordUuid());
        return own;
    }

    @Override
    public List<SharingOption> getSharingOptions(String recordUuid) {
        return sharingOptions;
    }

    @Override
    public String share(String recordUuid, String sharingProfileIdentifier) {
        shared.add(recordUuid + "/" + sharingProfileIdentifier);
        return "key-" + sharingProfileIdentifier;
    }

    @Override
    public void endSession(PrimarySession session) {
        ended.add(session.getRecordUuid());
        for (List<PrimarySession> sessions : world.values())
            sessions.remove(session);
    }

}
