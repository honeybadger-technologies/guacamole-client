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
import java.util.Collections;
import java.util.List;

/**
 * A request by one user to join a connection that other users hold.
 */
public class JoinRequest {

    /**
     * The possible states of a join request.
     */
    public enum Status {

        /**
         * Not yet answered.
         */
        PENDING,

        /**
         * Admitted; a share key was granted.
         */
        ADMITTED,

        /**
         * Declined by a connected user.
         */
        DENIED,

        /**
         * Overtaken by a newcomer ending the sessions it targeted.
         */
        CANCELLED

    }

    private final String id;
    private final String connectionIdentifier;
    private final List<String> recordUuids;
    private final String requester;
    private final long expires;
    private final Status status;
    private final String shareKey;

    /**
     * Creates a join request.
     *
     * @param id
     *     The unique identifier of this request.
     *
     * @param connectionIdentifier
     *     The identifier of the connection being joined.
     *
     * @param recordUuids
     *     The UUIDs of the primary sessions held by other users when the
     *     request was made.
     *
     * @param requester
     *     The username of the user asking to join.
     *
     * @param expires
     *     When this request expires, in milliseconds since the epoch.
     *
     * @param status
     *     The current status of this request.
     *
     * @param shareKey
     *     The share key granted on admission, or null.
     */
    public JoinRequest(String id, String connectionIdentifier,
            List<String> recordUuids, String requester, long expires,
            Status status, String shareKey) {
        this.id = id;
        this.connectionIdentifier = connectionIdentifier;
        this.recordUuids = Collections.unmodifiableList(new ArrayList<String>(recordUuids));
        this.requester = requester;
        this.expires = expires;
        this.status = status;
        this.shareKey = shareKey;
    }

    /**
     * @return The unique identifier of this request.
     */
    public String getId() { return id; }

    /**
     * @return The identifier of the connection being joined.
     */
    public String getConnectionIdentifier() { return connectionIdentifier; }

    /**
     * @return The UUIDs of the primary sessions this request targets.
     */
    public List<String> getRecordUuids() { return recordUuids; }

    /**
     * @return The username of the user asking to join.
     */
    public String getRequester() { return requester; }

    /**
     * @return When this request expires, in milliseconds since the epoch.
     */
    public long getExpires() { return expires; }

    /**
     * @return The current status of this request.
     */
    public Status getStatus() { return status; }

    /**
     * @return The share key granted on admission, or null.
     */
    public String getShareKey() { return shareKey; }

    /**
     * Returns whether this request has expired.
     *
     * @param now
     *     The current time, in milliseconds since the epoch.
     *
     * @return
     *     true if this request is no longer valid.
     */
    public boolean isExpired(long now) {
        return now >= expires;
    }

    /**
     * Returns a copy of this request with the given resolution.
     *
     * @param newStatus
     *     The new status.
     *
     * @param newShareKey
     *     The share key granted, or null.
     *
     * @return
     *     A resolved copy of this request.
     */
    public JoinRequest withResolution(Status newStatus, String newShareKey) {
        return new JoinRequest(id, connectionIdentifier, recordUuids, requester,
                expires, newStatus, newShareKey);
    }

}
