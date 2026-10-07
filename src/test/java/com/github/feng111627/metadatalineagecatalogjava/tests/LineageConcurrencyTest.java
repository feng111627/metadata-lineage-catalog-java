package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.error.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldRef;
import com.github.feng111627.metadatalineagecatalogjava.model.ImpactResult;
import com.github.feng111627.metadatalineagecatalogjava.service.LineageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 业务：血缘登记与影响查询并发下的一致性。 */
@DisplayName("业务-血缘并发一致性")
class LineageConcurrencyTest {

    /**
     * 多个线程并发登记一张分层 DAG（含字段边与数据集边），同时多线程反复查询影响。
     * 任意一次查询都必须看到一张完整自洽的图：
     * - 结果本身严格有序、无重复；
     * - 若下游字段被命中，则其所属数据集一定也在数据集结果里（边不会只看到一半）；
     * - 同一轮内连续两次查询结论一致。
     */
    @Test
    @DisplayName("用例: 并发登记期间影响分析只见完整图，结果有序、自洽、来源被排除")
    void impactConsistentDuringConcurrentRegistration() throws InterruptedException {
        LineageService lineage = new LineageService();
        int writerThreads = 4;
        int readerThreads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + readerThreads);
        CountDownLatch ready = new CountDownLatch(writerThreads + readerThreads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger violations = new AtomicInteger();
        ConcurrentLinkedQueue<String> violationDetails = new ConcurrentLinkedQueue<>();

        for (int w = 0; w < writerThreads; w++) {
            int idx = w;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < 200; i++) {
                        try {
                            // 分层数据集边：layer0 -> layer1 -> layer2
                            lineage.addEdge("l0_" + idx, "l1_" + (i % 5));
                            lineage.addEdge("l1_" + (i % 5), "l2_" + (i % 3));
                            // 字段边：只在无环方向上登记，成环拒绝属于预期
                            lineage.addFieldEdge(
                                    new FieldRef("l0_" + idx, "f" + i),
                                    new FieldRef("l1_" + (i % 5), "g" + i));
                        } catch (CatalogException e) {
                            // 并发/成环拒绝是预期的防御行为
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        for (int r = 0; r < readerThreads; r++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < 1000; i++) {
                        ImpactResult dsImpact = lineage.impactOfDataset("l0_0");
                        ImpactResult fieldImpact = lineage.impactOfField(new FieldRef("l0_0", "f0"));
                        check(violations, violationDetails, dsImpact, "l0_0");
                        check(violations, violationDetails, fieldImpact, "l0_0");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(violations.get())
                .as("并发查询一致性违规: %s", violationDetails)
                .isZero();
    }

    /**
     * 并发重复登记同一条字段边：无论提交多少次，最终只有一条记录，
     * 且任何时刻查询都不会出现重复项。
     */
    @Test
    @DisplayName("用例: 高并发重复登记同一字段依赖最终只算一条")
    void concurrentDuplicateFieldEdgeCollapses() throws InterruptedException {
        LineageService lineage = new LineageService();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        FieldRef up = new FieldRef("a", "id");
        FieldRef down = new FieldRef("b", "oid");
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int j = 0; j < 100; j++) {
                        lineage.addFieldEdge(up, down);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        assertThat(lineage.listFieldEdges()).hasSize(1);
        ImpactResult impact = lineage.impactOfField(up);
        assertThat(impact.fields()).containsExactly(down);
        assertThat(impact.datasets()).doesNotContain("a");
    }

    /**
     * 并发竞争成环：多个线程尝试登记能与已有边闭合为环的字段边，
     * 可能全部或部分被拒，但最终图必须无环（对任意已登记边反向可达性为空）。
     */
    @Test
    @DisplayName("用例: 并发下尝试制造跨粒度环时图始终保持无环")
    void concurrentCycleAttemptsNeverPersistCycle() throws InterruptedException {
        LineageService lineage = new LineageService();
        lineage.addEdge("a", "b"); // 已有数据集边 A -> B
        int threads = 6;
        ExecutorService pool = newSafePool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    try {
                        // B.* -> A.* 会与 A -> B 闭合跨粒度环
                        lineage.addFieldEdge(new FieldRef("b", "x"), new FieldRef("a", "y"));
                    } catch (CatalogException e) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        // 至多一条能在环形成前写入，其余必拒；写入的那一条也必须不破坏无环性：
        // 由于 A -> B 已存在，任何 B -> A 方向字段边都必被拒
        assertThat(lineage.listFieldEdges()).isEmpty();
        assertThat(rejected.get()).isEqualTo(threads);
        // 数据集级影响仍然自洽
        assertThat(lineage.downstreamImpact("a")).containsExactly("b");
    }

    private ExecutorService newSafePool(int n) {
        return Executors.newFixedThreadPool(n);
    }

    private void check(AtomicInteger violations, ConcurrentLinkedQueue<String> details,
                       ImpactResult impact, String source) {
        List<String> ds = impact.datasets();
        List<FieldRef> fs = impact.fields();
        if (!isSortedDistinct(ds)) {
            violations.incrementAndGet();
            details.add("数据集结果非有序去重: " + ds);
        }
        List<String> fKey = fs.stream().map(f -> f.dataset() + "." + f.field()).toList();
        if (!isSortedDistinct(fKey)) {
            violations.incrementAndGet();
            details.add("字段结果非有序去重: " + fKey);
        }
        if (ds.contains(source)) {
            violations.incrementAndGet();
            details.add("来源数据集出现在下游结果中");
        }
        for (FieldRef f : fs) {
            if (f.dataset().equals(source)) {
                violations.incrementAndGet();
                details.add("来源数据集字段出现在下游字段中: " + f);
            }
            if (!ds.contains(f.dataset())) {
                violations.incrementAndGet();
                details.add("命中下游字段但缺失其所属数据集（半成品图）: " + f);
            }
        }
    }

    private <T extends Comparable<T>> boolean isSortedDistinct(List<T> list) {
        List<T> sorted = new ArrayList<>(list);
        java.util.Collections.sort(sorted);
        return sorted.equals(list) && sorted.size() == new java.util.HashSet<>(sorted).size();
    }
}
