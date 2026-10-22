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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for InMemoryJoinRequestStore.
 */
public class InMemoryJoinRequestStoreTest {

    private final AtomicLong now = new AtomicLong(1000000L);
    private InMemoryJoinRequestStore store;

    @BeforeEach
    public void setUp() {
        store = new InMemoryJoinRequestStore(now::get);
    }

    private JoinRequest request(String id, long expires, String... uuids) {
        return new JoinRequest(id, "7", Arrays.asList(uuids), "bob", expires,
                JoinRequest.Status.PENDING, null);
    }

    @Test
    public void putThenGetReturnsRequest() throws Exception {
        store.put(request("r1", now.get() + 5000, "u1"));
        JoinRequest found = store.get("r1");
        assertEquals("bob", found.getRequester());
        assertEquals(JoinRequest.Status.PENDING, found.getStatus());
    }

    @Test
    public void expiredRequestIsGone() throws Exception {
        store.put(request("r1", now.get() + 5000, "u1"));
        now.addAndGet(5001);
        assertNull(store.get("r1"));
        assertTrue(store.listFor("u1").isEmpty());
    }

    @Test
    public void listForReturnsOnlyRequestsTargetingThatSession() throws Exception {
        store.put(request("r1", now.get() + 5000, "u1", "u2"));
        store.put(request("r2", now.get() + 5000, "u3"));
        assertEquals(1, store.listFor("u2").size());
        assertEquals("r1", store.listFor("u2").get(0).getId());
        assertTrue(store.listFor("u9").isEmpty());
    }

    @Test
    public void firstResolutionWins() throws Exception {
        store.put(request("r1", now.get() + 5000, "u1"));
        assertTrue(store.resolve("r1", JoinRequest.Status.ADMITTED, "key-1"));
        assertFalse(store.resolve("r1", JoinRequest.Status.DENIED, null));
        assertEquals(JoinRequest.Status.ADMITTED, store.get("r1").getStatus());
        assertEquals("key-1", store.get("r1").getShareKey());
    }

    @Test
    public void resolvingMissingOrExpiredRequestFails() throws Exception {
        assertFalse(store.resolve("nope", JoinRequest.Status.DENIED, null));
        store.put(request("r1", now.get() + 5000, "u1"));
        now.addAndGet(6000);
        assertFalse(store.resolve("r1", JoinRequest.Status.DENIED, null));
    }

    @Test
    public void removeDeletesRequest() throws Exception {
        store.put(request("r1", now.get() + 5000, "u1"));
        store.remove("r1");
        assertNull(store.get("r1"));
    }

}
