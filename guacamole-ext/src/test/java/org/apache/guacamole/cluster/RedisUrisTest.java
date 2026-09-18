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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class RedisUrisTest {

    @Test
    public void aUriWithoutCredentialsIsUnchanged() {
        assertEquals("redis://redis:6379", RedisUris.redact("redis://redis:6379"));
    }

    @Test
    public void aPasswordIsRemoved() {
        assertEquals("redis://***@redis:6379",
                RedisUris.redact("redis://:hunter2@redis:6379"));
    }

    @Test
    public void aUserAndPasswordAreRemoved() {
        assertEquals("rediss://***@redis.example.com:6379",
                RedisUris.redact("rediss://guacamole:hunter2@redis.example.com:6379"));
    }

    @Test
    public void theSchemeSurvivesSoEncryptionRemainsVisible() {
        assertEquals("rediss://***@redis:6379",
                RedisUris.redact("rediss://guacamole:hunter2@redis:6379"));
    }

    @Test
    public void aPasswordContainingAnAtSignIsStillRemoved() {

        // The last "@" delimits the authority, not the first
        String redacted = RedisUris.redact("redis://user:p@ss@redis:6379");
        assertEquals("redis://***@redis:6379", redacted);
        assertFalse(redacted.contains("p@ss"));

    }

    @Test
    public void anUnparseableUriIsNotEchoed() {

        // A URI this class does not understand is the one most likely to carry
        // a credential in an unanticipated shape, so it is never echoed
        assertEquals("(redacted)", RedisUris.redact("not-a-uri"));
        assertEquals("(redacted)", RedisUris.redact(null));

    }

}
