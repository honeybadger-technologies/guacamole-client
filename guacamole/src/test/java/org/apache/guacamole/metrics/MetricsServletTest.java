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

import javax.servlet.http.HttpServletResponse;
import org.apache.guacamole.cluster.metrics.ClusterMetrics;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests which requests the metrics endpoint answers.
 *
 * The servlet's decision logic is tested directly rather than through mock
 * request and response objects: this module has no mocking library, and
 * hand-stubbing the sixty methods of the Servlet 2.5 interfaces would test the
 * stubs rather than the endpoint.
 */
public class MetricsServletTest {

    private static final String TOKEN = "a-long-random-scrape-token";

    @Before
    public void setUp() {
        ClusterMetrics.reset();
        ClusterMetrics.gauge("guacamole_cluster_enabled", 1);
    }

    @Test
    public void servesTheRegistryWithACorrectToken() {

        MetricsServlet.Result result =
                new MetricsServlet(true, TOKEN, null).render("Bearer " + TOKEN);

        Assert.assertEquals(HttpServletResponse.SC_OK, result.getStatus());
        Assert.assertTrue(result.getBody(),
                result.getBody().contains("guacamole_cluster_enabled 1"));

    }

    @Test
    public void refusesAMissingToken() {

        MetricsServlet.Result result =
                new MetricsServlet(true, TOKEN, null).render(null);

        Assert.assertEquals(HttpServletResponse.SC_UNAUTHORIZED, result.getStatus());
        Assert.assertEquals("", result.getBody());

    }

    @Test
    public void refusesAWrongTokenOfTheSameLength() {

        // Same length as TOKEN, so this exercises the comparison rather than
        // the length check in front of it
        String wrong = "b-long-random-scrape-tokeX";
        MetricsServlet.Result result = new MetricsServlet(true, TOKEN, null)
                .render("Bearer " + wrong.substring(0, TOKEN.length()));

        Assert.assertEquals(HttpServletResponse.SC_UNAUTHORIZED, result.getStatus());
        Assert.assertEquals("", result.getBody());

    }

    @Test
    public void refusesAHeaderThatIsNotBearer() {

        MetricsServlet.Result result =
                new MetricsServlet(true, TOKEN, null).render("Basic " + TOKEN);

        Assert.assertEquals(HttpServletResponse.SC_UNAUTHORIZED, result.getStatus());

    }

    @Test
    public void looksLikeItWasNeverBuiltWhenDisabled() {

        MetricsServlet.Result result =
                new MetricsServlet(false, TOKEN, null).render("Bearer " + TOKEN);

        Assert.assertEquals(HttpServletResponse.SC_NOT_FOUND, result.getStatus());
        Assert.assertEquals("", result.getBody());

    }

    @Test
    public void refusesEverythingWhenNoTokenIsConfigured() {

        // Defence in depth: MetricsModule refuses to start in this state, so
        // reaching here means that check was bypassed or removed
        MetricsServlet.Result result =
                new MetricsServlet(true, null, null).render("Bearer anything");

        Assert.assertEquals(HttpServletResponse.SC_UNAUTHORIZED, result.getStatus());

    }

}
