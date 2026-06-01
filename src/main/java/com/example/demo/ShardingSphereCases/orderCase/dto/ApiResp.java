package com.example.demo.ShardingSphereCases.orderCase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResp<T> {

    private int code;
    private String message;
    private T data;

    public static <T> ApiResp<T> ok(T data) {
        return new ApiResp<>(200, "success", data);
    }

    public static <T> ApiResp<T> error(int code, String message) {
        return new ApiResp<>(code, message, null);
    }
}
