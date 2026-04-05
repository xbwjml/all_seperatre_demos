package com.example.demo.transfer.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateAccountRequest {
    private String userId;
    private String name;
    /** 初始余额，不传默认为 0 */
    private BigDecimal initialBalance;
}
