package com.novelanalyzer.common.result;

import org.springframework.http.HttpStatus;

public final class HttpStatusMapper {

    private HttpStatusMapper() {
    }

    public static HttpStatus toHttpStatus(int resultCode) {
        try {
            return HttpStatus.valueOf(resultCode);
        } catch (IllegalArgumentException ex) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    public static HttpStatus toHttpStatus(ResultCode resultCode) {
        return toHttpStatus(resultCode.getCode());
    }
}
