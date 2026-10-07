package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.api.GlobalExceptionHandler;
import com.github.feng111627.metadatalineagecatalogjava.api.LineageController;
import com.github.feng111627.metadatalineagecatalogjava.service.LineageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 业务：字段级血缘新接口的稳定错误类型与 HTTP 状态。 */
@DisplayName("业务-字段血缘接口错误类型")
class LineageApiErrorTest {

    private MockMvc mockMvc;
    private LineageService lineage;

    @BeforeEach
    void setUp() {
        lineage = new LineageService();
        mockMvc = MockMvcBuilders.standaloneSetup(new LineageController(lineage))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("用例: 字段边请求体未携带 -> 400 REQUEST_BODY_MISSING")
    void fieldEdgeMissingBody() throws Exception {
        mockMvc.perform(post("/api/lineage/field-edges"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_MISSING"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("用例: 字段边 JSON 写错 -> 400 MALFORMED_JSON，不回显底层异常原文")
    void fieldEdgeMalformedJson() throws Exception {
        mockMvc.perform(post("/api/lineage/field-edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-a-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.message").value("请求体不是合法的 JSON，请检查格式后重试"));
    }

    @Test
    @DisplayName("用例: 字段边必填项缺失 -> 422 VALIDATION_ERROR")
    void fieldEdgeMissingRequiredField() throws Exception {
        mockMvc.perform(post("/api/lineage/field-edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstreamDataset\":\"a\",\"upstreamField\":\"x\",\"downstreamDataset\":\"b\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("用例: 字段边成环 -> 422 LINEAGE_CYCLE，错误类型与参数错误可区分")
    void fieldEdgeCycleViaApi() throws Exception {
        lineage.addEdge("b", "a"); // 显式数据集边 b -> a
        mockMvc.perform(post("/api/lineage/field-edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstreamDataset\":\"a\",\"upstreamField\":\"x\","
                                + "\"downstreamDataset\":\"b\",\"downstreamField\":\"y\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LINEAGE_CYCLE"));
    }

    @Test
    @DisplayName("用例: 合法字段边 -> 201 并回显登记结果")
    void fieldEdgeAccepted() throws Exception {
        mockMvc.perform(post("/api/lineage/field-edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstreamDataset\":\"a\",\"upstreamField\":\"x\","
                                + "\"downstreamDataset\":\"b\",\"downstreamField\":\"y\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.upstreamDataset").value("a"))
                .andExpect(jsonPath("$.downstreamField").value("y"));
    }

    @Test
    @DisplayName("用例: 数据集边请求体缺失/JSON 非法同样区分两类 400 错误")
    void datasetEdgeBodyErrors() throws Exception {
        mockMvc.perform(post("/api/lineage/edges"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_MISSING"));
        mockMvc.perform(post("/api/lineage/edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("---"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    @DisplayName("用例: 字段影响分析正常返回 datasets/fields 结构且按序输出")
    void fieldImpactApiShape() throws Exception {
        lineage.addFieldEdge("a", "x", "b", "y");
        lineage.addFieldEdge("b", "y", "c", "z");
        mockMvc.perform(get("/api/lineage/impact/a/fields/x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasets[0]").value("b"))
                .andExpect(jsonPath("$.datasets[1]").value("c"))
                .andExpect(jsonPath("$.fields[0].dataset").value("b"))
                .andExpect(jsonPath("$.fields[0].field").value("y"))
                .andExpect(jsonPath("$.fields[1].dataset").value("c"))
                .andExpect(jsonPath("$.fields[1].field").value("z"));
    }
}
