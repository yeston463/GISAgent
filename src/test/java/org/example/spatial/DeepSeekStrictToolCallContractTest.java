package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

@SpringBootTest(properties = {
        "DEEPSEEK_API_KEY=contract-key",
        "ai.deepseek.base-url=http://deepseek.contract",
        "ai.deepseek.router-model-name=deepseek-v4-flash",
        "rag.auto-load=false",
        "spatial.knowledge-graph.url="
})
class DeepSeekStrictToolCallContractTest {
    @Autowired private RestTemplate restTemplate;
    @Autowired private LlmSpatialRouter router;
    @Autowired private SpatialRoutingTelemetry telemetry;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() { server = MockRestServiceServer.bindTo(restTemplate).build(); }

    @AfterEach
    void tearDown() { server.verify(); }

    @Test
    void sendsDisabledThinkingAndForcedStrictToolChoiceThenAcceptsValidatedRoute() {
        server.expect(once(), requestTo("http://deepseek.contract/beta/chat/completions"))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer contract-key"))
                .andExpect(request -> assertStrictToolCallPayload((MockClientHttpRequest) request))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"tool_calls":[{"function":{"name":"select_spatial_route",
                        "arguments":"{\\\"kind\\\":\\\"analysis\\\",\\\"catalog_id\\\":\\\"flood_analysis\\\",\\\"dataset\\\":\\\"none\\\",\\\"location\\\":\\\"\\\"}"}}]}}]}
                        """, MediaType.APPLICATION_JSON));

        LlmSpatialRouter.Route route = router.route("进行洪水分析");

        assertEquals("analysis", route.kind());
        assertEquals("flood_analysis", route.capabilityIds().get(0));
        assertTrue(((Number) telemetry.snapshot().get("deepseekCalls")).longValue() >= 1);
    }

    private void assertStrictToolCallPayload(MockClientHttpRequest request) {
        assertEquals(MediaType.APPLICATION_JSON, request.getHeaders().getContentType());
        JSONObject body = JSON.parseObject(request.getBodyAsString(StandardCharsets.UTF_8));
        assertEquals("deepseek-v4-flash", body.getString("model"));
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
        assertEquals("function", body.getJSONObject("tool_choice").getString("type"));
        assertEquals("select_spatial_route", body.getJSONObject("tool_choice").getJSONObject("function").getString("name"));
        JSONObject function = body.getJSONArray("tools").getJSONObject(0).getJSONObject("function");
        assertTrue(function.getBooleanValue("strict"));
        assertTrue(!function.getJSONObject("parameters").getBooleanValue("additionalProperties"));
        assertTrue(function.getJSONObject("parameters").getJSONArray("required").contains("location"));
    }
}
