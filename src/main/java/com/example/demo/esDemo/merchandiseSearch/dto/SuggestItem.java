package com.example.demo.esDemo.merchandiseSearch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 搜索下拉提示项。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SuggestItem {

    private String text;

    private Float score;
}
