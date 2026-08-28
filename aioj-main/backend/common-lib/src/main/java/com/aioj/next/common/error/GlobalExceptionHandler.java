package com.aioj.next.common.error;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.api.TraceIds;
import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Value("${spring.application.name:application-service}")
    private String serviceName;

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponse<Void>> handleDomain(DomainException ex) {
        log.warn("DomainException code={} msg={}", ex.errorCode().code(), ex.getMessage());
        UserErrorFeedback feedback = UserErrorFeedback.forDomain(ex.errorCode(), ex.getMessage(), serviceName);
        return ResponseEntity.status(toStatus(ex.errorCode()))
                .body(ApiResponse.failWithError(
                        ex.errorCode().code(),
                        feedback.message(),
                        null,
                        feedback.key(),
                        feedback.params()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleArgumentNotValid(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err ->
                fieldErrors.putIfAbsent(err.getField(), ValidationErrorMessages.forFieldError(err)));
        ex.getBindingResult().getGlobalErrors().forEach(err ->
                fieldErrors.putIfAbsent(err.getObjectName(), "提交内容组合不正确，请检查后重试。"));
        log.warn("Validation failed: fields={}", fieldErrors.keySet());
        UserErrorFeedback feedback = UserErrorFeedback.validation(serviceName);
        return ResponseEntity.badRequest().body(
                ApiResponse.failWithError(
                        ErrorCode.VALIDATION_FAILED.code(),
                        feedback.message(),
                        fieldErrors,
                        feedback.key(),
                        feedback.params()
                ));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getFieldErrors().forEach(err ->
                fieldErrors.putIfAbsent(err.getField(), ValidationErrorMessages.forFieldError(err)));
        log.warn("Bind failed: fields={}", fieldErrors.keySet());
        UserErrorFeedback feedback = UserErrorFeedback.validation(serviceName);
        return ResponseEntity.badRequest().body(
                ApiResponse.failWithError(
                        ErrorCode.VALIDATION_FAILED.code(),
                        feedback.message(),
                        fieldErrors,
                        feedback.key(),
                        feedback.params()
                ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            fieldErrors.putIfAbsent(path, ValidationErrorMessages.fieldLabel(path) + "填写不正确，请检查后重试。");
        });
        log.warn("Constraint violation: fields={}", fieldErrors.keySet());
        UserErrorFeedback feedback = UserErrorFeedback.validation(serviceName);
        return ResponseEntity.badRequest().body(
                ApiResponse.failWithError(
                        ErrorCode.VALIDATION_FAILED.code(),
                        feedback.message(),
                        fieldErrors,
                        feedback.key(),
                        feedback.params()
                ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        UserErrorFeedback feedback = UserErrorFeedback.forCode(ErrorCode.INVALID_PAYLOAD, serviceName);
        return ResponseEntity.badRequest().body(
                ApiResponse.failWithError(
                        ErrorCode.INVALID_PAYLOAD.code(),
                        feedback.message(),
                        null,
                        feedback.key(),
                        feedback.params()
                ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        Map<String, String> details = ValidationErrorMessages.missingParameter(ex.getParameterName());
        UserErrorFeedback feedback = UserErrorFeedback.field("request.missingParameter", ValidationErrorMessages.fieldLabel(ex.getParameterName()), serviceName);
        return ResponseEntity.badRequest().body(
                ApiResponse.failWithError(
                        ErrorCode.MISSING_PARAMETER.code(),
                        feedback.message(),
                        details,
                        feedback.key(),
                        feedback.params()
                ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, String> details = ValidationErrorMessages.typeMismatch(ex.getName(), ex.getRequiredType());
        UserErrorFeedback feedback = UserErrorFeedback.field("request.typeMismatch", ValidationErrorMessages.fieldLabel(ex.getName()), serviceName);
        return ResponseEntity.badRequest().body(
                ApiResponse.failWithError(
                        ErrorCode.TYPE_MISMATCH.code(),
                        feedback.message(),
                        details,
                        feedback.key(),
                        feedback.params()
                ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        UserErrorFeedback feedback = UserErrorFeedback.forCode(ErrorCode.METHOD_NOT_ALLOWED, serviceName);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                ApiResponse.failWithError(
                        ErrorCode.METHOD_NOT_ALLOWED.code(),
                        feedback.message(),
                        null,
                        feedback.key(),
                        feedback.params()
                ));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        UserErrorFeedback feedback = UserErrorFeedback.fromKey("request.unsupportedMediaType", "请求内容类型不受支持，请使用页面表单重新提交。", serviceName);
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(
                ApiResponse.failWithError(
                        ErrorCode.BAD_REQUEST.code(),
                        feedback.message(),
                        null,
                        feedback.key(),
                        feedback.params()
                ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        UserErrorFeedback feedback = UserErrorFeedback.forCode(ErrorCode.PAYLOAD_TOO_LARGE, serviceName);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                ApiResponse.failWithError(
                        ErrorCode.PAYLOAD_TOO_LARGE.code(),
                        feedback.message(),
                        null,
                        feedback.key(),
                        feedback.params()
                ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied traceId={}", TraceIds.current());
        UserErrorFeedback feedback = UserErrorFeedback.forCode(ErrorCode.FORBIDDEN, serviceName);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiResponse.failWithError(
                        ErrorCode.FORBIDDEN.code(),
                        feedback.message(),
                        null,
                        feedback.key(),
                        feedback.params()
                ));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        // The peer has already disconnected. Do not try to serialize ApiResponse into a
        // committed SSE/async response, which would only generate another error dispatch.
        log.debug("Async response already closed traceId={}", TraceIds.current());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex) {
        log.error("Unhandled exception traceId={}", TraceIds.current(), ex);
        UserErrorFeedback feedback = UserErrorFeedback.forCode(ErrorCode.INTERNAL_ERROR, serviceName);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.failWithError(
                        ErrorCode.INTERNAL_ERROR.code(),
                        feedback.message(),
                        null,
                        feedback.key(),
                        feedback.params()
                ));
    }

    private HttpStatus toStatus(ErrorCode code) {
        return switch (code) {
            case BAD_REQUEST, VALIDATION_FAILED, INVALID_PAYLOAD, MISSING_PARAMETER, TYPE_MISMATCH -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
