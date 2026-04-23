# MerchandiseSearch 商品搜索模块

一个基于 **Elasticsearch 7.17** 的电商商品搜索 Demo，演示从 **索引设计 → 搜索 DSL → 排序策略 → 聚合筛选 → 搜索建议** 的完整链路。

代码以 **RestTemplate + 原生 ES DSL** 实现，所有 JSON 查询体与 Kibana Dev Tools 中的语法**完全一致**，便于对照学习。

---

## 目录结构

```
merchandiseSearch/
├── README.md
├── MerchandiseSearchProperties.java      配置属性（开关 / ES 地址 / 索引名 / 分词器）
├── MerchandiseSearchAutoConfig.java      条件装配（enabled=true 时生效）
├── common/
│   └── ApiResponse.java                  模块内统一响应体
├── domain/
│   ├── Product.java                      商品文档模型（对应 ES _source）
│   └── ProductAttr.java                  规格属性（nested 类型）
├── dto/
│   ├── SearchRequest.java                搜索请求（关键词/筛选/排序/分页）
│   ├── SearchResponse.java               搜索响应（商品+聚合+总数+耗时）
│   ├── FacetBucket.java                  聚合桶（左侧筛选栏的一项）
│   └── SuggestItem.java                  下拉提示项
├── service/
│   ├── EsRestClient.java                 ES HTTP 极简封装（RestTemplate）
│   ├── QueryDslBuilder.java              索引 mapping + 搜索/聚合/建议 DSL 构造
│   ├── IndexService.java                 索引生命周期（创建/删除/重建）
│   ├── ProductSearchService.java         搜索核心（单/批量写入、查询、解析）
│   └── DataSeeder.java                   启动时自动灌入 10 条样例商品
└── controller/
    └── MerchandiseSearchController.java  HTTP 接口
```

---

## 快速开始

### 1. 启动 Elasticsearch

项目根目录已有 `docker-compose.yml`，直接：

```bash
docker compose up -d elasticsearch
# 健康检查
curl http://localhost:9200
```

> 注意：该 compose 中的 ES 本是 SkyWalking 的后端存储，但数据互不干扰（索引名不同）。

### 2. 打开模块开关

编辑 `src/main/resources/application.yml`：

```yaml
demo:
  merchandise-search:
    enabled: true            # ← 打开
    es-base-url: http://localhost:9200
    index-name: products
    analyzer: standard
    search-analyzer: standard
    seed-on-startup: true    # 自动建索引 + 写样例数据
    number-of-shards: 3
    number-of-replicas: 0
```

### 3. 启动应用

```bash
./mvnw spring-boot:run
```

应用启动后会看到日志：

```
索引 [products] 创建成功
bulk 写入 10 条：errors=false
MerchandiseSearch: 样例商品写入完成，共 10 条
```

### 4. 试一试

```bash
# 综合搜索：搜 "iPhone"，按综合排序，返回左侧筛选栏聚合
curl -s -X POST http://localhost:8080/api/merchandise-search/search \
  -H 'Content-Type: application/json' \
  -d '{ "keyword":"iPhone", "page":1, "size":5, "withAggs":true }' | jq

# 筛选:苹果品牌 + 256GB 内存
curl -s -X POST http://localhost:8080/api/merchandise-search/search \
  -H 'Content-Type: application/json' \
  -d '{ "brands":["Apple"], "attrs":{"内存":["256GB"]} }' | jq

# 按销量排序
curl -s -X POST http://localhost:8080/api/merchandise-search/search \
  -H 'Content-Type: application/json' \
  -d '{ "sort":"sales", "size":5 }' | jq

# 搜索建议（下拉补全）
curl -s 'http://localhost:8080/api/merchandise-search/suggest?q=Apple&size=5' | jq
```

---

## 接口速览

| Method | 路径 | 说明 |
|--------|------|------|
| POST   | `/api/merchandise-search/search`          | 综合搜索（关键词 + 过滤 + 聚合 + 排序 + 高亮） |
| GET    | `/api/merchandise-search/suggest?q=&size=` | 搜索下拉建议（completion suggester）|
| POST   | `/api/merchandise-search/products`        | 单条商品 upsert |
| DELETE | `/api/merchandise-search/products/{id}`   | 删除单条商品 |
| POST   | `/api/merchandise-search/index`           | 创建索引（若不存在） |
| DELETE | `/api/merchandise-search/index`           | 删除索引 |
| POST   | `/api/merchandise-search/index/recreate`  | 删除后重建（**会丢数据**）|
| POST   | `/api/merchandise-search/index/seed`      | 写入样例数据 |

### SearchRequest 字段

```json
{
  "keyword":       "iPhone",               // 关键词（可空）
  "brands":        ["Apple","Huawei"],     // 品牌(OR)
  "categoryId":    "mobile",               // 品类
  "minPrice":      1000,                    // 价格区间
  "maxPrice":      10000,
  "attrs": {                                // 规格属性（AND 组间，OR 组内）
    "颜色": ["黑色","白色"],
    "内存": ["256GB"]
  },
  "sort":          "composite",             // composite / sales / rating / price_asc / price_desc / newest
  "page":          1,
  "size":          10,
  "withAggs":      true,                    // 是否返回左侧筛选栏聚合
  "onlyAvailable": true                     // 是否只返回在售 + 有库存
}
```

---

## 索引设计说明

`QueryDslBuilder#buildIndexDefinition()` 生成的 mapping 要点：

| 字段 | 类型 | 作用 |
|------|------|------|
| `title`        | text + `keyword` 子字段 | 分词检索、同时支持精确匹配/排序 |
| `subTitle`     | text | 副标题分词 |
| `brand / categoryId / tags / shopId` | keyword | 过滤、聚合 |
| `price / sales / sales30d / rating / stock` | 数值 | 范围查询、排序、function_score |
| `isOnSale`     | boolean | 上下架过滤 |
| `attrs`        | **nested** | 多维属性筛选（颜色+内存组合必须用 nested）|
| `suggest`      | **completion** | 下拉补全 |
| `createdAt`    | date | 新品排序 |

> 生产环境强烈建议把 `analyzer` 换成 `ik_max_word`（需在 ES 安装 [analysis-ik](https://github.com/infinilabs/analysis-ik) 插件），否则中文分词效果很差。

---

## 核心代码串讲

### 1. 构造搜索 DSL（`QueryDslBuilder`）

最终生成的 DSL 大致形如：

```json
{
  "from": 0, "size": 10,
  "query": {
    "function_score": {
      "query": {
        "bool": {
          "must":   [{ "multi_match": { "query":"iPhone",
                        "fields":["title^3","subTitle","brand^2","tags^1.5"] }}],
          "filter": [
            { "term":  { "isOnSale": true } },
            { "range": { "stock": { "gt": 0 } } },
            { "terms": { "brand": ["Apple"] } },
            { "nested": { "path":"attrs",
                "query":{ "bool":{ "must":[
                  { "term":  { "attrs.name":"内存" } },
                  { "terms": { "attrs.value":["256GB"] } }]}}}}
          ]
        }
      },
      "functions": [
        { "field_value_factor": { "field":"sales30d", "modifier":"log1p", "factor":0.1 }},
        { "field_value_factor": { "field":"rating",   "modifier":"log1p", "factor":0.3 }}
      ],
      "score_mode":"sum", "boost_mode":"sum"
    }
  },
  "aggs":      { "brands": ..., "price_ranges": ..., "attrs": ... },
  "highlight": { "fields": { "title":{}, "subTitle":{} } }
}
```

可以打开 `logging.level.com.example.demo.esDemo.merchandiseSearch=DEBUG`
看到实际发送的 JSON，直接贴到 Kibana Dev Tools 里调试。

### 2. 排序策略对照表

| `sort` 值 | 实现方式 |
|-----------|---------|
| `composite`（默认） | `function_score`：相关度 + log(销量) + log(评分) |
| `sales`             | `sales30d` 降序 + `_score` 兜底 |
| `rating`            | `rating` 降序 |
| `price_asc` / `price_desc` | 按 `price` 升/降 |
| `newest`            | `createdAt` 降序 |

### 3. 左侧筛选栏聚合

一次搜索同时返回:

- **品牌**：`terms` 聚合 on `brand`
- **价格区间**：`range` 聚合（<1000 / 1000-3000 / 3000-5000 / 5000+）
- **属性**：`nested` → `attrs.name` → `attrs.value`

返回后在 `ProductSearchService` 中扁平化为 `attr_颜色`、`attr_内存` 这种 facet，前端可直接渲染。

### 4. Completion Suggest

写入时把 `title + brand` 作为补全候选存入 `suggest` 字段；
查询时用 prefix 做前缀匹配，毫秒级返回下拉候选词。

---

## 数据流

```
HTTP 请求 → MerchandiseSearchController
          → ProductSearchService  （封装写入/查询/解析）
          → QueryDslBuilder        （构造 mapping / DSL）
          → EsRestClient           （RestTemplate 直连 ES）
          → Elasticsearch (http://localhost:9200)
```

启动流程：

```
Spring 容器启动
  └─ DataSeeder.@PostConstruct
       ├─ IndexService.createIfAbsent()       若索引不存在则建
       └─ ProductSearchService.bulkIndex(10)  灌入样例商品
```

---

## 与真实电商搜索的对应关系

本 Demo 覆盖了"商品搜索"最核心的 6 件事：

1. ✅ **倒排索引与分词**（title text 字段 + analyzer）
2. ✅ **多字段加权**（`title^3, brand^2, tags^1.5`）
3. ✅ **过滤 vs 评分**（`filter` 走缓存，`must` 算分）
4. ✅ **综合排序**（function_score 融合销量 / 评分）
5. ✅ **左侧筛选栏**（terms / range / nested 聚合）
6. ✅ **搜索建议**（completion suggester）

生产级电商搜索还会加上，**本 Demo 故意未实现**，可按需扩展：

- Query 理解：拼音、同义词、纠错、意图识别
- 多路召回：向量召回(dense_vector + kNN)、协同过滤召回
- LTR 精排（learning-to-rank 插件）
- 个性化重排（基于用户画像）
- 冷热索引分层 + 别名切换
- Canal 订阅 binlog 实现 MySQL → ES 同步

---

## 常见问题

**Q: 启动时报 "ES 调用失败"？**
A: 先确认 `docker compose up -d elasticsearch` 起来了，再访问 `curl http://localhost:9200`。若 ES 未启动，只会打 WARN，不影响应用启动。

**Q: 为什么关闭开关后项目还能启动？**
A: 模块内所有 Bean 都带了 `@ConditionalOnProperty(... enabled=true)`，关闭时整个模块沉默，不依赖 ES。

**Q: 中文搜索效果差？**
A: 默认用 `standard` 分词器是为了开箱即用。生产请安装 IK 分词器，并把配置改为：

```yaml
analyzer: ik_max_word
search-analyzer: ik_smart
```

改完配置后调用 `POST /api/merchandise-search/index/recreate` 重建索引。

**Q: 深度分页 `page` 很大时变慢？**
A: `from/size` 分页在 `from` 大于几千后开销显著。本 Demo 数据量小没问题，生产需改用 `search_after`。

**Q: 数据怎么和 MySQL 保持一致?**
A: 本 Demo 是演示纯 ES 搜索能力，未接 MySQL。生产推荐 **Canal 监听 MySQL binlog → Kafka → 消费者批量写 ES**。

---

## 扩展方向

想动手扩展，推荐以下练习：

1. 把 `analyzer` 改成 IK，对比中文分词效果
2. 增加 `search_after` 的深度分页接口
3. 增加 `dense_vector` 字段 + kNN 查询，做语义召回
4. 用 `collapse` 对 `spu_id` 做折叠去重
5. 加 Redis 缓存热门搜索词的结果
