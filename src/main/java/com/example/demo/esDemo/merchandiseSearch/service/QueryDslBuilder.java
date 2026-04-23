package com.example.demo.esDemo.merchandiseSearch.service;

import com.example.demo.esDemo.merchandiseSearch.MerchandiseSearchProperties;
import com.example.demo.esDemo.merchandiseSearch.dto.SearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造索引 mapping 与搜索 DSL。
 *
 * <p>DSL 全部以 {@code Map} 形式拼装，Jackson 序列化后等价于 Kibana 里的 JSON。
 * 方便调试：可以 log 出来贴到 Kibana Dev Tools 里直接执行。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "demo.merchandise-search", name = "enabled", havingValue = "true")
public class QueryDslBuilder {

    private final MerchandiseSearchProperties properties;

    // ==================================================================
    //  一、索引 settings + mappings
    // ==================================================================

    public Map<String, Object> buildIndexDefinition() {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("settings", Map.of(
                "number_of_shards", properties.getNumberOfShards(),
                "number_of_replicas", properties.getNumberOfReplicas(),
                "refresh_interval", "1s"
        ));
        def.put("mappings", Map.of("properties", buildProperties()));
        return def;
    }

    private Map<String, Object> buildProperties() {
        Map<String, Object> props = new LinkedHashMap<>();

        props.put("productId", keyword());

        // 标题：分词检索 + keyword 子字段（排序/聚合）
        Map<String, Object> titleField = new LinkedHashMap<>();
        titleField.put("type", "text");
        titleField.put("analyzer", properties.getAnalyzer());
        titleField.put("search_analyzer", properties.getSearchAnalyzer());
        titleField.put("fields", Map.of(
                "keyword", Map.of("type", "keyword", "ignore_above", 256)
        ));
        props.put("title", titleField);

        props.put("subTitle", Map.of("type", "text", "analyzer", properties.getAnalyzer()));

        props.put("brand",        keyword());
        props.put("categoryId",   keyword());
        props.put("categoryPath", keyword());
        props.put("shopId",       keyword());
        props.put("tags",         keyword());

        props.put("price",     Map.of("type", "double"));
        props.put("sales",     Map.of("type", "integer"));
        props.put("sales30d",  Map.of("type", "integer"));
        props.put("rating",    Map.of("type", "float"));
        props.put("stock",     Map.of("type", "integer"));
        props.put("isOnSale",  Map.of("type", "boolean"));
        props.put("createdAt", Map.of("type", "date"));

        // 规格属性（颜色/内存/尺码 …）→ nested
        Map<String, Object> attrProps = new LinkedHashMap<>();
        attrProps.put("name",  keyword());
        attrProps.put("value", keyword());
        props.put("attrs", Map.of("type", "nested", "properties", attrProps));

        // 下拉补全
        props.put("suggest", Map.of(
                "type", "completion",
                "analyzer", properties.getAnalyzer()
        ));

        return props;
    }

    private Map<String, Object> keyword() {
        return Map.of("type", "keyword");
    }

    // ==================================================================
    //  二、搜索 DSL：query + filter + sort + function_score + aggs + highlight
    // ==================================================================

    public Map<String, Object> buildSearchDsl(SearchRequest req) {
        Map<String, Object> dsl = new LinkedHashMap<>();

        int page = req.getPage() == null || req.getPage() < 1 ? 1 : req.getPage();
        int size = req.getSize() == null || req.getSize() < 1 ? 10 : Math.min(req.getSize(), 100);
        dsl.put("from", (page - 1) * size);
        dsl.put("size", size);

        dsl.put("query", buildQuery(req));

        // 排序：非综合排序时不再需要 function_score
        Object sortNode = buildSort(req.getSort());
        if (sortNode != null) {
            dsl.put("sort", sortNode);
        }

        // 聚合（左侧筛选栏）
        if (Boolean.TRUE.equals(req.getWithAggs())) {
            dsl.put("aggs", buildAggs());
        }

        // 高亮
        dsl.put("highlight", Map.of(
                "pre_tags",  List.of("<em class='hl'>"),
                "post_tags", List.of("</em>"),
                "fields",    Map.of(
                        "title",    Map.of(),
                        "subTitle", Map.of()
                )
        ));

        // 只返回常用字段（减少 _source 传输开销）
        dsl.put("_source", List.of(
                "productId", "title", "subTitle", "brand", "categoryId",
                "price", "sales", "rating", "stock", "isOnSale",
                "shopId", "tags", "attrs", "createdAt"
        ));

        return dsl;
    }

    /** 构造 query 部分：关键词走 function_score，无关键词时直接 bool。 */
    private Map<String, Object> buildQuery(SearchRequest req) {
        Map<String, Object> bool = buildBool(req);

        boolean hasKeyword = StringUtils.hasText(req.getKeyword());
        boolean composite = req.getSort() == null || "composite".equalsIgnoreCase(req.getSort());

        // 综合排序 + 有关键词 → function_score 融合销量/评分
        if (hasKeyword && composite) {
            return Map.of("function_score", Map.of(
                    "query", Map.of("bool", bool),
                    "functions", List.of(
                            Map.of("field_value_factor", Map.of(
                                    "field", "sales30d",
                                    "modifier", "log1p",
                                    "factor", 0.1,
                                    "missing", 0
                            )),
                            Map.of("field_value_factor", Map.of(
                                    "field", "rating",
                                    "modifier", "log1p",
                                    "factor", 0.3,
                                    "missing", 3
                            ))
                    ),
                    "score_mode", "sum",
                    "boost_mode", "sum"
            ));
        }

        return Map.of("bool", bool);
    }

    /** 构造 bool: must(分词相关度) + filter(品牌/价格/属性/在售)。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildBool(SearchRequest req) {
        Map<String, Object> bool = new LinkedHashMap<>();

        List<Map<String, Object>> must = new ArrayList<>();
        if (StringUtils.hasText(req.getKeyword())) {
            must.add(Map.of("multi_match", Map.of(
                    "query", req.getKeyword(),
                    "fields", List.of("title^3", "subTitle", "brand^2", "tags^1.5"),
                    "type", "best_fields",
                    "tie_breaker", 0.3,
                    "operator", "or"
            )));
        } else {
            must.add(Map.of("match_all", Map.of()));
        }
        bool.put("must", must);

        List<Map<String, Object>> filters = new ArrayList<>();

        if (Boolean.TRUE.equals(req.getOnlyAvailable())) {
            filters.add(Map.of("term",  Map.of("isOnSale", true)));
            filters.add(Map.of("range", Map.of("stock", Map.of("gt", 0))));
        }

        if (!CollectionUtils.isEmpty(req.getBrands())) {
            filters.add(Map.of("terms", Map.of("brand", req.getBrands())));
        }

        if (StringUtils.hasText(req.getCategoryId())) {
            filters.add(Map.of("term", Map.of("categoryId", req.getCategoryId())));
        }

        if (req.getMinPrice() != null || req.getMaxPrice() != null) {
            Map<String, Object> range = new HashMap<>();
            if (req.getMinPrice() != null) range.put("gte", req.getMinPrice());
            if (req.getMaxPrice() != null) range.put("lte", req.getMaxPrice());
            filters.add(Map.of("range", Map.of("price", range)));
        }

        // nested 属性过滤：每一组属性(颜色 / 内存)都构造一个 nested 子句（AND）
        if (req.getAttrs() != null) {
            for (Map.Entry<String, List<String>> e : req.getAttrs().entrySet()) {
                if (!StringUtils.hasText(e.getKey()) || CollectionUtils.isEmpty(e.getValue())) continue;
                filters.add(Map.of("nested", Map.of(
                        "path", "attrs",
                        "query", Map.of("bool", Map.of(
                                "must", List.of(
                                        Map.of("term",  Map.of("attrs.name",  e.getKey())),
                                        Map.of("terms", Map.of("attrs.value", e.getValue()))
                                )
                        ))
                )));
            }
        }

        bool.put("filter", filters);
        return bool;
    }

    /** 排序：不同 sort 字段对应不同策略。 */
    private Object buildSort(String sort) {
        if (sort == null) return null;
        return switch (sort.toLowerCase()) {
            case "sales"      -> List.of(Map.of("sales30d",  Map.of("order", "desc")), "_score");
            case "rating"     -> List.of(Map.of("rating",    Map.of("order", "desc")), "_score");
            case "price_asc"  -> List.of(Map.of("price",     Map.of("order", "asc")));
            case "price_desc" -> List.of(Map.of("price",     Map.of("order", "desc")));
            case "newest"     -> List.of(Map.of("createdAt", Map.of("order", "desc")));
            default -> null; // composite：交给 function_score / _score
        };
    }

    /** 聚合：品牌、价格区间、属性。 */
    private Map<String, Object> buildAggs() {
        Map<String, Object> aggs = new LinkedHashMap<>();

        aggs.put("brands", Map.of("terms", Map.of("field", "brand", "size", 20)));

        aggs.put("price_ranges", Map.of("range", Map.of(
                "field", "price",
                "ranges", List.of(
                        Map.of("to", 1000),
                        Map.of("from", 1000, "to", 3000),
                        Map.of("from", 3000, "to", 5000),
                        Map.of("from", 5000)
                )
        )));

        aggs.put("attrs", Map.of(
                "nested", Map.of("path", "attrs"),
                "aggs", Map.of(
                        "attr_name", Map.of(
                                "terms", Map.of("field", "attrs.name", "size", 10),
                                "aggs", Map.of(
                                        "attr_value", Map.of(
                                                "terms", Map.of("field", "attrs.value", "size", 20)
                                        )
                                )
                        )
                )
        ));

        return aggs;
    }

    // ==================================================================
    //  三、Completion Suggest DSL
    // ==================================================================

    public Map<String, Object> buildSuggestDsl(String prefix, int size) {
        return Map.of(
                "_source", false,
                "suggest", Map.of(
                        "product_suggest", Map.of(
                                "prefix", prefix == null ? "" : prefix,
                                "completion", Map.of(
                                        "field", "suggest",
                                        "size", size,
                                        "skip_duplicates", true
                                )
                        )
                )
        );
    }

    /** 保留：演示价格区间 gauss 衰减（高级用法，可用于"价格锚点"排序）。 */
    @SuppressWarnings("unused")
    private Map<String, Object> priceGauss(BigDecimal origin) {
        return Map.of("gauss", Map.of("price", Map.of(
                "origin", origin,
                "scale",  1000,
                "decay",  0.5
        )));
    }
}
