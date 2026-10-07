package com.github.feng111627.metadatalineagecatalogjava.tests;

import com.github.feng111627.metadatalineagecatalogjava.error.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.error.ErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldRef;
import com.github.feng111627.metadatalineagecatalogjava.model.ImpactResult;
import com.github.feng111627.metadatalineagecatalogjava.service.LineageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 业务：血缘并发登记与影响分析的快照一致性。 */
@DisplayName("业务-血缘并发查询一致性")
class LineageConcurrencyTest {

    /**
     * 写线程不断登记字段边：src_i -> mid -> ads（合法），并穿插提交一条必然成环的边 ads -> src_i（拒绝）。
     * 读线程在并发期间反复做字段影响分析；每次结果都必须满足快照不变量：
     * 1) 结果必然包含 ads.a（因为 src 只可能经由 mid 到 ads，链完整才可见，绝不出现“只有 mid 没有 ads”的半图）；
     * 2) 结果内不含来源、无重复、始终有序；
     * 3) 成环写一律以 LINEAGE_CYCLE 拒绝，图中永不出现 ads -> src 的边。
     */
    @Test
    @DisplayName("用例: 并发登记与影响分析同时进行，每次查询都看到完整一致的快照")
    void concurrentWritesAndReadsSeeWholeSnapshots() throws InterruptedException {
        LineageService lineage = new LineageService();
        lineage.addFieldEdge("mid", "m", "ads", "a"); // 公共中间链

        int writers = 4;
        int readers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch ready = new CountDownLatch(writers + readers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger cycleRejected = new AtomicInteger();
        AtomicInteger illegalReads = new AtomicInteger();
        ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();

        for (int w = 0; w < writers; w++) {
            int idx = w;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int round = 0; round < 100; round++) {
                        String src = "src" + idx + "_" + round;
                        lineage.addFieldEdge(src, "f", "mid", "m");
                        try {
                            // 反向边：ads 已（经 mid）可达 src 时成环；
                            // 若 src 边尚未被该线程自己提交，则可能成功，故仅统计成环拒绝次数 >= 0。
                            lineage.addFieldEdge("ads", "a", src, "f");
                        } catch (CatalogException e) {
                            if (e.getCode() == ErrorCode.LINEAGE_CYCLE) {
                                cycleRejected.incrementAndGet();
                            } else {
                                unexpected.add("写线程收到非成环错误: " + e.getCode());
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int round = 0; round < 200; round++) {
                        ImpactResult impact = lineage.impactOfField("mid", "m");
                        Set<FieldRef> fields = Set.copyOf(impact.fields());
                        // 无重复
                        if (fields.size() != impact.fields().size()) {
                            illegalReads.incrementAndGet();
                        }
                        // 不含来源字段
                        if (impact.fields().contains(new FieldRef("mid", "m"))) {
                            illegalReads.incrementAndGet();
                        }
                        // 有序
                        for (int i = 1; i < impact.fields().size(); i++) {
                            if (impact.fields().get(i - 1).toString()
                                    .compareTo(impact.fields().get(i).toString()) > 0) {
                                illegalReads.incrementAndGet();
                            }
                        }
                        // 快照完整性不变量：mid.m 的下游只要存在，ads.a 必然在列（不存在半图）
                        if (!impact.fields().isEmpty()
                                && !impact.fields().contains(new FieldRef("ads", "a"))) {
                            illegalReads.incrementAndGet();
                        }
                        // 数据集与字段的归属一致
                        for (FieldRef ref : impact.fields()) {
                            if (!impact.datasets().contains(ref.dataset())) {
                                illegalReads.incrementAndGet();
                            }
                        }
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

        assertThat(illegalReads.get()).isZero();
        assertThat(unexpected).isEmpty();
        // 成环写确实发生过且被稳定拒绝
        assertThat(cycleRejected.get()).isGreaterThan(0);
        // 最终图里 ads 没有任何上游以外的反向污染：ads 的数据集级下游为空
        assertThat(lineage.downstreamImpact("ads")).isEmpty();
        // 所有 src 最终都可达 ads，且无重复
        ImpactResult finalImpact = lineage.impactOfField("mid", "m");
        assertThat(finalImpact.fields()).contains(new FieldRef("ads", "a"));
        assertThat(finalImpact.datasets()).doesNotContain("mid");
    }
}
