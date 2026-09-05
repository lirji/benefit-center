package com.lrj.benefit.web;

import com.lrj.benefit.application.service.BenefitApplicationException;
import com.lrj.benefit.contract.BenefitErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/** 全局异常转 RFC 9457 Problem Details，并保留稳定机器错误码。 */
@RestControllerAdvice
public class BenefitExceptionAdvice {
    private final Clock clock;

    public BenefitExceptionAdvice(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(AwardNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(AwardNotFoundException error) {
        return response(HttpStatus.NOT_FOUND, BenefitErrorCode.INVALID_INTENT, error.getMessage());
    }

    @ExceptionHandler(BenefitApplicationException.class)
    ResponseEntity<ProblemDetail> application(BenefitApplicationException error) {
        HttpStatus status = switch (error.code()) {
            case IDEMPOTENCY_PAYLOAD_CONFLICT -> HttpStatus.CONFLICT;
            case SKU_NOT_FOUND -> HttpStatus.UNPROCESSABLE_ENTITY;
            case SKU_NOT_ACTIVE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case SKU_NOT_DRAFT, SKU_VERSION_CONFLICT, SKU_APPROVAL_LOCKED,
                    SKU_ILLEGAL_TRANSITION, SKU_SUBMIT_IN_FLIGHT -> HttpStatus.CONFLICT;
            case WALLET_ENTRY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case WALLET_ILLEGAL_TRANSITION, WALLET_ALREADY_USED,
                    WALLET_VERSION_CONFLICT, WALLET_EXPIRED -> HttpStatus.CONFLICT;
            case USER_LIMIT_EXCEEDED -> HttpStatus.CONFLICT;
            case INVENTORY_INSUFFICIENT -> HttpStatus.CONFLICT;
            case REMEDIATION_NOT_ALLOWED -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return response(status, error.code(), error.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ProblemDetail> badRequest(Exception error) {
        return response(HttpStatus.BAD_REQUEST, BenefitErrorCode.INVALID_INTENT, error.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> conflict(IllegalStateException error) {
        return response(HttpStatus.CONFLICT, BenefitErrorCode.INTERNAL_ERROR, error.getMessage());
    }

    private ResponseEntity<ProblemDetail> response(HttpStatus status, BenefitErrorCode code, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message == null ? status.getReasonPhrase() : message);
        problem.setType(URI.create("urn:benefit:error:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle(code.name());
        problem.setProperty("code", code.name());
        // message 是旧客户端兼容字段；新客户端应读取标准 detail。
        problem.setProperty("message", problem.getDetail());
        problem.setProperty("timestamp", clock.instant());
        return ResponseEntity.status(status).body(problem);
    }
}
