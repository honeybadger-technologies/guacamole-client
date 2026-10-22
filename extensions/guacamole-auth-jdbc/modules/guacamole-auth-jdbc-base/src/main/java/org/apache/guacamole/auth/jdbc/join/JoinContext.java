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

import java.util.List;
import java.util.Set;
import org.apache.guacamole.GuacamoleException;

/**
 * Everything JoinRequestService needs to know about, or do on behalf of, the
 * user making a request.
 */
public interface JoinContext {

    /**
     * @return
     *     The username of the calling user.
     */
    String getUsername();

    /**
     * @param connectionIdentifier
     *     The connection to check.
     *
     * @return
     *     true if the caller has READ permission on the connection.
     *
     * @throws GuacamoleException
     *     If permissions cannot be read.
     */
    boolean canReadConnection(String connectionIdentifier) throws GuacamoleException;

    /**
     * @param connectionIdentifier
     *     The connection to inspect.
     *
     * @return
     *     Every primary session on the connection, cluster-wide.
     *
     * @throws GuacamoleException
     *     If active sessions cannot be listed.
     */
    List<PrimarySession> getPrimarySessions(String connectionIdentifier)
            throws GuacamoleException;

    /**
     * @return
     *     The record UUIDs of primary sessions the caller opened which are
     *     served by this web application instance.
     *
     * @throws GuacamoleException
     *     If active sessions cannot be listed.
     */
    Set<String> getOwnPrimaryRecordUuids() throws GuacamoleException;

    /**
     * @param recordUuid
     *     One of the caller's own primary sessions.
     *
     * @return
     *     The sharing profiles of that session's connection which the caller
     *     may share with.
     *
     * @throws GuacamoleException
     *     If the profiles cannot be read.
     */
    List<SharingOption> getSharingOptions(String recordUuid) throws GuacamoleException;

    /**
     * @param recordUuid
     *     One of the caller's own primary sessions.
     *
     * @param sharingProfileIdentifier
     *     The sharing profile to share with.
     *
     * @return
     *     A new share key for the session.
     *
     * @throws GuacamoleException
     *     If sharing is not permitted.
     */
    String share(String recordUuid, String sharingProfileIdentifier)
            throws GuacamoleException;

    /**
     * Ends the given session, wherever it is served.
     *
     * @param session
     *     The session to end.
     *
     * @throws GuacamoleException
     *     If the session cannot be ended.
     */
    void endSession(PrimarySession session) throws GuacamoleException;

}
