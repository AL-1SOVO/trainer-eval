package com.trainereval.common.exception;

import com.trainereval.common.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * 全局异常处理。
 * <p>
 * 本轮修复两个问题：
 * <ol>
 *   <li><b>客户端错误被当成服务端错误</b>：请求体格式错误/缺少参数/类型不匹配/方法不支持
 *       原先全部落到兜底 handler → 返回 HTTP 500。会让调用方以为服务端故障，
 *       也会污染告警。现在按语义返回 400 / 405 / 413 / 404。</li>
 *   <li><b>内部异常原文回传前端</b>：兜底 handler 原先返回
 *       {@code "服务器内部错误: " + e.getMessage()}，会把 SQL、mapper 类名、文件绝对路径、
 *       Jackson 内部类型信息直接泄露给客户端。现在只回固定文案，详细信息仅落服务端日志。</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public R<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return R.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public R<?> handleBadCredentialsException(BadCredentialsException e) {
        return R.error(400, "用户名或密码错误");
    }

    @ExceptionHandler(DisabledException.class)
    public R<?> handleDisabledException(DisabledException e) {
        return R.error(403, "账号已被禁用，请联系管理员");
    }

    @ExceptionHandler(AuthenticationException.class)
    public R<?> handleAuthenticationException(AuthenticationException e) {
        return R.error(403, e.getMessage() != null ? e.getMessage() : "认证失败");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<?> handleValidationException(MethodArgumentNotValidException e) {
        StringBuilder sb = new StringBuilder();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            sb.append(error.getField()).append(":").append(error.getDefaultMessage()).append("; ");
        }
        return R.error(400, sb.toString());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<?> handleAccessDeniedException(AccessDeniedException e) {
        return R.error(403, "无权限访问");
    }

    /** 请求体不是合法 JSON 或结构与目标类型不符 —— 这是客户端问题，返回 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<?> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return R.error(400, "请求体格式错误，请检查提交的 JSON");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<?> handleMissingParam(MissingServletRequestParameterException e) {
        return R.error(400, "缺少必需参数: " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return R.error(400, "参数类型不正确: " + e.getName());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public R<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return R.error(405, "不支持的请求方法: " + e.getMethod());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public R<?> handleMaxUpload(MaxUploadSizeExceededException e) {
        return R.error(413, "上传文件超过大小限制");
    }

    /** 访问不存在的静态资源/路径 —— 应为 404，而不是 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<?> handleNoResource(NoResourceFoundException e) {
        return R.error(404, "请求的资源不存在");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<?> handleException(Exception e) {
        // 只把详细信息写进服务端日志；响应体用固定文案，避免泄露内部实现
        log.error("系统异常", e);
        return R.error(500, "服务器内部错误，请稍后重试或联系管理员");
    }
}
