package com.novelanalyzer.common.exception;

import com.novelanalyzer.common.result.Result;
import com.novelanalyzer.common.result.ResultCode;
import com.novelanalyzer.common.result.HttpStatusMapper;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        LOGGER.warn("business exception: {}", ex.getMessage());
        return buildResponse(ex.getResultCode(), ex.getMessage());
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        BindException.class,
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Result<Void>> handleValidationException(Exception ex) {
        LOGGER.warn("validation exception: {}", ex.getMessage());
        return buildResponse(ResultCode.BAD_REQUEST, resolveValidationMessage(ex));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        LOGGER.error("unhandled exception", ex);
        return buildResponse(ResultCode.INTERNAL_ERROR, ResultCode.INTERNAL_ERROR.getMessage());
    }

    private ResponseEntity<Result<Void>> buildResponse(ResultCode resultCode, String message) {
        return ResponseEntity
            .status(HttpStatusMapper.toHttpStatus(resultCode))
            .body(Result.fail(resultCode, message));
    }

    private String resolveValidationMessage(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            return firstObjectErrorMessage(methodArgumentNotValidException.getBindingResult().getAllErrors());
        }
        if (ex instanceof BindException bindException) {
            return firstObjectErrorMessage(bindException.getBindingResult().getAllErrors());
        }
        if (ex instanceof ConstraintViolationException constraintViolationException) {
            return constraintViolationException.getConstraintViolations().stream()
                .map(violation -> violation == null ? null : violation.getMessage())
                .filter(message -> message != null && !message.isBlank())
                .findFirst()
                .orElse("invalid request parameter");
        }
        if (ex instanceof MissingServletRequestParameterException missingServletRequestParameterException) {
            return missingServletRequestParameterException.getParameterName() + " is required";
        }
        if (ex instanceof MethodArgumentTypeMismatchException methodArgumentTypeMismatchException) {
            return methodArgumentTypeMismatchException.getName() + " type mismatch";
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return "request body is invalid";
        }
        if (ex instanceof HandlerMethodValidationException handlerMethodValidationException) {
            return firstObjectErrorMessage(handlerMethodValidationException.getAllErrors());
        }
        return "invalid request parameter";
    }

    private String firstObjectErrorMessage(Iterable<? extends MessageSourceResolvable> errors) {
        for (MessageSourceResolvable error : errors) {
            String message = error == null ? null : error.getDefaultMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        return "invalid request parameter";
    }
}
