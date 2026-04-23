package com.example.demo.esDemo.merchandiseSearch.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商品规格属性（nested 嵌套对象）。
 * 例如：{"name":"颜色","value":"红色"}、{"name":"内存","value":"128GB"}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductAttr {

    /** 属性名（keyword）。 */
    private String name;

    /** 属性值（keyword）。 */
    private String value;
}
