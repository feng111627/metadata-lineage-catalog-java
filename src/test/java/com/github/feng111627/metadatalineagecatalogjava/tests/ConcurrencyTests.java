package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.domain.DataType;
import com.github.feng111627.metadatalineagecatalogjava.domain.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.domain.SensitivityLevel;
import com.github.feng111627.metadatalineagecatalogjava.engine.CompatibilityEngine;
import com.github.feng111627.metadatalineagecatalogjava.exception.CatalogErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.exception.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.service.CatalogService;
import com.github.feng111627.metadatalineagecatalogjava.store.CatalogStore;
import com.github.feng111627.metadatalineagecatalogjava.tests.support.BusinessCase;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.DatasetRegisterRequest;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.RegistrationOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发修改与历史一致性测试。
 */
@BusinessCase("并发修改与历史一致性")
class ConcurrencyTests {

    private static DatasetRegisterRequest request(int base, String fieldName) {
        return new DatasetRegisterRequest(base, "concurrent edit by " + fieldName, "t",
                List.of(new DatasetRegisterRequest.FieldInput(fieldName, DataType.STRING,
                        true, "", SensitivityLevel.NONE, "")));
    }

    @Test
    @BusinessCase(value = "并发修改与历史一致性", caseName = "基于同一版本并发提交：只有一方成功，其余得到 VERSION_CONFLICT")
    void concurrentSameBaseCommitsExactlyOne() throws Exception {
        CatalogService service = new CatalogService(new CatalogStore(), new CompatibilityEngine());
        service.register("ds", request(0, "seed")); // v1

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.register("ds", request(1, "f" + idx));
                    return true;
                } catch (CatalogException ex) {
                    assertEquals(CatalogErrorCode.VERSION_CONFLICT, ex.errorCode());
                    return false;
                }
            }));
        }
        start.countDown();

        AtomicInteger success = new AtomicInteger();
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                success.incrementAndGet();
            }
        }
        pool.shutdown();

        assertEquals(1, success.get(), "exactly one concurrent commit must succeed");
        assertEquals(2, service.latest("ds").version(), "no silent overwrite: only v2 exists");
    }

    @Test
    @BusinessCase(value = "并发修改与历史一致性", caseName = "失败方基于最新版本重试后可以串行推进")
    void conflictedClientCanRetryOnLatestVersion() {
        CatalogService service = new CatalogService(new CatalogStore(), new CompatibilityEngine());
        service.register("ds", request(0, "seed")); // v1
        service.register("ds", request(1, "first")); // v2 提交成功

        // 另一个客户端仍以为基线是 1
        CatalogException ex = assertThrows(CatalogException.class,
                () -> service.register("ds", request(1, "stale")));
        assertEquals(CatalogErrorCode.VERSION_CONFLICT, ex.errorCode());

        // 以最新版本 2 为基线重试，成功产生 v3
        RegistrationOutcome outcome = service.register("ds", request(2, "retry"));
        assertEquals(3, outcome.version().version());
    }

    @Test
    @BusinessCase(value = "并发修改与历史一致性", caseName = "历史只追加：旧版本内容在新版本产生后保持一致")
    void historyIsAppendOnlyAndImmutable() throws Exception {
        CatalogService service = new CatalogService(new CatalogStore(), new CompatibilityEngine());
        service.register("ds", request(0, "seed")); // v1
        service.register("ds", request(1, "second")); // v2

        DatasetVersion v1Snapshot = service.versionAt("ds", 1);
        DatasetVersion v2Snapshot = service.versionAt("ds", 2);

        // 并发读取历史期间继续追加 v3
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> {
                start.await();
                for (int j = 0; j < 50; j++) {
                    assertEquals("seed", service.versionAt("ds", 1)
                            .fields().get(0).name());
                    assertEquals("second", service.versionAt("ds", 2)
                            .fields().get(0).name());
                }
                return null;
            });
        }
        start.countDown();
        for (Future<Void> f : pool.invokeAll(tasks)) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        service.register("ds", request(2, "third")); // v3

        assertEquals(v1Snapshot, service.versionAt("ds", 1));
        assertEquals(v2Snapshot, service.versionAt("ds", 2));
        assertEquals(3, service.history("ds").size());
        assertEquals(1, service.history("ds").get(0).version());
    }

    @Test
    @BusinessCase(value = "并发修改与历史一致性", caseName = "首次登记并发：expectedVersion=0 只允许一方成功")
    void concurrentInitialRegistrationSingleWinner() throws Exception {
        CatalogService service = new CatalogService(new CatalogStore(), new CompatibilityEngine());
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.register("fresh", request(0, "x"));
                    return true;
                } catch (CatalogException ex) {
                    return false;
                }
            }));
        }
        start.countDown();
        int success = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) {
                success++;
            }
        }
        pool.shutdown();

        assertEquals(1, success);
        assertEquals(1, service.latest("fresh").version());
    }

    @Test
    @BusinessCase(value = "并发修改与历史一致性", caseName = "查询不存在的数据集/版本返回稳定错误类型")
    void missingEntitiesReturnTypedErrors() {
        CatalogService service = new CatalogService(new CatalogStore(), new CompatibilityEngine());

        CatalogException datasetEx = assertThrows(CatalogException.class,
                () -> service.latest("ghost"));
        assertEquals(CatalogErrorCode.DATASET_NOT_FOUND, datasetEx.errorCode());

        service.register("ds", request(0, "seed"));
        CatalogException versionEx = assertThrows(CatalogException.class,
                () -> service.versionAt("ds", 99));
        assertEquals(CatalogErrorCode.VERSION_NOT_FOUND, versionEx.errorCode());
        assertTrue(versionEx.getMessage().contains("99"));
    }

    @Test
    @BusinessCase(value = "并发修改与历史一致性", caseName = "同一版本重复字段以 DUPLICATE_FIELD 拒绝")
    void duplicateFieldsRejected() {
        CatalogService service = new CatalogService(new CatalogStore(), new CompatibilityEngine());
        DatasetRegisterRequest req = new DatasetRegisterRequest(0, "dup", "t", List.of(
                new DatasetRegisterRequest.FieldInput("a", DataType.STRING, true, "",
                        SensitivityLevel.NONE, ""),
                new DatasetRegisterRequest.FieldInput("a", DataType.INTEGER, true, "",
                        SensitivityLevel.NONE, "")));

        CatalogException ex = assertThrows(CatalogException.class,
                () -> service.register("ds", req));
        assertEquals(CatalogErrorCode.DUPLICATE_FIELD, ex.errorCode());
    }
}
