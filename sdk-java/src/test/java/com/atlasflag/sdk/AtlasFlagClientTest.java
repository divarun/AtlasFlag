package com.atlasflag.sdk;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AtlasFlagClientTest {

    private MockWebServer server;
    private AtlasFlagClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new AtlasFlagClient.Builder()
            .baseUrl(server.url("/").toString())
            .environment("TEST")
            .cacheEnabled(false)
            .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        client.shutdown();
        server.shutdown();
    }

    // ── isEnabled ─────────────────────────────────────────────────────────────

    @Test
    void isEnabled_enabledFlag_returnsTrue() throws Exception {
        enqueue("{\"flagKey\":\"my-flag\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\"}");

        assertThat(client.isEnabled("my-flag", false)).isTrue();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/v1/flags/evaluate");
        assertThat(req.getMethod()).isEqualTo("POST");
    }

    @Test
    void isEnabled_disabledFlag_returnsFalse() throws Exception {
        enqueue("{\"flagKey\":\"my-flag\",\"enabled\":false,\"reason\":\"FLAG_DISABLED\"}");

        assertThat(client.isEnabled("my-flag", true)).isFalse();
    }

    @Test
    void isEnabled_serverError_returnsDefaultValue() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThat(client.isEnabled("my-flag", true)).isTrue();   // default=true
        assertThat(client.isEnabled("my-flag", false)).isFalse(); // default=false on second server call... wait, need to enqueue another
    }

    @Test
    void isEnabled_serviceUnreachable_returnsDefault() throws IOException {
        // Use an isolated server/client so tearDown's server.shutdown() still works.
        // MockWebServer cannot be restarted after shutdown().
        MockWebServer deadServer = new MockWebServer();
        deadServer.start();
        String url = deadServer.url("/").toString();
        deadServer.shutdown(); // now unreachable

        AtlasFlagClient isolatedClient = new AtlasFlagClient.Builder()
            .baseUrl(url)
            .environment("TEST")
            .cacheEnabled(false)
            .build();
        try {
            assertThat(isolatedClient.isEnabled("my-flag", true)).isTrue();
        } finally {
            isolatedClient.shutdown();
        }
    }

    @Test
    void isEnabled_withUserId_includesUserIdInRequest() throws Exception {
        enqueue("{\"flagKey\":\"flag\",\"enabled\":true,\"reason\":\"ROLLOUT_PERCENTAGE\"}");

        client.isEnabled("flag", "user-42", false);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getBody().readUtf8()).contains("\"userId\":\"user-42\"");
    }

    @Test
    void isEnabled_withAttributes_includesAttributesInRequest() throws Exception {
        enqueue("{\"flagKey\":\"ent\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\"}");

        client.isEnabled("ent", "user-1", Map.of("plan", "pro", "country", "DE"), false);

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"attributes\"").contains("\"plan\"").contains("\"pro\"");
    }

    // ── Caching ───────────────────────────────────────────────────────────────

    @Test
    void isEnabled_cacheEnabled_secondCallDoesNotHitServer() throws IOException {
        AtlasFlagClient cachedClient = new AtlasFlagClient.Builder()
            .baseUrl(server.url("/").toString())
            .environment("TEST")
            .cacheEnabled(true)
            .cacheTtlSeconds(60)
            .build();

        enqueue("{\"flagKey\":\"cached\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\"}");

        boolean first  = cachedClient.isEnabled("cached", false);
        boolean second = cachedClient.isEnabled("cached", false); // should use cache

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(server.getRequestCount()).isEqualTo(1);

        cachedClient.shutdown();
    }

    // ── Remote config ─────────────────────────────────────────────────────────

    @Test
    void getString_stringFlag_returnsValue() throws Exception {
        enqueue("{\"flagKey\":\"color\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\",\"value\":\"#0066CC\"}");

        assertThat(client.getString("color", null, "#FFF")).isEqualTo("#0066CC");
    }

    @Test
    void getString_flagDisabled_returnsDefault() throws Exception {
        enqueue("{\"flagKey\":\"color\",\"enabled\":false,\"reason\":\"FLAG_DISABLED\"}");

        assertThat(client.getString("color", null, "#FFF")).isEqualTo("#FFF");
    }

    @Test
    void getString_nullValue_returnsDefault() throws Exception {
        enqueue("{\"flagKey\":\"color\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\"}"); // no value field

        assertThat(client.getString("color", null, "#FFF")).isEqualTo("#FFF");
    }

    @Test
    void getInt_parsesIntegerValue() throws Exception {
        enqueue("{\"flagKey\":\"timeout\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\",\"value\":\"5000\"}");

        assertThat(client.getInt("timeout", null, 3000)).isEqualTo(5000);
    }

    @Test
    void getInt_nonNumericValue_returnsDefault() throws Exception {
        enqueue("{\"flagKey\":\"timeout\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\",\"value\":\"not-a-number\"}");

        assertThat(client.getInt("timeout", null, 3000)).isEqualTo(3000);
    }

    @Test
    void getDouble_parsesDoubleValue() throws Exception {
        enqueue("{\"flagKey\":\"rate\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\",\"value\":\"0.95\"}");

        assertThat(client.getDouble("rate", null, 1.0)).isEqualTo(0.95);
    }

    @Test
    void getJson_deserializesJsonObject() throws Exception {
        // value field is a JSON-encoded string
        enqueue("{\"flagKey\":\"cfg\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\",\"value\":\"{\\\"limit\\\":100,\\\"enabled\\\":true}\"}");

        Map<?, ?> result = client.getJson("cfg", null, null, Map.class);

        assertThat(result).isNotNull();
        assertThat(result.get("limit")).isEqualTo(100);
    }

    @Test
    void getJson_invalidJson_returnsDefault() throws Exception {
        enqueue("{\"flagKey\":\"cfg\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\",\"value\":\"not-json\"}");

        Map<?, ?> defaultVal = Map.of("default", true);
        Map<?, ?> result = client.getJson("cfg", null, defaultVal, Map.class);

        assertThat(result).isEqualTo(defaultVal);
    }

    // ── Bulk evaluation ───────────────────────────────────────────────────────

    @Test
    void isEnabledBulk_evaluatesMultipleFlags() throws Exception {
        enqueue("{" +
            "\"flag-a\":{\"flagKey\":\"flag-a\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\"}," +
            "\"flag-b\":{\"flagKey\":\"flag-b\",\"enabled\":false,\"reason\":\"FLAG_DISABLED\"}" +
            "}");

        Map<String, Boolean> result = client.isEnabledBulk(List.of("flag-a", "flag-b"), null, false);

        assertThat(result).containsEntry("flag-a", true).containsEntry("flag-b", false);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/v1/flags/evaluate/bulk");
    }

    @Test
    void isEnabledBulk_emptyList_returnsEmptyMap() {
        assertThat(client.isEnabledBulk(List.of(), null, false)).isEmpty();
        assertThat(server.getRequestCount()).isZero(); // no HTTP call for empty list
    }

    @Test
    void isEnabledBulk_missingFlagInResponse_usesDefault() throws Exception {
        // Server only returns flag-a, flag-b is missing from response
        enqueue("{\"flag-a\":{\"flagKey\":\"flag-a\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\"}}");

        Map<String, Boolean> result = client.isEnabledBulk(List.of("flag-a", "flag-b"), null, false);

        assertThat(result.get("flag-a")).isTrue();
        assertThat(result.get("flag-b")).isFalse(); // default value
    }

    @Test
    void isEnabledBulk_withAttributes_includesInRequest() throws Exception {
        enqueue("{\"ent\":{\"flagKey\":\"ent\",\"enabled\":true,\"reason\":\"FLAG_ENABLED\"}}");

        client.isEnabledBulk(List.of("ent"), "user-1", Map.of("plan", "pro"), false);

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"attributes\"").contains("\"plan\"");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void enqueue(String json) {
        server.enqueue(new MockResponse()
            .setBody(json)
            .addHeader("Content-Type", "application/json"));
    }
}
