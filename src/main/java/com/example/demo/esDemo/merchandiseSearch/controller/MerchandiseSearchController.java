package com.example.demo.esDemo.merchandiseSearch.controller;

import com.example.demo.esDemo.merchandiseSearch.common.ApiResponse;
import com.example.demo.esDemo.merchandiseSearch.domain.Product;
import com.example.demo.esDemo.merchandiseSearch.dto.SearchRequest;
import com.example.demo.esDemo.merchandiseSearch.dto.SearchResponse;
import com.example.demo.esDemo.merchandiseSearch.dto.SuggestItem;
import com.example.demo.esDemo.merchandiseSearch.service.DataSeeder;
import com.example.demo.esDemo.merchandiseSearch.service.IndexService;
import com.example.demo.esDemo.merchandiseSearch.service.ProductSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 商品搜索对外 HTTP 接口。
 *
 * <pre>
 * POST   /api/merchandise-search/search           综合搜索（关键词 + 过滤 + 聚合 + 排序 + 高亮）
 * GET    /api/merchandise-search/suggest?q=ip     搜索下拉提示
 * POST   /api/merchandise-search/products         单条 upsert
 * DELETE /api/merchandise-search/products/{id}    删除单条
 *
 *  -- 管理类 --
 * POST   /api/merchandise-search/index            创建索引（若不存在）
 * DELETE /api/merchandise-search/index            删除索引
 * POST   /api/merchandise-search/index/recreate   删除后重建（会丢数据）
 * POST   /api/merchandise-search/index/seed       写入一批样例商品
 * </pre>
 */
@RestController
@RequestMapping("/api/merchandise-search")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "demo.merchandise-search", name = "enabled", havingValue = "true")
public class MerchandiseSearchController {

    private final ProductSearchService searchService;
    private final IndexService indexService;
    private final DataSeeder dataSeeder;

    // ---------------------- 搜索 ----------------------

    @PostMapping("/search")
    public ApiResponse<SearchResponse> search(@RequestBody SearchRequest request) {
        return ApiResponse.success(searchService.search(request));
    }

    @GetMapping("/suggest")
    public ApiResponse<List<SuggestItem>> suggest(@RequestParam("q") String q,
                                                  @RequestParam(value = "size", defaultValue = "10") int size) {
        return ApiResponse.success(searchService.suggest(q, size));
    }

    // ---------------------- 商品维护 ----------------------

    @PostMapping("/products")
    public ApiResponse<Void> upsert(@RequestBody Product product) {
        searchService.indexProduct(product);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/products/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") String id) {
        searchService.deleteById(id);
        return ApiResponse.success(null);
    }

    // ---------------------- 索引管理 ----------------------

    @PostMapping("/index")
    public ApiResponse<Map<String, Object>> createIndex() {
        boolean created = indexService.createIfAbsent();
        return ApiResponse.success(Map.of("created", created));
    }

    @DeleteMapping("/index")
    public ApiResponse<Void> dropIndex() {
        indexService.delete();
        return ApiResponse.success(null);
    }

    @PostMapping("/index/recreate")
    public ApiResponse<Void> recreateIndex() {
        indexService.recreate();
        return ApiResponse.success(null);
    }

    @PostMapping("/index/seed")
    public ApiResponse<Map<String, Object>> seed() {
        indexService.createIfAbsent();
        int count = searchService.bulkIndex(dataSeeder.sampleProducts());
        return ApiResponse.success(Map.of("indexed", count));
    }
}
