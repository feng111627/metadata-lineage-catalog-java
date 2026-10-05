package com.github.feng111627.metadatalineagecatalogjava.tests.support;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试覆盖报告监听器：测试结束后按业务（测试类）汇总用例及通过情况，打印到日志。
 */
public class BusinessCoverageListener implements TestExecutionListener {

    private final Map<String, List<CaseResult>> byBusiness = new LinkedHashMap<>();

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (!id.isTest()) {
            return;
        }
        String business = id.getUniqueId().contains("[class:")
                ? id.getUniqueId().replaceAll(".*\\[class:([^]]+)].*", "$1")
                : "unknown";
        String simpleBusiness = business.substring(business.lastIndexOf('.') + 1);
        byBusiness.computeIfAbsent(simpleBusiness, k -> new ArrayList<>())
                .add(new CaseResult(id.getDisplayName(),
                        result.getStatus() == TestExecutionResult.Status.SUCCESSFUL,
                        result.getThrowable().map(Throwable::getMessage).orElse("")));
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("==================== 业务覆盖测试报告 ====================").append(System.lineSeparator());
        int total = 0, passed = 0;
        for (Map.Entry<String, List<CaseResult>> entry : byBusiness.entrySet()) {
            long ok = entry.getValue().stream().filter(CaseResult::passed).count();
            sb.append(String.format("业务: %s  (%d/%d 通过)%n", entry.getKey(), ok, entry.getValue().size()));
            for (CaseResult c : entry.getValue()) {
                sb.append(String.format("  [%s] %s%s%n", c.passed() ? "通过" : "失败", c.displayName(),
                        c.passed() ? "" : "  -> " + c.error()));
            }
            total += entry.getValue().size();
            passed += ok;
        }
        sb.append(String.format("汇总: 业务 %d 个, 用例 %d 个, 通过 %d, 失败 %d%n",
                byBusiness.size(), total, passed, total - passed));
        sb.append("==========================================================");
        System.out.println(sb);
    }

    private record CaseResult(String displayName, boolean passed, String error) {
    }
}
