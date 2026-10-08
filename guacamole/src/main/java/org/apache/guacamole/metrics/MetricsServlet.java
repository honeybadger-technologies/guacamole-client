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

import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.google.inject.Provider;
import org.apache.guacamole.cluster.metrics.ClusterMetrics;
import org.apache.guacamole.rest.auth.AuthenticationService;

/**
 * Serves the cluster metrics registry in the Prometheus text exposition
 * format, guarded by a bearer token.
 *
 * The endpoint cannot require a Guacamole session, because a scraper does not
 * have one, and it cannot be open, because the Ingress routes every path to
 * this application and offers no per-path deny.
 */
public class MetricsServlet extends HttpServlet {

    /**
     * The content type of the Prometheus text exposition format.
     */
    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    /**
     * The prefix of an Authorization header carrying a bearer token.
     */
    private static final String BEARER = "Bearer ";

    /**
     * Whether the endpoint is served at all.
     */
    private final boolean enabled;

    /**
     * The bearer token a request must carry, which may be null.
     */
    private final String token;

    /**
     * Supplies the authentication service holding the store whose gauges are
     * sampled before each render, which may be null.
     *
     * A provider rather than the service itself: this servlet is bound while
     * the injector is still being created, so the service does not exist yet.
     */
    private final Provider<AuthenticationService> authenticationService;

    /**
     * The status and body of a single metrics request.
     */
    public static class Result {

        private final int status;
        private final String body;

        /**
         * Creates a result with the given status and body.
         *
         * @param status
         *     The HTTP status to send.
         *
         * @param body
         *     The body to send, which is empty for every refusal.
         */
        public Result(int status, String body) {
            this.status = status;
            this.body = body;
        }

        /**
         * Returns the HTTP status of this result.
         *
         * @return
         *     The HTTP status.
         */
        public int getStatus() {
            return status;
        }

        /**
         * Returns the body of this result.
         *
         * @return
         *     The body, which is empty for every refusal.
         */
        public String getBody() {
            return body;
        }

    }

    /**
     * Creates a servlet serving the given store's metrics.
     *
     * @param enabled
     *     Whether the endpoint is served at all.
     *
     * @param token
     *     The bearer token a request must carry.
     *
     * @param authenticationService
     *     Supplies the authentication service holding the store whose gauges
     *     are sampled before each render, which may be null.
     */
    public MetricsServlet(boolean enabled, String token,
            Provider<AuthenticationService> authenticationService) {
        this.enabled = enabled;
        this.token = token;
        this.authenticationService = authenticationService;
    }

    /**
     * Decides what a request carrying the given Authorization header receives.
     *
     * Separated from doGet so that it can be tested without mocking the
     * Servlet API, which this module has no library for.
     *
     * @param authorization
     *     The request's Authorization header, which may be null.
     *
     * @return
     *     The status and body to send.
     */
    public Result render(String authorization) {

        // A disabled endpoint is indistinguishable from one that was never
        // built
        if (!enabled)
            return new Result(HttpServletResponse.SC_NOT_FOUND, "");

        if (!authorized(authorization))
            return new Result(HttpServletResponse.SC_UNAUTHORIZED, "");

        if (authenticationService != null)
            authenticationService.get().getClusterStore().publishMetrics();

        return new Result(HttpServletResponse.SC_OK, ClusterMetrics.render());

    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        Result result = render(request.getHeader("Authorization"));

        response.setStatus(result.getStatus());

        if (result.getStatus() == HttpServletResponse.SC_OK) {
            response.setContentType(CONTENT_TYPE);
            response.getWriter().write(result.getBody());
        }

    }

    /**
     * Returns whether the given Authorization header carries the configured
     * token, compared in constant time.
     *
     * @param header
     *     The Authorization header, which may be null.
     *
     * @return
     *     true if the header authorises the request.
     */
    private boolean authorized(String header) {

        if (header == null || token == null || !header.startsWith(BEARER))
            return false;

        String presented = header.substring(BEARER.length());
        if (presented.length() != token.length())
            return false;

        // Constant time in the length that matters: a timing oracle on a
        // scrape token is cheap to remove and awkward to explain later
        int difference = 0;
        for (int i = 0; i < token.length(); i++)
            difference |= presented.charAt(i) ^ token.charAt(i);

        return difference == 0;

    }

}
