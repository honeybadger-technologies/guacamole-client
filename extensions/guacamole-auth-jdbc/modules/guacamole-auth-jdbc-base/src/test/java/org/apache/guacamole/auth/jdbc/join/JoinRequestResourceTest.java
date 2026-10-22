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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.guacamole.GuacamoleResourceNotFoundException;
import org.junit.jupiter.api.Test;

/**
 * Tests for JoinRequestResource.
 */
public class JoinRequestResourceTest {

    private final AtomicLong now = new AtomicLong(1000000L);

    @Test
    public void createReturnsIdAndExpiry() throws Exception {
        Map<String, List<PrimarySession>> world = new HashMap<String, List<PrimarySession>>();
        world.put("7", new ArrayList<PrimarySession>(Arrays.asList(new PrimarySession("rec-a", "alice"))));
        JoinRequestService service = new JoinRequestService(
                new InMemoryJoinRequestStore(now::get), true, 120, now::get, () -> "req-1");
        JoinRequestResource resource = new JoinRequestResource(service,
                new FakeJoinContext(world, "bob").canRead("7"));

        JoinRequestCreation body = new JoinRequestCreation();
        body.setConnectionId("7");
        Map<String, Object> created = resource.createRequest(body);

        assertEquals("req-1", created.get("id"));
        assertEquals(now.get() + 120000L, created.get("expires"));
        assertEquals("PENDING", resource.getRequest("req-1").get("status"));
    }

    @Test
    public void disabledFeatureIsNotFound() {
        JoinRequestService off = new JoinRequestService(
                new InMemoryJoinRequestStore(now::get), false, 120, now::get, () -> "x");
        JoinRequestResource resource = new JoinRequestResource(off,
                new FakeJoinContext(new HashMap<String, List<PrimarySession>>(), "bob"));
        assertThrows(GuacamoleResourceNotFoundException.class, () -> resource.getStatus("7"));
    }

}
