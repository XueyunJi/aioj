package com.aioj.next.common.error;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record UserErrorFeedback(String key, String message, Map<String, String> params) {

    private static final Map<String, String> EXACT_KEYS = Map.ofEntries(
            Map.entry("Refresh token required", "auth.tokenRequired"),
            Map.entry("Bearer token required", "auth.tokenRequired"),
            Map.entry("Access token required", "auth.accessTokenRequired"),
            Map.entry("Invalid token", "auth.invalidToken"),
            Map.entry("Invalid account or password", "auth.invalidCredentials"),
            Map.entry("Account is disabled. Please contact an administrator.", "auth.accountDisabled"),
            Map.entry("User is disabled", "auth.accountDisabled"),
            Map.entry("User account is disabled", "auth.accountDisabled"),
            Map.entry("Account already exists", "auth.accountExists"),
            Map.entry("Current password is incorrect", "auth.currentPasswordIncorrect"),
            Map.entry("New password cannot be the same as the current password", "auth.newPasswordSameAsCurrent"),
            Map.entry("Password reset is required", "auth.passwordResetRequired"),
            Map.entry("Public registration is disabled", "auth.registrationDisabled"),
            Map.entry("Refresh token is invalid", "auth.refreshTokenInvalid"),
            Map.entry("Public registration only supports student or teacher roles", "auth.publicRegistrationRole"),
            Map.entry("Invalid roles", "auth.invalidRoles"),
            Map.entry("Student and teacher roles are mutually exclusive", "auth.roleConflict"),
            Map.entry("User not found", "user.notFound"),
            Map.entry("Problem not found", "problem.notFound"),
            Map.entry("Imported problem has been deleted", "problem.importedDeleted"),
            Map.entry("Submission not found", "submission.notFound"),
            Map.entry("Language is required", "submission.languageRequired"),
            Map.entry("Cannot query other users' submissions", "submission.queryForbidden"),
            Map.entry("Cannot read other users' submissions", "submission.readForbidden"),
            Map.entry("Contest not found", "contest.notFound"),
            Map.entry("Contest run not found", "contest.runNotFound"),
            Map.entry("Cannot access contest", "contest.forbidden"),
            Map.entry("Cannot manage contest", "contest.manageForbidden"),
            Map.entry("Cannot access contest run", "contest.runForbidden"),
            Map.entry("Cannot manage contest runs", "contest.runManageForbidden"),
            Map.entry("Contest title already exists", "contest.titleDuplicate"),
            Map.entry("Contest run title already exists", "contest.runTitleDuplicate"),
            Map.entry("Published contest run title already exists", "contest.runTitleDuplicate"),
            Map.entry("Resolver requires a freeze time", "contest.resolverNoFreeze"),
            Map.entry("Contest run has no scoreboard freeze", "contest.publicScoreboardNoFreeze"),
            Map.entry("User group not found", "group.notFound"),
            Map.entry("Learning group not found", "group.notFound"),
            Map.entry("Class not found", "group.notFound"),
            Map.entry("Cannot access this class", "group.forbidden"),
            Map.entry("Cannot manage this class", "group.manageForbidden"),
            Map.entry("Group name is required", "group.nameRequired"),
            Map.entry("Class owner cannot be removed", "group.ownerCannotRemove"),
            Map.entry("Invalid group member role", "group.invalidMemberRole"),
            Map.entry("Teacher group role requires a teacher account", "group.teacherRoleRequiresTeacher"),
            Map.entry("Owner role cannot be changed through member edit", "group.ownerRoleLocked"),
            Map.entry("Only archived user groups can be restored", "group.restoreArchivedOnly"),
            Map.entry("Only archived user groups can be deleted", "group.deleteArchivedOnly"),
            Map.entry("User group is referenced by an active contest run", "group.referencedByRun"),
            Map.entry("Only .zip testcase packages are supported", "testcase.zipOnly"),
            Map.entry("Testcase package manifest.json is required", "testcase.manifestRequired"),
            Map.entry("Testcase manifest cases are required", "testcase.manifestCasesRequired"),
            Map.entry("Not all testcase chunks have been uploaded", "testcase.chunksIncomplete"),
            Map.entry("Testcase package file name is required", "testcase.fileNameRequired"),
            Map.entry("Testcase package file size exceeds limit", "testcase.fileTooLarge"),
            Map.entry("Chunk size exceeds configured limit", "testcase.chunkTooLarge"),
            Map.entry("Chunk index is out of range", "testcase.chunkIndexInvalid"),
            Map.entry("Chunk SHA-256 does not match X-Chunk-Sha256", "testcase.chunkShaMismatch"),
            Map.entry("Merged testcase package SHA-256 does not match init request", "testcase.packageShaMismatch"),
            Map.entry("Merged testcase package size does not match init request", "testcase.packageSizeMismatch"),
            Map.entry("Total chunks does not match file size and chunk size", "testcase.totalChunksMismatch"),
            Map.entry("Testcase upload session expired", "testcase.uploadExpired"),
            Map.entry("Testcase upload is not accepting chunks", "testcase.uploadNotAccepting"),
            Map.entry("Only READY testcase packages can be downloaded", "testcase.downloadReadyOnly"),
            Map.entry("Only READY testcase packages can be activated", "testcase.activateReadyOnly"),
            Map.entry("Archived testcase packages cannot be activated", "testcase.activateArchived"),
            Map.entry("Only archived testcase packages can be restored", "testcase.restoreArchivedOnly"),
            Map.entry("Only archived testcase packages can be deleted", "testcase.deleteArchivedOnly"),
            Map.entry("Active testcase package cannot be archived or deleted", "testcase.activeCannotArchive"),
            Map.entry("Processing testcase package cannot be archived or deleted", "testcase.processingCannotArchive"),
            Map.entry("Testcase package not found", "testcase.notFound"),
            Map.entry("Testcase upload session not found", "testcase.uploadNotFound"),
            Map.entry("Invalid testcase zip package", "testcase.invalidZip"),
            Map.entry("Failed to read testcase zip package", "testcase.readFailed"),
            Map.entry("Testcase package has too many entries", "testcase.tooManyEntries"),
            Map.entry("Testcase package is too large after decompression", "testcase.decompressedTooLarge"),
            Map.entry("Testcase manifest.json is too large", "testcase.manifestTooLarge"),
            Map.entry("Testcase manifest version is required", "testcase.manifestVersionRequired"),
            Map.entry("Testcase manifest version is too long", "testcase.manifestVersionTooLong"),
            Map.entry("Testcase manifest contains an empty case", "testcase.emptyCase"),
            Map.entry("Testcase name is too long", "testcase.caseNameTooLong"),
            Map.entry("Testcase zip path is required", "testcase.zipPathRequired"),
            Map.entry("Testcase package must not contain symbolic links", "testcase.symlinkForbidden"),
            Map.entry("Custom testcase checker language must be cpp", "testcase.checkerLanguageCpp"),
            Map.entry("Custom testcase checker protocol must be AIOJ_JSON", "testcase.checkerProtocol"),
            Map.entry("Subtask score must not be negative", "testcase.subtaskScoreInvalid"),
            Map.entry("Testcase package zip not found", "testcase.zipMissing"),
            Map.entry("Failed to process testcase package", "testcase.processFailed"),
            Map.entry("AI quota exceeded", "ai.quotaExceeded"),
            Map.entry("AI assistance is not available for this contest problem", "ai.contestProblemAssistDisabled"),
            Map.entry("This question appears to reference a problem from a running contest", "ai.contestProblemLeakBlocked"),
            Map.entry("During an active contest, complete solution code for a contest problem cannot be requested outside the contest assistance page", "ai.contestPublicCodeBlocked"),
            Map.entry("AI rolling quota exceeded", "ai.rollingQuotaExceeded"),
            Map.entry("AI monthly quota exceeded", "ai.monthlyQuotaExceeded"),
            Map.entry("AI model configuration is disabled", "ai.configDisabled"),
            Map.entry("AI API key is not configured", "ai.keyMissing"),
            Map.entry("API Key must be configured through server environment variables", "ai.keyEnvOnly"),
            Map.entry("AI provider returned empty content", "ai.emptyContent"),
            Map.entry("AI provider response could not be parsed", "ai.responseParseFailed"),
            Map.entry("AI provider call failed", "ai.providerFailed"),
            Map.entry("比赛进行中不能请求该题的完整解题代码，可以询问思路、复杂度、边界情况或调试方向。", "ai.contestCodeBlocked"),
            Map.entry("Text AI API must be DeepSeek or Kimi", "ai.providerUnsupported"),
            Map.entry("Text AI API and model/base URL do not match", "ai.providerModelMismatch"),
            Map.entry("Unsupported apiKeyAction", "ai.keyActionUnsupported"),
            Map.entry("temperature must be between 0 and 2", "ai.temperatureRange"),
            Map.entry("maxTokens must be positive", "ai.maxTokensPositive"),
            Map.entry("embeddingDimension must be positive", "ai.embeddingDimensionPositive"),
            Map.entry("Problem draft not found", "draft.notFound"),
            Map.entry("Problem draft must be approved before import", "draft.approveBeforeImport"),
            Map.entry("Cannot import an invalid draft. Please regenerate or fix the validation errors first.", "draft.invalidImport"),
            Map.entry("Cannot import a draft without successful execution verification. Please regenerate or fix it first.", "draft.unverifiedImport"),
            Map.entry("Cannot import without a verified official testcase package. Please rerun verification.", "draft.testcasePackageMissing"),
            Map.entry("Problem draft is already imported", "draft.alreadyImported"),
            Map.entry("Cannot update an imported draft with invalid content. Please fix the validation errors first.", "draft.invalidImportedUpdate"),
            Map.entry("Provider returned no draft", "draft.providerNoDraft"),
            Map.entry("Problem draft approval failed", "draft.approvalFailed"),
            Map.entry("Cannot reject an already-imported draft", "draft.rejectImported"),
            Map.entry("Only archived problem drafts can be deleted", "draft.deleteArchivedOnly"),
            Map.entry("Only archived problem drafts can be restored", "draft.restoreArchivedOnly"),
            Map.entry("Archived problem drafts cannot be modified", "draft.archivedLocked"),
            Map.entry("Unable to serialize problem draft", "draft.serializeFailed"),
            Map.entry("Operation job not found", "operation.jobNotFound"),
            Map.entry("Only failed or cancelled jobs can be retried", "operation.retryFailedOnly"),
            Map.entry("Operation job has no completed artifact", "operation.noArtifact"),
            Map.entry("Operation job artifact not found", "operation.artifactNotFound"),
            Map.entry("Invalid artifact path", "operation.invalidArtifactPath"),
            Map.entry("Cannot view this operation job", "operation.viewForbidden"),
            Map.entry("Cannot manage operation jobs", "operation.manageForbidden"),
            Map.entry("Invalid operation job payload", "operation.invalidPayload"),
            Map.entry("Failed to persist operation artifact", "operation.persistArtifactFailed"),
            Map.entry("Cannot view operation audit events", "operation.auditForbidden")
    );

    public static UserErrorFeedback forCode(ErrorCode code, String serviceName) {
        return fromKey(defaultKey(code), defaultMessage(defaultKey(code)), serviceName);
    }

    public static UserErrorFeedback forDomain(ErrorCode code, String rawMessage, String serviceName) {
        String raw = rawMessage == null || rawMessage.isBlank() ? code.message() : rawMessage.trim();
        String key = classify(raw, code);
        Map<String, String> params = params(serviceName);
        addDynamicParams(raw, params);
        return new UserErrorFeedback(key, defaultMessage(key), params);
    }

    public static UserErrorFeedback validation(String serviceName) {
        return fromKey("request.validationFailed", defaultMessage("request.validationFailed"), serviceName);
    }

    public static UserErrorFeedback field(String key, String field, String serviceName) {
        Map<String, String> params = params(serviceName);
        params.put("field", field);
        return new UserErrorFeedback(key, defaultMessage(key), params);
    }

    public static UserErrorFeedback fromKey(String key, String message, String serviceName) {
        return new UserErrorFeedback(key, message, params(serviceName));
    }

    private static Map<String, String> params(String serviceName) {
        Map<String, String> params = new LinkedHashMap<>();
        if (serviceName != null && !serviceName.isBlank()) {
            params.put("service", serviceName);
        }
        return params;
    }

    private static String classify(String raw, ErrorCode code) {
        String exact = EXACT_KEYS.get(raw);
        if (exact != null) return exact;
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("request failed: 504") || lower.contains("gateway time-out") || lower.contains("timed out") || lower.contains("timeout")) {
            return "request.timeout";
        }
        if (lower.startsWith("provider returned http 401")
                || lower.contains("invalid authentication")
                || lower.contains("authentication fails")
                || lower.contains("api key") && lower.contains("invalid")) {
            return "ai.keyInvalid";
        }
        if (lower.startsWith("provider returned http 429") || lower.contains("rate limit") || lower.contains("quota")) {
            return "ai.rateLimited";
        }
        if (lower.startsWith("provider returned http 5") || lower.contains("ai provider http 5")) {
            return "ai.providerUnavailable";
        }
        if (lower.startsWith("provider returned http") || lower.startsWith("ai provider http")) {
            return "ai.providerRejected";
        }
        if (lower.startsWith("ai provider request failed") || lower.startsWith("ai provider call failed")) {
            return "ai.providerFailed";
        }
        if (lower.contains("could not be parsed") || lower.contains("response could not be parsed")) {
            return lower.contains("ai") ? "ai.responseParseFailed" : "system.parseFailed";
        }
        if (lower.contains("serialize") || lower.contains("serialization")) {
            return "system.serializeFailed";
        }
        if (lower.contains("sha-256 is unavailable") || lower.contains("sha-256 is not available")) {
            return "system.cryptoUnavailable";
        }
        if (lower.startsWith("unsupported language:")) {
            return "submission.unsupportedLanguage";
        }
        if (lower.startsWith("testcase upload has failed:")) {
            return "testcase.uploadFailed";
        }
        if (lower.startsWith("unsafe testcase zip path:")) {
            return "testcase.unsafePath";
        }
        if (lower.startsWith("missing testcase upload chunk:") || lower.startsWith("uploaded testcase chunk is missing on disk:")) {
            return "testcase.missingChunk";
        }
        if (lower.startsWith("duplicate testcase zip entry:")) {
            return "testcase.duplicateEntry";
        }
        if (lower.startsWith("testcase zip entry is too large:")) {
            return "testcase.entryTooLarge";
        }
        if (lower.contains("contest run has unfinished or system-error submissions")
                || lower.contains("contest has unfinished or system-error submissions")) {
            return "contest.unfinishedJudging";
        }
        if (lower.contains("resolver requires a freeze time")) {
            return "contest.resolverNoFreeze";
        }
        if (lower.contains("contest run has no scoreboard freeze")) {
            return "contest.publicScoreboardNoFreeze";
        }
        if (lower.contains("time must be before")
                || lower.contains("freeze time")
                || lower.contains("start time")
                || lower.contains("end time")
                || lower.contains("registration") && lower.contains("time")) {
            return "request.timeRangeInvalid";
        }
        if (lower.contains("too long")) {
            return "request.tooLong";
        }
        if (lower.contains("must be positive")
                || lower.contains("must not be negative")
                || lower.contains("cannot be negative")) {
            return "request.positiveRequired";
        }
        if (lower.contains("does not belong")
                || lower.contains("do not belong")
                || lower.contains("not in this")
                || lower.contains("not part of")) {
            return "request.relationInvalid";
        }
        if (lower.contains("only draft")
                || lower.contains("only archived")
                || lower.contains("only published")
                || lower.contains("cannot be changed")
                || lower.contains("cannot be modified")
                || lower.contains("cannot be deleted")
                || lower.contains("cannot be archived")
                || lower.contains("cannot be restored")) {
            return "request.lifecycleInvalid";
        }
        if (lower.contains("not accepting")
                || lower.contains("not open")
                || lower.contains("not visible yet")
                || lower.contains("available after")
                || lower.contains("not available yet")
                || lower.contains("already ended")) {
            return "request.stateUnavailable";
        }
        if (lower.contains("requires")
                || lower.contains(" must ")
                || lower.startsWith("must ")
                || lower.contains("at least one")
                || lower.contains("has no")
                || lower.contains("no deterministic statistics")) {
            return "request.precondition";
        }
        if (lower.contains("invalid")) {
            return "request.invalidOption";
        }
        if (lower.contains("cannot access")) return "auth.forbidden";
        if (lower.contains("cannot manage")) return "auth.forbidden";
        if (lower.contains("not found")) return "resource.notFound";
        if (lower.contains("required")) return "request.required";
        if (lower.contains("unsupported")) return "request.unsupported";
        if (lower.contains("failed")) return serviceFailureKey(code);
        return defaultKey(code);
    }

    private static void addDynamicParams(String raw, Map<String, String> params) {
        addSuffix(raw, "Unsupported language:", "language", params);
        addSuffix(raw, "Unsafe testcase zip path:", "path", params);
        addSuffix(raw, "Duplicate testcase zip entry:", "path", params);
        addSuffix(raw, "Testcase zip entry is too large:", "path", params);
        addSuffix(raw, "Unknown testcase subtask key:", "subtask", params);
        addSuffix(raw, "Unsupported testcase checker type:", "type", params);
        addSuffix(raw, "Unsupported testcase checker protocol:", "protocol", params);
    }

    private static void addSuffix(String raw, String prefix, String name, Map<String, String> params) {
        if (raw.startsWith(prefix)) {
            String value = raw.substring(prefix.length()).trim();
            if (!value.isBlank()) {
                params.put(name, value);
            }
        }
    }

    private static String serviceFailureKey(ErrorCode code) {
        if (code == ErrorCode.SERVICE_UNAVAILABLE) return "service.unavailable";
        if (code == ErrorCode.INTERNAL_ERROR) return "system.internal";
        return defaultKey(code);
    }

    private static String defaultKey(ErrorCode code) {
        return switch (code) {
            case BAD_REQUEST -> "request.badRequest";
            case VALIDATION_FAILED -> "request.validationFailed";
            case INVALID_PAYLOAD -> "request.invalidPayload";
            case MISSING_PARAMETER -> "request.missingParameter";
            case TYPE_MISMATCH -> "request.typeMismatch";
            case UNAUTHORIZED -> "auth.required";
            case FORBIDDEN -> "auth.forbidden";
            case NOT_FOUND -> "resource.notFound";
            case METHOD_NOT_ALLOWED -> "request.methodNotAllowed";
            case CONFLICT -> "request.conflict";
            case PAYLOAD_TOO_LARGE -> "request.payloadTooLarge";
            case TOO_MANY_REQUESTS -> "request.tooMany";
            case INTERNAL_ERROR -> "system.internal";
            case SERVICE_UNAVAILABLE -> "service.unavailable";
        };
    }

    private static String defaultMessage(String key) {
        return switch (key) {
            case "request.badRequest" -> "请求内容有误，请检查后重试。";
            case "request.validationFailed" -> "提交内容未通过校验，请检查具体字段。";
            case "request.invalidPayload" -> "请求体格式不正确，请检查填写内容或重新提交。";
            case "request.missingParameter" -> "缺少必要参数，请补全后重试。";
            case "request.typeMismatch" -> "参数类型不正确，请检查数字、时间或选项格式。";
            case "request.methodNotAllowed" -> "该操作方式不受支持，请刷新页面后重试。";
            case "request.payloadTooLarge" -> "上传内容超过大小限制，请压缩或拆分后再试。";
            case "request.tooMany" -> "操作过于频繁，请稍后再试。";
            case "request.conflict" -> "当前操作与已有数据冲突，请刷新后确认状态。";
            case "request.timeout" -> "请求耗时过长，服务已超时，请稍后重试。";
            case "request.required" -> "缺少必填内容，请补全后重试。";
            case "request.unsupported" -> "当前配置或选项不受支持，请检查后重试。";
            case "request.timeRangeInvalid" -> "时间设置不正确，请检查开始、结束、冻结或报名时间。";
            case "contest.resolverNoFreeze" -> "该比赛未设置封榜，无法生成揭榜回放。";
            case "contest.publicScoreboardNoFreeze" -> "该比赛未设置封榜，无法解冻公开榜。";
            case "request.tooLong" -> "填写内容过长，请缩短后重试。";
            case "request.positiveRequired" -> "数值必须为正数或非负数，请重新填写。";
            case "request.relationInvalid" -> "选择的数据不属于当前范围，请重新选择。";
            case "request.lifecycleInvalid" -> "当前数据状态不允许执行该操作，请刷新后确认状态。";
            case "request.stateUnavailable" -> "当前状态暂不能执行该操作，请稍后或刷新后重试。";
            case "request.precondition" -> "当前条件未满足，请按页面提示补齐前置配置后重试。";
            case "request.invalidOption" -> "选项或参数无效，请重新选择。";
            case "auth.required" -> "请先登录后再继续操作。";
            case "auth.invalidToken" -> "登录状态无效，请重新登录。";
            case "auth.tokenRequired" -> "请先登录后再继续操作。";
            case "auth.accessTokenRequired" -> "请使用登录凭证访问该接口。";
            case "auth.invalidCredentials" -> "账号或密码错误。";
            case "auth.accountDisabled" -> "账号已被禁用，请联系管理员处理。";
            case "auth.accountExists" -> "账号已存在，请更换账号或直接登录。";
            case "auth.currentPasswordIncorrect" -> "当前密码不正确，请重新输入。";
            case "auth.newPasswordSameAsCurrent" -> "新密码不能与当前密码相同。";
            case "auth.passwordResetRequired" -> "首次登录需要先修改初始密码。";
            case "auth.registrationDisabled" -> "公开注册已关闭，请联系管理员创建账号。";
            case "auth.refreshTokenInvalid" -> "登录状态已失效，请重新登录。";
            case "auth.publicRegistrationRole" -> "公开注册只支持学生或教师身份。";
            case "auth.invalidRoles" -> "角色配置不正确，请重新选择。";
            case "auth.roleConflict" -> "学生和教师身份不能同时选择。";
            case "auth.forbidden" -> "当前账号没有权限执行该操作。";
            case "resource.notFound" -> "请求的资源不存在或已被删除。";
            case "user.notFound" -> "用户不存在，请检查账号或用户 ID。";
            case "group.notFound" -> "用户组不存在或已被删除。";
            case "group.forbidden" -> "当前账号没有权限访问这个班级。";
            case "group.manageForbidden" -> "当前账号没有权限管理这个班级。";
            case "group.nameRequired" -> "用户组名称不能为空。";
            case "group.ownerCannotRemove" -> "不能移除班级所有者。";
            case "group.invalidMemberRole" -> "成员角色不正确，请重新选择。";
            case "group.teacherRoleRequiresTeacher" -> "教师角色只能分配给教师账号。";
            case "group.ownerRoleLocked" -> "不能通过成员编辑修改所有者角色。";
            case "group.restoreArchivedOnly" -> "只有已归档的用户组可以恢复。";
            case "group.deleteArchivedOnly" -> "只有已归档的用户组可以删除。";
            case "group.referencedByRun" -> "该用户组仍被进行中的比赛使用，暂不能删除。";
            case "problem.notFound" -> "题目不存在或已被删除。";
            case "problem.importedDeleted" -> "导入关联的题目已被删除，请重新导入或删除草稿。";
            case "submission.notFound" -> "提交记录不存在或已被删除。";
            case "submission.languageRequired" -> "请选择编程语言。";
            case "submission.unsupportedLanguage" -> "暂不支持所选编程语言。";
            case "submission.queryForbidden" -> "不能查看其他用户的提交列表。";
            case "submission.readForbidden" -> "不能查看其他用户的提交详情。";
            case "contest.notFound" -> "比赛不存在或已被删除。";
            case "contest.runNotFound" -> "比赛运行不存在或已被删除。";
            case "contest.forbidden" -> "当前账号没有权限访问该比赛。";
            case "contest.manageForbidden" -> "当前账号没有权限管理该比赛。";
            case "contest.runForbidden" -> "当前账号没有权限访问该比赛运行。";
            case "contest.runManageForbidden" -> "当前账号没有权限管理该比赛运行。";
            case "contest.unfinishedJudging" -> "仍有提交未完成判题或判题异常，完成处理后才能继续该操作。";
            case "contest.titleDuplicate" -> "已存在同名比赛，请更换比赛名称。";
            case "contest.runTitleDuplicate" -> "已存在同名运行，请更换运行名称。";
            case "testcase.zipOnly" -> "测试包只支持 .zip 文件。";
            case "testcase.manifestRequired" -> "测试包缺少 manifest.json。";
            case "testcase.manifestCasesRequired" -> "测试包 manifest 中缺少用例列表。";
            case "testcase.chunksIncomplete" -> "测试包分片尚未全部上传，请重新上传。";
            case "testcase.fileNameRequired" -> "测试包文件名不能为空。";
            case "testcase.fileTooLarge" -> "测试包文件大小超过限制。";
            case "testcase.chunkTooLarge" -> "测试包分片大小超过限制。";
            case "testcase.chunkIndexInvalid" -> "测试包分片序号不正确，请重新上传。";
            case "testcase.chunkShaMismatch" -> "测试包分片校验失败，请重新上传该文件。";
            case "testcase.packageShaMismatch" -> "测试包整体校验失败，请确认文件未损坏后重新上传。";
            case "testcase.packageSizeMismatch" -> "测试包大小与上传初始化信息不一致，请重新上传。";
            case "testcase.totalChunksMismatch" -> "测试包分片数量与文件大小不匹配，请重新选择文件上传。";
            case "testcase.uploadExpired" -> "测试包上传会话已过期，请重新上传。";
            case "testcase.uploadNotAccepting" -> "当前测试包上传状态不接受继续上传，请刷新后重试。";
            case "testcase.downloadReadyOnly" -> "只有处理完成且可用的测试包可以下载。";
            case "testcase.activateReadyOnly" -> "只有处理完成且可用的测试包可以启用。";
            case "testcase.activateArchived" -> "已归档的测试包不能启用，请先恢复。";
            case "testcase.restoreArchivedOnly" -> "只有已归档的测试包可以恢复。";
            case "testcase.deleteArchivedOnly" -> "只有已归档的测试包可以删除。";
            case "testcase.activeCannotArchive" -> "当前启用的测试包不能归档或删除，请先切换版本。";
            case "testcase.processingCannotArchive" -> "处理中的测试包不能归档或删除，请稍后再试。";
            case "testcase.notFound" -> "测试包不存在或已被删除。";
            case "testcase.uploadNotFound" -> "测试包上传会话不存在，请重新上传。";
            case "testcase.invalidZip" -> "测试包不是有效的 zip 文件。";
            case "testcase.readFailed" -> "读取测试包失败，请确认 zip 文件未损坏。";
            case "testcase.tooManyEntries" -> "测试包内文件数量过多，请精简后重新上传。";
            case "testcase.decompressedTooLarge" -> "测试包解压后体积过大，请精简数据后重新上传。";
            case "testcase.manifestTooLarge" -> "manifest.json 文件过大，请精简配置。";
            case "testcase.manifestVersionRequired" -> "manifest.json 中的版本号不能为空。";
            case "testcase.manifestVersionTooLong" -> "manifest.json 中的版本号过长。";
            case "testcase.emptyCase" -> "manifest.json 中存在空用例，请补全或删除。";
            case "testcase.caseNameTooLong" -> "测试用例名称过长。";
            case "testcase.zipPathRequired" -> "测试用例文件路径不能为空。";
            case "testcase.symlinkForbidden" -> "测试包不能包含符号链接。";
            case "testcase.checkerLanguageCpp" -> "自定义 checker 目前只支持 C++。";
            case "testcase.checkerProtocol" -> "自定义 checker 必须使用 AIOJ_JSON 协议。";
            case "testcase.subtaskScoreInvalid" -> "子任务分值不能为负数。";
            case "testcase.zipMissing" -> "测试包文件在服务器上不存在，请重新上传。";
            case "testcase.processFailed", "testcase.uploadFailed" -> "测试包处理失败，请检查 zip 结构和 manifest.json 后重试。";
            case "testcase.unsafePath" -> "测试包包含不安全路径，请移除绝对路径或 .. 路径。";
            case "testcase.missingChunk" -> "测试包分片缺失，请重新上传。";
            case "testcase.duplicateEntry" -> "测试包内存在重复文件路径。";
            case "testcase.entryTooLarge" -> "测试包内存在过大的单个文件。";
            case "ai.quotaExceeded" -> "AI 使用次数已达上限，请稍后再试。";
            case "ai.rollingQuotaExceeded" -> "近 2 小时 AI 使用次数已达 50 次，请稍后再试。";
            case "ai.monthlyQuotaExceeded" -> "本月 AI 使用次数已达 1000 次，请下月再试或联系管理员。";
            case "ai.configDisabled" -> "AI 模型配置已停用，请在管理端启用或继承默认配置。";
            case "ai.keyMissing" -> "AI API Key 未配置，请在服务器环境变量中配置对应密钥。";
            case "ai.keyInvalid" -> "AI API Key 无效或已失效，请检查服务器环境变量中的密钥。";
            case "ai.keyEnvOnly" -> "AI API Key 只能通过服务器环境变量配置。";
            case "ai.emptyContent" -> "模型没有返回内容，请调整提示词或稍后重试。";
            case "ai.responseParseFailed" -> "模型返回格式不符合要求，请重试或降低生成要求。";
            case "ai.providerFailed" -> "AI 模型调用失败，请稍后重试。";
            case "ai.contestCodeBlocked" -> "比赛进行中不能请求该题的完整解题代码，可以询问思路、复杂度、边界情况或调试方向。";
            case "ai.contestProblemAssistDisabled" -> "比赛时不可询问未公开赛题。";
            case "ai.contestProblemLeakBlocked" -> "你的提问与正在进行比赛的题目内容相似，比赛期间不能通过 AI 讨论该题目。";
            case "ai.contestPublicCodeBlocked" -> "比赛进行中，不能在比赛辅助之外向 AI 索要该题的完整答案代码，可以询问思路、复杂度、边界情况或调试方向。";
            case "ai.providerUnavailable" -> "AI 模型服务暂不可用，请稍后重试。";
            case "ai.providerRejected" -> "AI 模型服务拒绝了请求，请检查模型配置和额度。";
            case "ai.rateLimited" -> "AI 模型请求过于频繁或额度不足，请稍后重试。";
            case "ai.providerUnsupported" -> "文本 AI 只能选择 DeepSeek 或 Kimi。";
            case "ai.providerModelMismatch" -> "所选 API 与模型或 Base URL 不匹配，请重新选择。";
            case "ai.keyActionUnsupported" -> "当前密钥操作不受支持，请使用服务器环境变量配置。";
            case "ai.temperatureRange" -> "Temperature 必须在 0 到 2 之间。";
            case "ai.maxTokensPositive" -> "Max tokens 必须为正整数。";
            case "ai.embeddingDimensionPositive" -> "Embedding 维度必须为正整数。";
            case "draft.notFound" -> "AI 草稿不存在或已被删除。";
            case "draft.approveBeforeImport" -> "请先通过审核后再导入题库。";
            case "draft.invalidImport", "draft.invalidImportedUpdate" -> "草稿内容校验未通过，请修复或重新生成后再导入。";
            case "draft.unverifiedImport" -> "草稿自动测试未通过，请修复或重新生成后再导入题库。";
            case "draft.testcasePackageMissing" -> "草稿缺少可用的官方测试包，请先重新运行验证。";
            case "draft.alreadyImported" -> "该草稿已导入题库，无需再进行人工审核。";
            case "draft.providerNoDraft" -> "AI 未返回草稿内容，请调整要求后重试。";
            case "draft.approvalFailed" -> "草稿审核失败，请检查内容后重试。";
            case "draft.rejectImported" -> "已导入题库的草稿不能再拒绝。";
            case "draft.deleteArchivedOnly" -> "只有已归档的草稿可以删除。";
            case "draft.restoreArchivedOnly" -> "只有已归档的草稿可以恢复。";
            case "draft.archivedLocked" -> "已归档的草稿不能修改，请先恢复。";
            case "draft.serializeFailed" -> "草稿内容保存失败，请稍后重试。";
            case "operation.jobNotFound" -> "异步任务不存在或已被删除。";
            case "operation.retryFailedOnly" -> "只有失败或已取消的任务可以重试。";
            case "operation.noArtifact" -> "该任务还没有可下载产物。";
            case "operation.artifactNotFound" -> "任务产物不存在或已过期。";
            case "operation.invalidArtifactPath" -> "任务产物路径异常，无法下载。";
            case "operation.viewForbidden" -> "当前账号没有权限查看该任务。";
            case "operation.manageForbidden" -> "当前账号没有权限管理异步任务。";
            case "operation.invalidPayload" -> "异步任务参数不正确，请重新发起任务。";
            case "operation.persistArtifactFailed" -> "异步任务产物保存失败，请稍后重试。";
            case "operation.auditForbidden" -> "当前账号没有权限查看运维审计。";
            case "service.unavailable" -> "依赖服务暂不可用，请稍后重试。";
            case "system.parseFailed" -> "服务响应解析失败，请稍后重试。";
            case "system.serializeFailed" -> "服务保存数据时出现异常，请稍后重试。";
            case "system.cryptoUnavailable" -> "服务器缺少加密校验能力，请联系管理员检查运行环境。";
            case "system.internal" -> "服务内部异常，请稍后重试。";
            default -> "操作失败，请检查输入或稍后重试。";
        };
    }
}
