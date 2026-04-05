package com.example.demo.transfer.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DepositRequest {
    private BigDecimal amount;
}
