package com.aioj.next.problem.config;

import com.aioj.next.common.security.JwtProperties;
import com.aioj.next.common.security.JwtTokenService;
import com.aioj.next.common.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.Callable;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Guards the authenticated-request/async-redispatch contract used by notification SSE. */
@WebMvcTest(controllers = ProblemServiceAsyncSecurityTest.AsyncResponseController.class)
@Import({SecurityConfig.class, ProblemServiceAsyncSecurityTest.AsyncResponseController.class})
@TestPropertySource(properties = "aioj.security.jwt.hmac-secret=problem-service-async-security-test-secret-0123456789")
class ProblemServiceAsyncSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    void initialRequestStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/test/async"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedAsyncRedispatchCompletesAfterInitialAuthorization() throws Exception {
        String token = jwtTokenService.createAccessToken(7L, "student", List.of(Role.STUDENT));

        MvcResult result = mockMvc.perform(get("/test/async")
                        .header("Authorization", "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("ok"));
    }

    @RestController
    public static class AsyncResponseController {
        @GetMapping(value = "/test/async", produces = MediaType.TEXT_PLAIN_VALUE)
        Callable<String> asynchronousResponse() {
            return () -> "ok";
        }
    }

    @SpringBootConfiguration
    @EnableConfigurationProperties({JwtProperties.class, InternalApiProperties.class})
    static class TestApplication {
    }
}
