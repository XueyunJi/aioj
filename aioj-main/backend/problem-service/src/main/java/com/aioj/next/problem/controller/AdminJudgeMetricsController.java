package com.aioj.next.problem.controller;
import com.aioj.next.common.api.ApiResponse; import com.aioj.next.problem.domain.JudgeMetricsClient; import org.springframework.security.access.prepost.PreAuthorize; import org.springframework.web.bind.annotation.*; import java.util.Map;
@RestController @RequestMapping("/admin/judge") @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class AdminJudgeMetricsController { private final JudgeMetricsClient client; public AdminJudgeMetricsController(JudgeMetricsClient c){client=c;} @GetMapping("/metrics") public ApiResponse<Map> metrics(){return ApiResponse.ok(client.metrics());} }
