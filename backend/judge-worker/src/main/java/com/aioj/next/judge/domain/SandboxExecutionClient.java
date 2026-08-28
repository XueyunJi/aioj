package com.aioj.next.judge.domain;

import com.aioj.next.judge.config.JudgeWorkerProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SandboxExecutionClient {
    private static final int DEFAULT_STDOUT_BASE_COLLECT_LIMIT_BYTES = 64 * 1024;
    private static final int DEFAULT_STDERR_COLLECT_LIMIT_BYTES = 64 * 1024;
    /** Hidden testcase validation is intentionally exhaustive: every generated
     * input must be verified and packaged. These values remain only for binary
     * compatibility with older callers; they no longer truncate official data. */
    private static final int DEFAULT_SCRIPT_TARGET_CASE_COUNT = Integer.MAX_VALUE;
    private static final int MAX_SCRIPT_TARGET_CASE_COUNT = Integer.MAX_VALUE;
    private static final String COLLECT_MODE_PAIRED = "PAIRED";
    private static final String COLLECT_MODE_OFFICIAL_INPUTS = "OFFICIAL_INPUTS";
    private static final String COLLECT_MODE_OFFICIAL_PACKAGE = "OFFICIAL_PACKAGE";
    private static final String GENERATED_FILES_JSON = ".aioj_generated_files.json";
    private static final String OFFICIAL_PACKAGE_JSON = ".aioj_official_package.json";
    private static final String OFFICIAL_PACKAGE_ZIP = ".aioj_official_hidden.zip";
    private static final ParameterizedTypeReference<List<SandboxRunResult>> RUN_RESULT_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final JudgeWorkerProperties properties;
    private final RestClient restClient;

    public SandboxExecutionClient(JudgeWorkerProperties properties) {
        this.properties = properties;
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(stripTrailingPath(properties.getSandboxEndpoint()))
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .connectTimeout(resolveTimeout(properties.getSandboxTimeout()))
                        .build()));
        if (StringUtils.hasText(properties.getSandboxToken())) {
            builder.defaultHeader("Authorization", "Bearer " + properties.getSandboxToken());
        }
        this.restClient = builder.build();
    }

    public CompileOutcome compileSource(String language, String sourceCode, long cpuLimitNs, long memoryLimitBytes) {
        LangProfile lang = LangProfile.of(language);
        if (!lang.requiresCompile()) {
            return CompileOutcome.success(null, 0L, 0L, null, 0, 0L);
        }
        SandboxRunResult result = runSandbox(List.of(Map.of(
                "args", lang.compileArgs(),
                "env", lang.envVars(),
                "files", standardFiles("", stdoutBaseCollectLimitBytes(), stderrCollectLimitBytes()),
                "cpuLimit", cpuLimitNs,
                "memoryLimit", memoryLimitBytes,
                "procLimit", 50,
                "copyIn", Map.of(lang.sourceFileName(), Map.of("content", sourceCode == null ? "" : sourceCode)),
                "copyOutCached", List.of(lang.executableName())
        )));
        Long timeMs = nanosToMillis(result.time());
        Long memoryKb = bytesToKb(result.memory());
        Long runTimeMs = nanosToMillis(result.runTime());
        String stderr = fileContent(result, "stderr");
        if (!"Accepted".equals(result.status())) {
            return CompileOutcome.failed(firstText(stderr, result.error(), result.status()), timeMs, memoryKb,
                    stderr, result.exitStatus(), runTimeMs);
        }
        String fileId = result.fileIds() == null ? null : result.fileIds().get(lang.executableName());
        if (!StringUtils.hasText(fileId)) {
            return CompileOutcome.failed("Compile succeeded but sandbox did not return cached fileId",
                    timeMs, memoryKb, stderr, result.exitStatus(), runTimeMs);
        }
        return CompileOutcome.success(fileId, timeMs, memoryKb, stderr, result.exitStatus(), runTimeMs);
    }

    public RunOutcome runSource(String language, String sourceCode, String compiledFileId, String stdin,
                                long cpuLimitNs, long memoryLimitBytes, int stdoutCollectLimitBytes) {
        LangProfile lang = LangProfile.of(language);
        String temporaryFileId = null;
        try {
            String executableFileId = compiledFileId;
            if (lang.requiresCompile() && !StringUtils.hasText(executableFileId)) {
                CompileOutcome compile = compileSource(language, sourceCode, safeMultiply(cpuLimitNs, 10),
                        safeMultiply(memoryLimitBytes, 2));
                if (compile.failed()) {
                    return RunOutcome.fromCompileFailure(compile);
                }
                executableFileId = compile.fileId();
                temporaryFileId = executableFileId;
            }
            Map<String, Object> copyIn = new LinkedHashMap<>();
            if (lang.requiresCompile()) {
                copyIn.put(lang.executableName(), Map.of("fileId", executableFileId));
            } else {
                copyIn.put(lang.sourceFileName(), Map.of("content", sourceCode == null ? "" : sourceCode));
            }
            SandboxRunResult result = runSandbox(List.of(Map.of(
                    "args", lang.runArgs(),
                    "env", lang.envVars(),
                    "files", standardFiles(stdin == null ? "" : stdin,
                            positiveOrDefault(stdoutCollectLimitBytes, stdoutBaseCollectLimitBytes()),
                            stderrCollectLimitBytes()),
                    "cpuLimit", cpuLimitNs,
                    "memoryLimit", memoryLimitBytes,
                    "procLimit", 50,
                    "copyIn", copyIn
            )));
            return RunOutcome.from(result);
        } finally {
            if (temporaryFileId != null) {
                deleteCachedFile(temporaryFileId);
            }
        }
    }

    public RunOutcome runPythonScript(String script, long cpuLimitNs, long memoryLimitBytes, int stdoutCollectLimitBytes) {
        return runPythonScript(script, cpuLimitNs, memoryLimitBytes, stdoutCollectLimitBytes,
                DEFAULT_SCRIPT_TARGET_CASE_COUNT);
    }

    public RunOutcome runPythonScript(String script, long cpuLimitNs, long memoryLimitBytes, int stdoutCollectLimitBytes,
                                      int targetCaseCount) {
        return runPythonScript(script, cpuLimitNs, memoryLimitBytes, stdoutCollectLimitBytes, targetCaseCount,
                COLLECT_MODE_PAIRED);
    }

    public RunOutcome runPythonScript(String script, long cpuLimitNs, long memoryLimitBytes, int stdoutCollectLimitBytes,
                                      int targetCaseCount, String collectMode) {
        String collector = generatedFilesCollector(safeTargetCaseCount(targetCaseCount),
                safeCollectMode(collectMode));
        SandboxRunResult result = runSandbox(List.of(Map.of(
                "args", List.of("/bin/bash", "-lc", collector),
                "env", List.of("PATH=/usr/bin:/bin"),
                "files", standardFiles("", positiveOrDefault(stdoutCollectLimitBytes, stdoutBaseCollectLimitBytes()),
                        stderrCollectLimitBytes()),
                "cpuLimit", cpuLimitNs,
                "memoryLimit", memoryLimitBytes,
                "procLimit", 50,
                "copyIn", Map.of("generator.py", Map.of("content", script == null ? "" : script)),
                "copyOut", List.of(GENERATED_FILES_JSON)
        )));
        return RunOutcome.from(result, fileContent(result, GENERATED_FILES_JSON));
    }

    public RunOutcome runOfficialPackageScript(String script, String standardSolutionLanguage, String standardSolutionCode,
                                               long cpuLimitNs, long memoryLimitBytes, int stdoutCollectLimitBytes,
                                               int targetCaseCount, long standardCpuLimitNs, long standardMemoryLimitBytes,
                                               long maxCaseBytes, long maxPackageBytes) {
        LangProfile lang = LangProfile.of(standardSolutionLanguage);
        String collector = officialPackageCollector(
                safeTargetCaseCount(targetCaseCount),
                nanosToMillis(standardCpuLimitNs) == null ? 1000L : Math.max(1L, nanosToMillis(standardCpuLimitNs)),
                Math.max(1L, standardMemoryLimitBytes),
                Math.max(1L, maxCaseBytes),
                Math.max(1L, maxPackageBytes),
                lang
        );
        Map<String, Object> copyIn = new LinkedHashMap<>();
        copyIn.put("generator.py", Map.of("content", script == null ? "" : script));
        copyIn.put(lang.sourceFileName(), Map.of("content", standardSolutionCode == null ? "" : standardSolutionCode));
        SandboxRunResult result = runSandbox(List.of(Map.of(
                "args", List.of("/bin/bash", "-lc", collector),
                "env", List.of("PATH=/usr/bin:/bin"),
                "files", standardFiles("", positiveOrDefault(stdoutCollectLimitBytes, stdoutBaseCollectLimitBytes()),
                        stderrCollectLimitBytes()),
                "cpuLimit", cpuLimitNs,
                "memoryLimit", memoryLimitBytes,
                "procLimit", 80,
                "copyIn", copyIn,
                "copyOut", List.of(OFFICIAL_PACKAGE_JSON),
                "copyOutCached", List.of(OFFICIAL_PACKAGE_ZIP)
        )));
        return RunOutcome.fromOfficialPackage(result, fileContent(result, OFFICIAL_PACKAGE_JSON));
    }

    static String generatedFilesCollector(int targetCaseCount) {
        return generatedFilesCollector(targetCaseCount, COLLECT_MODE_PAIRED);
    }

    static String generatedFilesCollector(int targetCaseCount, String collectMode) {
        return """
                python3 generator.py
                status=$?
                if [ $status -ne 0 ]; then exit $status; fi
                python3 - <<'PY'
                import json
                from pathlib import Path
                target_case_count = %d
                collect_mode = '%s'
                output_json = Path('%s')

                def candidate_files(root, relative_to_root):
                    items = []
                    if not root.exists():
                        return items
                    for path in sorted(root.rglob('*')):
                        if not path.is_file() or path.suffix not in ('.in', '.out'):
                            continue
                        name = path.name
                        if name.startswith('.aioj_') or name in ('generator.py', 'std.cpp', 'std', 'std.exe', 'main', 'main.cpp'):
                            continue
                        relative = path.as_posix() if relative_to_root == Path('.') else path.relative_to(relative_to_root).as_posix()
                        key = relative[:-len(path.suffix)]
                        items.append((key, path.suffix, relative, path))
                    return items

                def collect(root, relative_to_root):
                    parts = {}
                    input_count = 0
                    output_count = 0
                    for key, suffix, relative, path in candidate_files(root, relative_to_root):
                        if suffix == '.in':
                            input_count += 1
                        elif suffix == '.out':
                            output_count += 1
                        parts.setdefault(key, {})[suffix] = (relative, path)
                    pair_names = sorted(name for name, value in parts.items() if '.in' in value and '.out' in value)
                    input_names = sorted(name for name, value in parts.items() if '.in' in value)
                    return parts, pair_names, input_names, input_count, output_count

                parts, pair_names, input_names, input_count, output_count = collect(Path('testcases'), Path('testcases'))
                scan_root = 'testcases'
                if (collect_mode == 'OFFICIAL_INPUTS' and not input_names) or (collect_mode != 'OFFICIAL_INPUTS' and not pair_names):
                    parts, pair_names, input_names, input_count, output_count = collect(Path('.'), Path('.'))
                    scan_root = '.'

                def is_stress_case(name):
                    return Path(name).stem.startswith('stress_small_')

                files = {}
                if collect_mode == 'OFFICIAL_INPUTS':
                    official_inputs = [name for name in input_names if not is_stress_case(name)]
                    for name in official_inputs:
                        for suffix in ('.in', '.out'):
                            if suffix not in parts[name]:
                                continue
                            relative, path = parts[name][suffix]
                            files[relative] = path.read_text(encoding='utf-8', errors='replace')
                else:
                    for name in pair_names:
                        for suffix in ('.in', '.out'):
                            relative, path = parts[name][suffix]
                            files[relative] = path.read_text(encoding='utf-8', errors='replace')

                cross_check_inputs = {}
                for name in sorted(parts):
                    value = parts[name]
                    if '.in' not in value:
                        continue
                    relative, path = value['.in']
                    if Path(relative).stem.startswith('stress_small_'):
                        cross_check_inputs[relative] = path.read_text(encoding='utf-8', errors='replace')
                    if len(cross_check_inputs) >= 30:
                        break

                manifest_json = ''
                for manifest_path in (Path('testcases') / 'manifest.json', Path('manifest.json')):
                    if manifest_path.exists() and manifest_path.is_file():
                        manifest_json = manifest_path.read_text(encoding='utf-8', errors='replace')[:20000]
                        break

                output_json.write_text(
                    json.dumps({
                        'files': files,
                        'generatedPairCount': len(pair_names),
                        'generatedFileCount': input_count + output_count,
                        'generatedInputCount': input_count,
                        'generatedOutputCount': output_count,
                        'crossCheckInputs': cross_check_inputs,
                        'manifestJson': manifest_json,
                        'scanRoot': scan_root
                    }, ensure_ascii=False),
                    encoding='utf-8'
                )
                PY
                """.formatted(targetCaseCount, safeCollectMode(collectMode), GENERATED_FILES_JSON);
    }

    static String officialPackageCollector(int targetCaseCount, long standardTimeLimitMillis,
                                           long standardMemoryLimitBytes, long maxCaseBytes,
                                           long maxPackageBytes, LangProfile lang) {
        return """
                python3 - <<'PY'
                import hashlib
                import json
                import os
                import shutil
                import subprocess
                import sys
                import time
                import zipfile
                from pathlib import Path
                try:
                    import resource
                except Exception:
                    resource = None

                TARGET_CASE_COUNT = %d
                STANDARD_TIME_LIMIT_SECONDS = max(1, int((%d + 999) // 1000))
                STANDARD_MEMORY_LIMIT_BYTES = %d
                MAX_CASE_BYTES = %d
                MAX_PACKAGE_BYTES = %d
                OUTPUT_JSON = Path('%s')
                OUTPUT_ZIP = Path('%s')
                SOURCE_FILE = %s
                COMPILE_ARGS = %s
                RUN_ARGS = %s
                HAS_COMPILE = %s

                def sha256_file(path):
                    h = hashlib.sha256()
                    with path.open('rb') as f:
                        for chunk in iter(lambda: f.read(1024 * 1024), b''):
                            h.update(chunk)
                    return h.hexdigest()

                def write_package(status, error_code=None, error_message=None, cases=None, scan_root=None, manifest_json='', package_path=None, generated_input_count=0, generated_output_count=0):
                    cases = cases or []
                    total_input = sum(c.get('inputBytes', 0) for c in cases)
                    total_output = sum(c.get('outputBytes', 0) for c in cases)
                    largest_case = 0
                    for c in cases:
                        largest_case = max(largest_case, c.get('inputBytes', 0), c.get('outputBytes', 0))
                    payload = {
                        'status': status,
                        'errorCode': error_code,
                        'errorMessage': error_message,
                        'caseCount': len(cases),
                        'generatedInputCount': generated_input_count,
                        'generatedOutputCount': generated_output_count,
                        'totalInputBytes': total_input,
                        'totalOutputBytes': total_output,
                        'totalBytes': total_input + total_output,
                        'largestCaseBytes': largest_case,
                        'manifestJson': manifest_json,
                        'scanRoot': scan_root,
                        'cases': cases,
                        'packageFileName': OUTPUT_ZIP.name,
                    }
                    if package_path and package_path.exists():
                        payload['packageFileSizeBytes'] = package_path.stat().st_size
                        payload['packageSha256'] = sha256_file(package_path)
                    OUTPUT_JSON.write_text(json.dumps(payload, ensure_ascii=False), encoding='utf-8')

                def ensure_tiny_zip():
                    with zipfile.ZipFile(OUTPUT_ZIP, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
                        zf.writestr('README.txt', 'AI-OJ official package was not materialized. See .aioj_official_package.json.\\n')

                def candidate_inputs(root, relative_to_root):
                    items = []
                    if not root.exists():
                        return items
                    for path in sorted(root.rglob('*.in')):
                        if not path.is_file():
                            continue
                        name = path.name
                        if name.startswith('.aioj_') or Path(name).stem.startswith('stress_small_'):
                            continue
                        relative = path.as_posix() if relative_to_root == Path('.') else path.relative_to(relative_to_root).as_posix()
                        key = relative[:-3]
                        items.append((key, relative, path))
                    return items

                def count_generated(root, relative_to_root):
                    input_count = 0
                    output_count = 0
                    if not root.exists():
                        return 0, 0
                    for path in root.rglob('*'):
                        if not path.is_file():
                            continue
                        if path.suffix == '.in':
                            input_count += 1
                        elif path.suffix == '.out':
                            output_count += 1
                    return input_count, output_count

                def run_generator():
                    return subprocess.run([sys.executable, 'generator.py'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

                def compile_standard():
                    if not HAS_COMPILE:
                        return None
                    return subprocess.run(COMPILE_ARGS, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

                def child_memory_kb():
                    if resource is None:
                        return None
                    try:
                        value = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
                        if sys.platform == 'darwin':
                            value = (value + 1023) // 1024
                        return int(value)
                    except Exception:
                        return None

                def run_standard(input_path, output_path):
                    started = time.perf_counter()
                    try:
                        with input_path.open('rb') as fin, output_path.open('wb') as fout:
                            result = subprocess.run(RUN_ARGS, stdin=fin, stdout=fout, stderr=subprocess.PIPE,
                                                    timeout=STANDARD_TIME_LIMIT_SECONDS)
                            elapsed_ms = max(0, int((time.perf_counter() - started) * 1000))
                            return result, elapsed_ms, child_memory_kb()
                    except subprocess.TimeoutExpired as ex:
                        elapsed_ms = max(0, int((time.perf_counter() - started) * 1000))
                        return ex, elapsed_ms, child_memory_kb()

                def safe_entry_name(index, suffix):
                    return f'testcases/{index:03d}{suffix}'

                def manifest_for(cases):
                    return {
                        'version': 'ai-draft-hidden-' + time.strftime('%%Y%%m%%d%%H%%M%%S'),
                        'checker': {'type': 'STANDARD'},
                        'cases': [
                            {
                                'name': c['name'],
                                'input': c['inputPath'],
                                'output': c['outputPath'],
                                'sample': False
                            }
                            for c in cases
                        ]
                    }

                ensure_tiny_zip()
                generator_result = run_generator()
                if generator_result.returncode != 0:
                    detail = (generator_result.stderr or generator_result.stdout or 'generator.py failed')[:2000]
                    write_package('FAILED', 'GENERATOR_PYTHON_FAILED', detail)
                    sys.stderr.write(detail)
                    sys.exit(generator_result.returncode)

                inputs = candidate_inputs(Path('testcases'), Path('testcases'))
                scan_root = 'testcases'
                generated_input_count, generated_output_count = count_generated(Path('testcases'), Path('testcases'))
                if not inputs:
                    inputs = candidate_inputs(Path('.'), Path('.'))
                    scan_root = '.'
                    generated_input_count, generated_output_count = count_generated(Path('.'), Path('.'))

                if not inputs:
                    write_package('FAILED', 'GENERATOR_MISSING_INPUTS',
                                  'testcaseGeneratorPython generated no official .in cases',
                                  scan_root=scan_root, generated_input_count=generated_input_count,
                                  generated_output_count=generated_output_count)
                    sys.exit(0)

                # Auxiliary stress_small_* inputs are used only for optional
                # cross-checking and are not official hidden test cases. The
                # coverage denominator must describe the exact set that will
                # be packaged and verified below.
                generated_input_count = len(inputs)

                compile_result = compile_standard()
                if compile_result is not None and compile_result.returncode != 0:
                    detail = (compile_result.stderr or compile_result.stdout or 'standard solution compile failed')[:2000]
                    write_package('FAILED', 'STANDARD_COMPILE_FAILED', detail, scan_root=scan_root,
                                  generated_input_count=generated_input_count, generated_output_count=generated_output_count)
                    sys.exit(0)

                package_root = Path('.aioj_official_package')
                if package_root.exists():
                    shutil.rmtree(package_root)
                (package_root / 'testcases').mkdir(parents=True, exist_ok=True)

                cases = []
                total_bytes = 0
                # Exhaustive validation is a hard correctness invariant. Never
                # slice this collection: generatedInputCount must equal the
                # number of verified and packaged cases.
                for index, (key, relative, input_path) in enumerate(inputs, 1):
                    input_size = input_path.stat().st_size
                    if input_size > MAX_CASE_BYTES:
                        write_package('FAILED', 'GENERATOR_CASE_TOO_LARGE',
                                      f'{relative} size {input_size} exceeds per-file limit {MAX_CASE_BYTES}',
                                      cases=cases, scan_root=scan_root, generated_input_count=generated_input_count,
                                      generated_output_count=generated_output_count)
                        sys.exit(0)
                    out_path = package_root / safe_entry_name(index, '.out')
                    in_path = package_root / safe_entry_name(index, '.in')
                    shutil.copyfile(input_path, in_path)
                    run_result, run_time_ms, run_memory_kb = run_standard(in_path, out_path)
                    if isinstance(run_result, subprocess.TimeoutExpired):
                        write_package('FAILED', 'STANDARD_TLE_ON_GENERATED_CASE',
                                      f'standardSolutionCode exceeded time limit on {relative}',
                                      cases=cases, scan_root=scan_root, generated_input_count=generated_input_count,
                                      generated_output_count=generated_output_count)
                        sys.exit(0)
                    if run_result.returncode != 0:
                        detail = (run_result.stderr.decode('utf-8', errors='replace') if isinstance(run_result.stderr, bytes) else (run_result.stderr or 'standard solution runtime error'))[:2000]
                        write_package('FAILED', 'STANDARD_RUNTIME_ON_GENERATED_CASE',
                                      f'{relative}: {detail}', cases=cases, scan_root=scan_root,
                                      generated_input_count=generated_input_count,
                                      generated_output_count=generated_output_count)
                        sys.exit(0)
                    output_size = out_path.stat().st_size if out_path.exists() else 0
                    if not out_path.exists():
                        write_package('FAILED', 'STANDARD_OUTPUT_MATERIALIZATION_FAILED',
                                      f'standardSolutionCode produced no output file for {relative}',
                                      cases=cases, scan_root=scan_root, generated_input_count=generated_input_count,
                                      generated_output_count=generated_output_count)
                        sys.exit(0)
                    if output_size > MAX_CASE_BYTES:
                        write_package('FAILED', 'GENERATOR_CASE_TOO_LARGE',
                                      f'{safe_entry_name(index, ".out")} size {output_size} exceeds per-file limit {MAX_CASE_BYTES}',
                                      cases=cases, scan_root=scan_root, generated_input_count=generated_input_count,
                                      generated_output_count=generated_output_count)
                        sys.exit(0)
                    total_bytes += input_size + output_size
                    if total_bytes > MAX_PACKAGE_BYTES:
                        write_package('FAILED', 'GENERATOR_PACKAGE_TOO_LARGE',
                                      f'official hidden testcase package size {total_bytes} exceeds limit {MAX_PACKAGE_BYTES}',
                                      cases=cases, scan_root=scan_root, generated_input_count=generated_input_count,
                                      generated_output_count=generated_output_count)
                        sys.exit(0)
                    cases.append({
                        'name': f'{index:03d}',
                        'sourceInputPath': relative,
                        'inputPath': safe_entry_name(index, '.in'),
                        'outputPath': safe_entry_name(index, '.out'),
                        'inputBytes': input_size,
                        'outputBytes': output_size,
                        'inputSha256': sha256_file(in_path),
                        'outputSha256': sha256_file(out_path),
                        'status': 'ACCEPTED',
                        'timeMillis': run_time_ms,
                        'memoryKb': run_memory_kb,
                        'message': 'Accepted'
                    })

                # A successful package is exhaustive: every discovered input
                # must have been executed and materialized into the package.
                if len(cases) != len(inputs) or len(cases) != generated_input_count:
                    write_package('FAILED', 'HIDDEN_CASE_COVERAGE_INCOMPLETE',
                                  f'generated={generated_input_count}, candidates={len(inputs)}, verified={len(cases)}',
                                  cases=cases, scan_root=scan_root,
                                  generated_input_count=generated_input_count,
                                  generated_output_count=generated_output_count)
                    sys.exit(0)
                manifest_json = json.dumps(manifest_for(cases), ensure_ascii=False, separators=(',', ':'))
                (package_root / 'manifest.json').write_text(manifest_json, encoding='utf-8')
                with zipfile.ZipFile(OUTPUT_ZIP, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
                    zf.write(package_root / 'manifest.json', 'manifest.json')
                    for c in cases:
                        zf.write(package_root / c['inputPath'], c['inputPath'])
                        zf.write(package_root / c['outputPath'], c['outputPath'])
                write_package('PASSED', cases=cases, scan_root=scan_root, manifest_json=manifest_json,
                              package_path=OUTPUT_ZIP, generated_input_count=generated_input_count,
                              generated_output_count=generated_output_count)
                PY
                """.formatted(
                targetCaseCount,
                standardTimeLimitMillis,
                standardMemoryLimitBytes,
                maxCaseBytes,
                maxPackageBytes,
                OFFICIAL_PACKAGE_JSON,
                OFFICIAL_PACKAGE_ZIP,
                pythonString(lang.sourceFileName()),
                pythonList(lang.compileArgs()),
                pythonList(lang.runArgs()),
                lang.requiresCompile() ? "True" : "False"
        );
    }

    public void deleteCachedFile(String fileId) {
        restClient.delete().uri("/file/{id}", fileId).retrieve().toBodilessEntity();
    }

    public void downloadCachedFile(String fileId, OutputStream outputStream) {
        restClient.get().uri("/file/{id}", fileId).exchange((request, response) -> {
            try (var input = response.getBody()) {
                if (input != null) {
                    input.transferTo(outputStream);
                }
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to stream sandbox cached file", ex);
            }
            return null;
        });
    }

    private SandboxRunResult runSandbox(List<Map<String, Object>> commands) {
        List<SandboxRunResult> results = restClient.post()
                .uri("/run")
                .body(Map.of("cmd", commands))
                .retrieve()
                .body(RUN_RESULT_TYPE);
        if (results == null || results.isEmpty() || results.get(0) == null) {
            throw new IllegalStateException("Sandbox returned empty /run response");
        }
        return results.get(0);
    }

    private List<Map<String, Object>> standardFiles(String stdin, int stdoutCollectLimit, int stderrCollectLimit) {
        return List.of(
                Map.of("content", stdin),
                Map.of("name", "stdout", "max", stdoutCollectLimit),
                Map.of("name", "stderr", "max", stderrCollectLimit)
        );
    }

    public static long millisToNanos(long millis) {
        return safeMultiply(millis, 1_000_000L);
    }

    public static long kbToBytes(long kb) {
        return safeMultiply(kb, 1024L);
    }

    static int stdoutBaseCollectLimitBytes(int configuredLimit) {
        return positiveOrDefault(configuredLimit, DEFAULT_STDOUT_BASE_COLLECT_LIMIT_BYTES);
    }

    static int stderrCollectLimitBytes(int configuredLimit) {
        return positiveOrDefault(configuredLimit, DEFAULT_STDERR_COLLECT_LIMIT_BYTES);
    }

    private int stdoutBaseCollectLimitBytes() {
        return stdoutBaseCollectLimitBytes(properties.getStdoutBaseCollectLimitBytes());
    }

    private int stderrCollectLimitBytes() {
        return stderrCollectLimitBytes(properties.getStderrCollectLimitBytes());
    }

    private static String stripTrailingPath(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return "http://localhost:8090";
        }
        String trimmed = endpoint.trim();
        if (trimmed.endsWith("/run") || trimmed.endsWith("/execute")) {
            return trimmed.substring(0, trimmed.lastIndexOf('/'));
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static Duration resolveTimeout(Duration timeout) {
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(10) : timeout;
    }

    private static long safeMultiply(long value, long factor) {
        if (value > Long.MAX_VALUE / factor) {
            return Long.MAX_VALUE;
        }
        return value * factor;
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static int safeTargetCaseCount(int targetCaseCount) {
        if (targetCaseCount <= 0) {
            return DEFAULT_SCRIPT_TARGET_CASE_COUNT;
        }
        return Math.min(MAX_SCRIPT_TARGET_CASE_COUNT, targetCaseCount);
    }

    private static String safeCollectMode(String collectMode) {
        if (COLLECT_MODE_OFFICIAL_INPUTS.equals(collectMode)) {
            return COLLECT_MODE_OFFICIAL_INPUTS;
        }
        if (COLLECT_MODE_OFFICIAL_PACKAGE.equals(collectMode)) {
            return COLLECT_MODE_OFFICIAL_PACKAGE;
        }
        return COLLECT_MODE_PAIRED;
    }

    private static String pythonString(String value) {
        String escaped = (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "'" + escaped + "'";
    }

    private static String pythonList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return "[" + values.stream().map(SandboxExecutionClient::pythonString).reduce((a, b) -> a + ", " + b).orElse("") + "]";
    }

    private static Long nanosToMillis(Long nanos) {
        if (nanos == null) {
            return null;
        }
        if (nanos <= 0) {
            return 0L;
        }
        return (nanos + 999_999L) / 1_000_000L;
    }

    private static Long bytesToKb(Long bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes <= 0) {
            return 0L;
        }
        return (bytes + 1023L) / 1024L;
    }

    private static String fileContent(SandboxRunResult result, String name) {
        return result.files() == null ? null : result.files().get(name);
    }

    private static String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        return "Sandbox execution failed";
    }

    private record LangProfile(
            boolean requiresCompile,
            List<String> compileArgs,
            String sourceFileName,
            String executableName,
            List<String> runArgs,
            List<String> envVars
    ) {
        static LangProfile of(String lang) {
            return switch (lang) {
                case "cpp" -> new LangProfile(true,
                        List.of("/usr/bin/g++", "-O2", "-std=c++17", "main.cpp", "-o", "main"),
                        "main.cpp", "main",
                        List.of("./main"),
                        List.of("PATH=/usr/bin:/bin"));
                case "java" -> new LangProfile(true,
                        List.of("/usr/bin/javac", "Main.java"),
                        "Main.java", "Main.class",
                        List.of("/usr/bin/java", "-cp", ".", "Main"),
                        List.of("PATH=/usr/bin:/bin"));
                case "python" -> new LangProfile(false,
                        List.of(), "main.py", null,
                        List.of("/usr/bin/python3", "main.py"),
                        List.of("PATH=/usr/bin:/bin"));
                default -> throw new IllegalArgumentException("Unsupported language: " + lang);
            };
        }
    }

    public record CompileOutcome(boolean failed,
                                 String fileId,
                                 String message,
                                 Long timeMillis,
                                 Long memoryKb,
                                 String stderr,
                                 Integer exitStatus,
                                 Long runTimeMillis) {
        static CompileOutcome success(String fileId, Long timeMillis, Long memoryKb, String stderr,
                                      Integer exitStatus, Long runTimeMillis) {
            return new CompileOutcome(false, fileId, "Accepted", timeMillis, memoryKb, stderr,
                    exitStatus, runTimeMillis);
        }

        static CompileOutcome failed(String message, Long timeMillis, Long memoryKb, String stderr,
                                     Integer exitStatus, Long runTimeMillis) {
            return new CompileOutcome(true, null, message, timeMillis, memoryKb, stderr,
                    exitStatus, runTimeMillis);
        }
    }

    public record RunOutcome(String status,
                             String message,
                             Long timeMillis,
                             Long memoryKb,
                             String stdout,
                             String stderr,
                             Integer exitStatus,
                             Long runTimeMillis,
                             String generatedFilesJson,
                             String officialPackageJson,
                             Map<String, String> fileIds) {
        public RunOutcome(String status, String message, Long timeMillis, Long memoryKb, String stdout,
                          String stderr, Integer exitStatus, Long runTimeMillis, String generatedFilesJson) {
            this(status, message, timeMillis, memoryKb, stdout, stderr, exitStatus, runTimeMillis,
                    generatedFilesJson, null, Map.of());
        }

        static RunOutcome from(SandboxRunResult result) {
            return from(result, null);
        }

        static RunOutcome from(SandboxRunResult result, String generatedFilesJson) {
            return new RunOutcome(
                    result.status(),
                    firstText(result.error(), result.status()),
                    nanosToMillis(result.time()),
                    bytesToKb(result.memory()),
                    fileContent(result, "stdout"),
                    fileContent(result, "stderr"),
                    result.exitStatus(),
                    nanosToMillis(result.runTime()),
                    generatedFilesJson,
                    null,
                    result.fileIds()
            );
        }

        static RunOutcome fromOfficialPackage(SandboxRunResult result, String officialPackageJson) {
            return new RunOutcome(
                    result.status(),
                    firstText(result.error(), result.status()),
                    nanosToMillis(result.time()),
                    bytesToKb(result.memory()),
                    fileContent(result, "stdout"),
                    fileContent(result, "stderr"),
                    result.exitStatus(),
                    nanosToMillis(result.runTime()),
                    null,
                    officialPackageJson,
                    result.fileIds()
            );
        }

        static RunOutcome fromCompileFailure(CompileOutcome compile) {
            return new RunOutcome("Compile Error", compile.message(), compile.timeMillis(), compile.memoryKb(),
                    null, compile.stderr(), compile.exitStatus(), compile.runTimeMillis(), null, null, Map.of());
        }

        public boolean accepted() {
            return "Accepted".equals(status);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SandboxRunResult(String status,
                                    Integer exitStatus,
                                    Long time,
                                    Long memory,
                                    Long runTime,
                                    Integer procPeak,
                                    Map<String, String> files,
                                    String error,
                                    List<SandboxFileError> fileError,
                                    Map<String, String> fileIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SandboxFileError(String name, String type, String message) {
    }
}
