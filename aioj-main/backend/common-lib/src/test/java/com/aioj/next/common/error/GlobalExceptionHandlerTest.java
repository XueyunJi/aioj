package com.aioj.next.common.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Set;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.TestController.class})
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void domain_exception_maps_status_and_code() throws Exception {
        mockMvc.perform(get("/test/domain"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONFLICT.code()))
                .andExpect(jsonPath("$.message").value("当前操作与已有数据冲突，请刷新后确认状态。"))
                .andExpect(jsonPath("$.errorKey").value("request.conflict"))
                .andExpect(jsonPath("$.details").value(nullValue()));
    }

    @Test
    void method_argument_not_valid_returns_field_errors() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.message").value("提交内容未通过校验，请检查具体字段。"))
                .andExpect(jsonPath("$.errorKey").value("request.validationFailed"))
                .andExpect(jsonPath("$.details.name").value("名称不能为空。"));
    }

    @Test
    void bind_exception_returns_field_errors() throws Exception {
        mockMvc.perform(get("/test/bind").param("name", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.message").value("提交内容未通过校验，请检查具体字段。"))
                .andExpect(jsonPath("$.errorKey").value("request.validationFailed"))
                .andExpect(jsonPath("$.details.name").value("名称不能为空。"));
    }

    @Test
    void constraint_violation_returns_field_errors() throws Exception {
        mockMvc.perform(get("/test/violate").param("id", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.message").value("提交内容未通过校验，请检查具体字段。"))
                .andExpect(jsonPath("$.errorKey").value("request.validationFailed"))
                .andExpect(jsonPath("$.details.id").exists());
    }

    @Test
    void unreadable_body_returns_invalid_payload() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PAYLOAD.code()))
                .andExpect(jsonPath("$.message").value("请求体格式不正确，请检查填写内容或重新提交。"))
                .andExpect(jsonPath("$.errorKey").value("request.invalidPayload"))
                .andExpect(jsonPath("$.details").value(nullValue()));
    }

    @Test
    void missing_parameter_returns_missing() throws Exception {
        mockMvc.perform(get("/test/required-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.MISSING_PARAMETER.code()))
                .andExpect(jsonPath("$.message").value("缺少必要参数，请补全后重试。"))
                .andExpect(jsonPath("$.errorKey").value("request.missingParameter"))
                .andExpect(jsonPath("$.details.token").value("token不能为空。"));
    }

    @Test
    void type_mismatch_returns_type_error() throws Exception {
        mockMvc.perform(get("/test/typed/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.TYPE_MISMATCH.code()))
                .andExpect(jsonPath("$.message").value("参数类型不正确，请检查数字、时间或选项格式。"))
                .andExpect(jsonPath("$.errorKey").value("request.typeMismatch"))
                .andExpect(jsonPath("$.details.id").value("id格式不正确，需要填写数字。"));
    }

    @Test
    void method_not_allowed_returns_405() throws Exception {
        mockMvc.perform(put("/test/validated"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.code()))
                .andExpect(jsonPath("$.message").value("该操作方式不受支持，请刷新页面后重试。"))
                .andExpect(jsonPath("$.errorKey").value("request.methodNotAllowed"));
    }

    @Test
    void media_type_not_supported_returns_415() throws Exception {
        mockMvc.perform(post("/test/validated")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=demo"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.code()))
                .andExpect(jsonPath("$.message").value("请求内容类型不受支持，请使用页面表单重新提交。"))
                .andExpect(jsonPath("$.errorKey").value("request.unsupportedMediaType"));
    }

    @Test
    void upload_too_large_returns_413() throws Exception {
        mockMvc.perform(get("/test/oversize"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(ErrorCode.PAYLOAD_TOO_LARGE.code()))
                .andExpect(jsonPath("$.message").value("上传内容超过大小限制，请压缩或拆分后再试。"))
                .andExpect(jsonPath("$.errorKey").value("request.payloadTooLarge"));
    }

    @Test
    void access_denied_returns_forbidden_without_leaking_message() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()))
                .andExpect(jsonPath("$.message").value("当前账号没有权限执行该操作。"))
                .andExpect(jsonPath("$.errorKey").value("auth.forbidden"))
                .andExpect(jsonPath("$.message").value(not(stringContainsInOrder("teacher-only secret"))));
    }

    @Test
    void unknown_exception_does_not_leak_message() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.code()))
                .andExpect(jsonPath("$.message").value("服务内部异常，请稍后重试。"))
                .andExpect(jsonPath("$.errorKey").value("system.internal"))
                .andExpect(jsonPath("$.message").value(not(stringContainsInOrder("secret leak attempt"))));
    }

    @Test
    void async_response_already_closed_is_not_serialized_as_json() throws Exception {
        mockMvc.perform(get("/test/async-response-closed").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @SpringBootApplication
    static class TestApplication {
    }

    @RestController
    @Validated
    static class TestController {
        private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

        @PostMapping("/test/validated")
        public String validated(@RequestBody @Valid Payload payload) {
            return payload.name();
        }

        @GetMapping("/test/bind")
        public String bind(@Valid @ModelAttribute Payload payload) {
            return payload.name();
        }

        @GetMapping("/test/violate")
        public String violate(@RequestParam Long id) {
            Set<ConstraintViolation<ViolationTarget>> violations = VALIDATOR.validateValue(ViolationTarget.class, "id", id);
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException(violations);
            }
            return id.toString();
        }

        @GetMapping("/test/required-param")
        public String required(@RequestParam String token) {
            return token;
        }

        @GetMapping("/test/typed/{id}")
        public String typed(@PathVariable Long id) {
            return id.toString();
        }

        @GetMapping("/test/domain")
        public String domain() {
            throw new DomainException(ErrorCode.CONFLICT, "duplicate");
        }

        @GetMapping("/test/oversize")
        public String oversize() {
            throw new MaxUploadSizeExceededException(1024);
        }

        @GetMapping("/test/access-denied")
        public String accessDenied() {
            throw new AccessDeniedException("teacher-only secret");
        }

        @GetMapping("/test/boom")
        public String boom() {
            throw new RuntimeException("secret leak attempt");
        }

        @GetMapping(value = "/test/async-response-closed", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public String asyncResponseClosed() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("client disconnected");
        }
    }

    record Payload(@NotBlank String name) {
    }

    static class ViolationTarget {
        @Min(1)
        private Long id;
    }
}
