# 数据目录与血缘服务（Metadata & Lineage Catalog）

一个可在本地直接运行的 Spring Boot 服务，用内存数据模拟数据集、字段和上下游依赖，
提供**版本化登记、相邻版本兼容性判定、敏感标记演进追溯、血缘登记与影响分析、
并发修改保护**能力。所有结论稳定、可复现、可解释，对外只暴露固定错误类型。

## 1. 快速开始

要求 JDK 21+（在 JDK 25 上验证通过）与 Maven 3.9+。

```bash
# 运行全部测试（控制台打印“业务模块 -> 用例 -> PASS/FAIL”覆盖报告）
mvn test

# 启动服务（默认 8080 端口，启动时自动写入模拟数据）
mvn spring-boot:run
# 或
mvn package -DskipTests && java -jar target/metadata-lineage-catalog-java-1.0-SNAPSHOT.jar
```

启动后可直接查询内置样例：

```bash
curl http://localhost:8080/api/datasets
curl http://localhost:8080/api/datasets/ods_user/versions
curl http://localhost:8080/api/lineage/edges
curl http://localhost:8080/api/lineage/impact/ods_user
```

模拟数据：`ods_user`（含 v1、v2 两个版本，字段含 PII 标记）、`dwd_user_profile`、
`ads_user_stats`，以及 `ods_user.user_id -> dwd_user_profile.user_id` 字段级血缘、
`dwd_user_profile -> ads_user_stats` 数据集级血缘；启动时还会重复提交一次同一条边以演示幂等。

## 2. 元数据模型

| 类型 | 说明 |
| --- | --- |
| `FieldSchema` | 字段不可变模式：`name`、`type`（STRING/INTEGER/BIGINT/DECIMAL/BOOLEAN/DATE/TIMESTAMP）、`nullable`、`semantic`（业务语义标识，如 `user_id`）、`sensitivity`（NONE/INTERNAL/CONFIDENTIAL/PII）、`description` |
| `DatasetVersion` | 数据集的一个不可变版本：数据集名、从 1 起单调递增的版本号、完整字段列表、创建时间/人、变更说明、`basedOn`（提交基线） |
| `LineageEdge` | 血缘边“上游 -> 下游”。两端字段都为空=数据集级；两端字段都非空=字段级。**身份只由四元组决定**，`createdAt/createdBy` 不参与等值 |
| `CompatibilityResult` | 相邻版本兼容结论：`compatible`、起止版本号、按字段名稳定排序的 `FieldChange` 列表 |
| `FieldChange` | 单字段差异：`reason`（稳定枚举）、`fieldName`、`detail`（旧值 -> 新值）、`blocking` |
| `ImpactReport` | 影响分析：变更源、按跳数与名称稳定排序的受影响下游节点（含 `via` 直接上游）、涉及去重边数 |

设计要点：

- **版本不可变、历史只追加**。`DatasetVersion` 是 record，写入后不可修改；
  字段列表在存储时做防御性拷贝。任何时刻按版本号读到的内容都一致。
- 全部数据保存在 `CatalogStore`（内存）：每个数据集一把锁，版本提交是
  “校验基线 -> 分配新版本号 -> 追加”的原子操作；血缘边存于并发去重集合。

## 3. 版本与兼容性语义

### 3.1 乐观并发控制

每次登记必须带 `expectedVersion`（首次登记为 `0`），表示“我基于哪个版本修改”：

- 与服务端当前最新版本一致 -> 原子产生新版本号并追加；
- 不一致 -> 返回 `409 VERSION_CONFLICT`，**绝不静默覆盖**；
  调用方拉取最新版本、合并后以新版本号为基线重试即可。

### 3.2 兼容性判定规则（相邻版本，按字段名配对）

| 差异原因 `reason` | 触发条件 | 是否阻断（不兼容） |
| --- | --- | --- |
| `FIELD_ADDED` | 新版本出现新字段（首次登记的所有字段同此原因） | 否 |
| `FIELD_REMOVED` | 字段被删除 | **是** |
| `TYPE_CHANGED` | 字段逻辑类型改变 | **是** |
| `NULLABILITY_TIGHTENED` | 可空收窄为非空 | **是** |
| `SEMANTIC_CHANGED` | 业务语义标识改变（同类型但含义变了） | **是** |
| `SENSITIVITY_DOWNGRADED` | 敏感级别下调或标记被移除 | **是** |
| `SENSITIVITY_UPGRADED` | 敏感级别上调 | 否（提示性变更） |

补充：非空放宽为可空、仅修改描述文本不产生差异。
判定是两个版本内容的纯函数：**同样输入永远得到同样结论**；差异按字段名排序，
每个不兼容项都能定位到具体字段并给出可区分的原因。

**重要语义**：即使新版本不兼容，登记依然成功（产生新版本），响应同时携带兼容性结论，
由调用方决定是否发布；兼容性也可通过预演接口在不写版本的情况下提前检查。
这样历史不会被阻断性变更“吞掉”，且破坏性变更有明确、不可忽视的记录。

### 3.3 敏感标记演进

敏感级别随每个版本完整保留，可追溯任意历史版本：

- 移除/下调已有标记（PII→NONE 等）= `SENSITIVITY_DOWNGRADED` 阻断项，
  防止敏感标记“悄悄失效”；
- 上调（NONE→PII 等）= `SENSITIVITY_UPGRADED`，兼容但在差异清单中明确提示。

## 4. 血缘与影响分析语义

- **粒度**：数据集级边两端字段都为空；字段级边两端字段都非空，且字段必须存在于
  对应数据集的最新版本，否则返回 `LINEAGE_ENDPOINT_NOT_FOUND`；粒度混用返回
  `INVALID_REQUEST`。
- **幂等**：四元组相同的边重复提交返回 HTTP 200（首次为 201），底层集合只保留一条。
- **成环拒绝**：登记前在“血缘有向边 + 数据集↔字段双向成员关系”的统一图上
  从下游向上游做可达性检查；若新边会形成环（含自依赖、间接环、跨粒度环），
  返回 `409 LINEAGE_CYCLE`，边不写入。
- **影响分析**：从变更源出发 BFS。成员关系（同数据集的数据集节点与字段节点）
  **不占跳数**；跨数据集只沿登记边方向传播，跳数即经过的血缘边数。
  源数据集自身节点不出现在结果中，结果按跳数、名称稳定排序，`via` 给出直接上游。

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/datasets` | 数据集名列表 |
| POST | `/api/datasets/{name}/versions` | 登记新版本，返回 `{version, compatibility}` |
| POST | `/api/datasets/{name}/compatibility-checks` | 预演兼容性，不写版本 |
| GET | `/api/datasets/{name}/versions` | 全部历史版本 |
| GET | `/api/datasets/{name}/versions/latest` | 最新版本 |
| GET | `/api/datasets/{name}/versions/{v}` | 指定历史版本 |
| POST | `/api/lineage/edges` | 登记血缘边（201 新增 / 200 已存在） |
| GET | `/api/lineage/edges` | 全部血缘边 |
| GET | `/api/lineage/impact/{dataset}?field={f}` | 影响分析 |

登记请求示例：

```json
{
  "expectedVersion": 0,
  "changeNote": "初始登记",
  "createdBy": "alice",
  "fields": [
    {"name": "id", "type": "BIGINT", "nullable": false,
     "semantic": "user_id", "sensitivity": "PII", "description": "用户ID"}
  ]
}
```

错误响应统一为 `{"code": "...", "message": "..."}`，不向外部泄漏堆栈或底层异常：

| code | HTTP | 含义 |
| --- | --- | --- |
| `DATASET_NOT_FOUND` | 404 | 数据集不存在 |
| `VERSION_NOT_FOUND` | 404 | 指定历史版本不存在 |
| `VERSION_CONFLICT` | 409 | 乐观锁冲突（基线版本过期） |
| `DUPLICATE_FIELD` | 400 | 同一版本内字段名重复 |
| `INVALID_REQUEST` | 400 | 请求体缺失/格式错误/粒度不一致等 |
| `LINEAGE_CYCLE` | 409 | 血缘边会成环 |
| `LINEAGE_ENDPOINT_NOT_FOUND` | 400 | 血缘端点数据集或字段不存在 |
| `INTERNAL_ERROR` | 500 | 未预期内部错误（细节仅记日志） |

## 6. 测试覆盖与日志

`mvn test` 通过自定义 JUnit Platform `TestExecutionListener`（见
`tests/support/BusinessLoggingListener` 与 `@BusinessCase`）在控制台输出分组报告，
形如：

```
▌业务模块：版本兼容性边界
  [PASS]  新增字段判定为兼容
  [PASS]  删除字段判定为不兼容并定位到 FIELD_REMOVED
...
==================== 合计：通过 42，失败 0 ====================
```

覆盖 6 个业务分组（共 40+ 用例）：

- **版本兼容性边界**：首版、新增/删除字段、改类型、可空收窄/放宽、语义改变、
  多破坏性差异、结论可复现；
- **敏感标记演进**：跨版本保留、丢标记/降级阻断、升级提示、预演不写版本、
  降级与删字段并存；
- **血缘登记与影响分析**：重复提交幂等、自依赖/间接/字段级/跨粒度成环拒绝、
  下游 BFS 排序、跨粒度影响、端点校验；
- **并发修改与历史一致性**：16 线程同基线竞争仅一方成功、冲突重试串行推进、
  并发读历史一致性、首次登记竞争、稳定错误类型、重复字段；
- **HTTP 接口契约**：端到端状态码与错误码（409/404/400）、错误体不泄漏底层异常；
- 应用上下文加载冒烟。

## 7. 代码结构

```
domain/     领域模型（不可变记录、枚举）
store/      内存存储：版本原子提交（乐观锁）、血缘去重集合
engine/     兼容性判定引擎（纯函数，稳定可复现）
service/    CatalogService（登记/历史/预演）、LineageService（幂等/成环/影响）
web/        REST 控制器与全局异常映射
bootstrap/  启动样例数据
tests/      分业务测试 + 业务覆盖日志监听器
```
