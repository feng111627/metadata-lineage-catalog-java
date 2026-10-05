package com.github.feng111627.metadatalineagecatalogjava.tests.support;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按业务模块分组打印测试用例及通过情况。
 *
 * <p>通过 {@link BusinessCase} 注解读取“业务模块/用例名”，输出形如：</p>
 * <pre>
 * ▌业务模块：版本兼容性边界
 *   ✓ 新增字段判定为兼容
 *   ✗ 删除字段判定为不兼容并给出 FIELD_REMOVED
 * </pre>
 */
public class BusinessLoggingListener implements TestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-COVERAGE");

    private final Set<String> printedGroups = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> groupPass = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> groupFail = new ConcurrentHashMap<>();
    private final Set<String> groupsInOrder = ConcurrentHashMap.newKeySet();

    private int totalPass;
    private int totalFail;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        log.info("==================== 业务测试覆盖报告开始 ====================");
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        BusinessRef ref = resolve(testIdentifier);
        if (ref == null) {
            return;
        }
        groupsInOrder.add(ref.group());
        if (printedGroups.add(ref.group())) {
            log.info("▌业务模块：{}", ref.group());
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
        BusinessRef ref = resolve(testIdentifier);
        if (ref == null) {
            return;
        }
        String mark;
        if (result.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
            mark = "  [PASS] ";
            totalPass++;
            groupPass.computeIfAbsent(ref.group(), k -> new AtomicInteger()).incrementAndGet();
        } else {
            mark = "  [FAIL] ";
            totalFail++;
            groupFail.computeIfAbsent(ref.group(), k -> new AtomicInteger()).incrementAndGet();
        }
        log.info("{} {}", mark, ref.caseName());
        result.getThrowable().ifPresent(t -> log.info("         原因：{}", String.valueOf(t.getMessage())));
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        log.info("-------------------- 分业务汇总 --------------------");
        for (String group : new LinkedHashSet<>(groupsInOrder)) {
            int pass = groupPass.getOrDefault(group, new AtomicInteger()).get();
            int fail = groupFail.getOrDefault(group, new AtomicInteger()).get();
            log.info("  {}：通过 {} 个，失败 {} 个 {}", group, pass, fail, fail == 0 ? "✓" : "✗");
        }
        log.info("==================== 合计：通过 {}，失败 {} ====================",
                totalPass, totalFail);
    }

    private static BusinessRef resolve(TestIdentifier identifier) {
        return identifier.getSource()
                .filter(MethodSource.class::isInstance)
                .map(MethodSource.class::cast)
                .map(source -> {
                    Class<?> javaClass = source.getJavaClass();
                    String methodName = source.getMethodName();
                    String group = groupOf(javaClass);
                    String caseName = methodName;
                    try {
                        for (Method m : javaClass.getDeclaredMethods()) {
                            if (m.getName().equals(methodName) && m.isAnnotationPresent(BusinessCase.class)) {
                                BusinessCase bc = m.getAnnotation(BusinessCase.class);
                                if (!bc.caseName().isBlank()) {
                                    caseName = bc.caseName();
                                }
                                if (!bc.value().isBlank()) {
                                    group = bc.value();
                                }
                            }
                        }
                    } catch (SecurityException ignored) {
                        // 回退到方法名
                    }
                    return new BusinessRef(group, caseName);
                })
                .orElse(null);
    }

    private static String groupOf(Class<?> javaClass) {
        BusinessCase annotation = javaClass.getAnnotation(BusinessCase.class);
        if (annotation != null && !annotation.value().isBlank()) {
            return annotation.value();
        }
        return javaClass.getSimpleName();
    }

    private record BusinessRef(String group, String caseName) {
    }
}
