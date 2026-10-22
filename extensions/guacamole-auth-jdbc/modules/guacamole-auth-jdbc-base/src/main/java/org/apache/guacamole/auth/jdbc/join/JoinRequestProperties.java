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

import org.apache.guacamole.properties.BooleanGuacamoleProperty;
import org.apache.guacamole.properties.IntegerGuacamoleProperty;

/**
 * Properties controlling join requests.
 */
public class JoinRequestProperties {

    private JoinRequestProperties() {}

    /**
     * The number of seconds an unanswered join request remains valid if
     * join-request-timeout is not set.
     */
    public static final int DEFAULT_TIMEOUT = 120;

    /**
     * Whether opening a connection that another user is connected to asks
     * that user to admit the newcomer. Disabled by default.
     */
    public static final BooleanGuacamoleProperty JOIN_REQUEST_ENABLED =
            new BooleanGuacamoleProperty() {

        @Override
        public String getName() { return "join-request-enabled"; }

    };

    /**
     * The number of seconds an unanswered join request remains valid.
     */
    public static final IntegerGuacamoleProperty JOIN_REQUEST_TIMEOUT =
            new IntegerGuacamoleProperty() {

        @Override
        public String getName() { return "join-request-timeout"; }

    };

}
