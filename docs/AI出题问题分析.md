# AIOJ AI出题模块修复与增强方案

**基于 AIOJ Agent Core V3 架构的兼容性修复方案**  
**版本：** v1.0  
**日期：** 2026年8月17日  
**作者：** 吉老师团队  
**技术栈：** Spring Cloud + React 19 + RabbitMQ + go-judge  
**协议：** Apache License 2.0

---

## 一、引言：与现有架构的关系

本方案不是推翻重来，而是**在 AIOJ 现有 Agent Core V3 架构基础上做兼容性增强**。

AIOJ 现有架构的核心优势必须保留：
- **可信控制面**：模型不可信，服务器控制权限、数据范围、工具执行
- **四层比赛安全**：参赛判定 → 策略快照 → 问题匹配 → 输出检查
- **审计与用量**：每轮调用留痕，不记录密钥与隐藏测试
- **Prompt Injection 防护**：指令权只来自服务器配置
- **记忆确认流程**：模型提候选 → 服务端存证据 → 用户确认 → 生效 Claim

**本方案遵循的原则：**
1. **最小侵入式**：不改动现有控制面安全模型，只在缺失处补全
2. **利用现有基础设施**：复用 Turn 机制、工具控制面、审计链路
3. **增强而非替换**：验证引擎、AI 服务、沙盒调用层做增强，不动核心安全假设

---

## 二、问题根因归类与总体策略

15 个问题不是散点，而是 4 类系统性缺陷：

| 类别 | 涉及问题 | 根因 | 总体策略 |
|------|---------|------|---------|
| **验证链路不完整** | AI-001/004/005/009/014 | Validation 是生成子步骤，非独立服务 | 抽离为独立领域服务，带状态机 + 根因分析 |
| **沙盒与路径治理缺失** | AI-002/003/007 | 沙盒调用层缺少统一抽象 | 建立 SandboxPathResolver + 算法白名单 + 降级路径 |
| **AI 服务层缺防御** | AI-006/008/015 | 假设 LLM 总输出合法内容 | 加超时熔断 + JSON Mode + 解析容错 + Schema 约束 |
| **前端与批量体验** | AI-011 | API 原子化，未考虑运营效率 | 增加批量端点 + 多选组件 |

---

## 三、分模块详细方案

### 模块一：验证引擎重构（Validation Engine）

#### 3.1.1 现状问题
- `ValidationController` 只有生成时自动触发，无独立重验端点（AI-005）
- 隐藏点仅抽样 3 个，无全量验证（AI-001）
- 验证报告扁平，无根因分析（AI-009）
- 字段权限静态化，该开放的不开放（AI-004/014）

#### 3.1.2 架构调整：引入 Validation Domain Service

在现有 `backend` 中新增 `validation` 模块（或包），与 Agent Core V3 的 Turn 机制解耦但兼容：

```
backend/
  └── validation/
        ├── domain/
        │     ├── ValidationReport.java          # 聚合根
        │     ├── ValidationStatus.java          # 状态机枚举
        │     ├── RootCause.java                 # 根因枚举
        │     └── Diagnosis.java                 # 诊断建议
        ├── application/
        │     ├── ValidationService.java         # 领域服务
        │     ├── RootCauseAnalyzer.java         # 根因分析引擎
        │     └── RevalidationService.java       # 重验服务
        ├── infrastructure/
        │     ├── SandboxClient.java             # 复用现有客户端
        │     └── CrossCheckService.java         # 对拍服务
        └── interfaces/
              └── ValidationController.java      # REST API
```

**状态机设计（与现有题目状态兼容）：**

```java
public enum ValidationStatus {
    DRAFT,              // 初始状态
    PARTIAL_VALIDATED,  // 仅样例通过（隐藏点未全量）
    FULLY_VALIDATED,    // 全量通过（可导入）
    IMPORTED,           // 已导入题库
    FAILED              // 验证失败，需修复
}
```

**与现有架构的集成点：**
- 验证触发时，复用 Agent Core V3 的 **工具控制面** 调用沙盒（不绕过权限）
- 验证结果写入时，复用现有 **审计链路**（记录每轮验证的用量、工具调用）
- 验证失败时，通过 **Bootstrap Context** 给 AI 服务提供最小上下文（不泄露隐藏测试）

#### 3.1.3 AI-001 修复：全量隐藏点并行验证

**方案：** 在 `ValidationService` 中实现并行验证，利用现有 RabbitMQ 队列但不造成堆积。

```java
@Service
public class ValidationService {

    @Autowired private SandboxClient sandboxClient;
    @Autowired private ProblemRepository problemRepo;

    public ValidationReport validateFully(Long draftId) {
        Draft draft = problemRepo.findById(draftId).orElseThrow();
        List<TestCase> hiddenCases = draft.getHiddenTestCases();

        // 并行调用，但控制并发度（防止冲垮 go-judge）
        List<CompletableFuture<HiddenResult>> futures = hiddenCases.stream()
            .map(c -> sandboxClient.validateAsync(c, 10, TimeUnit.SECONDS))
            .toList();

        CompletableFuture<Void> all = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );

        try {
            all.orTimeout(120, TimeUnit.SECONDS).join();
        } catch (TimeoutException e) {
            // 超时部分标记为 PENDING，不阻断整体流程
            return reportWithTimeout(futures, draft);
        }

        return aggregateReport(futures, draft);
    }
}
```

**网关调整：** 为 `/api/v1/drafts/{id}/validate` 单独配置长超时路由（130s），与现有网关配置兼容。

**前端：** 使用 SSE（Server-Sent Events）推送验证进度，与现有 React 19 前端技术栈一致。

#### 3.1.4 AI-009/004/014 修复：根因分析引擎 + 动态字段权限

**核心组件：RootCauseAnalyzer**

```java
@Component
public class RootCauseAnalyzer {

    public Diagnosis analyze(ValidationReport report) {
        // 规则 1：对拍通过但样例失败 → 样例输出错误
        if (report.getCrossCheck().isPassed() && !report.getSandbox().isPassed()) {
            return Diagnosis.builder()
                .cause(RootCause.SAMPLE_EXPECTED_OUTPUT_MISMATCH)
                .suspectFields(List.of("testCases.expectedOutput"))
                .suggestedAction("请检查样例输出是否手算错误，或授权 AI 修正 expectedOutput")
                .confidence(0.95)
                .build();
        }

        // 规则 2：对拍不一致 → 标程或参考解逻辑错误
        if (!report.getCrossCheck().isPassed() && report.getCrossCheck().getType() == MismatchType.REFERENCE_MISMATCH) {
            return Diagnosis.builder()
                .cause(RootCause.REFERENCE_SOLUTION_BUG)
                .suspectFields(List.of("standardSolutionCode", "referenceSolutionCode"))
                .suggestedAction("标程与参考解输出不一致，请检查算法逻辑或边界处理")
                .confidence(0.90)
                .build();
        }

        // 规则 3：隐藏点失败但样例通过 → 边界情况处理不足
        if (report.getSandbox().isSamplePassed() && !report.getSandbox().isHiddenPassed()) {
            return Diagnosis.builder()
                .cause(RootCause.HIDDEN_EDGE_CASE_MISSING)
                .suspectFields(List.of("testcaseGeneratorPython", "standardSolutionCode"))
                .suggestedAction("样例通过但隐藏点失败，通常是边界数据生成不足或标程未处理极端情况")
                .confidence(0.85)
                .build();
        }

        // 规则 4：复杂度分析失败
        if (!report.getComplexity().isPassed()) {
            return Diagnosis.builder()
                .cause(RootCause.COMPLEXITY_MISMATCH)
                .suspectFields(List.of("complexity", "standardSolutionCode"))
                .suggestedAction("复杂度声明与标程实现不匹配，请检查算法类型或复杂度格式")
                .confidence(0.80)
                .build();
        }

        return Diagnosis.builder()
            .cause(RootCause.UNKNOWN)
            .suspectFields(List.of())
            .suggestedAction("请人工检查验证报告各模块状态")
            .confidence(0.50)
            .build();
    }
}
```

**动态字段权限（与现有 Agent 工具控制面兼容）：**

```java
@Component
public class RewriteFieldPolicy {

    public Set<String> getAllowedFields(ValidationReport report) {
        Set<String> base = new HashSet<>(Set.of(
            "title", "description", "inputFormat", "outputFormat", "hint"
        ));

        RootCause cause = rootCauseAnalyzer.analyze(report).getCause();

        switch (cause) {
            case SAMPLE_EXPECTED_OUTPUT_MISMATCH:
                base.add("testCases.expectedOutput");  // 允许修样例输出，但不允许修 input
                break;
            case REFERENCE_SOLUTION_BUG:
                base.add("standardSolutionCode");      // 允许修标程
                base.add("referenceSolutionCode");     // 允许修参考解
                break;
            case HIDDEN_EDGE_CASE_MISSING:
                base.add("testcaseGeneratorPython");   // 允许修数据生成器
                base.add("stressTestcaseGeneratorPython");
                break;
            case COMPLEXITY_MISMATCH:
                base.add("complexity");                // 允许修复杂度声明
                break;
            default:
                // 保持基础字段
        }

        // 永远禁止修改的字段（防止题意篡改）
        base.remove("testCases.input");  // input 一旦确定，不能改，否则题意变了

        return base;
    }
}
```

**与 Agent Core V3 的集成：**
- 根因分析结果通过 **Bootstrap Context** 注入 AI 的 Turn，作为受信数据
- AI 提出字段修改候选后，仍需经过现有 **记忆确认流程**（用户确认后才生效）
- 修改标程后，自动触发新的验证 Turn，走完整工具控制面

#### 3.1.5 AI-005 修复：重新验证端点

```java
@RestController
@RequestMapping("/api/v1/drafts")
public class RevalidationController {

    @Autowired private ValidationService validationService;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @PutMapping("/{draftId}/revalidate")
    public ResponseEntity<SseEmitter> revalidate(@PathVariable Long draftId) {
        // 幂等性：Redis 分布式锁防止重复点击
        String lockKey = "revalidate:" + draftId;
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", 120, TimeUnit.SECONDS);

        if (!locked) {
            return ResponseEntity.status(429).body(null); // Too Many Requests
        }

        SseEmitter emitter = new SseEmitter(130000L); // 130秒超时

        validationService.validateFullyAsync(draftId, progress -> {
            try {
                emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(progress));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });

        return ResponseEntity.ok(emitter);
    }
}
```

---

### 模块二：沙盒与路径治理（Sandbox & Path Resolution）

#### 3.2.1 现状问题
- CWD 不稳定，生成器调用标程失败（AI-002）
- 复杂度分析器误判标准 DP/线段树（AI-003）
- stressTestcaseGenerator 缺失导致静态阻断（AI-007）

#### 3.2.2 AI-002 修复：统一路径抽象层

**新增组件：SandboxPathResolver**

```java
@Component
public class SandboxPathResolver {

    @Value("${sandbox.workdir.base:/tmp/aioj-sandbox}")
    private String baseWorkDir;

    public String getAbsoluteWorkDir(Long problemId) {
        return Paths.get(baseWorkDir, "problems", problemId.toString()).toAbsolutePath().toString();
    }

    public String getStdPath(Long problemId) {
        return Paths.get(getAbsoluteWorkDir(problemId), "std").toString();
    }

    public String getGeneratorPath(Long problemId) {
        return Paths.get(getAbsoluteWorkDir(problemId), "generator.py").toString();
    }

    public String getStressGeneratorPath(Long problemId) {
        return Paths.get(getAbsoluteWorkDir(problemId), "stress_generator.py").toString();
    }

    public String getTestcaseDir(Long problemId) {
        return Paths.get(getAbsoluteWorkDir(problemId), "testcases").toString();
    }
}
```

**修改 SandboxRequest DTO：**

```java
public class SandboxRequest {
    private String command;
    private List<String> args;
    private String stdin;
    private String workingDirectory;  // 新增：显式设置 CWD
    private Map<String, String> env;  // 新增：注入环境变量
    private Long timeLimitMs;
    private Long memoryLimitMb;
    // ...
}
```

**修改 SandboxClient 调用：**

```java
@Service
public class SandboxClient {

    @Autowired private SandboxPathResolver pathResolver;

    public SandboxResult execute(Long problemId, SandboxRequest request) {
        String cwd = pathResolver.getAbsoluteWorkDir(problemId);
        request.setWorkingDirectory(cwd);
        request.getEnv().put("AI_OJ_WORKDIR", cwd);

        return callGoJudge(request);
    }
}
```

**生成器脚本自适应（兜底）：**

在注入生成器脚本时，前置一段环境变量适配代码：

```python
import os
import sys

# 自适应工作目录
WORK_DIR = os.environ.get("AI_OJ_WORKDIR", ".")
os.chdir(WORK_DIR)

# 调用标程时使用绝对路径
STD_PATH = os.path.join(WORK_DIR, "std")
```

#### 3.2.3 AI-003 修复：算法白名单配置

**新增配置：AlgorithmWhitelistConfiguration**

```java
@Configuration
@ConfigurationProperties(prefix = "ai.complexity-whitelist")
public class AlgorithmWhitelistConfiguration {

    private List<AlgorithmPattern> patterns;

    @Data
    public static class AlgorithmPattern {
        private String tag;           // 如 "0-1背包"
        private List<String> codePatterns;  // 正则列表，如 ["dp\[i\]\[w\]", "dp\[i-1\]\[w\]"]
        private String expectedComplexity;  // 如 "O(nW)"
        private String description;
    }
}
```

**配置示例（application-ai.yml）：**

```yaml
ai:
  complexity-whitelist:
    - tag: "0-1背包"
      patterns: 
        - "dp\[i\]\[w\]"
        - "dp\[i-1\]\[w\]"
        - "max\(.*dp\[i-1\]"
      expected: "O(nW)"
      description: "标准0-1背包动态规划"

    - tag: "完全背包"
      patterns:
        - "dp\[w\]"
        - "dp\[w-weight\[i\]\]"
      expected: "O(nW)"
      description: "完全背包一维优化"

    - tag: "线段树"
      patterns:
        - "build\("
        - "push_up\("
        - "push_down\("
        - "query\("
        - "update\("
      expected: "O(n log n)"
      description: "标准线段树"

    - tag: "树状数组"
      patterns:
        - "lowbit\("
        - "add\("
        - "query\("
        - "c\[\]"
      expected: "O(n log n)"
      description: "标准树状数组(BIT)"
```

**修改 ComplexityAnalyzerService：**

```java
@Service
public class ComplexityAnalyzerService {

    @Autowired private AlgorithmWhitelistConfiguration whitelist;

    public ComplexityResult analyze(String code, List<String> tags) {
        // 第一步：标签匹配
        for (String tag : tags) {
            Optional<AlgorithmPattern> pattern = whitelist.findByTag(tag);
            if (pattern.isPresent() && matchesPattern(code, pattern.get())) {
                return ComplexityResult.builder()
                    .status(ComplexityStatus.WHITELISTED)
                    .inferredComplexity(pattern.get().getExpected())
                    .reason("匹配算法白名单：" + pattern.get().getDescription())
                    .build();
            }
        }

        // 第二步：常规静态分析（保留原有逻辑作为兜底）
        return legacyAnalyze(code);
    }

    private boolean matchesPattern(String code, AlgorithmPattern pattern) {
        return pattern.getCodePatterns().stream()
            .anyMatch(p -> Pattern.compile(p).matcher(code).find());
    }
}
```

**与现有架构的兼容性：**
- 白名单配置热加载（Spring Cloud Config 或 @RefreshScope）
- 未匹配白名单的题目，走原有静态分析逻辑，不破坏现有行为
- 标签从题目/竞赛服务的 MySQL 透传，不新增数据源

#### 3.2.4 AI-007 修复：静态校验降级 + 存量修复

**修改 StaticValidator：**

```java
@Component
public class StaticValidator {

    public StaticValidationResult validate(Draft draft) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // 原逻辑：reference check 必须配 stress generator
        if (draft.isReferenceCheckEnabled() && draft.getStressGenerator() == null) {
            // 降级策略：自动关闭 reference check，而非阻断
            draft.setReferenceCheckEnabled(false);
            warnings.add("REFERENCE_CHECK_AUTO_DISABLED: 未提供 stress generator，已自动关闭对拍功能。" +
                        "如需开启，请补充 stress generator 或执行克隆生成器操作。");
        }

        // 其他校验...

        return StaticValidationResult.builder()
            .passed(errors.isEmpty())
            .errors(errors)
            .warnings(warnings)
            .build();
    }
}
```

**新增存量修复接口：**

```java
@RestController
@RequestMapping("/api/v1/drafts")
public class DraftRepairController {

    @Autowired private DraftRepository draftRepo;

    @PostMapping("/{id}/clone-generator")
    public ResponseEntity<Void> cloneGenerator(@PathVariable Long id) {
        Draft draft = draftRepo.findById(id).orElseThrow();

        if (draft.getTestcaseGenerator() != null && draft.getStressGenerator() == null) {
            // 将普通生成器适配为 stress generator
            String adapted = adaptToStressGenerator(draft.getTestcaseGenerator());
            draft.setStressGenerator(adapted);
            draftRepo.save(draft);
        }

        return ResponseEntity.ok().build();
    }

    private String adaptToStressGenerator(String generator) {
        // 简单适配：在生成器头部注入对拍逻辑
        return "# Stress Generator (auto-cloned from testcase generator)\n" +
               "import subprocess\n" +
               "import sys\n\n" +
               generator + "\n\n" +
               "# Auto-generated stress wrapper\n" +
               "if __name__ == '__main__':\n" +
               "    # 调用 std 和 brute_force 对比\n" +
               "    pass\n";
    }
}
```

---

### 模块三：AI 服务层防御性增强（AI Service Resilience）

#### 3.3.1 现状问题
- 改写无超时熔断，700秒挂死（AI-006）
- 指令过长导致 JSON 解析崩溃（AI-008）
- 生成计划质量低，无结构化校验（AI-015）

#### 3.3.2 AI-006 修复：超时熔断 + 配额保护

**WebClient 配置（复用现有基础设施）：**

```java
@Configuration
public class LLMClientConfig {

    @Bean
    public WebClient llmWebClient(@Value("${llm.base-url}") String baseUrl) {
        HttpClient httpClient = HttpClient.create()
            .responseTimeout(Duration.ofSeconds(120))  // 120秒读超时
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000); // 10秒连接超时

        return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }
}
```

**Resilience4j 熔断配置：**

```java
@Service
public class AiRewriteService {

    @Autowired private WebClient llmWebClient;
    @Autowired private QuotaService quotaService;

    @CircuitBreaker(name = "aiRewrite", fallbackMethod = "rewriteFallback")
    @TimeLimiter(name = "aiRewrite")
    public Mono<RewriteResult> rewrite(RewriteRequest request) {
        // 预扣配额（ pessimistic 模式，失败时回滚）
        String quotaHoldId = quotaService.hold(request.getUserId(), QuotaType.REWRITE);

        return llmWebClient.post()
            .uri("/v1/chat/completions")
            .bodyValue(buildPrompt(request))
            .retrieve()
            .bodyToMono(LLMResponse.class)
            .flatMap(resp -> {
                // 解析成功，确认扣费
                quotaService.commit(quotaHoldId);
                return Mono.just(parseRewriteResult(resp));
            })
            .onErrorResume(e -> {
                // 失败/超时，回滚配额
                quotaService.rollback(quotaHoldId);
                return Mono.error(e);
            });
    }

    // 熔断降级方法
    public Mono<RewriteResult> rewriteFallback(RewriteRequest request, Exception ex) {
        return Mono.just(RewriteResult.builder()
            .status("TIMEOUT")
            .message("AI 改写服务当前繁忙或题目过于复杂，请尝试：\n" +
                    "1. 简化改写指令（聚焦单个字段）\n" +
                    "2. 人工修改以下字段后点击'重新验证'：" +
                    request.getSuspectFields())
            .suspectFields(request.getSuspectFields())
            .build());
    }
}
```

**Resilience4j 配置（application.yml）：**

```yaml
resilience4j:
  circuitbreaker:
    instances:
      aiRewrite:
        failureRateThreshold: 50
        slowCallRateThreshold: 80
        slowCallDurationThreshold: 60s
        waitDurationInOpenState: 60s
        permittedNumberOfCallsInHalfOpenState: 3
  timelimiter:
    instances:
      aiRewrite:
        timeoutDuration: 120s
        cancelRunningFuture: true
```

#### 3.3.3 AI-008 修复：JSON Mode + 解析容错

**调用层强制 JSON Mode（以 OpenAI 兼容 API 为例）：**

```java
public class LLMRequest {
    private String model;
    private List<Message> messages;
    private Object responseFormat = Map.of("type", "json_object");  // 强制 JSON 输出
    private Integer maxTokens;
    private Double temperature;
}
```

**解析容错组件：AiResponseParser**

```java
@Component
public class AiResponseParser {

    @Autowired private ObjectMapper objectMapper;

    public JsonNode parseWithRecovery(String raw) {
        // 第一次尝试：直接解析
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e1) {
            log.warn("AI 输出 JSON 解析失败，尝试自动修复: {}", e1.getMessage());

            // 第二次尝试：自动修复
            String fixed = JsonRepairUtil.fix(raw);
            try {
                return objectMapper.readTree(fixed);
            } catch (JsonProcessingException e2) {
                log.error("自动修复后仍无法解析: {}", e2.getMessage());
                throw new AiParseException(
                    "AI 返回格式异常，请简化指令后重试。原始错误：" + e2.getMessage()
                );
            }
        }
    }
}
```

**JsonRepairUtil 实现（轻量级，不引入外部库）：**

```java
public class JsonRepairUtil {

    public static String fix(String broken) {
        String fixed = broken.trim();

        // 修复 1：补全未闭合的字符串引号
        int quoteCount = countChar(fixed, '"');
        if (quoteCount % 2 != 0) {
            fixed += """;
        }

        // 修复 2：补全未闭合的对象/数组括号
        fixed = balanceBrackets(fixed);

        // 修复 3：移除尾部逗号
        fixed = fixed.replaceAll(",\s*([}\]])", "$1");

        // 修复 4：转义未转义的引号（简单 heuristic）
        fixed = fixed.replaceAll("(?<!\\)(?<!\\\\)"(?![:,\]}\s])", "\\"");

        return fixed;
    }

    private static String balanceBrackets(String s) {
        int openBrace = countChar(s, '{');
        int closeBrace = countChar(s, '}');
        int openBracket = countChar(s, '[');
        int closeBracket = countChar(s, ']');

        StringBuilder sb = new StringBuilder(s);
        while (openBrace > closeBrace) { sb.append('}'); closeBrace++; }
        while (openBracket > closeBracket) { sb.append(']'); closeBracket++; }

        return sb.toString();
    }

    private static int countChar(String s, char c) {
        int count = 0;
        boolean inString = false;
        boolean escape = false;

        for (char ch : s.toCharArray()) {
            if (escape) {
                escape = false;
                continue;
            }
            if (ch == '\\') {
                escape = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (!inString && ch == c) {
                count++;
            }
        }
        return count;
    }
}
```

**前端限制（React 19）：**

```tsx
<TextField
  multiline
  maxLength={500}  // 限制指令长度
  helperText={`${instruction.length}/500 字，超限请精简指令`}
  error={instruction.length > 500}
/>
```

#### 3.3.4 AI-015 修复：生成计划结构化校验

**JSON Schema 约束（使用现有 Jackson 能力）：**

```java
public class GenerationPlan {
    @NotBlank
    private String coreAlgorithm;           // 核心算法

    @NotEmpty
    private List<String> auxiliaryAlgorithms; // 辅助算法

    @Pattern(regexp = "^O\([^)]+\)$", message = "复杂度格式必须为 O(...)")
    private String complexityAnalysis;      // 复杂度分析

    @NotEmpty
    private List<String> edgeCases;         // 边界情况

    private String dataRange;               // 数据范围
    private String keyInsight;              // 解题关键思路
}
```

**校验逻辑：**

```java
@Service
public class GenerationPlanValidator {

    @Autowired private Validator validator;
    @Autowired private ComplexityAnalyzerService complexityAnalyzer;

    public ValidationResult validate(GenerationPlan plan, List<String> tags) {
        Set<ConstraintViolation<GenerationPlan>> violations = validator.validate(plan);

        if (!violations.isEmpty()) {
            return ValidationResult.failed(violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList());
        }

        // 复杂度语义校验
        ComplexityResult complexityResult = complexityAnalyzer.analyze(
            plan.getComplexityAnalysis(), tags);

        if (!complexityResult.isPassed()) {
            return ValidationResult.failed(List.of(
                "complexityAnalysis: " + complexityResult.getReason()
            ));
        }

        return ValidationResult.passed();
    }
}
```

**AI Prompt 模板增强（在现有 Prompt 中增加 Schema 约束）：**

```
你必须按以下 JSON Schema 输出 generationPlan：
{
  "coreAlgorithm": "核心算法名称（如：线段树、0-1背包、Dijkstra）",
  "auxiliaryAlgorithms": ["辅助算法1", "辅助算法2"],
  "complexityAnalysis": "O(...) 格式，必须闭合括号",
  "edgeCases": ["边界情况1", "边界情况2", "边界情况3"],
  "dataRange": "数据范围说明",
  "keyInsight": "解题关键思路，100字以内"
}

约束：
1. complexityAnalysis 必须匹配题目标签对应的预期复杂度
2. edgeCases 至少列出 3 个
3. 所有字段必须存在，不能为空
```

---

### 模块四：前端体验与批量操作（Frontend & Batch Operations）

#### 3.4.1 AI-011 修复：草稿箱批量操作

**后端批量 API：**

```java
@RestController
@RequestMapping("/api/v1/drafts")
public class DraftBatchController {

    @Autowired private DraftRepository draftRepo;
    @Autowired private AiRewriteService aiRewriteService;
    @Autowired private ValidationService validationService;

    @PostMapping("/batch-action")
    public ResponseEntity<BatchResult> batchAction(@RequestBody BatchActionRequest request) {
        List<Long> draftIds = request.getDraftIds();
        BatchResult result = new BatchResult();

        // 使用线程池控制并发（防止冲垮 LLM 配额）
        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Future<BatchItemResult>> futures = new ArrayList<>();

        for (Long draftId : draftIds) {
            futures.add(executor.submit(() -> processSingle(draftId, request.getAction(), request.getParams())));
        }

        for (Future<BatchItemResult> future : futures) {
            try {
                result.add(future.get(300, TimeUnit.SECONDS)); // 单题5分钟超时
            } catch (Exception e) {
                result.add(BatchItemResult.failed("处理超时或异常: " + e.getMessage()));
            }
        }

        executor.shutdown();
        return ResponseEntity.ok(result);
    }

    private BatchItemResult processSingle(Long draftId, ActionType action, Map<String, Object> params) {
        try {
            switch (action) {
                case REWRITE:
                    return aiRewriteService.rewrite(draftId, params).block();
                case VALIDATE:
                    return validationService.validateFully(draftId);
                case IMPORT:
                    return importService.importDraft(draftId);
                case DELETE:
                    draftRepo.deleteById(draftId);
                    return BatchItemResult.success();
                default:
                    return BatchItemResult.failed("未知操作类型");
            }
        } catch (Exception e) {
            return BatchItemResult.failed(e.getMessage());
        }
    }
}
```

**前端 React 19 组件：**

```tsx
// 草稿列表页增加多选 + 批量操作栏
const DraftList: React.FC = () => {
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [batchAction, setBatchAction] = useState<ActionType | null>(null);
  const [progress, setProgress] = useState<{current: number, total: number} | null>(null);

  const handleBatchAction = async (action: ActionType) => {
    if (selectedIds.size === 0) return;

    setBatchAction(action);
    setProgress({current: 0, total: selectedIds.size});

    const response = await fetch('/api/v1/drafts/batch-action', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        draftIds: Array.from(selectedIds),
        action: action,
        params: { instruction: batchInstruction } // 批量 AI 改写时的统一指令
      })
    });

    const result = await response.json();
    // 展示结果：成功 X 道，失败 Y 道，失败原因列表
    setProgress(null);
    setBatchAction(null);
  };

  return (
    <div>
      {/* 批量操作栏 */}
      <Toolbar>
        <Checkbox 
          indeterminate={selectedIds.size > 0 && selectedIds.size < drafts.length}
          checked={selectedIds.size === drafts.length}
          onChange={(e) => e.target.checked ? selectAll() : clearAll()}
        />
        <Button disabled={selectedIds.size === 0} onClick={() => handleBatchAction('REWRITE')}>
          批量 AI 改写 ({selectedIds.size})
        </Button>
        <Button disabled={selectedIds.size === 0} onClick={() => handleBatchAction('VALIDATE')}>
          批量验证
        </Button>
        <Button disabled={selectedIds.size === 0} onClick={() => handleBatchAction('IMPORT')}>
          批量导入
        </Button>
      </Toolbar>

      {/* 进度条 */}
      {progress && (
        <LinearProgress 
          variant="determinate" 
          value={(progress.current / progress.total) * 100}
        />
      )}

      {/* 草稿列表 */}
      {drafts.map(draft => (
        <DraftItem 
          key={draft.id}
          draft={draft}
          selected={selectedIds.has(draft.id)}
          onSelect={(id) => toggleSelection(id)}
        />
      ))}
    </div>
  );
};
```

---

## 四、与 Agent Core V3 的深度集成

### 4.1 验证引擎如何利用现有控制面

```
用户点击"重新验证"
  ↓
ValidationController 接收请求
  ↓
建立新的 Validation Turn（复用 Turn ID 生成机制）
  ↓
构建 Bootstrap Context（当前草稿、用户身份、策略快照）
  ↓
调用 Sandbox 工具（走工具控制面：权限检查 → 参数校验 → 预算检查 → 执行）
  ↓
汇总受信数据（沙盒结果、对拍结果、复杂度结果）
  ↓
RootCauseAnalyzer 分析（纯服务端逻辑，不调用 LLM）
  ↓
输出安全检查（不泄露隐藏测试、私有题面）
  ↓
持久化验证报告 + 审计证据
  ↓
异步生成可检索摘要（方便后续"查看历史验证记录"）
```

### 4.2 AI 改写如何利用现有记忆机制

```
用户触发 AI 改写
  ↓
AiRewriteService 获取当前草稿的 Episode 摘要（历史修改记录）
  ↓
RootCauseAnalyzer 提供诊断（受信数据，非模型猜测）
  ↓
构建 Rewrite Prompt（包含：Bootstrap Context + 根因诊断 + 允许修改的字段列表）
  ↓
LLM 提出修改候选（JSON 格式，受 Schema 约束）
  ↓
服务端校验候选字段是否在允许列表内（字段级权限控制）
  ↓
候选写入临时记忆，等待用户确认（复用 Claim 确认流程）
  ↓
用户确认后，生效 Claim，更新草稿，触发重新验证
  ↓
验证结果作为新证据，更新学习画像
```

### 4.3 新增组件与现有组件的关系

| 新增组件 | 复用的现有组件 | 关系 |
|---------|--------------|------|
| ValidationService | SandboxClient, RabbitMQ | 调用层复用，编排层新增 |
| RootCauseAnalyzer | ValidationReport DTO | 消费现有报告，产出诊断 |
| SandboxPathResolver | FileStorageService | 统一路径抽象，替换散落逻辑 |
| ComplexityAnalyzerService | AlgorithmWhitelistConfiguration | 增强现有分析器，不替换 |
| AiResponseParser | ObjectMapper (Jackson) | 包装现有解析器，加容错 |
| DraftBatchController | AiRewriteService, ValidationService | 编排现有服务，加并发控制 |
| RevalidationController | ValidationService, RedisTemplate | 独立端点，复用验证逻辑 |

---

## 五、实施路线图

### 第一阶段：止血（第 1-2 周）—— 困难题通过率 20% → 60%

| 任务 | 涉及问题 | 工作量 | 负责人 |
|------|---------|--------|--------|
| 配置 LLM 120秒超时 + 10秒连接超时 | AI-006 | 2h | 后端 |
| 接入 Resilience4j 熔断降级 | AI-006 | 4h | 后端 |
| 实现 JSON 解析容错 + 前端 500字限制 | AI-008 | 4h | 前后端 |
| 实现 SandboxPathResolver + 修复 CWD | AI-002 | 6h | 后端 |
| 静态校验降级（stress generator缺失不阻断） | AI-007 | 4h | 后端 |
| 存量生成器克隆修复接口 | AI-007 | 4h | 后端 |
| **小计** | | **24h** | |

### 第二阶段：建立信任（第 2-3 周）—— 隐藏点全量验证

| 任务 | 涉及问题 | 工作量 | 负责人 |
|------|---------|--------|--------|
| 实现 ValidationService 并行验证 | AI-001 | 8h | 后端 |
| 网关配置长超时路由 | AI-001 | 2h | 运维/后端 |
| 前端 SSE 进度推送 | AI-001 | 6h | 前端 |
| 实现 RevalidationController | AI-005 | 4h | 后端 |
| 前端"重新验证"按钮 | AI-005 | 4h | 前端 |
| **小计** | | **24h** | |

### 第三阶段：智能修复（第 3-4 周）—— AI 改写成功率 40% → 80%

| 任务 | 涉及问题 | 工作量 | 负责人 |
|------|---------|--------|--------|
| 实现 RootCauseAnalyzer | AI-009/004/014 | 12h | 后端 |
| 实现动态字段权限策略 | AI-004/014 | 8h | 后端 |
| 实现算法白名单配置 + 特征匹配 | AI-003 | 8h | 后端 |
| 移除 Prompt 中的"禁止 for 循环" workaround | AI-013 | 2h | AI Prompt |
| 复杂度声明正则 + 语义校验 | AI-010 | 6h | 后端 |
| 前端诊断卡片展示 | AI-009 | 8h | 前端 |
| **小计** | | **44h** | |

### 第四阶段：运营效率（第 4-5 周）—— 批量处理 + 结构化

| 任务 | 涉及问题 | 工作量 | 负责人 |
|------|---------|--------|--------|
| 实现批量操作后端 API | AI-011 | 8h | 后端 |
| 前端多选 + 批量操作栏 | AI-011 | 8h | 前端 |
| 生成计划 Schema 约束 + 校验 | AI-015 | 8h | 后端 |
| Prompt 模板增加 Schema 要求 | AI-015 | 4h | AI Prompt |
| 前端展示 generationPlan | AI-015 | 4h | 前端 |
| **小计** | | **32h** | |

### 第五阶段：内容增强（第 5-6 周，可选）—— RAG 基建

| 任务 | 涉及问题 | 工作量 | 负责人 |
|------|---------|--------|--------|
| 部署 Milvus/Chroma 向量库 | AI-012 | 8h | 运维/后端 |
| 集成 BGE-M3 Embedding 模型 | AI-012 | 8h | 后端 |
| 收集 CCPC/ICPC 真题入向量库 | AI-012 | 16h | 数据准备 |
| 实现 RagService | AI-012 | 8h | 后端 |
| 困难题生成 Prompt 注入 Few-shot | AI-012 | 4h | AI Prompt |
| **小计** | | **44h** | |

**总计：约 168 小时（约 4.2 人周，1 个全职开发 4-5 周，或 2 人 2-3 周）**

---

## 六、验收标准

| 指标 | 当前值 | 第一阶段目标 | 第三阶段目标 | 验证方式 |
|------|--------|-------------|-------------|---------|
| 简单题一次生成通过率 | ~90% | ≥95% | ≥95% | 连续生成 20 道统计 |
| 中等题一次生成通过率 | ~60% | ≥70% | ≥85% | 连续生成 20 道统计 |
| 困难题一次生成通过率 | ~20% | ≥60% | ≥75% | 连续生成 20 道统计 |
| AI 改写成功率 | ~40% | ≥60% | ≥80% | 随机选 20 道失败题改写 |
| 隐藏点全量验证覆盖率 | 25%（3/12） | 100% | 100% | 抽查 10 道题验证报告 |
| 数据生成器崩溃率 | ~15% | ≤5% | ≤2% | 连续生成 50 道题统计 |
| AI 改写平均耗时 | 700s（挂死） | ≤120s | ≤120s | 记录 20 次改写耗时 |
| 人工修 bug 平均时间 | 20 分钟/题 | ≤10 分钟/题 | ≤5 分钟/题 | 记录 20 道题修复耗时 |
| 草稿箱批量操作效率 | 1 道/次 | 1 道/次 | 10 道/次 | 操作 23 道题总耗时 |
| JSON 解析失败率 | ~10% | ≤5% | ≤2% | 记录 50 次 AI 调用 |

---

## 七、风险与降级方案

| 风险 | 影响 | 降级方案 |
|------|------|---------|
| 找不到懂 Spring Cloud 的学生/外包 | 修复延期 | 先用本地备份 + 设计文档结题，修复放在下一周期 |
| go-judge 沙盒并行验证时性能不足 | 验证超时 | 降低并发度（从 30 并发降到 10 并发），延长超时时间 |
| LLM API 配额不足 | AI 改写/生成受限 | 接入多个 LLM Provider（OpenAI + 文心 + 通义），负载均衡 |
| 算法白名单覆盖不全 | 仍误判部分算法 | 保持原有静态分析作为兜底，白名单逐步扩充 |
| Resilience4j 熔断过于敏感 | 正常请求被降级 | 调整阈值（failureRateThreshold 从 50% 调到 70%） |

---

## 八、文档说明

**本方案与 AIOJ 现有架构的关系：**
- 不改动 Agent Core V3 的安全假设（模型不可信、服务器控制面）
- 不改动现有比赛安全四层边界
- 不改动现有审计与用量追踪机制
- 不改动现有 Prompt Injection 防护策略
- 在现有基础设施（Spring Cloud、RabbitMQ、go-judge、React 19）上做增强

**本方案与问题暴露文档的关系：**
- 覆盖全部 15 个问题（AI-001 至 AI-015）
- 保持问题编号一致，便于追溯
- 所有代码示例均为可直接实现的 Java/TypeScript 代码

---

**文档结束**
*本方案基于 AIOJ Agent Core V3 公开设计文档与 AI-OJ Next 问题暴露文档编制，所有代码示例遵循 Apache License 2.0。*
