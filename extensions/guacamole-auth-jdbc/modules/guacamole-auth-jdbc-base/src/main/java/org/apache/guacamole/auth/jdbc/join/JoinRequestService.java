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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.guacamole.GuacamoleClientException;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleResourceConflictException;
import org.apache.guacamole.GuacamoleResourceNotFoundException;
import org.apache.guacamole.GuacamoleSecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rules for asking to join, admitting into, and taking over connections that
 * other users hold.
 */
public class JoinRequestService {

    /**
     * Logger for this class.
     */
    private static final Logger logger = LoggerFactory.getLogger(JoinRequestService.class);

    private final JoinRequestStore store;
    private final boolean enabled;
    private final int timeoutSeconds;
    private final LongSupplier clock;
    private final Supplier<String> ids;

    /**
     * Creates a JoinRequestService.
     *
     * @param store
     *     Where requests are kept.
     *
     * @param enabled
     *     Whether join requests are enabled.
     *
     * @param timeoutSeconds
     *     How long an unanswered request remains valid.
     *
     * @param clock
     *     Supplies the current time, in milliseconds since the epoch.
     *
     * @param ids
     *     Generates unguessable request IDs.
     */
    public JoinRequestService(JoinRequestStore store, boolean enabled,
            int timeoutSeconds, LongSupplier clock, Supplier<String> ids) {
        this.store = store;
        this.enabled = enabled;
        this.timeoutSeconds = timeoutSeconds;
        this.clock = clock;
        this.ids = ids;
    }

    private void requireEnabled() throws GuacamoleException {
        if (!enabled)
            throw new GuacamoleResourceNotFoundException("Join requests are disabled.");
    }

    private void requireRead(JoinContext ctx, String connectionId) throws GuacamoleException {
        if (!ctx.canReadConnection(connectionId))
            throw new GuacamoleSecurityException("Permission denied.");
    }

    private List<PrimarySession> othersOn(JoinContext ctx, String connectionId)
            throws GuacamoleException {
        List<PrimarySession> others = new ArrayList<PrimarySession>();
        for (PrimarySession session : ctx.getPrimarySessions(connectionId))
            if (!session.getUsername().equals(ctx.getUsername()))
                others.add(session);
        return others;
    }

    private JoinRequest requireOwnRequest(JoinContext ctx, String id) throws GuacamoleException {
        JoinRequest request = store.get(id);
        if (request == null || !request.getRequester().equals(ctx.getUsername()))
            throw new GuacamoleResourceNotFoundException("No such join request.");
        return request;
    }

    /**
     * Reports whether a connection is held by users other than the caller.
     *
     * @param ctx
     *     The calling user.
     *
     * @param connectionId
     *     The connection to check.
     *
     * @return
     *     {"state":"free"}, or {"state":"inUse","users":N}.
     *
     * @throws GuacamoleException
     *     If disabled, if the caller cannot read the connection, or if
     *     sessions cannot be listed.
     */
    public Map<String, Object> status(JoinContext ctx, String connectionId)
            throws GuacamoleException {
        requireEnabled();
        requireRead(ctx, connectionId);
        List<PrimarySession> others = othersOn(ctx, connectionId);
        Map<String, Object> result = new HashMap<String, Object>();
        if (others.isEmpty())
            result.put("state", "free");
        else {
            result.put("state", "inUse");
            result.put("users", others.size());
        }
        return result;
    }

    /**
     * Asks the users holding a connection to admit the caller.
     *
     * @param ctx
     *     The calling user.
     *
     * @param connectionId
     *     The connection to join.
     *
     * @return
     *     The new pending request.
     *
     * @throws GuacamoleException
     *     If disabled, if the caller cannot read the connection, if no other
     *     user holds it, or if the request cannot be stored.
     */
    public JoinRequest create(JoinContext ctx, String connectionId) throws GuacamoleException {
        requireEnabled();
        requireRead(ctx, connectionId);
        List<PrimarySession> others = othersOn(ctx, connectionId);
        if (others.isEmpty())
            throw new GuacamoleResourceConflictException("The connection is not in use by another user.");
        List<String> uuids = new ArrayList<String>();
        for (PrimarySession session : others)
            uuids.add(session.getRecordUuid());
        JoinRequest request = new JoinRequest(ids.get(), connectionId, uuids,
                ctx.getUsername(), clock.getAsLong() + timeoutSeconds * 1000L,
                JoinRequest.Status.PENDING, null);
        store.put(request);
        return request;
    }

    /**
     * Returns the state of the caller's own request. An admitted request's
     * share key is returned once, after which the request is gone.
     *
     * @param ctx
     *     The calling user.
     *
     * @param id
     *     The request ID.
     *
     * @return
     *     {"status": PENDING|ADMITTED|DENIED|CANCELLED|FREE, "shareKey"?}.
     *
     * @throws GuacamoleException
     *     If disabled, or if the request is unknown, expired, or not the
     *     caller's.
     */
    public Map<String, Object> poll(JoinContext ctx, String id) throws GuacamoleException {
        requireEnabled();
        JoinRequest request = requireOwnRequest(ctx, id);
        Map<String, Object> result = new HashMap<String, Object>();
        if (request.getStatus() == JoinRequest.Status.ADMITTED) {
            store.remove(id);
            result.put("status", request.getStatus().name());
            result.put("shareKey", request.getShareKey());
            return result;
        }
        if (request.getStatus() == JoinRequest.Status.PENDING
                && othersOn(ctx, request.getConnectionIdentifier()).isEmpty()) {
            store.remove(id);
            result.put("status", "FREE");
            return result;
        }
        result.put("status", request.getStatus().name());
        return result;
    }

    /**
     * Withdraws the caller's own request.
     *
     * @param ctx
     *     The calling user.
     *
     * @param id
     *     The request ID.
     *
     * @throws GuacamoleException
     *     If disabled, or if the request is unknown or not the caller's.
     */
    public void cancel(JoinContext ctx, String id) throws GuacamoleException {
        requireEnabled();
        requireOwnRequest(ctx, id);
        store.remove(id);
    }

    /**
     * Lists pending requests to join sessions the caller opened.
     *
     * @param ctx
     *     The calling user.
     *
     * @return
     *     One entry per request: id, requester, connectionIdentifier and
     *     sharingOptions (identifier, name).
     *
     * @throws GuacamoleException
     *     If disabled, or if sessions or requests cannot be read.
     */
    public List<Map<String, Object>> incoming(JoinContext ctx) throws GuacamoleException {
        requireEnabled();
        Map<String, Map<String, Object>> found = new LinkedHashMap<String, Map<String, Object>>();
        for (String recordUuid : ctx.getOwnPrimaryRecordUuids()) {
            for (JoinRequest request : store.listFor(recordUuid)) {
                if (request.getStatus() != JoinRequest.Status.PENDING
                        || request.getRequester().equals(ctx.getUsername())
                        || found.containsKey(request.getId()))
                    continue;
                List<Map<String, String>> options = new ArrayList<Map<String, String>>();
                for (SharingOption option : ctx.getSharingOptions(recordUuid)) {
                    Map<String, String> entry = new HashMap<String, String>();
                    entry.put("identifier", option.getIdentifier());
                    entry.put("name", option.getName());
                    options.add(entry);
                }
                Map<String, Object> item = new HashMap<String, Object>();
                item.put("id", request.getId());
                item.put("requester", request.getRequester());
                item.put("connectionIdentifier", request.getConnectionIdentifier());
                item.put("sharingOptions", options);
                found.put(request.getId(), item);
            }
        }
        return new ArrayList<Map<String, Object>>(found.values());
    }

    /**
     * Answers a request to join one of the caller's sessions. The first
     * answer wins.
     *
     * @param ctx
     *     The calling user.
     *
     * @param id
     *     The request ID.
     *
     * @param decision
     *     "admit" or "deny".
     *
     * @param sharingProfile
     *     The sharing profile to admit with; required for "admit".
     *
     * @throws GuacamoleException
     *     If disabled, if the request is unknown, if the caller holds none of
     *     the targeted sessions, if it was already answered, or if the
     *     decision is invalid.
     */
    public void decide(JoinContext ctx, String id, String decision, String sharingProfile)
            throws GuacamoleException {
        requireEnabled();
        JoinRequest request = store.get(id);
        if (request == null)
            throw new GuacamoleResourceNotFoundException("No such join request.");

        String ownedUuid = null;
        Set<String> own = ctx.getOwnPrimaryRecordUuids();
        for (String recordUuid : request.getRecordUuids()) {
            if (own.contains(recordUuid)) {
                ownedUuid = recordUuid;
                break;
            }
        }
        if (ownedUuid == null)
            throw new GuacamoleSecurityException("Permission denied.");

        if (request.getStatus() != JoinRequest.Status.PENDING)
            throw new GuacamoleResourceConflictException("The join request was already answered.");

        if ("deny".equals(decision)) {
            if (!store.resolve(id, JoinRequest.Status.DENIED, null))
                throw new GuacamoleResourceConflictException("The join request was already answered.");
            return;
        }

        if (!"admit".equals(decision))
            throw new GuacamoleClientException("Unknown decision.");
        if (sharingProfile == null)
            throw new GuacamoleClientException("A sharing profile is required.");

        String shareKey = ctx.share(ownedUuid, sharingProfile);
        if (!store.resolve(id, JoinRequest.Status.ADMITTED, shareKey))
            throw new GuacamoleResourceConflictException("The join request was already answered.");
    }

    /**
     * Ends every session other users hold on a connection, so the caller can
     * connect. Pending requests targeting those sessions are cancelled.
     *
     * @param ctx
     *     The calling user.
     *
     * @param connectionId
     *     The connection to take over.
     *
     * @return
     *     The number of sessions ended.
     *
     * @throws GuacamoleException
     *     If disabled, if the caller cannot read the connection, or if a
     *     session cannot be ended.
     */
    public int enforce(JoinContext ctx, String connectionId) throws GuacamoleException {
        requireEnabled();
        requireRead(ctx, connectionId);
        List<PrimarySession> others = othersOn(ctx, connectionId);
        for (PrimarySession session : others) {
            ctx.endSession(session);
            for (JoinRequest request : store.listFor(session.getRecordUuid()))
                if (request.getStatus() == JoinRequest.Status.PENDING)
                    store.resolve(request.getId(), JoinRequest.Status.CANCELLED, null);
        }
        if (!others.isEmpty())
            logger.info("User \"{}\" enforced connection \"{}\", ending {} session(s).",
                    ctx.getUsername(), connectionId, others.size());
        return others.size();
    }

}
