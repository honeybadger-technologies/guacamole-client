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

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how a private certificate authority is trusted for the Redis
 * connection.
 *
 * Trust is scoped to this client rather than installed into the JVM's default
 * truststore: replacing that store would also govern every outbound HTTPS call
 * the deployment makes, SAML identity provider metadata included.
 */
public class RedisTlsTrustTest {

    @Test
    public void aMissingCertificateIsRefusedAtConstruction() {

        // A configured but absent CA is a configuration error, and it is
        // better to say so at startup than to fail every connection later
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new RedisClusterStore("rediss://redis:6379", 30000L, "node-1",
                        "/nonexistent/ca.crt"));

        assertTrue(e.getMessage().contains("/nonexistent/ca.crt"), e.getMessage());

    }

    @Test
    public void anExistingCertificateIsAccepted() throws IOException {

        File ca = File.createTempFile("guac-test-ca", ".crt");
        ca.deleteOnExit();
        Files.write(ca.toPath(),
                "-----BEGIN CERTIFICATE-----\n".getBytes(Charset.forName("UTF-8")));

        RedisClusterStore store = new RedisClusterStore(
                "rediss://redis:6379", 30000L, "node-1", ca.getAbsolutePath());

        store.shutdown();

    }

    @Test
    public void noCertificateLeavesTheDefaultTrustInPlace() {

        // The publicly trusted case: a managed Redis needs no CA file, and
        // passing none must not disable TLS or change the client's trust
        RedisClusterStore store = new RedisClusterStore(
                "rediss://redis:6379", 30000L, "node-1", null);

        store.shutdown();

    }

}
