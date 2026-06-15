package com.lianyu.web.handler;

import cn.dev33.satoken.exception.NotLoginException;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.base.Result;
import com.lianyu.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) {
        if (shouldSkipJsonErrorBody(request, response)) {
            log.warn("Business exception during SSE/stream: {}", e.getMessage());
            return null;
        }
        log.warn("Business exception: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        int httpStatus = switch (e.getErrorCode()) {
            case UNAUTHORIZED -> 401;
            case FORBIDDEN, CONTENT_POLICY_VIOLATION -> 403;
            case NOT_FOUND, USER_NOT_FOUND, CHARACTER_NOT_FOUND,
                 CONVERSATION_NOT_FOUND, MESSAGE_NOT_FOUND, MEMORY_NOT_FOUND -> 404;
            case CONFLICT, USERNAME_EXISTS -> 409;
            case AUTH_RATE_LIMITED, AI_RATE_LIMITED -> 429;
            default -> 400;
        };
        return ResponseEntity.status(httpStatus).body(Result.fail(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLogin(NotLoginException e,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) {
        if (shouldSkipJsonErrorBody(request, response)) {
            if (response != null && !response.isCommitted()) {
                response.setStatus(401);
            }
            return null;
        }
        return ResponseEntity.status(401).body(Result.fail(ErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleJsonParse(HttpMessageNotReadableException e,
                                                         HttpServletRequest request,
                                                         HttpServletResponse response) {
        if (shouldSkipJsonErrorBody(request, response)) {
            return null;
        }
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.BAD_REQUEST, "请求格式有误"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e,
                                                            HttpServletRequest request,
                                                            HttpServletResponse response) {
        if (shouldSkipJsonErrorBody(request, response)) {
            return null;
        }
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.BAD_REQUEST, "缺少必要参数"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e,
                                                          HttpServletRequest request,
                                                          HttpServletResponse response) {
        if (shouldSkipJsonErrorBody(request, response)) {
            return null;
        }
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError != null ? fieldError.getDefaultMessage() : "填写内容有误，请检查后重试";
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.BAD_REQUEST, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknown(Exception e, HttpServletRequest request,
                                                       HttpServletResponse response) {
        if (shouldSkipJsonErrorBody(request, response)) {
            log.error("Unhandled exception during SSE/async: {}", e.getClass().getSimpleName());
            return null;
        }
        // 生产环境不泄露栈轨迹，仅记录异常类型和消息
        log.error("Unhandled exception: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity.status(500).body(Result.fail(ErrorCode.INTERNAL_ERROR));
    }

    /** SSE / 流式响应已提交或即将写入时不能返回 Result JSON，否则会二次报错 */
    private static boolean shouldSkipJsonErrorBody(HttpServletRequest request, HttpServletResponse response) {
        if (response != null && response.isCommitted()) {
            return true;
        }
        if (response != null) {
            String contentType = response.getContentType();
            if (contentType != null && contentType.contains("text/event-stream")) {
                return true;
            }
        }
        String accept = request != null ? request.getHeader("Accept") : null;
        if (accept != null && accept.contains("text/event-stream")) {
            return true;
        }
        String path = request != null ? request.getRequestURI() : "";
        return path.contains("stream");
    }
}
