package com.prizeflux.ai.api;

import com.prizeflux.contract.api.ApiResponse;
import com.prizeflux.contract.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AiApiExceptionHandler {
    @ExceptionHandler(AiRequestException.class)
    public ResponseEntity<ApiResponse<Void>> requestFailure(AiRequestException exception) {
        ErrorCode code = switch (exception.status()) {
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case BAD_REQUEST -> ErrorCode.INVALID_ARGUMENT;
            case NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case CONFLICT -> ErrorCode.CONFLICT;
            case TOO_MANY_REQUESTS -> ErrorCode.RATE_LIMITED;
            default -> ErrorCode.INTERNAL_ERROR;
        };
        return ResponseEntity.status(exception.status()).body(ApiResponse.failure(code));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Void>> invalidRequest(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failure(ErrorCode.INVALID_ARGUMENT));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(ErrorCode.INTERNAL_ERROR));
    }
}
