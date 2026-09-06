package com.aioj.next.judge.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.aioj.next.judge.monitor.JudgeRuntimeMonitor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/internal/judge")
public class InternalJudgeMetricsController {
  private final JudgeRuntimeMonitor monitor; private final JudgeWorkerProperties properties;
  public InternalJudgeMetricsController(JudgeRuntimeMonitor monitor, JudgeWorkerProperties properties){this.monitor=monitor;this.properties=properties;}
  @GetMapping("/metrics") public ApiResponse<JudgeRuntimeMonitor.Snapshot> metrics(@RequestHeader(value="X-Internal-Token",required=false) String token){
    String expected=properties.getInternalApiToken(); if(expected==null||token==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return ApiResponse.ok(monitor.snapshot());
  }
}
