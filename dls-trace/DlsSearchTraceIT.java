/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.dlsfls;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.xpack.core.security.authc.support.UsernamePasswordToken;
import org.elasticsearch.xpack.security.SecurityOnTrialLicenseRestTestCase;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.equalTo;

/**
 * Integration test that exercises a DLS-restricted search across a multi-shard index
 * on the 2-node trial-license cluster. The search request carries an {@code X-Opaque-Id}
 * header so that exec-tree-vis trace instrumentation can correlate the full request
 * processing path (HTTP entry, authn/authz, transport fan-out, shard query, DLS
 * reader wrapping) across both nodes.
 */
public class DlsSearchTraceIT extends SecurityOnTrialLicenseRestTestCase {

    private static final String DLS_USER = "dls_trace_user";
    private static final SecureString DLS_PASSWORD = new SecureString("dls-trace-password".toCharArray());
    private static final String INDEX_NAME = "dls-trace-test";

    @Before
    public void setup() throws IOException {
        createUser(DLS_USER, DLS_PASSWORD, List.of("dls_search_trace_role"));

        Request createIndex = new Request("PUT", INDEX_NAME);
        createIndex.setJsonEntity("""
            {
              "settings": {
                "number_of_shards": 3,
                "number_of_replicas": 0
              },
              "mappings": {
                "properties": {
                  "department": { "type": "keyword" },
                  "title": { "type": "text" }
                }
              }
            }""");
        assertOK(adminClient().performRequest(createIndex));

        bulkIndex(
            Map.of("department", "engineering", "title", "Build system"),
            Map.of("department", "engineering", "title", "Search infrastructure"),
            Map.of("department", "marketing", "title", "Campaign launch"),
            Map.of("department", "marketing", "title", "Brand strategy"),
            Map.of("department", "engineering", "title", "Security hardening"),
            Map.of("department", "sales", "title", "Q3 pipeline"),
            Map.of("department", "engineering", "title", "Code review tooling"),
            Map.of("department", "sales", "title", "Enterprise accounts"),
            Map.of("department", "marketing", "title", "Social media analytics"),
            Map.of("department", "engineering", "title", "Cluster resilience"),
            Map.of("department", "hr", "title", "Benefits enrollment"),
            Map.of("department", "engineering", "title", "Query optimization"),
            Map.of("department", "sales", "title", "Partner integrations"),
            Map.of("department", "marketing", "title", "Product launch event"),
            Map.of("department", "engineering", "title", "Index lifecycle"),
            Map.of("department", "hr", "title", "Onboarding workflow"),
            Map.of("department", "engineering", "title", "Snapshot restore"),
            Map.of("department", "sales", "title", "Renewal forecasting"),
            Map.of("department", "engineering", "title", "Watcher alerting"),
            Map.of("department", "marketing", "title", "Content strategy")
        );

        assertOK(adminClient().performRequest(new Request("POST", INDEX_NAME + "/_refresh")));
    }

    @After
    public void cleanup() throws IOException {
        deleteUser(DLS_USER);
    }

    public void testDlsSearchWithOpaqueId() throws IOException {
        String opaqueId = "dls-search-trace-" + System.currentTimeMillis();
        System.out.println(opaqueId);

        Request searchRequest = new Request("GET", INDEX_NAME + "/_search");
        searchRequest.addParameter("size", "10");
        searchRequest.setOptions(
            RequestOptions.DEFAULT.toBuilder()
                .addHeader("Authorization", UsernamePasswordToken.basicAuthHeaderValue(DLS_USER, DLS_PASSWORD))
                .addHeader("X-Opaque-Id", opaqueId)
        );

        Response response = client().performRequest(searchRequest);
        assertOK(response);
        assertSearchHits(response, Set.of("engineering"));
    }

    @SuppressWarnings("unchecked")
    private void assertSearchHits(Response response, Set<String> allowedDepartments) throws IOException {
        Map<String, Object> body = responseAsMap(response);
        Map<String, Object> hits = (Map<String, Object>) body.get("hits");
        List<Map<String, Object>> docs = (List<Map<String, Object>>) hits.get("hits");

        assertThat(
            "DLS should filter to only permitted departments",
            docs.stream()
                .map(d -> (String) ((Map<String, Object>) d.get("_source")).get("department"))
                .collect(Collectors.toSet()),
            equalTo(allowedDepartments)
        );

        assertThat("Expected 9 engineering docs", docs.size(), equalTo(9));
    }

    @SafeVarargs
    private void bulkIndex(Map<String, String>... docs) throws IOException {
        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < docs.length; i++) {
            bulk.append("{\"index\":{\"_id\":\"").append(i + 1).append("\"}}\n");
            bulk.append("{");
            boolean first = true;
            for (Map.Entry<String, String> entry : docs[i].entrySet()) {
                if (first == false) {
                    bulk.append(",");
                }
                bulk.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                first = false;
            }
            bulk.append("}\n");
        }
        Request bulkRequest = new Request("POST", INDEX_NAME + "/_bulk");
        bulkRequest.setJsonEntity(bulk.toString());
        assertOK(adminClient().performRequest(bulkRequest));
    }
}
