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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import org.apache.guacamole.GuacamoleClientException;
import org.apache.guacamole.GuacamoleException;

/**
 * REST endpoints for join requests, served under
 * api/session/ext/DATA_SOURCE/join/.
 */
@Produces(MediaType.APPLICATION_JSON)
public class JoinRequestResource {

    private final JoinRequestService service;
    private final JoinContext context;

    /**
     * Creates a JoinRequestResource.
     *
     * @param service
     *     The join request rules.
     *
     * @param context
     *     The calling user's context.
     */
    public JoinRequestResource(JoinRequestService service, JoinContext context) {
        this.service = service;
        this.context = context;
    }

    /**
     * @param connectionId
     *     The connection to check.
     *
     * @return
     *     Whether the connection is held by other users.
     *
     * @throws GuacamoleException
     *     If the check is not permitted or fails.
     */
    @GET
    @Path("status/{connectionId}")
    public Map<String, Object> getStatus(@PathParam("connectionId") String connectionId)
            throws GuacamoleException {
        return service.status(context, connectionId);
    }

    /**
     * @param body
     *     The connection to join.
     *
     * @return
     *     The new request's id and expiry.
     *
     * @throws GuacamoleException
     *     If the request is not permitted or cannot be stored.
     */
    @POST
    @Path("requests")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> createRequest(JoinRequestCreation body)
            throws GuacamoleException {
        if (body == null || body.getConnectionId() == null)
            throw new GuacamoleClientException("A connection is required.");
        JoinRequest request = service.create(context, body.getConnectionId());
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("id", request.getId());
        result.put("expires", request.getExpires());
        return result;
    }

    /**
     * @param id
     *     The caller's request.
     *
     * @return
     *     The request's status, and its share key once admitted.
     *
     * @throws GuacamoleException
     *     If the request is unknown, expired, or not the caller's.
     */
    @GET
    @Path("requests/{id}")
    public Map<String, Object> getRequest(@PathParam("id") String id)
            throws GuacamoleException {
        return service.poll(context, id);
    }

    /**
     * @param id
     *     The caller's request to withdraw.
     *
     * @throws GuacamoleException
     *     If the request is unknown or not the caller's.
     */
    @DELETE
    @Path("requests/{id}")
    public void cancelRequest(@PathParam("id") String id) throws GuacamoleException {
        service.cancel(context, id);
    }

    /**
     * @return
     *     Pending requests to join the caller's sessions.
     *
     * @throws GuacamoleException
     *     If the requests cannot be read.
     */
    @GET
    @Path("incoming")
    public List<Map<String, Object>> getIncoming() throws GuacamoleException {
        return service.incoming(context);
    }

    /**
     * @param id
     *     The request to answer.
     *
     * @param body
     *     The decision, and the sharing profile when admitting.
     *
     * @throws GuacamoleException
     *     If the answer is not permitted or the request was already answered.
     */
    @POST
    @Path("incoming/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public void decide(@PathParam("id") String id, JoinDecision body)
            throws GuacamoleException {
        if (body == null)
            throw new GuacamoleClientException("A decision is required.");
        service.decide(context, id, body.getDecision(), body.getSharingProfile());
    }

    /**
     * @param connectionId
     *     The connection to take over.
     *
     * @throws GuacamoleException
     *     If not permitted, or if a session cannot be ended.
     */
    @POST
    @Path("enforce/{connectionId}")
    public void enforce(@PathParam("connectionId") String connectionId)
            throws GuacamoleException {
        service.enforce(context, connectionId);
    }

}
