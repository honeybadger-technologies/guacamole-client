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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.guacamole.GuacamoleClientException;
import org.apache.guacamole.GuacamoleResourceConflictException;
import org.apache.guacamole.GuacamoleResourceNotFoundException;
import org.apache.guacamole.GuacamoleSecurityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for JoinRequestService.
 */
public class JoinRequestServiceTest {

    private final AtomicLong now = new AtomicLong(1000000L);
    private final AtomicInteger ids = new AtomicInteger();
    private Map<String, List<PrimarySession>> world;
    private JoinRequestService service;

    /**
     * Connected to connection "7".
     */
    private FakeJoinContext alice;

    /**
     * The newcomer.
     */
    private FakeJoinContext bob;

    @BeforeEach
    public void setUp() {
        world = new HashMap<String, List<PrimarySession>>();
        world.put("7", new ArrayList<PrimarySession>(Arrays.asList(
                new PrimarySession("rec-a", "alice"))));
        service = new JoinRequestService(new InMemoryJoinRequestStore(now::get),
                true, 120, now::get, () -> "req-" + ids.incrementAndGet());
        alice = new FakeJoinContext(world, "alice").canRead("7")
                .offers("11", "Allow control").offers("12", "View only");
        bob = new FakeJoinContext(world, "bob").canRead("7");
    }

    @Test
    public void disabledServiceIsNotFound() {
        JoinRequestService off = new JoinRequestService(new InMemoryJoinRequestStore(now::get),
                false, 120, now::get, () -> "x");
        assertThrows(GuacamoleResourceNotFoundException.class, () -> off.status(bob, "7"));
        assertThrows(GuacamoleResourceNotFoundException.class, () -> off.incoming(alice));
    }

    @Test
    public void statusRequiresRead() {
        FakeJoinContext eve = new FakeJoinContext(world, "eve");
        assertThrows(GuacamoleSecurityException.class, () -> service.status(eve, "7"));
        assertThrows(GuacamoleSecurityException.class, () -> service.create(eve, "7"));
        assertThrows(GuacamoleSecurityException.class, () -> service.enforce(eve, "7"));
    }

    @Test
    public void statusIsInUseWhenAnotherUserIsConnected() throws Exception {
        assertEquals("inUse", service.status(bob, "7").get("state"));
        assertEquals(1, service.status(bob, "7").get("users"));
    }

    @Test
    public void statusIsFreeForOwnSessionsAndEmptyConnections() throws Exception {
        assertEquals("free", service.status(alice, "7").get("state"));
        world.get("7").clear();
        assertEquals("free", service.status(bob, "7").get("state"));
    }

    @Test
    public void createOnFreeConnectionConflicts() {
        world.get("7").clear();
        assertThrows(GuacamoleResourceConflictException.class, () -> service.create(bob, "7"));
    }

    @Test
    public void admitDeliversShareKeyOnceToRequesterOnly() throws Exception {
        JoinRequest request = service.create(bob, "7");
        assertEquals("PENDING", service.poll(bob, request.getId()).get("status"));

        List<Map<String, Object>> incoming = service.incoming(alice);
        assertEquals(1, incoming.size());
        assertEquals("bob", incoming.get(0).get("requester"));
        assertEquals(2, ((List<?>) incoming.get(0).get("sharingOptions")).size());

        FakeJoinContext eve = new FakeJoinContext(world, "eve").canRead("7");
        assertThrows(GuacamoleResourceNotFoundException.class,
                () -> service.poll(eve, request.getId()));

        service.decide(alice, request.getId(), "admit", "12");
        assertEquals(Arrays.asList("rec-a/12"), alice.shared);

        Map<String, Object> admitted = service.poll(bob, request.getId());
        assertEquals("ADMITTED", admitted.get("status"));
        assertEquals("key-12", admitted.get("shareKey"));
        assertThrows(GuacamoleResourceNotFoundException.class,
                () -> service.poll(bob, request.getId()));
    }

    @Test
    public void denyIsReportedToRequester() throws Exception {
        JoinRequest request = service.create(bob, "7");
        service.decide(alice, request.getId(), "deny", null);
        assertEquals("DENIED", service.poll(bob, request.getId()).get("status"));
    }

    @Test
    public void firstAnswerWins() throws Exception {
        world.get("7").add(new PrimarySession("rec-c", "carol"));
        FakeJoinContext carol = new FakeJoinContext(world, "carol").canRead("7");
        JoinRequest request = service.create(bob, "7");
        service.decide(carol, request.getId(), "deny", null);
        assertThrows(GuacamoleResourceConflictException.class,
                () -> service.decide(alice, request.getId(), "admit", "11"));
        assertTrue(alice.shared.isEmpty());
    }

    @Test
    public void onlyOwnersMayDecide() throws Exception {
        JoinRequest request = service.create(bob, "7");
        FakeJoinContext eve = new FakeJoinContext(world, "eve").canRead("7");
        assertThrows(GuacamoleSecurityException.class,
                () -> service.decide(eve, request.getId(), "admit", "11"));
        assertTrue(service.incoming(eve).isEmpty());
        assertTrue(service.incoming(bob).isEmpty());
    }

    @Test
    public void admitWithoutProfileOrUnknownDecisionIsRejected() throws Exception {
        JoinRequest request = service.create(bob, "7");
        assertThrows(GuacamoleClientException.class,
                () -> service.decide(alice, request.getId(), "admit", null));
        assertThrows(GuacamoleClientException.class,
                () -> service.decide(alice, request.getId(), "maybe", null));
    }

    @Test
    public void unansweredRequestExpires() throws Exception {
        JoinRequest request = service.create(bob, "7");
        now.addAndGet(120001);
        assertThrows(GuacamoleResourceNotFoundException.class,
                () -> service.poll(bob, request.getId()));
        assertTrue(service.incoming(alice).isEmpty());
    }

    @Test
    public void pollReportsFreeWhenOwnerLeft() throws Exception {
        JoinRequest request = service.create(bob, "7");
        world.get("7").clear();
        assertEquals("FREE", service.poll(bob, request.getId()).get("status"));
    }

    @Test
    public void cancelRemovesRequest() throws Exception {
        JoinRequest request = service.create(bob, "7");
        service.cancel(bob, request.getId());
        assertTrue(service.incoming(alice).isEmpty());
    }

    @Test
    public void enforceEndsOtherUsersSessionsAndCancelsPending() throws Exception {
        world.get("7").add(new PrimarySession("rec-b", "bob"));
        FakeJoinContext dave = new FakeJoinContext(world, "dave").canRead("7");
        JoinRequest pending = service.create(dave, "7");

        assertEquals(1, service.enforce(bob, "7"));
        assertEquals(Arrays.asList("rec-a"), bob.ended);
        assertEquals("CANCELLED", service.poll(dave, pending.getId()).get("status"));
        assertFalse(world.get("7").isEmpty());
    }

}
