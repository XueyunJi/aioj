package com.aioj.next.problem.domain;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import java.util.Map;
@Component public class JudgeMetricsClient {
 private final RestClient client; private final String token;
 public JudgeMetricsClient(@Value("${JUDGE_WORKER_URI:http://judge-worker:8203}") String uri,@Value("${aioj.internal.api-token}") String token){this.client=RestClient.builder().baseUrl(uri).build();this.token=token;}
 public Map metrics(){ Map body=client.get().uri("/api/v1/internal/judge/metrics").header("X-Internal-Token",token).retrieve().body(Map.class); if(body!=null&&body.get("data") instanceof Map data)return data; return body; }
}
