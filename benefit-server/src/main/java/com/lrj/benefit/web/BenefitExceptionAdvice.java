package com.lrj.benefit.web;

import com.lrj.benefit.application.service.BenefitApplicationException;
import com.lrj.benefit.contract.BenefitErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class BenefitExceptionAdvice {
    @ExceptionHandler(AwardNotFoundException.class)
    ResponseEntity<ErrorBody> notFound(AwardNotFoundException error) {
        return response(HttpStatus.NOT_FOUND, BenefitErrorCode.INVALID_INTENT, error.getMessage());
    }

    @ExceptionHandler(BenefitApplicationException.class)
    ResponseEntity<ErrorBody> application(BenefitApplicationException error) {
        HttpStatus status = switch (error.code()) {
            case IDEMPOTENCY_PAYLOAD_CONFLICT -> HttpStatus.CONFLICT;
            case SKU_NOT_FOUND -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVENTORY_INSUFFICIENT -> HttpStatus.CONFLICT;
            case REMEDIATION_NOT_ALLOWED -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return response(status, error.code(), error.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ErrorBody> badRequest(Exception error) {
        return response(HttpStatus.BAD_REQUEST, BenefitErrorCode.INVALID_INTENT, error.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorBody> conflict(IllegalStateException error) {
        return response(HttpStatus.CONFLICT, BenefitErrorCode.INTERNAL_ERROR, error.getMessage());
    }

    private static ResponseEntity<ErrorBody> response(HttpStatus status, BenefitErrorCode code, String message) {
        return ResponseEntity.status(status).body(new ErrorBody(code.name(), message, Instant.now()));
    }

    record ErrorBody(String code, String message, Instant timestamp) {}
}
