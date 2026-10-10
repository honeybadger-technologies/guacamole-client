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

package org.apache.guacamole.auth.jdbc.kms;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.auth.jdbc.encryption.Keyring;
import org.apache.guacamole.auth.jdbc.encryption.ParameterKeyProvider;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.kms.model.KmsException;

public class KmsParameterKeyProviderTest {

    @Test
    public void unwrapsEachKeyOnce() throws Exception {
        int[] calls = { 0 };
        String wrapped = Base64.getEncoder().encodeToString(new byte[] { 9, 9 });
        ParameterKeyProvider p = KmsParameterKeyProvider.fromKeyring(
            Keyring.parse("k1:" + wrapped, KmsParameterKeyProvider.decoder(blob -> {
                calls[0]++;
                assertArrayEquals(new byte[] { 9, 9 }, blob);
                return new byte[32];
            })), "k1");
        assertEquals(1, calls[0]);
        assertEquals("k1", p.currentKeyId());
        assertEquals(32, p.keyFor("k1").length);
    }

    @Test
    public void unwrapFailureNamesKeyOnly() {
        String wrapped = Base64.getEncoder().encodeToString(new byte[] { 1 });
        GuacamoleServerException e = assertThrows(GuacamoleServerException.class,
            () -> Keyring.parse("k1:" + wrapped, KmsParameterKeyProvider.decoder(blob -> {
                throw new RuntimeException("AccessDenied");
            })));
        assertTrue(e.getMessage().contains("k1"));
        assertFalse(e.getMessage().contains(wrapped));
        assertFalse(e.getMessage().contains("AccessDenied"), e.getMessage());
    }

    @Test
    public void unwrapFailureIncludesAwsErrorCodeOnly() {
        String wrapped = Base64.getEncoder().encodeToString(new byte[] { 1 });
        GuacamoleServerException e = assertThrows(GuacamoleServerException.class,
            () -> Keyring.parse("k1:" + wrapped, KmsParameterKeyProvider.decoder(blob -> {
                throw KmsException.builder().message("detail that must not leak")
                    .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("AccessDeniedException").build())
                    .build();
            })));
        assertTrue(e.getMessage().endsWith("key \"k1\": KmsException (AccessDeniedException)"),
            e.getMessage());
        assertFalse(e.getMessage().contains("detail that must not leak"));
    }

}
