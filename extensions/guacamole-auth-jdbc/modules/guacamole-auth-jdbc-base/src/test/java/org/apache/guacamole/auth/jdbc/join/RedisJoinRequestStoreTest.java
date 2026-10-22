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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.guacamole.cluster.NoOpClusterStore;
import org.junit.jupiter.api.Test;

/**
 * Tests for RedisJoinRequestStore over a map-backed cluster store.
 */
public class RedisJoinRequestStoreTest {

    /**
     * ClusterStore keeping join requests in maps; ignores TTLs.
     */
    private static class MapClusterStore extends NoOpClusterStore {

        final Map<String, Map<String, String>> requests = new HashMap<String, Map<String, String>>();
        final Map<String, Set<String>> index = new HashMap<String, Set<String>>();
        int lastTtl;

        @Override
        public void putJoinRequest(String id, Map<String, String> fields,
                Collection<String> recordUuids, int ttlSeconds) {
            requests.put(id, new HashMap<String, String>(fields));
            for (String uuid : recordUuids) {
                if (!index.containsKey(uuid))
                    index.put(uuid, new HashSet<String>());
                index.get(uuid).add(id);
            }
            lastTtl = ttlSeconds;
        }

        @Override
        public Map<String, String> getJoinRequest(String id) {
            return requests.get(id);
        }

        @Override
        public Collection<String> listJoinRequests(String recordUuid) {
            Set<String> ids = index.get(recordUuid);
            return ids == null ? new ArrayList<String>() : ids;
        }

        @Override
        public boolean resolveJoinRequest(String id, String status, String shareKey) {
            Map<String, String> fields = requests.get(id);
            if (fields == null || !"PENDING".equals(fields.get("status")))
                return false;
            fields.put("status", status);
            if (shareKey != null)
                fields.put("shareKey", shareKey);
            return true;
        }

        @Override
        public void removeJoinRequest(String id) {
            requests.remove(id);
        }

    }

    private final AtomicLong now = new AtomicLong(1000000L);

    @Test
    public void roundTripsEveryField() throws Exception {
        MapClusterStore cluster = new MapClusterStore();
        RedisJoinRequestStore store = new RedisJoinRequestStore(cluster, now::get);
        store.put(new JoinRequest("r1", "7", Arrays.asList("u1", "u2"), "bob",
                now.get() + 90500, JoinRequest.Status.PENDING, null));

        assertEquals(91, cluster.lastTtl);
        JoinRequest found = store.get("r1");
        assertEquals("7", found.getConnectionIdentifier());
        assertEquals(Arrays.asList("u1", "u2"), found.getRecordUuids());
        assertEquals("bob", found.getRequester());
        assertEquals(JoinRequest.Status.PENDING, found.getStatus());
        assertNull(found.getShareKey());
        assertEquals(1, store.listFor("u2").size());
    }

    @Test
    public void resolveAndRemove() throws Exception {
        MapClusterStore cluster = new MapClusterStore();
        RedisJoinRequestStore store = new RedisJoinRequestStore(cluster, now::get);
        store.put(new JoinRequest("r1", "7", Arrays.asList("u1"), "bob",
                now.get() + 60000, JoinRequest.Status.PENDING, null));
        assertTrue(store.resolve("r1", JoinRequest.Status.ADMITTED, "k"));
        assertFalse(store.resolve("r1", JoinRequest.Status.DENIED, null));
        assertEquals("k", store.get("r1").getShareKey());
        store.remove("r1");
        assertNull(store.get("r1"));
        assertTrue(store.listFor("u1").isEmpty());
    }

    @Test
    public void expiredEntryIsIgnoredEvenIfStillStored() throws Exception {
        MapClusterStore cluster = new MapClusterStore();
        RedisJoinRequestStore store = new RedisJoinRequestStore(cluster, now::get);
        store.put(new JoinRequest("r1", "7", Arrays.asList("u1"), "bob",
                now.get() + 1000, JoinRequest.Status.PENDING, null));
        now.addAndGet(1001);
        assertNull(store.get("r1"));
    }

}
