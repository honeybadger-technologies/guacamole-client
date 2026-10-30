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


package org.apache.guacamole.metrics;

import com.google.inject.servlet.ServletModule;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.cluster.ClusterProperties;
import org.apache.guacamole.environment.Environment;
import org.apache.guacamole.rest.auth.AuthenticationService;

/**
 * Binds the metrics endpoint, when it is configured.
 */
public class MetricsModule extends ServletModule {

    /**
     * The Guacamole server environment.
     */
    private final Environment environment;

    /**
     * Creates a module serving the given store's metrics.
     *
     * @param environment
     *     The Guacamole server environment.
     *
     */
    public MetricsModule(Environment environment) {
        this.environment = environment;
    }

    @Override
    protected void configureServlets() {

        try {

            boolean enabled = environment.getProperty(
                    ClusterProperties.CLUSTER_METRICS_ENABLED, false);

            String token = environment.getProperty(
                    ClusterProperties.CLUSTER_METRICS_TOKEN);

            if (enabled && (token == null || token.isEmpty()))
                throw new GuacamoleServerException("\"cluster-metrics-enabled\" "
                        + "is true but \"cluster-metrics-token\" is unset. The "
                        + "metrics endpoint is reachable through the same "
                        + "Ingress as the application, so it is never served "
                        + "unguarded.");

            // getProvider defers resolution until after the injector exists,
            // which is what lets the endpoint sample the web application's own
            // store rather than building a second one
            serve("/metrics").with(new MetricsServlet(enabled, token,
                    getProvider(AuthenticationService.class)));

        }

        catch (GuacamoleException e) {
            addError(e);
        }

    }

}
