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

package org.apache.guacamole.auth.jdbc.user;

import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.auth.jdbc.join.JoinRequestResource;
import org.apache.guacamole.auth.jdbc.reset.PasswordResetResource;

/**
 * The single extension resource of a JDBC user context, served under
 * api/session/ext/DATA_SOURCE/.
 */
@Produces(MediaType.APPLICATION_JSON)
public class JDBCUserContextResource {

    private final PasswordResetResource passwordReset;
    private final JoinRequestResource joinRequests;

    /**
     * Creates a JDBCUserContextResource.
     *
     * @param passwordReset
     *     The password reset endpoints.
     *
     * @param joinRequests
     *     The join request endpoints.
     */
    public JDBCUserContextResource(PasswordResetResource passwordReset,
            JoinRequestResource joinRequests) {
        this.passwordReset = passwordReset;
        this.joinRequests = joinRequests;
    }

    /**
     * Sends a password reset link; see PasswordResetResource.
     *
     * @param username
     *     The user whose password is reset.
     *
     * @throws GuacamoleException
     *     If not permitted or the link cannot be sent.
     */
    @POST
    @Path("password-reset")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public void sendResetLink(@FormParam("username") String username)
            throws GuacamoleException {
        passwordReset.sendResetLink(username);
    }

    /**
     * @return
     *     The join request endpoints.
     */
    @Path("join")
    public JoinRequestResource getJoinRequests() {
        return joinRequests;
    }

}
