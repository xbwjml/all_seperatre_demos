package com.example.demo.esDemo.merchandiseSearch.service;

import com.example.demo.esDemo.merchandiseSearch.MerchandiseSearchProperties;
import com.example.demo.esDemo.merchandiseSearch.domain.Product;
import com.example.demo.esDemo.merchandiseSearch.domain.ProductAttr;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 启动时自动建索引并写入一批样例商品，方便演示搜索效果。
 *
 * <p>由 {@code demo.merchandise-search.seed-on-startup=true} 控制。
 * 若 ES 不可用会打 WARN 日志但不影响应用启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "demo.merchandise-search", name = "enabled", havingValue = "true")
public class DataSeeder {

    private final MerchandiseSearchProperties properties;
    private final IndexService indexService;
    private final ProductSearchService searchService;

    @PostConstruct
    public void init() {
        if (!properties.isSeedOnStartup()) {
            log.info("MerchandiseSearch: seed-on-startup=false，跳过自动初始化");
            return;
        }
        try {
            boolean created = indexService.createIfAbsent();
            if (created) {
                int count = searchService.bulkIndex(sampleProducts());
                log.info("MerchandiseSearch: 样例商品写入完成，共 {} 条", count);
            }
        } catch (Exception e) {
            log.warn("MerchandiseSearch: 初始化失败（ES 可能未启动），可稍后手动调用 " +
                    "POST /api/merchandise-search/index 重建。原因: {}", e.getMessage());
        }
    }

    public List<Product> sampleProducts() {
        return List.of(
                product("P001", "Apple iPhone 15 Pro 256GB 黑色钛金属",
                        "A17 Pro 芯片 · USB-C · 全天候显示",
                        "Apple", "mobile", "电子>手机>智能手机",
                        "7999", 15230, 3500, 4.8f, 200,
                        List.of("新品", "自营", "热销"),
                        List.of(new ProductAttr("颜色", "黑色"),
                                new ProductAttr("内存", "256GB"),
                                new ProductAttr("网络", "5G"))),
                product("P002", "Apple iPhone 15 128GB 蓝色",
                        "A16 芯片 · 灵动岛 · 4800 万像素",
                        "Apple", "mobile", "电子>手机>智能手机",
                        "5999", 20100, 4200, 4.7f, 350,
                        List.of("热销", "自营"),
                        List.of(new ProductAttr("颜色", "蓝色"),
                                new ProductAttr("内存", "128GB"),
                                new ProductAttr("网络", "5G"))),
                product("P003", "Huawei Mate 60 Pro 512GB 雅川青",
                        "昆仑玻璃 · 北斗卫星通信",
                        "Huawei", "mobile", "电子>手机>智能手机",
                        "6999", 18800, 5800, 4.9f, 150,
                        List.of("热销", "旗舰"),
                        List.of(new ProductAttr("颜色", "青色"),
                                new ProductAttr("内存", "512GB"),
                                new ProductAttr("网络", "5G"))),
                product("P004", "Xiaomi 14 Ultra 16GB+1TB 白色",
                        "徕卡光学 · 骁龙 8 Gen 3",
                        "Xiaomi", "mobile", "电子>手机>智能手机",
                        "6499", 9800, 2100, 4.6f, 300,
                        List.of("新品", "影像旗舰"),
                        List.of(new ProductAttr("颜色", "白色"),
                                new ProductAttr("内存", "1TB"),
                                new ProductAttr("网络", "5G"))),
                product("P005", "Redmi K70 12GB+256GB 墨羽",
                        "第二代骁龙 8 · 2K 屏",
                        "Xiaomi", "mobile", "电子>手机>智能手机",
                        "2999", 32000, 12000, 4.5f, 800,
                        List.of("性价比", "热销"),
                        List.of(new ProductAttr("颜色", "黑色"),
                                new ProductAttr("内存", "256GB"),
                                new ProductAttr("网络", "5G"))),
                product("P006", "OPPO Find X7 Ultra 16GB+512GB 海阔天空",
                        "双潜望长焦 · 哈苏影像",
                        "OPPO", "mobile", "电子>手机>智能手机",
                        "6499", 7200, 1800, 4.7f, 220,
                        List.of("影像旗舰"),
                        List.of(new ProductAttr("颜色", "蓝色"),
                                new ProductAttr("内存", "512GB"),
                                new ProductAttr("网络", "5G"))),
                product("P007", "Apple iPad Pro 11 M4 256GB 深空黑",
                        "M4 芯片 · Ultra Retina XDR",
                        "Apple", "tablet", "电子>平板>iPad",
                        "8999", 3400, 600, 4.8f, 100,
                        List.of("新品", "自营"),
                        List.of(new ProductAttr("颜色", "黑色"),
                                new ProductAttr("内存", "256GB"))),
                product("P008", "MacBook Air 13 M3 16GB+512GB 午夜色",
                        "Apple 芯片 · 18 小时续航",
                        "Apple", "laptop", "电子>电脑>笔记本",
                        "11499", 2200, 400, 4.9f, 80,
                        List.of("自营", "轻薄本"),
                        List.of(new ProductAttr("颜色", "黑色"),
                                new ProductAttr("内存", "512GB"))),
                product("P009", "AirPods Pro 2 USB-C 版",
                        "主动降噪 · 自适应音频",
                        "Apple", "audio", "电子>耳机>蓝牙耳机",
                        "1899", 58000, 15600, 4.8f, 2000,
                        List.of("热销", "自营"),
                        List.of(new ProductAttr("颜色", "白色"))),
                product("P010", "Sony WH-1000XM5 无线降噪耳机",
                        "降噪旗舰 · 30 小时续航",
                        "Sony", "audio", "电子>耳机>头戴式",
                        "2299", 12500, 3800, 4.7f, 500,
                        List.of("降噪"),
                        List.of(new ProductAttr("颜色", "黑色")))
        );
    }

    private Product product(String id, String title, String subTitle,
                            String brand, String categoryId, String categoryPath,
                            String price, int sales, int sales30d, float rating, int stock,
                            List<String> tags, List<ProductAttr> attrs) {
        return Product.builder()
                .productId(id)
                .title(title)
                .subTitle(subTitle)
                .brand(brand)
                .categoryId(categoryId)
                .categoryPath(categoryPath)
                .price(new BigDecimal(price))
                .sales(sales)
                .sales30d(sales30d)
                .rating(rating)
                .stock(stock)
                .isOnSale(true)
                .shopId("shop-" + brand.toLowerCase())
                .tags(tags)
                .attrs(attrs)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
