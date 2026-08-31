package com.aioj.next.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the gateway routing and CORS contract. The gateway is
 * pure configuration, so these tests pin the route surface and the explicit
 * CORS whitelist against accidental edits.
 */
@SpringBootTest
class GatewayRouteAndCorsTest {
    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Autowired
    private Environment environment;

    @Test
    void routeDefinitionsCoverAllBackendServices() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertNotNull(routes);
        Set<String> ids = routes.stream().map(RouteDefinition::getId).collect(Collectors.toSet());
        assertTrue(ids.contains("tutor-service"), "tutor-service route missing");
        assertTrue(ids.contains("tutor-problem-service"), "tutor-problem-service route missing");
        assertTrue(ids.contains("auth-service"), "auth-service route missing");
        assertTrue(ids.contains("problem-service"), "problem-service route missing");
        assertTrue(ids.contains("ai-service"), "ai-service route missing");
    }

    @Test
    void browserTrafficOnlyEntersThroughApiV1Paths() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertNotNull(routes);
        for (RouteDefinition route : routes) {
            String patterns = route.getPredicates().stream()
                    .filter(predicate -> "Path".equals(predicate.getName()))
                    .flatMap(predicate -> predicate.getArgs().values().stream())
                    .collect(Collectors.joining(","));
            assertFalse(patterns.isBlank(), "route " + route.getId() + " has no Path predicate");
            for (String pattern : patterns.split(",")) {
                assertTrue(pattern.trim().startsWith("/api/v1/"),
                        "route " + route.getId() + " exposes non-gateway path " + pattern);
            }
        }
    }

    @Test
    void authRoutesKeepUserAndDiagnosticsPaths() {
        String patterns = pathPatterns("auth-service");
        assertTrue(patterns.contains("/api/v1/auth/**"), patterns);
        assertTrue(patterns.contains("/api/v1/users/**"), patterns);
        assertTrue(patterns.contains("/api/v1/admin/users/**"), patterns);
        assertTrue(patterns.contains("/api/v1/admin/roles/**"), patterns);
        assertTrue(patterns.contains("/api/v1/diagnostics/**"), patterns);
    }

    @Test
    void tutorRoutePreservesTutorApiPrefix() {
        String patterns = pathPatterns("tutor-service");
        assertEquals("/api/v1/tutor/**", patterns);
    }

    @Test
    void tutorProblemRoutesGoToProblemService() {
        String patterns = pathPatterns("tutor-problem-service");
        assertTrue(patterns.contains("/api/v1/tutor/capabilities"), patterns);
        assertTrue(patterns.contains("/api/v1/tutor/problems"), patterns);
    }

    @Test
    void tutorRouteDefaultsToHostTutorService() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertNotNull(routes);
        RouteDefinition tutor = routes.stream()
                .filter(route -> "tutor-service".equals(route.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tutor-service route missing"));
        assertEquals("http://host.docker.internal:8600", tutor.getUri().toString());
    }

    @Test
    void problemAndAiRoutesKeepContestAndDraftPaths() {
        String problem = pathPatterns("problem-service");
        assertTrue(problem.contains("/api/v1/problems/**"), problem);
        assertTrue(problem.contains("/api/v1/submissions/**"), problem);
        assertTrue(problem.contains("/api/v1/contests/**"), problem);
        assertTrue(problem.contains("/api/v1/notifications/**"), problem);
        String ai = pathPatterns("ai-service");
        assertTrue(ai.contains("/api/v1/ai/**"), ai);
        assertTrue(ai.contains("/api/v1/admin/problem-drafts/**"), ai);
    }

    @Test
    void globalCorsUsesExplicitOriginWhitelistWithCredentials() {
        String allowCredentials = environment.getProperty(
                "spring.cloud.gateway.globalcors.cors-configurations.[/**].allow-credentials");
        assertEquals("true", allowCredentials);
        String origins = environment.getProperty(
                "spring.cloud.gateway.globalcors.cors-configurations.[/**].allowed-origins");
        assertNotNull(origins);
        assertFalse(origins.contains("*"), "CORS must stay an explicit whitelist, got: " + origins);
    }

    private String pathPatterns(String routeId) {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertNotNull(routes);
        return routes.stream()
                .filter(route -> routeId.equals(route.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("route " + routeId + " missing"))
                .getPredicates().stream()
                .filter(predicate -> "Path".equals(predicate.getName()))
                .flatMap(predicate -> predicate.getArgs().values().stream())
                .collect(Collectors.joining(","));
    }
}
