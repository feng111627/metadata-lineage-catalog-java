package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.error.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.error.ErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.model.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldMeta;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldType;
import com.github.feng111627.metadatalineagecatalogjava.service.CompatibilityChecker;
import com.github.feng111627.metadatalineagecatalogjava.service.DatasetCatalogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 业务：同一数据集的并发修改与历史一致性。 */
@DisplayName("业务-并发修改与历史一致性")
class ConcurrentModificationTest {

    private final DatasetCatalogService catalog = new DatasetCatalogService(new CompatibilityChecker());

    private FieldMeta field(String name) {
        return new FieldMeta(name, FieldType.STRING, name + "-语义", Set.of());
    }

    @Test
    @DisplayName("用例: 两个线程基于同一基线并发提交，只有一个成功，另一个收到 VERSION_CONFLICT")
    void concurrentSameBaselineOnlyOneWins() throws InterruptedException {
        catalog.registerDataset("events", List.of(field("id")));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        ConcurrentLinkedQueue<CatalogException> conflicts = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 2; i++) {
            int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    catalog.registerNewVersion("events", 1,
                            List.of(field("id"), field("f" + idx)));
                    success.incrementAndGet();
                } catch (CatalogException e) {
                    conflicts.add(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts).hasSize(1).allSatisfy(e -> {
            assertThat(e.getCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
            assertThat(e.getMessage()).contains("并发修改");
        });
        // 版本链连续且无静默覆盖：v1 + 一个 v2
        assertThat(catalog.listVersions("events")).hasSize(2);
        assertThat(catalog.listVersions("events").get(1).version()).isEqualTo(2);
    }

    @Test
    @DisplayName("用例: 过期基线版本提交被拒绝，不会静默覆盖")
    void staleBaselineRejected() {
        catalog.registerDataset("events", List.of(field("id")));
        catalog.registerNewVersion("events", 1, List.of(field("id"), field("a")));
        assertThatThrownBy(() -> catalog.registerNewVersion("events", 1,
                List.of(field("id"), field("b"))))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
    }

    @Test
    @DisplayName("用例: 并发写入期间历史版本查询结论保持一致")
    void historicalVersionConsistentDuringConcurrentWrites() throws InterruptedException {
        catalog.registerDataset("metrics", List.of(field("id")));
        DatasetVersion v1Snapshot = catalog.getVersion("metrics", 1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicInteger mismatches = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    go.await();
                    for (int round = 0; round < 50; round++) {
                        if (idx % 2 == 0) {
                            // 写线程：基于当前最新版本追加
                            int base = catalog.listVersions("metrics").size();
                            try {
                                catalog.registerNewVersion("metrics", base,
                                        List.of(field("id"), field("w" + idx + "_" + round)));
                            } catch (CatalogException ignored) {
                                // 并发冲突是预期行为
                            }
                        } else {
                            // 读线程：任何时刻查 v1 都必须等于最初快照
                            if (!catalog.getVersion("metrics", 1).equals(v1Snapshot)) {
                                mismatches.incrementAndGet();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(mismatches.get()).isZero();
        // 版本号严格连续递增
        List<DatasetVersion> versions = catalog.listVersions("metrics");
        for (int v = 1; v <= versions.size(); v++) {
            assertThat(versions.get(v - 1).version()).isEqualTo(v);
        }
    }

    @Test
    @DisplayName("用例: 重复登记同名数据集初始版本被拒绝")
    void duplicateInitialRegistrationRejected() {
        catalog.registerDataset("dup", List.of(field("id")));
        assertThatThrownBy(() -> catalog.registerDataset("dup", List.of(field("id"))))
                .isInstanceOfSatisfying(CatalogException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
    }
}
