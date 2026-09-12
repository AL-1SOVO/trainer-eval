package com.trainereval.common.result;

import lombok.Data;

@Data
public class R<T> {
    private Integer code;
    private String message;
    private T data;

    public static final int SUCCESS = 200;
    public static final int ERROR = 500;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;

    public static <T> R<T> ok() {
        R<T> r = new R<>();
        r.code = SUCCESS;
        r.message = "操作成功";
        return r;
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = SUCCESS;
        r.message = "操作成功";
        r.data = data;
        return r;
    }

    public static <T> R<T> ok(String message, T data) {
        R<T> r = new R<>();
        r.code = SUCCESS;
        r.message = message;
        r.data = data;
        return r;
    }

    public static <T> R<T> error(String message) {
        R<T> r = new R<>();
        r.code = ERROR;
        r.message = message;
        return r;
    }

    public static <T> R<T> error(Integer code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
