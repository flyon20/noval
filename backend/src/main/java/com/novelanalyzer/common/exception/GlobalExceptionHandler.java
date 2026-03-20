package com.novelanalyzer.common.exception;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.common.result.ResultCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException ex) {
        LOGGER.warn("business exception: {}", ex.getMessage());
        return Result.fail(ex.getResultCode(), ex.getMessage());
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        BindException.class,
        HttpMessageNotReadableException.class
    })
    public Result<Void> handleValidationException(Exception ex) {
        LOGGER.warn("validation exception: {}", ex.getMessage());
        return Result.fail(ResultCode.BAD_REQUEST, "invalid request parameter");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception ex) {
        LOGGER.error("unhandled exception", ex);
        return Result.fail(ResultCode.INTERNAL_ERROR);
    }
}

