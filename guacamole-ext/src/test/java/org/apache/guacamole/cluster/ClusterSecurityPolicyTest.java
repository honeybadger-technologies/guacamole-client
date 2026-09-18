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


package org.apache.guacamole.cluster;

import org.apache.guacamole.GuacamoleException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests which Redis URIs the cluster is willing to hold session identity in.
 */
public class ClusterSecurityPolicyTest {

    @Test
    public void anAuthenticatedEncryptedUriIsAccepted() throws Exception {
        String posture = ClusterSecurityPolicy.check(
                "rediss://guacamole:secret@redis:6379", false);
        assertTrue(posture.contains("authenticated"));
        assertTrue(posture.contains("encrypted"));
    }

    @Test
    public void anUnauthenticatedUriIsRefused() {
        GuacamoleException e = assertThrows(GuacamoleException.class,
                () -> ClusterSecurityPolicy.check("redis://redis:6379", false));
        assertTrue(e.getMessage().contains("cluster-allow-insecure-redis"),
                "the refusal must name the property that permits it");
    }

    @Test
    public void anUnencryptedButAuthenticatedUriIsRefused() {

        // Authentication without TLS sends the password in clear text, and the
        // session identity behind it in clear text too
        assertThrows(GuacamoleException.class,
                () -> ClusterSecurityPolicy.check(
                        "redis://guacamole:secret@redis:6379", false));

    }

    @Test
    public void insecureIsAllowedWhenExplicitlyOptedInto() throws Exception {
        String posture = ClusterSecurityPolicy.check("redis://redis:6379", true);
        assertTrue(posture.contains("UNAUTHENTICATED"));
        assertFalse(posture.contains("secret"));
    }

    @Test
    public void thePostureNeverContainsTheCredential() throws Exception {

        String posture = ClusterSecurityPolicy.check(
                "rediss://guacamole:hunter2@redis:6379", false);

        assertFalse(posture.contains("hunter2"),
                "the posture line is logged, so it must be redacted");

    }

    @Test
    public void anUnparseableUriIsRefusedRatherThanAssumedSafe() {
        assertThrows(GuacamoleException.class,
                () -> ClusterSecurityPolicy.check("not-a-uri", false));
    }

}
