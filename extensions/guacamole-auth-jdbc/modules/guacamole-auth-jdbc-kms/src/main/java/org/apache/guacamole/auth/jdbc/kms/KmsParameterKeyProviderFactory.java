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

import java.time.Duration;
import java.util.function.Supplier;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.auth.jdbc.JDBCEnvironment;
import org.apache.guacamole.auth.jdbc.encryption.Keyring;
import org.apache.guacamole.auth.jdbc.encryption.ParameterKeyProvider;
import org.apache.guacamole.auth.jdbc.encryption.ParameterKeyProviderFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;

/**
 * Selected by "*-parameter-encryption-provider: kms". Uses the default AWS
 * credential and region chains (including web identity / IRSA).
 */
public class KmsParameterKeyProviderFactory implements ParameterKeyProviderFactory {

    @Override
    public String getName() {
        return "kms";
    }

    @Override
    public ParameterKeyProvider create(JDBCEnvironment environment)
            throws GuacamoleException {
        return create(environment.getParameterEncryptionKeys(),
                environment.getParameterEncryptionCurrentKey(),
                KmsParameterKeyProviderFactory::buildClient);
    }

    /** Builds the client with the URL-connection HTTP client and bounded call time. */
    private static KmsClient buildClient() {
        return KmsClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3))
                        .build())
                .build();
    }

    /** Test seam: unwraps the keyring using clients from the given supplier. */
    static ParameterKeyProvider create(String keys, String currentKeyId,
            Supplier<KmsClient> clientSupplier) throws GuacamoleException {
        try (KmsClient kms = clientSupplier.get()) {
            return KmsParameterKeyProvider.fromKeyring(
                    Keyring.parse(keys,
                            KmsParameterKeyProvider.decoder(blob -> kms.decrypt(
                                    DecryptRequest.builder()
                                            .ciphertextBlob(SdkBytes.fromByteArray(blob))
                                            .build())
                                    .plaintext().asByteArray())),
                    currentKeyId);
        }
        catch (GuacamoleServerException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new GuacamoleServerException("Could not initialise the AWS KMS "
                    + "client: " + e.getClass().getSimpleName());
        }
    }

}
