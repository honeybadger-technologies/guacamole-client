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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * JoinRequestStore for a single web application instance.
 */
public class InMemoryJoinRequestStore implements JoinRequestStore {

    /**
     * All stored requests, by ID.
     */
    private final ConcurrentMap<String, JoinRequest> requests =
            new ConcurrentHashMap<String, JoinRequest>();

    /**
     * Supplies the current time, in milliseconds since the epoch.
     */
    private final LongSupplier clock;

    /**
     * Creates a store using the system clock.
     */
    public InMemoryJoinRequestStore() {
        this(System::currentTimeMillis);
    }

    /**
     * Creates a store using the given clock.
     *
     * @param clock
     *     Supplies the current time, in milliseconds since the epoch.
     */
    public InMemoryJoinRequestStore(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public void put(JoinRequest request) {
        long now = clock.getAsLong();
        Iterator<JoinRequest> iterator = requests.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().isExpired(now))
                iterator.remove();
        }
        requests.put(request.getId(), request);
    }

    @Override
    public JoinRequest get(String id) {
        JoinRequest request = requests.get(id);
        if (request == null)
            return null;
        if (request.isExpired(clock.getAsLong())) {
            requests.remove(id, request);
            return null;
        }
        return request;
    }

    @Override
    public List<JoinRequest> listFor(String recordUuid) {
        long now = clock.getAsLong();
        List<JoinRequest> matches = new ArrayList<JoinRequest>();
        for (JoinRequest request : requests.values()) {
            if (!request.isExpired(now) && request.getRecordUuids().contains(recordUuid))
                matches.add(request);
        }
        return matches;
    }

    @Override
    public boolean resolve(String id, JoinRequest.Status status, String shareKey) {
        JoinRequest current = get(id);
        if (current == null || current.getStatus() != JoinRequest.Status.PENDING)
            return false;
        return requests.replace(id, current, current.withResolution(status, shareKey));
    }

    @Override
    public void remove(String id) {
        requests.remove(id);
    }

}
