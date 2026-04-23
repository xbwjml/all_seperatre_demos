package com.example.demo.esDemo.merchandiseSearch.service;

import com.example.demo.esDemo.merchandiseSearch.MerchandiseSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 索引生命周期管理：创建 / 删除 / 判断是否存在。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "demo.merchandise-search", name = "enabled", havingValue = "true")
public class IndexService {

    private final MerchandiseSearchProperties properties;
    private final EsRestClient esClient;
    private final QueryDslBuilder dslBuilder;

    public boolean exists() {
        return esClient.exists("/" + properties.getIndexName());
    }

    /** 创建索引（若不存在）。返回 true 表示新建。 */
    public boolean createIfAbsent() {
        if (exists()) {
            log.info("索引 [{}] 已存在，跳过创建", properties.getIndexName());
            return false;
        }
        Map<String, Object> def = dslBuilder.buildIndexDefinition();
        esClient.put("/" + properties.getIndexName(), def);
        log.info("索引 [{}] 创建成功", properties.getIndexName());
        return true;
    }

    public void delete() {
        if (!exists()) {
            log.info("索引 [{}] 不存在，跳过删除", properties.getIndexName());
            return;
        }
        esClient.delete("/" + properties.getIndexName());
        log.info("索引 [{}] 已删除", properties.getIndexName());
    }

    public void recreate() {
        delete();
        Map<String, Object> def = dslBuilder.buildIndexDefinition();
        esClient.put("/" + properties.getIndexName(), def);
        log.info("索引 [{}] 已重建", properties.getIndexName());
    }
}
