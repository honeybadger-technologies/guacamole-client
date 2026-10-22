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

package org.apache.guacamole.cluster.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * Tests join request storage against a real Redis.
 */
public class JoinRequestStorageTest {

    private static final long STALE_WINDOW_MS = 30000;
    private RedisClusterStore store;

    @BeforeAll
    public static void requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available; skipping Redis-backed tests.");
    }

    @BeforeEach
    public void setUp() {
        store = new RedisClusterStore(RedisTestSupport.redisUri(), STALE_WINDOW_MS, "node-1");
        store.flushForTesting();
    }

    @AfterEach
    public void tearDown() {
        store.shutdown();
    }

    private Map<String, String> fields() {
        Map<String, String> fields = new HashMap<String, String>();
        fields.put("requester", "bob");
        fields.put("status", "PENDING");
        return fields;
    }

    @Test
    public void putThenGetAndList() throws Exception {
        store.putJoinRequest("r1", fields(), Arrays.asList("u1", "u2"), 60);
        assertEquals("bob", store.getJoinRequest("r1").get("requester"));
        assertTrue(store.listJoinRequests("u2").contains("r1"));
        assertTrue(store.listJoinRequests("u9").isEmpty());
        assertTrue(store.joinRequestTtlForTesting("r1") > 0);
    }

    @Test
    public void resolveOnlyOnce() throws Exception {
        store.putJoinRequest("r1", fields(), Arrays.asList("u1"), 60);
        assertTrue(store.resolveJoinRequest("r1", "ADMITTED", "key-1"));
        assertFalse(store.resolveJoinRequest("r1", "DENIED", null));
        assertEquals("ADMITTED", store.getJoinRequest("r1").get("status"));
        assertEquals("key-1", store.getJoinRequest("r1").get("shareKey"));
    }

    @Test
    public void missingRequest() throws Exception {
        assertNull(store.getJoinRequest("nope"));
        assertFalse(store.resolveJoinRequest("nope", "DENIED", null));
    }

    @Test
    public void removeDeletesRequest() throws Exception {
        store.putJoinRequest("r1", fields(), Arrays.asList("u1"), 60);
        store.removeJoinRequest("r1");
        assertNull(store.getJoinRequest("r1"));
    }

}
