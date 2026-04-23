package com.example.demo.esDemo.merchandiseSearch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个聚合桶（key + 文档数），用于渲染左侧筛选栏。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FacetBucket {

    private String key;

    private long docCount;
}
