package com.example.demo.esDemo.merchandiseSearch.service;

import com.example.demo.esDemo.merchandiseSearch.MerchandiseSearchProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 极简的 Elasticsearch REST 客户端。
 *
 * <p>采用原生 RestTemplate + JSON 字符串的方式直连 ES HTTP API，
 * 目的是让演示代码中的 DSL 与 Kibana/curl 看到的完全一致，
 * 便于对照理解 "商品搜索" 的各种查询语法。
 *
 * <p>生产可替换为：co.elastic.clients:elasticsearch-java（官方推荐）
 * 或 spring-data-elasticsearch。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "demo.merchandise-search", name = "enabled", havingValue = "true")
public class EsRestClient {

    private final MerchandiseSearchProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 发送任意 ES 请求，返回反序列化后的 Map。 */
    public Map<String, Object> exchange(HttpMethod method, String path, Object body) {
        String url = properties.getEsBaseUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String payload = null;
        try {
            if (body != null) {
                payload = body instanceof String s ? s : objectMapper.writeValueAsString(body);
            }
        } catch (Exception e) {
            throw new IllegalStateException("序列化 ES 请求体失败", e);
        }

        HttpEntity<String> entity = new HttpEntity<>(payload, headers);
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, method, entity, String.class);
            String raw = response.getBody();
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (HttpStatusCodeException e) {
            log.warn("ES 调用失败 {} {} -> {} : {}", method, url, e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException("ES 调用失败: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new IllegalStateException("ES 调用异常: " + url, e);
        }
    }

    public Map<String, Object> get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    public Map<String, Object> put(String path, Object body) {
        return exchange(HttpMethod.PUT, path, body);
    }

    public Map<String, Object> post(String path, Object body) {
        return exchange(HttpMethod.POST, path, body);
    }

    public Map<String, Object> delete(String path) {
        return exchange(HttpMethod.DELETE, path, null);
    }

    /** HEAD 请求：判断索引是否存在（200 存在，404 不存在）。 */
    public boolean exists(String path) {
        String url = properties.getEsBaseUrl() + path;
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.HEAD, HttpEntity.EMPTY, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (HttpStatusCodeException e) {
            return false;
        }
    }
}
