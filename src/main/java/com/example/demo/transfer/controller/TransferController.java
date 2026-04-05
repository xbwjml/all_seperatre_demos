package com.example.demo.transfer.controller;

import com.example.demo.transfer.common.ApiResponse;
import com.example.demo.transfer.domain.TransferOrder;
import com.example.demo.transfer.dto.TransferRequest;
import com.example.demo.transfer.dto.TransferResult;
import com.example.demo.transfer.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 转账接口。
 *
 * <pre>
 * POST  /api/transfers              发起转账（幂等，transferNo 相同时直接返回首次结果）
 * GET   /api/transfers/{transferNo} 查询转账状态
 * </pre>
 */
@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public ApiResponse<TransferResult> transfer(@RequestBody TransferRequest request) {
        return ApiResponse.success(transferService.transfer(request));
    }

    @GetMapping("/{transferNo}")
    public ApiResponse<TransferOrder> queryTransfer(@PathVariable String transferNo) {
        return ApiResponse.success(transferService.queryByTransferNo(transferNo));
    }
}
