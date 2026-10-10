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

import java.util.Base64;
import java.util.Map;
import java.util.function.Function;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.auth.jdbc.encryption.Keyring;
import org.apache.guacamole.auth.jdbc.encryption.ParameterKeyProvider;
import org.apache.guacamole.auth.jdbc.encryption.PropertyParameterKeyProvider;

/**
 * Key provider whose keyring values are AWS KMS ciphertext blobs. Each is
 * unwrapped once with KMS Decrypt at startup; plain keys stay in memory.
 */
public final class KmsParameterKeyProvider {

    private KmsParameterKeyProvider() {}

    /** Decoder turning a base64 KMS blob into a plain key via unwrap. */
    public static Keyring.Decoder decoder(Function<byte[], byte[]> unwrap) {
        return (keyId, value) -> {
            byte[] blob;
            try {
                blob = Base64.getDecoder().decode(value);
            }
            catch (IllegalArgumentException e) {
                throw new GuacamoleServerException("Parameter encryption key \""
                        + keyId + "\" is not valid base64.");
            }
            try {
                return unwrap.apply(blob);
            }
            catch (RuntimeException e) {
                throw new GuacamoleServerException("AWS KMS could not decrypt "
                        + "parameter encryption key \"" + keyId + "\": "
                        + e.getClass().getSimpleName());
            }
        };
    }

    /** Builds the provider from an already-unwrapped keyring. */
    public static ParameterKeyProvider fromKeyring(Map<String, byte[]> keys,
            String currentKeyId) throws GuacamoleServerException {
        return new PropertyParameterKeyProvider(keys, currentKeyId);
    }

}
