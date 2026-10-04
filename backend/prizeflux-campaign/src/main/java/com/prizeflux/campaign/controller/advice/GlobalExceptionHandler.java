package com.prizeflux.campaign.controller.advice;

import com.prizeflux.contract.api.ApiResponse;
import com.prizeflux.contract.error.BusinessException;
import com.prizeflux.contract.error.ErrorCode;
import com.prizeflux.campaign.application.InvalidCredentialsException;
import com.prizeflux.campaign.application.DrawQueueRateLimitException;
import com.prizeflux.campaign.application.DrawQueueProtectionUnavailableException;
import com.prizeflux.campaign.application.UsernameAlreadyRegisteredException;
import org.springframework.beans.TypeMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Campaign 的 MVC 异常边界，将失败转换成 HTTP 状态及统一响应。
 * 继承框架处理器以保留其他已知 MVC 异常的状态和响应头，而不是全改成500。
 * 不负责 JVM Error、过滤器、后台线程/任务异常，也不提供业务判断逻辑。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** DTO已读取但字段约束不通过；Controller业务方法尚未执行。 */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // 只记录固定事件，不输出 BindingResult/rejectedValue 或完整异常消息。
        log.warn("request_validation_failed");
        return new ResponseEntity<>(ApiResponse.failure(ErrorCode.INVALID_ARGUMENT), headers, status);
    }

    /** 请求体缺失、JSON非法或无法转换成DTO；业务方法尚未执行。 */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // 原解析异常可能包含请求片段；不输出其 message 或堆栈。
        log.warn("request_body_unreadable");
        return new ResponseEntity<>(ApiResponse.failure(ErrorCode.INVALID_ARGUMENT), headers, status);
    }

    /** 路径变量等MVC参数无法转换时，返回固定400且不记录被拒绝的原值。 */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        log.warn("request_type_mismatch");
        return new ResponseEntity<>(ApiResponse.failure(ErrorCode.INVALID_ARGUMENT), headers, status);
    }

    /** 已知业务失败：错误枚举来自业务代码，Web层只决定HTTP映射并包装响应。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        HttpStatus status = switch (errorCode) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BUSINESS_RULE_VIOLATION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            // BusinessException构造器已拒绝SUCCESS；这里防止将成功当作异常响应。
            case SUCCESS -> throw new IllegalStateException("业务异常不能携带成功标识");
        };
        if (ex instanceof DrawQueueProtectionUnavailableException protectionFailure) {
            log.warn("draw_queue_protection_rejected code={} reason={}",
                    errorCode.code(), protectionFailure.reason().name());
        } else {
            log.warn("business_failure code={}", errorCode.code());
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (ex instanceof DrawQueueRateLimitException limited) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()));
        }
        return response.body(ApiResponse.failure(errorCode));
    }

    /**
     * 登录失败统一映射为 401，不区分账号不存在、禁用或口令错误，避免账号枚举；
     * 不把具体原因或口令写入日志与响应。
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidCredentials(InvalidCredentialsException ex) {
        log.warn("login_failed");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure(ErrorCode.UNAUTHORIZED));
    }

    /** 注册冲突返回稳定409；不把数据库约束、用户名或 SQL 细节写入日志/响应。 */
    @ExceptionHandler(UsernameAlreadyRegisteredException.class)
    public ResponseEntity<ApiResponse<Void>> handleUsernameConflict(UsernameAlreadyRegisteredException ex) {
        log.warn("registration_conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure(ErrorCode.CONFLICT));
    }

    /**
     * 未预期的MVC请求异常，包括程序错误；不将所有IllegalArgumentException当客户端400。
     * 服务端保留诊断堆栈，对外只用固定INTERNAL_ERROR消息。
     * 堆栈自身仍可能包含敏感信息，生产日志访问控制与脱敏不由本方法保证。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        log.error("unexpected_exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(ErrorCode.INTERNAL_ERROR));
    }
}
