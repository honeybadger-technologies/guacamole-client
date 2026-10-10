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

import org.apache.guacamole.GuacamoleServerException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;

public class KmsParameterKeyProviderFactoryTest {

    @Test
    public void clientFailureReportsClassNameOnly() {
        GuacamoleServerException e = assertThrows(GuacamoleServerException.class,
            () -> KmsParameterKeyProviderFactory.create("k1:AAAA", "k1", () -> {
                throw SdkClientException.create("secret-ish text");
            }));
        assertTrue(e.getMessage().contains("SdkClientException"));
        assertFalse(e.getMessage().contains("secret-ish"));
    }

}
