# metadata-lineage-catalog-java

本地可运行的数据目录与血缘服务（Spring Boot，内存存储，无需外部依赖）。
支持数据集与字段的版本化登记、相邻版本兼容性判定、敏感标记演进保护、
血缘登记与影响分析、并发修改控制与历史一致性查询。

## 元数据模型

| 模型 | 字段 | 说明 |
| --- | --- | --- |
| `DatasetVersion` | datasetName, version, fields, createdAt | 数据集某一版本的**不可变快照**，写入后永不修改 |
| `FieldMeta` | name, type, semantic, sensitivityTags | 字段元数据：名称、类型、语义描述、敏感标记集合 |
| `FieldType` | INT / LONG / DOUBLE / STRING / BOOLEAN / DATE / TIMESTAMP | 字段数据类型 |
| `SensitivityTag` | PII / SECRET / FINANCIAL | 敏感属性标记，随版本保留可追溯 |
| `LineageEdge` | upstream, downstream | 数据集级血缘边 |
| `CompatibilityReport` | compatible, issues[] | 兼容性结论；issue 定位到字段并带可区分原因类型 |

## 兼容性判定规则（相邻版本）

判定是纯函数：只依赖两个版本快照内容，结论稳定可复现（问题列表按字段名与类型排序）。

| 变更 | 结论 | 原因类型 |
| --- | --- | --- |
| 新增字段 | 兼容 | — |
| 删除字段 | 不兼容 | `FIELD_REMOVED` |
| 类型加宽（INT→LONG→DOUBLE、DATE→TIMESTAMP、任意→STRING） | 兼容 | — |
| 其他类型变更（含收窄） | 不兼容 | `TYPE_CHANGED_INCOMPATIBLE` |
| 语义描述变化 | 不兼容 | `SEMANTIC_CHANGED` |
| 新增敏感标记 | 兼容 | — |
| 丢失已有敏感标记 | 不兼容 | `SENSITIVITY_REMOVED` |
| 替换敏感标记（如 PII 改 SECRET） | 不兼容 | `SENSITIVITY_CHANGED` |

不兼容时错误信息定位到具体字段并携带上述原因类型；
涉及敏感标记的问题统一以错误码 `SENSITIVITY_LOST` 抛出，与 `INCOMPATIBLE_SCHEMA` 可区分。

## 版本与并发语义

- 每个数据集维护单调递增的版本链（1, 2, 3, ...），历史版本不可变。
- 登记新版本必须声明 `expectedBaseVersion`（自己基于哪个版本修改）：
  基线过期即抛 `VERSION_CONFLICT`，**不会静默覆盖**他人提交。
- 并发控制 = per-dataset 锁 + 基线版本校验，同一基线并发提交只有一个成功。
- 任何时刻查询同一历史版本，返回的都是同一不可变快照，结论一致。

## 血缘语义

- 边以集合存储，**重复提交同一条依赖幂等**，不产生重复结果。
- 写入前做环路检测（含自环与间接环），成环依赖以 `LINEAGE_CYCLE` **明确拒绝**，不静默写入。
- 影响分析：`downstreamImpact(ds)` 返回全部传递下游（排序，结果稳定）；
  `upstreamLineage(ds)` 返回全部传递上游。

## 对外错误类型（稳定、可区分）

| 错误码 | HTTP | 场景 |
| --- | --- | --- |
| `DATASET_NOT_FOUND` | 404 | 数据集不存在 |
| `VERSION_NOT_FOUND` | 404 | 版本不存在 |
| `VERSION_CONFLICT` | 409 | 并发修改 / 基线过期 / 重复登记 |
| `INCOMPATIBLE_SCHEMA` | 422 | 相邻版本 schema 不兼容 |
| `SENSITIVITY_LOST` | 422 | 敏感标记丢失或被替换 |
| `LINEAGE_CYCLE` | 422 | 血缘成环 |
| `VALIDATION_ERROR` | 422 | 入参非法 |
| `INTERNAL_ERROR` | 500 | 兜底，底层异常不会原样外抛 |

## REST 接口

```
POST   /api/datasets                                   登记数据集初始版本
POST   /api/datasets/{name}/versions                   基于基线版本登记新版本
GET    /api/datasets/{name}/versions                   版本历史
GET    /api/datasets/{name}/versions/{v}               指定版本快照
GET    /api/datasets/{name}/compatibility/{from}/{to}  相邻版本兼容性判定
POST   /api/lineage/edges                              登记血缘边（幂等、拒环）
GET    /api/lineage/edges                              全部血缘边
GET    /api/lineage/impact/{dataset}                   下游影响分析
GET    /api/lineage/upstream/{dataset}                 上游血缘追溯
```

## 本地验证方法

```bash
# 运行全部测试（日志末尾打印业务覆盖报告：业务 -> 用例 -> 通过/失败）
mvn test

# 本地启动服务（application.properties 中 server.port=0 表示随机端口，可改为固定端口）
mvn spring-boot:run

# 示例调用
curl -X POST localhost:8080/api/datasets -H 'Content-Type: application/json' -d '{
  "name": "orders",
  "fields": [{"name":"id","type":"LONG","semantic":"订单ID","sensitivityTags":[]}]
}'
curl -X POST localhost:8080/api/lineage/edges -H 'Content-Type: application/json' -d '{
  "upstream": "orders", "downstream": "ads_report"
}'
curl localhost:8080/api/lineage/impact/orders
```

## 测试覆盖

| 业务（测试类） | 用例数 | 覆盖点 |
| --- | --- | --- |
| `CompatibilityBoundaryTest` | 7 | 兼容边界：增删字段、类型加宽/收窄、语义变化、结论可复现 |
| `SensitivityEvolutionTest` | 4 | 敏感标记新增/丢失/替换、混合演进、历史可追溯 |
| `LineageGraphTest` | 6 | 重复提交幂等、自环/间接环拒绝、下游影响、上游追溯、菱形依赖 |
| `ConcurrentModificationTest` | 4 | 同基线并发只一胜、过期基线拒绝、并发读写下历史一致、重复登记拒绝 |
| `ApplicationTests` | 1 | Spring 上下文加载 |
