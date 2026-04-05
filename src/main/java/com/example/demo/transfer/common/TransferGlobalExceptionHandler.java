package com.example.demo.transfer.common;

import com.example.demo.transfer.exception.AccountNotFoundException;
import com.example.demo.transfer.exception.InsufficientBalanceException;
import com.example.demo.transfer.exception.TransferException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "com.example.demo.transfer")
public class TransferGlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleAccountNotFound(AccountNotFoundException e) {
        log.warn("账户不存在: {}", e.getMessage());
        return ApiResponse.error(404, e.getMessage());
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleInsufficientBalance(InsufficientBalanceException e) {
        log.warn("余额不足: {}", e.getMessage());
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(TransferException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleTransferException(TransferException e) {
        log.error("转账异常: {}", e.getMessage(), e);
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("系统内部错误: {}", e.getMessage(), e);
        return ApiResponse.error(500, "系统内部错误，请联系管理员");
    }
}
