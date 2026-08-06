package org.example.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 离线演示案例接口测试：验证内嵌 classpath 资源的 case/rules 可通过
 * GET /api/gis/offline-case 完整返回，且不依赖 OSM 等外部服务。
 */
@SpringBootTest(properties = {
        "spatial.demo.enabled=true",
        "rag.auto-load=false",
        "spatial.knowledge-graph.url="
})
@AutoConfigureMockMvc
@WithMockUser(roles = "USER")
class OfflineCaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void offlineCase_returnsBundledCaseAndRules() throws Exception {
        mockMvc.perform(get("/api/gis/offline-case"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Success"))
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.sourceData").value("bundle"))
                .andExpect(jsonPath("$.case.caseId").value("urban-renewal-demo-01"))
                .andExpect(jsonPath("$.case.aoi.geometry.coordinates[0][0][0]").value(121.4720))
                .andExpect(jsonPath("$.case.buildings.features.length()").value(6))
                .andExpect(jsonPath("$.case.buildings.features[0].properties.id").value("B01"))
                .andExpect(jsonPath("$.case.greenSpaces.features.length()").value(1))
                .andExpect(jsonPath("$.case.greenSpaces.features[0].properties.id").value("G01"))
                .andExpect(jsonPath("$.rules.ruleSetId").value("demo-r2-01"))
                .andExpect(jsonPath("$.rules.effective").value(false))
                .andExpect(jsonPath("$.rules.rules.far.max").value(2.0))
                .andExpect(jsonPath("$.rules.rules.buildingHeight.max").value(54.0));
    }
}