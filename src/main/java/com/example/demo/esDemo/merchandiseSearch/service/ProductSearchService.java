package com.example.demo.esDemo.merchandiseSearch.service;

import com.example.demo.esDemo.merchandiseSearch.MerchandiseSearchProperties;
import com.example.demo.esDemo.merchandiseSearch.domain.Product;
import com.example.demo.esDemo.merchandiseSearch.domain.ProductAttr;
import com.example.demo.esDemo.merchandiseSearch.dto.FacetBucket;
import com.example.demo.esDemo.merchandiseSearch.dto.SearchRequest;
import com.example.demo.esDemo.merchandiseSearch.dto.SearchResponse;
import com.example.demo.esDemo.merchandiseSearch.dto.SuggestItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品搜索核心服务：封装 索引 / 搜索 / 建议 / 批量写入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "demo.merchandise-search", name = "enabled", havingValue = "true")
public class ProductSearchService {

    private final MerchandiseSearchProperties properties;
    private final EsRestClient esClient;
    private final QueryDslBuilder dslBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================================================================
    //  写入 / 删除
    // ==================================================================

    /** 单条 upsert。 */
    public void indexProduct(Product product) {
        esClient.put(
                "/" + properties.getIndexName() + "/_doc/" + product.getProductId() + "?refresh=wait_for",
                enrichForWrite(product)
        );
    }

    /** 批量写入（bulk API，生产批量导入必备）。 */
    public int bulkIndex(Collection<Product> products) {
        if (products == null || products.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (Product p : products) {
                Map<String, Object> action = Map.of("index", Map.of("_id", p.getProductId()));
                sb.append(objectMapper.writeValueAsString(action)).append('\n');
                sb.append(objectMapper.writeValueAsString(enrichForWrite(p))).append('\n');
            }
        } catch (Exception e) {
            throw new IllegalStateException("构造 bulk 请求失败", e);
        }
        String path = "/" + properties.getIndexName() + "/_bulk?refresh=wait_for";
        Map<String, Object> resp = esClient.exchange(
                org.springframework.http.HttpMethod.POST, path, sb.toString()
        );
        log.info("bulk 写入 {} 条：errors={}", products.size(), resp.get("errors"));
        return products.size();
    }

    public void deleteById(String productId) {
        esClient.delete("/" + properties.getIndexName() + "/_doc/" + productId + "?refresh=wait_for");
    }

    /** 写入前补全 suggest 字段（用 title + brand 作为补全候选）。 */
    private Map<String, Object> enrichForWrite(Product product) {
        Map<String, Object> doc = objectMapper.convertValue(product, LinkedHashMap.class);
        List<String> suggest = new ArrayList<>();
        if (product.getTitle() != null) suggest.add(product.getTitle());
        if (product.getBrand() != null) suggest.add(product.getBrand());
        doc.put("suggest", suggest);
        return doc;
    }

    // ==================================================================
    //  搜索
    // ==================================================================

    @SuppressWarnings("unchecked")
    public SearchResponse search(SearchRequest request) {
        Map<String, Object> dsl = dslBuilder.buildSearchDsl(request);
        if (log.isDebugEnabled()) {
            try {
                log.debug("ES DSL = {}", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(dsl));
            } catch (Exception ignore) {}
        }

        Map<String, Object> raw = esClient.post("/" + properties.getIndexName() + "/_search", dsl);

        SearchResponse resp = new SearchResponse();
        resp.setTookMs(asLong(raw.get("took")));
        resp.setPage(request.getPage() == null ? 1 : request.getPage());
        resp.setSize(request.getSize() == null ? 10 : request.getSize());

        Map<String, Object> hits = (Map<String, Object>) raw.get("hits");
        if (hits != null) {
            Object totalObj = hits.get("total");
            if (totalObj instanceof Map<?, ?> m) {
                resp.setTotal(asLong(m.get("value")));
            }
            List<Map<String, Object>> list = (List<Map<String, Object>>) hits.get("hits");
            resp.setItems(extractItems(list));
        } else {
            resp.setItems(Collections.emptyList());
        }

        resp.setAggs(extractAggs((Map<String, Object>) raw.get("aggregations")));
        return resp;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractItems(List<Map<String, Object>> hits) {
        if (hits == null) return Collections.emptyList();
        List<Map<String, Object>> items = new ArrayList<>(hits.size());
        for (Map<String, Object> h : hits) {
            Map<String, Object> source = (Map<String, Object>) h.get("_source");
            if (source == null) source = new LinkedHashMap<>();
            Map<String, Object> item = new LinkedHashMap<>(source);
            item.put("_score", h.get("_score"));

            Map<String, Object> highlight = (Map<String, Object>) h.get("highlight");
            if (highlight != null && !highlight.isEmpty()) {
                item.put("_highlight", highlight);
            }
            items.add(item);
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<FacetBucket>> extractAggs(Map<String, Object> aggs) {
        Map<String, List<FacetBucket>> result = new LinkedHashMap<>();
        if (aggs == null) return result;

        Map<String, Object> brands = (Map<String, Object>) aggs.get("brands");
        if (brands != null) {
            result.put("brands", toBuckets((List<Map<String, Object>>) brands.get("buckets")));
        }

        Map<String, Object> priceRanges = (Map<String, Object>) aggs.get("price_ranges");
        if (priceRanges != null) {
            List<Map<String, Object>> buckets = (List<Map<String, Object>>) priceRanges.get("buckets");
            List<FacetBucket> list = new ArrayList<>();
            for (Map<String, Object> b : buckets) {
                String key = String.valueOf(b.get("key"));
                list.add(new FacetBucket(key, asLong(b.get("doc_count"))));
            }
            result.put("price_ranges", list);
        }

        // nested attrs：扁平化为 "颜色" / "内存" 这样的独立 facet
        Map<String, Object> attrs = (Map<String, Object>) aggs.get("attrs");
        if (attrs != null) {
            Map<String, Object> attrName = (Map<String, Object>) attrs.get("attr_name");
            if (attrName != null) {
                List<Map<String, Object>> nameBuckets = (List<Map<String, Object>>) attrName.get("buckets");
                for (Map<String, Object> nameB : nameBuckets) {
                    String name = String.valueOf(nameB.get("key"));
                    Map<String, Object> valueAgg = (Map<String, Object>) nameB.get("attr_value");
                    if (valueAgg == null) continue;
                    result.put("attr_" + name,
                            toBuckets((List<Map<String, Object>>) valueAgg.get("buckets")));
                }
            }
        }
        return result;
    }

    private List<FacetBucket> toBuckets(List<Map<String, Object>> src) {
        if (src == null) return Collections.emptyList();
        List<FacetBucket> list = new ArrayList<>(src.size());
        for (Map<String, Object> b : src) {
            list.add(new FacetBucket(String.valueOf(b.get("key")), asLong(b.get("doc_count"))));
        }
        return list;
    }

    private long asLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================================================================
    //  Suggest 下拉补全
    // ==================================================================

    @SuppressWarnings("unchecked")
    public List<SuggestItem> suggest(String prefix, int size) {
        Map<String, Object> dsl = dslBuilder.buildSuggestDsl(prefix, size);
        Map<String, Object> raw = esClient.post("/" + properties.getIndexName() + "/_search", dsl);

        Map<String, Object> suggestNode = (Map<String, Object>) raw.get("suggest");
        if (suggestNode == null) return Collections.emptyList();

        List<Map<String, Object>> list = (List<Map<String, Object>>) suggestNode.get("product_suggest");
        if (list == null || list.isEmpty()) return Collections.emptyList();

        List<SuggestItem> items = new ArrayList<>();
        for (Map<String, Object> s : list) {
            List<Map<String, Object>> options = (List<Map<String, Object>>) s.get("options");
            if (options == null) continue;
            for (Map<String, Object> opt : options) {
                items.add(new SuggestItem(
                        String.valueOf(opt.get("text")),
                        opt.get("_score") == null ? 0f : ((Number) opt.get("_score")).floatValue()
                ));
            }
        }
        return items;
    }

    /** 辅助构造，便于 Seeder 使用。 */
    public static ProductAttr attr(String name, String value) {
        return new ProductAttr(name, value);
    }
}
