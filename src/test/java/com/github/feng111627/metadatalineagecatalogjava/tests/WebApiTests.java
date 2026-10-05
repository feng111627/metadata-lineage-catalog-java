package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.tests.support.BusinessCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 接口端到端测试：契约、状态码与稳定错误类型。
 */
@SpringBootTest
@AutoConfigureMockMvc
@BusinessCase("HTTP 接口契约")
class WebApiTests {

    @Autowired
    private MockMvc mvc;

    private static String registerBody(int expectedVersion, String fieldJson) {
        return """
                {"expectedVersion":%d,"changeNote":"t","createdBy":"tester","fields":[%s]}"""
                .formatted(expectedVersion, fieldJson);
    }

    private static final String FIELD_ID =
            """
            {"name":"id","type":"BIGINT","nullable":false,"semantic":"user_id","sensitivity":"PII","description":"id"}""";
    private static final String FIELD_NAME =
            """
            {"name":"name","type":"STRING","nullable":true,"semantic":"","sensitivity":"NONE","description":"n"}""";

    @Test
    @BusinessCase(value = "HTTP 接口契约", caseName = "启动样例数据已写入并可查询")
    void sampleDataIsAvailable() throws Exception {
        mvc.perform(get("/api/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@=='ods_user')]").exists());
        mvc.perform(get("/api/datasets/ods_user/versions/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    @BusinessCase(value = "HTTP 接口契约", caseName = "登记新版本返回版本号与兼容性结论")
    void registerVersionReturnsOutcome() throws Exception {
        mvc.perform(post("/api/datasets/api_ds_x/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(0, FIELD_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.version").value(1))
                .andExpect(jsonPath("$.compatibility.compatible").value(true));
    }

    @Test
    @BusinessCase(value = "HTTP 接口契约", caseName = "并发冲突返回 409 与 VERSION_CONFLICT 错误码")
    void conflictReturns409() throws Exception {
        mvc.perform(post("/api/datasets/api_ds_c/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(0, FIELD_ID))).andExpect(status().isOk());
        mvc.perform(post("/api/datasets/api_ds_c/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(0, FIELD_NAME)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    @BusinessCase(value = "HTTP 接口契约", caseName = "删除字段的不兼容登记返回 blocking 差异与字段名")
    void incompatibleChangeIsExplained() throws Exception {
        mvc.perform(post("/api/datasets/api_ds_i/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(0, FIELD_ID + "," + FIELD_NAME))).andExpect(status().isOk());
        mvc.perform(post("/api/datasets/api_ds_i/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(1, FIELD_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatibility.compatible").value(false))
                .andExpect(jsonPath("$.compatibility.changes[0].reason").value("FIELD_REMOVED"))
                .andExpect(jsonPath("$.compatibility.changes[0].fieldName").value("name"));
    }

    @Test
    @BusinessCase(value = "HTTP 接口契约", caseName = "血缘成环返回 409 与 LINEAGE_CYCLE 错误码")
    void lineageCycleReturns409() throws Exception {
        mvc.perform(post("/api/datasets/api_a/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(0, FIELD_ID))).andExpect(status().isOk());
        String edgeAtoA = """
                {"upstreamDataset":"api_a","upstreamField":null,"downstreamDataset":"api_a","downstreamField":null,"createdBy":"t"}""";
        mvc.perform(post("/api/lineage/edges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edgeAtoA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LINEAGE_CYCLE"));
    }

    @Test
    @BusinessCase(value = "HTTP 接口契约", caseName = "查询不存在数据集返回 404 与 DATASET_NOT_FOUND")
    void notFoundReturns404() throws Exception {
        mvc.perform(get("/api/datasets/nope/versions/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DATASET_NOT_FOUND"));
    }

    @Test
    @BusinessCase(value = "HTTP 接口契约", caseName = "错误 JSON 返回 400 且不泄漏底层异常")
    void malformedJsonReturns400() throws Exception {
        mvc.perform(post("/api/datasets/api_ds_b/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
