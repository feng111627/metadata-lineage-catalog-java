package com.github.feng111627.metadatalineagecatalogjava.bootstrap;

import com.github.feng111627.metadatalineagecatalogjava.domain.DataType;
import com.github.feng111627.metadatalineagecatalogjava.domain.SensitivityLevel;
import com.github.feng111627.metadatalineagecatalogjava.service.CatalogService;
import com.github.feng111627.metadatalineagecatalogjava.service.LineageService;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.DatasetRegisterRequest;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.LineageRegisterRequest;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.RegistrationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时写入本地模拟数据：三张分层数据集、两个版本、若干血缘边。
 */
@Component
public class SampleDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataSeeder.class);

    private final CatalogService catalogService;
    private final LineageService lineageService;

    public SampleDataSeeder(CatalogService catalogService, LineageService lineageService) {
        this.catalogService = catalogService;
        this.lineageService = lineageService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedDatasets();
        seedLineage();
        log.info("[sample-data] seeding complete: datasets={}", catalogService.datasets());
    }

    private void seedDatasets() {
        RegistrationOutcome odsV1 = catalogService.register("ods_user",
                new DatasetRegisterRequest(0, "初始登记 ODS 用户表", "system", List.of(
                        field("user_id", DataType.BIGINT, false, "user_id", SensitivityLevel.PII, "用户唯一标识"),
                        field("email", DataType.STRING, false, "email", SensitivityLevel.PII, "邮箱地址"),
                        field("age", DataType.INTEGER, true, "", SensitivityLevel.NONE, "年龄"))));

        RegistrationOutcome odsV2 = catalogService.register("ods_user",
                new DatasetRegisterRequest(1, "新增注册时间字段（兼容）", "system", List.of(
                        field("user_id", DataType.BIGINT, false, "user_id", SensitivityLevel.PII, "用户唯一标识"),
                        field("email", DataType.STRING, false, "email", SensitivityLevel.PII, "邮箱地址"),
                        field("age", DataType.INTEGER, true, "", SensitivityLevel.NONE, "年龄"),
                        field("registered_at", DataType.TIMESTAMP, true, "", SensitivityLevel.NONE, "注册时间"))));

        catalogService.register("dwd_user_profile",
                new DatasetRegisterRequest(0, "初始登记 DWD 用户画像", "system", List.of(
                        field("user_id", DataType.BIGINT, false, "user_id", SensitivityLevel.PII, "用户唯一标识"),
                        field("age_bucket", DataType.STRING, true, "", SensitivityLevel.NONE, "年龄段分桶"))));

        catalogService.register("ads_user_stats",
                new DatasetRegisterRequest(0, "初始登记 ADS 用户统计", "system", List.of(
                        field("stat_date", DataType.DATE, false, "", SensitivityLevel.NONE, "统计日期"),
                        field("user_count", DataType.BIGINT, false, "", SensitivityLevel.NONE, "用户数"))));

        log.info("[sample-data] ods_user v1 compatible={}, v2 compatible={}",
                odsV1.compatibility().compatible(), odsV2.compatibility().compatible());
    }

    private void seedLineage() {
        register(new LineageRegisterRequest("ods_user", "user_id",
                "dwd_user_profile", "user_id", "system"));
        register(new LineageRegisterRequest("dwd_user_profile", null,
                "ads_user_stats", null, "system"));
        // 重复提交同一条依赖，验证幂等（不会产生第二条边）
        boolean duplicated = register(new LineageRegisterRequest("ods_user", "user_id",
                "dwd_user_profile", "user_id", "system"));
        log.info("[sample-data] lineage edges seeded; duplicate submission createdAnotherEdge={}", duplicated);
    }

    private boolean register(LineageRegisterRequest request) {
        return lineageService.registerEdge(request);
    }

    private static DatasetRegisterRequest.FieldInput field(String name, DataType type, boolean nullable,
                                                            String semantic, SensitivityLevel sensitivity,
                                                            String description) {
        return new DatasetRegisterRequest.FieldInput(name, type, nullable, semantic, sensitivity, description);
    }
}
