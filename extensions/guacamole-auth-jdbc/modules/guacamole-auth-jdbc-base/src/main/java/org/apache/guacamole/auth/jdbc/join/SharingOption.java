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

/**
 * A sharing profile the connected user may admit a newcomer with.
 */
public class SharingOption {

    private final String identifier;
    private final String name;

    /**
     * Creates a SharingOption.
     *
     * @param identifier
     *     The sharing profile identifier.
     *
     * @param name
     *     The human-readable sharing profile name.
     */
    public SharingOption(String identifier, String name) {
        this.identifier = identifier;
        this.name = name;
    }

    /**
     * @return The sharing profile identifier.
     */
    public String getIdentifier() { return identifier; }

    /**
     * @return The human-readable sharing profile name.
     */
    public String getName() { return name; }

}
