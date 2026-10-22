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
import org.apache.guacamole.GuacamoleException;

/**
 * Storage for pending join requests. Expired requests behave as absent.
 */
public interface JoinRequestStore {

    /**
     * Stores the given request until it expires.
     *
     * @param request
     *     The request to store.
     *
     * @throws GuacamoleException
     *     If the request cannot be stored.
     */
    void put(JoinRequest request) throws GuacamoleException;

    /**
     * Returns the request with the given ID.
     *
     * @param id
     *     The ID of the request.
     *
     * @return
     *     The request, or null if it does not exist or has expired.
     *
     * @throws GuacamoleException
     *     If the store cannot be read.
     */
    JoinRequest get(String id) throws GuacamoleException;

    /**
     * Returns every unexpired request targeting the given primary session.
     *
     * @param recordUuid
     *     The UUID of the primary session.
     *
     * @return
     *     The matching requests, in any status. Never null.
     *
     * @throws GuacamoleException
     *     If the store cannot be read.
     */
    List<JoinRequest> listFor(String recordUuid) throws GuacamoleException;

    /**
     * Resolves a pending request. Only the first resolution of a request
     * succeeds.
     *
     * @param id
     *     The ID of the request.
     *
     * @param status
     *     The new status.
     *
     * @param shareKey
     *     The share key granted, or null.
     *
     * @return
     *     true if the request was pending and is now resolved, false
     *     otherwise.
     *
     * @throws GuacamoleException
     *     If the store cannot be written.
     */
    boolean resolve(String id, JoinRequest.Status status, String shareKey)
            throws GuacamoleException;

    /**
     * Removes the request with the given ID, if present.
     *
     * @param id
     *     The ID of the request.
     *
     * @throws GuacamoleException
     *     If the store cannot be written.
     */
    void remove(String id) throws GuacamoleException;

}
