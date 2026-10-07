package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.api.GlobalExceptionHandler;
import com.github.feng111627.metadatalineagecatalogjava.api.LineageController;
import com.github.feng111627.metadatalineagecatalogjava.service.LineageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务：字段级血缘接口的稳定错误类型与 HTTP 状态。
 * 用 standalone MockMvc + 真实服务 + 全局异常处理器，不依赖 Spring Boot 全上下文。
 */
@DisplayName("业务-接口错误类型稳定性")
class LineageApiErrorTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LineageController(new LineageService()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("用例: 请求体完全缺失返回 400 REQUEST_BODY_MISSING")
    void missingBodyReturnsStableType() throws Exception {
        mockMvc.perform(post("/api/lineage/field-edges"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_MISSING"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("用例: JSON 语法错误返回 400 MALFORMED_JSON，不外抛底层原文")
    void malformedJsonReturnsStableType() throws Exception {
        mockMvc.perform(post("/api/lineage/field-edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not-json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.message").value("请求体不是合法的 JSON，请检查格式后重试"));
    }

    @Test
    @DisplayName("用例: 字段边缺少必填坐标返回 422 VALIDATION_ERROR")
    void missingRequiredFieldReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/lineage/field-edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstream\": {\"dataset\": \"a\"}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("必填项 downstream（含 dataset、field）缺失"));
    }

    @Test
    @DisplayName("用例: 字段坐标内层属性为空串返回 422 VALIDATION_ERROR")
    void blankInnerFieldReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/lineage/field-edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstream\":{\"dataset\":\"a\",\"field\":\"\"},"
                                + "\"downstream\":{\"dataset\":\"b\",\"field\":\"y\"}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("必填项 [upstream.field] 缺失或为空"));
    }

    @Test
    @DisplayName("用例: 数据集边接口缺少必填项同样返回 422 VALIDATION_ERROR")
    void datasetEdgeMissingRequiredReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/lineage/edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstream\": \"a\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("必填项 [downstream] 缺失或为空"));
    }

    @Test
    @DisplayName("用例: 三类请求错误的错误码互相可区分")
    void threeErrorTypesAreDistinguishable() throws Exception {
        String missing = mockMvc.perform(post("/api/lineage/edges"))
                .andReturn().getResponse().getContentAsString();
        String malformed = mockMvc.perform(post("/api/lineage/edges")
                        .contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andReturn().getResponse().getContentAsString();
        String validation = mockMvc.perform(post("/api/lineage/edges")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"upstream\":\"a\"}"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(missing).contains("REQUEST_BODY_MISSING");
        org.assertj.core.api.Assertions.assertThat(malformed).contains("MALFORMED_JSON");
        org.assertj.core.api.Assertions.assertThat(validation).contains("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("用例: 成环登记经接口返回 422 LINEAGE_CYCLE，与参数错误可区分")
    void cycleThroughApiReturnsStableType() throws Exception {
        mockMvc.perform(post("/api/lineage/edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstream\":\"a\",\"downstream\":\"a\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LINEAGE_CYCLE"));
    }

    @Test
    @DisplayName("用例: 正常登记字段边返回 201 且字段影响查询返回 200")
    void happyPathWorks() throws Exception {
        mockMvc.perform(post("/api/lineage/field-edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstream\":{\"dataset\":\"a\",\"field\":\"id\"},"
                                + "\"downstream\":{\"dataset\":\"b\",\"field\":\"oid\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.upstream.dataset").value("a"))
                .andExpect(jsonPath("$.downstream.field").value("oid"));
        mockMvc.perform(get("/api/lineage/impact/field/a/id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasets[0]").value("b"))
                .andExpect(jsonPath("$.fields[0].dataset").value("b"));
    }
}
