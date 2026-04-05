package com.example.demo.transfer.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TransferRequest {
    /**
     * 幂等键，由客户端生成（推荐 UUID）。
     * 相同 transferNo 的重复请求只执行一次，直接返回首次结果。
     */
    private String transferNo;
    private String fromAccountId;
    private String toAccountId;
    private BigDecimal amount;
    private String remark;
}
