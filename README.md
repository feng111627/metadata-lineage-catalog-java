# metadata-lineage-catalog-java

本地可运行的数据目录与血缘服务（Spring Boot，内存存储，无需外部依赖）。
支持数据集与字段的版本化登记、相邻版本兼容性判定、敏感标记演进保护、
数据集级与**字段级**血缘登记、下游**影响分析**（数据集 / 字段两个粒度）、
并发修改控制与历史/快照一致性查询。

## 元数据模型

| 模型 | 字段 | 说明 |
| --- | --- | --- |
| `DatasetVersion` | datasetName, version, fields, createdAt | 数据集某一版本的**不可变快照**，写入后永不修改 |
| `FieldMeta` | name, type, semantic, sensitivityTags | 字段元数据：名称、类型、语义描述、敏感标记集合 |
| `FieldType` | INT / LONG / DOUBLE / STRING / BOOLEAN / DATE / TIMESTAMP | 字段数据类型 |
| `SensitivityTag` | PII / SECRET / FINANCIAL | 敏感属性标记，随版本保留可追溯 |
| `LineageEdge` | upstream, downstream | 数据集级血缘边 |
| `FieldRef` | dataset, field | 字段引用，定位“某个数据集里的某个字段” |
| `FieldLineageEdge` | upstreamDataset, upstreamField, downstreamDataset, downstreamField | 字段级血缘边 |
| `ImpactResult` | datasets[], fields[] | 影响分析结果：受波及的下游数据集与下游字段 |
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

## 血缘语义（数据集级 + 字段级）

### 登记与幂等

- 数据集级边：`upstream 数据集 -> downstream 数据集`；
  字段级边：`upstream 数据集.upstream 字段 -> downstream 数据集.downstream 字段`。
- 两类边都以集合存储，**同一条依赖重复提交多少次都只算一条**（按四元组完整相等判重），不产生重复记录。
- 字段级依赖的上下游必须落在**不同数据集**：同一数据集内的字段依赖（含同一字段自环）
  本身必然绕回本数据集，统一按成环拒绝。

### 成环检测（含跨粒度）

- 每条字段级边隐含其上下游**数据集之间**存在一条同向依赖。
- 环路检测在「显式数据集边 ∪ 字段边隐含数据集边」的并集图上进行，因此：
  - 字段与字段绕成环（`a.x -> b.y -> c.z -> a.x`）拒绝；
  - 字段边与数据集边**混在一起**绕成环（如已有 `ads -> ods`，再登记 `ods.x -> ads.r`；
    或反向先有字段边再登记反向数据集边）也拒绝。
- 所有成环（含数据集自环、间接环）当场以稳定错误码 `LINEAGE_CYCLE`（HTTP 422）拒绝，
  **绝不静默写入**；拒绝后图中查不到这条边。

### 影响分析口径

| 接口 | 入参 | `datasets` 包含什么 | `fields` 包含什么 |
| --- | --- | --- | --- |
| `GET /api/lineage/impact/{dataset}` | 数据集 | 并集图上的全部**传递下游数据集**（显式边和字段边隐含边都计入） | 这些下游数据集中，经由字段级边被波及的**下游字段**（字段边下游落点） |
| `GET /api/lineage/impact/{dataset}/fields/{field}` | 某字段 | 沿字段级边传递闭包到达的字段，其所属的下游数据集 | 沿字段级边可达的全部**下游字段** |

统一约定：

- **来源排除**：来源数据集不出现在 `datasets`，来源字段不出现在 `fields`；
- **去重**：菱形/多路径汇入同一数据集或同一字段只出现一次；
- **稳定有序**：`datasets` 按名称升序，`fields` 先按数据集名再按字段名升序；
  相同输入每次查出的内容和顺序完全一致，可复现；
- **并发一致**：图状态为不可变快照，写入在全局写锁内「拷贝-修改-整体替换」，
  读取无锁、只取某一时刻快照。登记与查询并发时，查询永远看到一张完整的图，
  不会出现只登记了一半的中间态。

## 对外错误类型（稳定、可区分）

| 错误码 | HTTP | 场景 |
| --- | --- | --- |
| `DATASET_NOT_FOUND` | 404 | 数据集不存在 |
| `VERSION_NOT_FOUND` | 404 | 版本不存在 |
| `VERSION_CONFLICT` | 409 | 并发修改 / 基线过期 / 重复登记 |
| `INCOMPATIBLE_SCHEMA` | 422 | 相邻版本 schema 不兼容 |
| `SENSITIVITY_LOST` | 422 | 敏感标记丢失或被替换 |
| `LINEAGE_CYCLE` | 422 | 血缘成环（数据集级、字段级、跨粒度混用一律此码） |
| `VALIDATION_ERROR` | 422 | 入参非法 / 必填项缺失（如字段名、数据集名为空） |
| `REQUEST_BODY_MISSING` | 400 | 要求 JSON 请求体但完全没有携带 |
| `MALFORMED_JSON` | 400 | 请求体不是合法 JSON（解析失败，不回显底层异常原文） |
| `INTERNAL_ERROR` | 500 | 兜底，底层异常不会原样外抛 |

## REST 接口

```
# 数据集版本
POST   /api/datasets                                   登记数据集初始版本
POST   /api/datasets/{name}/versions                   基于基线版本登记新版本
GET    /api/datasets/{name}/versions                   版本历史
GET    /api/datasets/{name}/versions/{v}               指定版本快照
GET    /api/datasets/{name}/compatibility/{from}/{to}  相邻版本兼容性判定

# 血缘（数据集级）
POST   /api/lineage/edges                              登记数据集级血缘边（幂等、拒环）
GET    /api/lineage/edges                              全部数据集级血缘边
GET    /api/lineage/upstream/{dataset}                 上游数据集追溯

# 血缘（字段级）
POST   /api/lineage/field-edges                        登记字段级血缘边（幂等、拒环）
GET    /api/lineage/field-edges                        全部字段级血缘边
GET    /api/lineage/impact/{dataset}                   数据集影响分析（datasets + fields）
GET    /api/lineage/impact/{dataset}/fields/{field}    字段影响分析（datasets + fields）
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
# 数据集级血缘
curl -X POST localhost:8080/api/lineage/edges -H 'Content-Type: application/json' -d '{
  "upstream": "orders", "downstream": "ads_report"
}'
curl localhost:8080/api/lineage/impact/orders

# 字段级血缘：orders.id -> dwd_order.order_id -> ads_report.oid
curl -X POST localhost:8080/api/lineage/field-edges -H 'Content-Type: application/json' -d '{
  "upstreamDataset": "orders", "upstreamField": "id",
  "downstreamDataset": "dwd_order", "downstreamField": "order_id"
}'
curl localhost:8080/api/lineage/impact/orders/fields/id
# {"datasets":["ads_report","dwd_order"],"fields":[{"dataset":"ads_report","field":"oid"},...]}

# 错误类型示例
curl -i -X POST localhost:8080/api/lineage/field-edges                       # 400 REQUEST_BODY_MISSING
curl -i -X POST localhost:8080/api/lineage/field-edges -d '{bad'             # 400 MALFORMED_JSON
```

## 测试覆盖

| 业务（测试类） | 用例数 | 覆盖点 |
| --- | --- | --- |
| `CompatibilityBoundaryTest` | 7 | 兼容边界：增删字段、类型加宽/收窄、语义变化、结论可复现 |
| `SensitivityEvolutionTest` | 4 | 敏感标记新增/丢失/替换、混合演进、历史可追溯 |
| `LineageGraphTest` | 6 | 数据集级：重复提交幂等、自环/间接环拒绝、下游影响、上游追溯、菱形依赖 |
| `ConcurrentModificationTest` | 4 | 同基线并发只一胜、过期基线拒绝、并发读写下历史一致、重复登记拒绝 |
| `FieldLineageTest` | 10 | 字段边幂等、字段环/同集自依赖/双向跨粒度环拒绝、字段影响传递、来源排除、菱形去重、结果可复现、数据集影响合并隐含边、必填校验 |
| `LineageConcurrencyTest` | 1 | 并发登记（含成环写被拒）与影响分析同时进行时每次查询都看到完整有序、无半图的快照 |
| `LineageApiErrorTest` | 7 | 请求体缺失 400 / JSON 非法 400 / 必填缺失 422 / 成环 422 / 成功 201 / 影响分析结构 |
| `ApplicationTests` | 1 | Spring 上下文加载 |
