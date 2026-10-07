# metadata-lineage-catalog-java

本地可运行的数据目录与血缘服务（Spring Boot，内存存储，无需外部依赖）。
支持数据集与字段的版本化登记、相邻版本兼容性判定、敏感标记演进保护、
**数据集级 + 字段级血缘登记与影响分析**、跨粒度成环拒绝、并发修改控制与历史一致性查询。

## 元数据模型

| 模型 | 字段 | 说明 |
| --- | --- | --- |
| `DatasetVersion` | datasetName, version, fields, createdAt | 数据集某一版本的**不可变快照**，写入后永不修改 |
| `FieldMeta` | name, type, semantic, sensitivityTags | 字段元数据：名称、类型、语义描述、敏感标记集合 |
| `FieldType` | INT / LONG / DOUBLE / STRING / BOOLEAN / DATE / TIMESTAMP | 字段数据类型 |
| `SensitivityTag` | PII / SECRET / FINANCIAL | 敏感属性标记，随版本保留可追溯 |
| `LineageEdge` | upstream, downstream | 数据集级血缘边 |
| `FieldRef` | dataset, field | 字段坐标，定位“某数据集里的某一个字段” |
| `FieldLineageEdge` | upstream(FieldRef), downstream(FieldRef) | 字段级血缘边：哪个字段依赖哪个字段 |
| `ImpactResult` | datasets[], fields[] | 影响分析结果：被波及的下游数据集 + 被波及的下游字段 |
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

血缘支持两种粒度，登记与成环判定都在**同一张统一依赖图**上进行：

- 数据集级边：`数据集 A -> 数据集 B`，表示 A 整体被 B 依赖。
- 字段级边：`数据集.字段 -> 数据集.字段`，精确表达“B 的哪个字段依赖 A 的哪个字段”。

### 登记规则

- 边以集合存储：同一条依赖（数据集对相同，或字段坐标对相同）**重复提交任意次只算一条**，不产生重复记录，接口幂等。
- 写入前在统一图上做环路检测，以下情况一律以 `LINEAGE_CYCLE`（HTTP 422）**当场拒绝、不静默写入**：
  - 数据集与数据集直接/间接绕成环（含自环）；
  - 字段与字段直接/间接绕成环（含字段自依赖）；
  - **跨粒度混合环**：字段边与已有数据集级边组合绕成环。
    例如已有数据集边 `A -> B`，再登记字段边 `B.f -> A.g` 会闭合混合环，拒绝；
    先登记字段边 `B.f -> A.g` 再补数据集边 `A -> B` 同样拒绝。
- 同一数据集内部的字段加工链（如 `B.raw -> B.clean`）是合法的，
  即使该数据集本身参与数据集级血缘，也不会被误判为环。

### 影响分析口径

两个入口：
- `GET /api/lineage/impact/{dataset}`：数据集整体变更，视为“整体波及”起点。
- `GET /api/lineage/impact/field/{dataset}/{field}`：某个具体字段变更。

返回 `ImpactResult { datasets: [...], fields: [...] }`，传播口径：

1. 字段边只沿字段边传播：命中下游具体字段；命中某字段时，其所属数据集也算被波及。
2. 数据集边做整体传播：被**整体波及**的数据集（经数据集边到达，或数据集变更入口本身），
   其全部字段边的源字段都视为参与变更，继续沿字段边向下游传播。
3. 仅被**个别字段触达**的数据集，只沿它的数据集边整体波及下游，
   **不会株连该数据集内其他不相干字段**（如只改 `a.id`，不会把 `a.name` 的下游算进来）。
4. 结果稳定性保证：
   - 数据集名按字典序、字段按 `(dataset, field)` 字典序排列，相同输入每次内容与顺序完全一致；
   - 结果去重（菱形/多路径到达只出现一次）；
   - **来源数据集自身及其全部字段不出现在结果中**。
5. 并发一致性：登记（写锁）与查询（读锁）互斥/共享由同一把读写锁保护，
   任何一次影响分析看到的都是某一时刻完整的图，不会读到“登记了一半”的撕裂结果。

- `upstreamLineage(ds)` 仍返回某数据集的全部传递上游（数据集级，排序稳定）。

## 对外错误类型（稳定、可区分）

| 错误码 | HTTP | 场景 |
| --- | --- | --- |
| `DATASET_NOT_FOUND` | 404 | 数据集不存在 |
| `VERSION_NOT_FOUND` | 404 | 版本不存在 |
| `VERSION_CONFLICT` | 409 | 并发修改 / 基线过期 / 重复登记 |
| `INCOMPATIBLE_SCHEMA` | 422 | 相邻版本 schema 不兼容 |
| `SENSITIVITY_LOST` | 422 | 敏感标记丢失或被替换 |
| `LINEAGE_CYCLE` | 422 | 血缘成环（字段环、数据集环、跨粒度混合环） |
| `VALIDATION_ERROR` | 422 | 入参非法 / 必填项缺失或为空 / 参数类型不符 |
| `REQUEST_BODY_MISSING` | 400 | 请求体没带（body 缺失） |
| `MALFORMED_JSON` | 400 | 请求体不是合法 JSON（语法错误） |
| `INTERNAL_ERROR` | 500 | 兜底，底层异常不会原样外抛 |

## REST 接口

```
POST   /api/datasets                                   登记数据集初始版本
POST   /api/datasets/{name}/versions                   基于基线版本登记新版本
GET    /api/datasets/{name}/versions                   版本历史
GET    /api/datasets/{name}/versions/{v}               指定版本快照
GET    /api/datasets/{name}/compatibility/{from}/{to}  相邻版本兼容性判定
POST   /api/lineage/edges                              登记数据集级血缘边（幂等、拒环）
GET    /api/lineage/edges                              全部数据集级血缘边
POST   /api/lineage/field-edges                        登记字段级血缘边（幂等、拒环，含跨粒度）
GET    /api/lineage/field-edges                        全部字段级血缘边
GET    /api/lineage/impact/{dataset}                   数据集整体变更影响（datasets + fields）
GET    /api/lineage/impact/field/{dataset}/{field}     具体字段变更影响（datasets + fields）
GET    /api/lineage/upstream/{dataset}                 上游血缘追溯
```

### 字段级血缘请求/响应示例

```bash
# 登记：dwd_order.oid 依赖 ods_order.order_id
curl -X POST localhost:8080/api/lineage/field-edges -H 'Content-Type: application/json' -d '{
  "upstream":   {"dataset": "ods_order", "field": "order_id"},
  "downstream": {"dataset": "dwd_order", "field": "oid"}
}'
# 201 -> {"upstream":{"dataset":"ods_order","field":"order_id"},
#         "downstream":{"dataset":"dwd_order","field":"oid"}}

# 影响分析：查 ods_order.order_id 变更会波及谁
curl localhost:8080/api/lineage/impact/field/ods_order/order_id
# 200 -> {"datasets":["dwd_order"],
#         "fields":[{"dataset":"dwd_order","field":"oid"}]}

# 制造跨粒度混合环会被拒绝（已有数据集边 ods_order -> dwd_order 时）
curl -X POST localhost:8080/api/lineage/field-edges -H 'Content-Type: application/json' -d '{
  "upstream":   {"dataset": "dwd_order", "field": "x"},
  "downstream": {"dataset": "ods_order", "field": "y"}
}'
# 422 -> {"code":"LINEAGE_CYCLE","message":"字段依赖 dwd_order.x -> ods_order.y 会形成环路……"}
```

### 请求错误的区分

| 请求形态 | HTTP | code |
| --- | --- | --- |
| 不带 body | 400 | `REQUEST_BODY_MISSING` |
| body 是坏 JSON | 400 | `MALFORMED_JSON` |
| JSON 合法但缺必填项 / 值为空白 | 422 | `VALIDATION_ERROR` |

错误响应统一为 `{"code": ..., "message": ...}`，消息为服务端可控文案，不回传底层异常原文。

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

# 字段级血缘 + 字段影响分析
curl -X POST localhost:8080/api/lineage/field-edges -H 'Content-Type: application/json' -d '{
  "upstream":   {"dataset": "orders", "field": "id"},
  "downstream": {"dataset": "ads_report", "field": "order_id"}
}'
curl localhost:8080/api/lineage/impact/field/orders/id
```

## 测试覆盖

| 业务（测试类） | 用例数 | 覆盖点 |
| --- | --- | --- |
| `CompatibilityBoundaryTest` | 7 | 兼容边界：增删字段、类型加宽/收窄、语义变化、结论可复现 |
| `SensitivityEvolutionTest` | 4 | 敏感标记新增/丢失/替换、混合演进、历史可追溯 |
| `LineageGraphTest` | 6 | 重复提交幂等、自环/间接环拒绝、下游影响、上游追溯、菱形依赖 |
| `FieldLineageGraphTest` | 13 | 字段边重复提交幂等、字段自环/间接环、跨粒度混合环（两种闭合方向）、同数据集字段链不误判、影响分析稳定排序/来源排除/去重、字段触达沿数据集边传播 |
| `LineageConcurrencyTest` | 3 | 并发登记+并发查询只见完整图（有序自洽、来源排除）、并发重复登记坍缩为一条、并发制造混合环全部被拒 |
| `LineageApiErrorTest` | 8 | 缺体 400 REQUEST_BODY_MISSING、坏 JSON 400 MALFORMED_JSON、必填缺失 422 VALIDATION_ERROR、三类可区分、成环 422 LINEAGE_CYCLE、正常路径 201/200 |
| `ConcurrentModificationTest` | 4 | 同基线并发只一胜、过期基线拒绝、并发读写下历史一致、重复登记拒绝 |
| `ApplicationTests` | 1 | Spring 上下文加载 |
