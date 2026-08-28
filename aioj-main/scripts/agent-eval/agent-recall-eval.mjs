#!/usr/bin/env node
/**
 * Agent Core V3 P4 recall eval: advanced-recall gap measurement (design doc §10 P4,
 * §11 metrics). Runs against the live local stack (gateway -> ai-service -> DeepSeek)
 * and measures from the observability plane (ai_agent_runs / ai_tool_calls /
 * ai_turn_digests), never from subjective answer grading.
 *
 * Groups (P4-0 baseline: X is EXPECTED to fail before P4-1 cross-conversation work):
 *   - X: cross-conversation recall — plant in conversation 1, ask from conversation 2
 *        (scope=ALL_MY_CONVERSATIONS does not exist yet at baseline);
 *   - S: semantic-only reference — the query uses a technique name that never appears
 *        in the statement surface, so pure KEYWORD matching cannot hit;
 *   - M: highly-similar histories — 3 near-identical problems differing only in one
 *        constraint + marker; the answer must pick the right one;
 *   - R: digest-not-ready — ask immediately after planting (no curator wait); the
 *        synchronous stub digest must still make the turn retrievable;
 *   - N: two-batch ordinal disambiguation — "这一批第1题" vs "最开始那批第2题" vs a
 *        bare ambiguous "第2题" (resolve-to-recent OR explicit clarification both
 *        count as correct).
 *
 * P4-0 is a BASELINE run: the script always exits 0 on a completed measurement
 * (non-zero only on infra FATAL). Set AIOJ_EVAL_STRICT_GATE=true later (P4-3) to
 * enforce: long-range-style fetch-hit cases all pass, exact_detail_accuracy >= 0.9,
 * and cross-conversation cases all pass.
 *
 * Env:
 *   AIOJ_EVAL_BASE_URL   gateway base       (default http://127.0.0.1:8101)
 *   AIOJ_EVAL_ACCOUNT    student account    (default recalleval001)
 *   AIOJ_EVAL_PASSWORD   account password   (required)
 *   AIOJ_EVAL_GROUP      comma subset, e.g. "X,S" (default: all)
 *   AIOJ_EVAL_KEEP       keep eval rows     (default false)
 *   AIOJ_EVAL_STRICT_GATE  enforce P4-3 thresholds (default false)
 *   MYSQL_EXE / MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DB
 *
 * Raw per-case output goes to scripts/agent-eval/out/ (git-ignored); the console
 * summary plus <runTag>-report.json is the durable conclusion.
 */

import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = join(HERE, "out");

const config = {
  baseUrl: (process.env.AIOJ_EVAL_BASE_URL || "http://127.0.0.1:8101").replace(/\/+$/, ""),
  account: process.env.AIOJ_EVAL_ACCOUNT || "recalleval001",
  password: process.env.AIOJ_EVAL_PASSWORD || "",
  keep: (process.env.AIOJ_EVAL_KEEP || "false") === "true",
  strictGate: (process.env.AIOJ_EVAL_STRICT_GATE || "false") === "true",
  groups: (process.env.AIOJ_EVAL_GROUP || "X,S,M,R,N").split(",").map((g) => g.trim().toUpperCase()),
  mysql: {
    exe: process.env.MYSQL_EXE || "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe",
    host: process.env.MYSQL_HOST || "127.0.0.1",
    port: process.env.MYSQL_PORT || "3306",
    user: process.env.MYSQL_USER || "aioj",
    password: process.env.MYSQL_PASSWORD || "",
    db: process.env.MYSQL_DB || "ai_oj_next",
  },
};

const RUN_TAG = `rcleval-${Date.now()}`;
const MARKER_PREFIX = "rcleval-";
const FILLER_ID_BASE = 8_900_000_000_000_000_000n;

function fail(message) {
  console.error(`FATAL: ${message}`);
  process.exit(2);
}

if (!config.password) {
  fail("AIOJ_EVAL_PASSWORD is required");
}
if (!config.mysql.password) {
  fail("MYSQL_PASSWORD env is required for observability queries");
}

// ---------------------------------------------------------------------------
// MySQL CLI helper (same conventions as agent-context-eval.mjs)
// ---------------------------------------------------------------------------

function mysqlArgs() {
  return [
    `--host=${config.mysql.host}`,
    `--port=${config.mysql.port}`,
    `--user=${config.mysql.user}`,
    `--password=${config.mysql.password}`,
    `--database=${config.mysql.db}`,
    "--batch", "--raw", "--silent", "--skip-column-names",
    "--default-character-set=utf8mb4",
  ];
}

function query(sql) {
  const result = spawnSync(config.mysql.exe, [...mysqlArgs(), `--execute=${sql}`], {
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.status !== 0) {
    fail(`mysql query failed: ${result.stderr || result.stdout}`);
  }
  const out = (result.stdout || "").replace(/\r\n/g, "\n").trim();
  if (!out) {
    return [];
  }
  return out.split("\n").map((line) => line.split("\t").map((col) => (col === "NULL" ? null : col)));
}

function queryOne(sql) {
  const rows = query(sql);
  return rows.length ? rows[0] : null;
}

function executeSqlFile(filePath) {
  const sourcePath = filePath.replace(/\\/g, "/");
  const result = spawnSync(config.mysql.exe, [...mysqlArgs(), `--execute=source ${sourcePath}`], {
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  if (result.status !== 0) {
    fail(`mysql script failed: ${result.stderr || result.stdout}`);
  }
}

function sqlString(value) {
  return `'${String(value).replace(/\\/g, "\\\\").replace(/'/g, "''")}'`;
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

async function apiRequest(method, path, body, token, timeoutMs = 200_000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${config.baseUrl}${path}`, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    const text = await response.text();
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch {
      fail(`${path} returned non-JSON (HTTP ${response.status}): ${text.slice(0, 200)}`);
    }
    if (!response.ok || parsed == null || parsed.data == null) {
      fail(`${path} failed (HTTP ${response.status}): ${text.slice(0, 300)}`);
    }
    return { data: parsed.data, raw: text };
  } finally {
    clearTimeout(timer);
  }
}

async function apiPost(path, body, token, timeoutMs = 200_000) {
  return apiRequest("POST", path, body, token, timeoutMs);
}

/** Snowflake ids are 19-digit numbers that JS Number cannot hold; pull them from raw JSON. */
function extractDataId(raw, label) {
  const markerIndex = raw.indexOf('"data"');
  const searchable = markerIndex >= 0 ? raw.slice(markerIndex) : raw;
  const match = searchable.match(/"id"\s*:\s*"?(\d+)"?/);
  if (!match) {
    fail(`${label} response did not include an id`);
  }
  return match[1];
}

async function login() {
  const { data } = await apiPost("/api/v1/auth/login", {
    account: config.account,
    password: config.password,
  }, null, 30_000);
  if (!data.accessToken) {
    fail("login response missing accessToken");
  }
  return data.accessToken;
}

// ---------------------------------------------------------------------------
// Eval primitives (mirrors agent-context-eval.mjs)
// ---------------------------------------------------------------------------

let sendSeq = 0;

/** Sends one chat turn; returns {messageId, conversationId, content, turnId, userMessageId}. */
async function sendTurn(token, conversationId, message, note) {
  const clientMessageId = `${RUN_TAG}-${String(++sendSeq).padStart(3, "0")}`;
  const { data, raw } = await apiPost("/api/v1/ai/chat/send", {
    conversationId,
    message,
    clientMessageId,
  }, token);
  const messageId = extractDataId(raw, note);
  const turnRow = queryOne(
    `SELECT id, user_message_id FROM ai_turns WHERE assistant_message_id = '${messageId}' LIMIT 1`
  );
  if (!turnRow) {
    fail(`no ai_turns row for assistant message ${messageId} (${note}) — V3 pipeline inactive?`);
  }
  return {
    messageId,
    userMessageId: turnRow[1],
    conversationId: data.conversationId,
    content: data.content || "",
    turnId: turnRow[0],
    clientMessageId,
  };
}

/** Fast preflight: without the digest pipeline the run would burn minutes in timeouts. */
function preflightDigestPipeline(turnId) {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const row = queryOne(`SELECT status FROM ai_turn_digests WHERE turn_id = '${turnId}' LIMIT 1`);
    if (row) {
      return;
    }
    sleepSync(2_000);
  }
  fail(`no digest row appeared for turn ${turnId} within 60s — rebuild/restart ai-service first`);
}

/** Waits until the latest digest version of a turn reaches READY (curator async). */
function waitDigestReady(turnId, timeoutMs = 150_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const row = queryOne(
      `SELECT status, id FROM ai_turn_digests WHERE turn_id = '${turnId}' ORDER BY digest_version DESC LIMIT 1`
    );
    if (row && row[0] === "READY") {
      return { status: "READY", digestId: row[1] };
    }
    sleepSync(3_000);
  }
  const row = queryOne(
    `SELECT status, id FROM ai_turn_digests WHERE turn_id = '${turnId}' ORDER BY digest_version DESC LIMIT 1`
  );
  return { status: row ? row[0] : "MISSING", digestId: row ? row[1] : null };
}

function sleepSync(ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    // busy-wait: keeps the script dependency-free and synchronous in DB polling spots
  }
}

function toolCalls(turnId) {
  const rows = query(
    `SELECT tool_name, status, COALESCE(CAST(arguments_redacted AS CHAR), '') ` +
    `FROM ai_tool_calls WHERE turn_id = '${turnId}' ORDER BY call_seq`
  );
  return rows.map(([toolName, status, args]) => ({ toolName, status, args: args || "" }));
}

function runRow(turnId) {
  return queryOne(`SELECT status, tool_call_count, model FROM ai_agent_runs WHERE turn_id = '${turnId}' LIMIT 1`);
}

// ---------------------------------------------------------------------------
// Filler messages (same technique as agent-context-eval.mjs)
// ---------------------------------------------------------------------------

const FILLER_PARAGRAPH =
  "The quick brown fox jumps over the lazy dog near the riverbank while engineers " +
  "discuss caching layers, queue backpressure, and idempotent retries in distributed " +
  "systems. Nothing in this message relates to any algorithm problem or earlier turn. ";

function insertFiller(conversationId, userId, options = {}) {
  const perMessage = options.perMessageChars || 800;
  const messageCount = options.messageCount ?? 12;
  const lines = ["SET NAMES utf8mb4;", "START TRANSACTION;"];
  const baseContent = FILLER_PARAGRAPH.repeat(Math.ceil(perMessage / FILLER_PARAGRAPH.length));
  for (let i = 0; i < messageCount; i++) {
    const id = FILLER_ID_BASE + BigInt(Date.now() % 1_000_000) * 100_000n + BigInt(i);
    const role = i % 2 === 0 ? "user" : "assistant";
    const content = baseContent.slice(0, perMessage - 40) + ` filler-seq-${i}`;
    lines.push(
      `INSERT INTO ai_messages (id, conversation_id, user_id, client_message_id, role, content, status, created_at, updated_at, completed_at) VALUES (` +
      `${id.toString()}, '${conversationId}', ${userId}, '${RUN_TAG}-fill', '${role}', ${sqlString(content)}, 'COMPLETED', NOW(3), NOW(3), NOW(3));`
    );
  }
  lines.push("COMMIT;");
  mkdirSync(OUT_DIR, { recursive: true });
  const file = join(OUT_DIR, `filler-${RUN_TAG}-${conversationId.slice(-6)}.sql`);
  writeFileSync(file, lines.join("\n"), "utf8");
  executeSqlFile(file);
  return messageCount;
}

// ---------------------------------------------------------------------------
// Cleanup (only rows tied to rcleval-% client message ids)
// ---------------------------------------------------------------------------

function evalConversations() {
  return query(
    `SELECT DISTINCT conversation_id FROM ai_messages WHERE client_message_id LIKE '${MARKER_PREFIX}%'`
  ).map(([id]) => id);
}

function cleanupEvalData() {
  const conversations = evalConversations();
  if (!conversations.length) {
    return 0;
  }
  const idList = conversations.map(sqlString).join(",");
  const digestIds = query(
    `SELECT id FROM ai_turn_digests WHERE conversation_id IN (${idList})`
  ).map(([id]) => id);
  if (digestIds.length) {
    const dgList = digestIds.map(sqlString).join(",");
    query(`DELETE FROM ai_retrieval_chunks WHERE owner_type = 'TURN_DIGEST' AND owner_id IN (${dgList})`);
    query(`DELETE FROM ai_async_jobs WHERE job_type = 'EMBED_DIGEST' AND CAST(payload_json AS CHAR) REGEXP '"digestId":(${digestIds.join("|")})'`);
  }
  query(`DELETE FROM ai_async_jobs WHERE job_type = 'TURN_CURATE' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.conversationId')) IN (${idList})`);
  const turnIds = query(`SELECT id FROM ai_turns WHERE conversation_id IN (${idList})`).map(([id]) => id);
  if (turnIds.length) {
    const tList = turnIds.map(sqlString).join(",");
    query(`DELETE FROM ai_context_manifests WHERE turn_id IN (${tList})`);
    query(`DELETE FROM ai_tool_calls WHERE turn_id IN (${tList})`);
    query(`DELETE FROM ai_guard_decisions WHERE turn_id IN (${tList})`);
    query(`DELETE FROM ai_policy_snapshots WHERE turn_id IN (${tList})`);
    query(`DELETE FROM ai_agent_runs WHERE turn_id IN (${tList})`);
  }
  query(`DELETE FROM ai_turn_digests WHERE conversation_id IN (${idList})`);
  query(`DELETE FROM ai_turns WHERE conversation_id IN (${idList})`);
  query(`DELETE FROM ai_messages WHERE conversation_id IN (${idList})`);
  query(`DELETE FROM ai_conversations WHERE id IN (${idList})`);
  return conversations.length;
}

// ---------------------------------------------------------------------------
// Case bookkeeping
// ---------------------------------------------------------------------------

const cases = [];

function recordCase(entry) {
  cases.push(entry);
  const flag = entry.warn ? "WARN" : entry.pass ? "PASS" : "FAIL";
  console.log(`  [${flag}] ${entry.name}${entry.detail ? " — " + entry.detail : ""}`);
}

/** Instruments one answered turn and records the standard recall checks. */
function instrument(turn, expectations) {
  const run = runRow(turn.turnId);
  const calls = toolCalls(turn.turnId);
  const searchCalls = calls.filter((c) => c.toolName === "context.search_digests" || c.toolName === "context.search_exact");
  const fetchCalls = calls.filter((c) => c.toolName === "context.fetch_sources");
  const fetchSuccess = fetchCalls.filter((c) => c.status === "SUCCESS");
  const expectedRefs = expectations.expectedFetchRefs || [];
  const fetchHit = expectedRefs.length === 0
    ? null
    : fetchCalls.some((c) => expectedRefs.some((ref) => c.args.includes(ref)));
  const markerHit = expectations.marker ? turn.content.includes(expectations.marker) : null;
  return {
    runStatus: run ? run[0] : "MISSING",
    model: run ? run[2] : null,
    searchCallCount: searchCalls.length,
    fetchCallCount: fetchCalls.length,
    fetchSuccessCount: fetchSuccess.length,
    fetchHit,
    markerHit,
    searchArgsPreview: searchCalls.map((c) => c.args.slice(0, 200)),
    fetchArgsPreview: fetchCalls.map((c) => c.args.slice(0, 200)),
    answerPreview: turn.content.slice(0, 160),
  };
}

/** Expected refs for one planted turn: both message sides plus its digest when known. */
function expectedRefsFor(plantedTurn, digest) {
  const refs = [`msg-${plantedTurn.userMessageId}`, `msg-${plantedTurn.messageId}`];
  if (digest && digest.digestId) {
    refs.push(`dg-${digest.digestId}`);
  }
  return refs;
}

/** Heuristic: the model asked a clarifying question instead of guessing (counts as correct for N3). */
function looksLikeClarification(content) {
  return /哪一批|哪一道|你指的是|是指|澄清|请问/.test(content || "");
}

// ---------------------------------------------------------------------------
// Problem text builders
// ---------------------------------------------------------------------------

/** A problem statement with the marker buried past the digest summary preview (~100 chars). */
function buildProblem(title, topicLine, marker, extraLine = "") {
  return `【题目】${title}\n` +
    `${topicLine}\n` +
    `输入：第一行两个整数 n 和 q；第二行 n 个整数；接下来 q 行每行一组查询参数。\n` +
    `输出：对每个查询输出一行答案。\n` +
    (extraLine ? `${extraLine}\n` : "") +
    `备注：本题在评测系统里的内部校验标记是 ${marker}，之后讨论这道题时请原样带上这个标记。`;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
  console.log(`Agent Core V3 P4 recall eval — run tag ${RUN_TAG}`);
  console.log(`gateway=${config.baseUrl} account=${config.account} groups=${config.groups.join(",")} strictGate=${config.strictGate}`);

  const removed = cleanupEvalData();
  if (removed > 0) {
    console.log(`cleaned ${removed} leftover eval conversation(s) from previous runs`);
  }

  const token = await login();
  console.log("login ok");

  const wants = (group) => config.groups.includes(group);
  let userId = null;

  // ----- Group X: cross-conversation recall ----------------------------------
  if (wants("X")) {
    console.log("\n[X] cross-conversation recall (baseline: expected FAIL pre-P4-1)");
    const xMarker = "RCX-4177";
    const xProblem = buildProblem(
      "树上的重链拆分查询",
      "给定一棵 n 个结点的树，结点带权，需要处理 q 组操作：将 u 到 v 路径上的权值整体加上 x，或查询该路径的权值和。",
      xMarker,
      "提示：可以把树按重儿子拆成若干条链来维护。"
    );
    const xPlant = await sendTurn(token, null, xProblem, "X plant conv1");
    const xConv1 = xPlant.conversationId;
    if (!userId) {
      userId = queryOne(`SELECT user_id FROM ai_conversations WHERE id = '${xConv1}' LIMIT 1`)[0];
      console.log(`  user ${userId}`);
    }
    preflightDigestPipeline(xPlant.turnId);
    const xDigest = waitDigestReady(xPlant.turnId);
    console.log(`  conv1 ${xConv1} digest=${xDigest.status}`);

    // Conversation 2: a couple of unrelated turns so the conversation is non-empty.
    const xSmall = await sendTurn(token, null, "你好，我想问一些之前聊过的内容。", "X conv2 smalltalk");
    const xConv2 = xSmall.conversationId;
    console.log(`  conv2 ${xConv2}`);

    const xAsk = await sendTurn(
      token,
      xConv2,
      `我在另一个会话里发过一道带 ${xMarker} 标记的题。那道题的输入格式是什么？原样回答。`,
      "X1 cross-conversation ask"
    );
    const xInfo = instrument(xAsk, { marker: "第一行两个整数 n 和 q", expectedFetchRefs: expectedRefsFor(xPlant, xDigest) });
    // The input-format answer proves the model read the conv1 statement; the marker
    // alone could be echoed from the question itself, so assert on the format text.
    const xMarkerHit = xAsk.content.includes("第一行两个整数 n 和 q");
    recordCase({
      group: "X", name: "X1 cross-conversation locate+fetch",
      pass: xMarkerHit && xInfo.fetchHit === true && xInfo.runStatus === "COMPLETED",
      ...xInfo, markerHit: xMarkerHit,
      detail: `formatHit=${xMarkerHit} fetchHit=${xInfo.fetchHit} search=${xInfo.searchCallCount} fetch=${xInfo.fetchCallCount}`,
    });
  }

  // ----- Group S: semantic-only reference (no shared surface terms) ----------
  if (wants("S")) {
    console.log("\n[S] semantic-only reference (query terms absent from statement)");
    const sMarker1 = "RCS-2210";
    const sMarker2 = "RCS-3389";
    // Statements describe the task WITHOUT naming the technique; the query only
    // names the technique, so the KEYWORD lane cannot bridge the gap.
    const sPlant1 = await sendTurn(token, null, buildProblem(
      "序列上的区间聚合",
      "给定一个长度为 n 的序列，需要回答 q 组询问：每次给出 l 和 r，求 a[l..r] 中所有数字按位异或的结果。",
      sMarker1
    ), "S plant 1");
    const sConv = sPlant1.conversationId;
    if (!userId) {
      userId = queryOne(`SELECT user_id FROM ai_conversations WHERE id = '${sConv}' LIMIT 1`)[0];
      console.log(`  user ${userId}`);
    }
    if (!cases.length) {
      preflightDigestPipeline(sPlant1.turnId);
    }
    const sPlant2 = await sendTurn(token, sConv, buildProblem(
      "静态区间的众数统计",
      "给定一个长度为 n 的序列，需要回答 q 组询问：每次给出 l 和 r，求 a[l..r] 中出现次数最多的那个数值出现了多少次。",
      sMarker2
    ), "S plant 2");
    const sDigests = [waitDigestReady(sPlant1.turnId), waitDigestReady(sPlant2.turnId)];
    console.log(`  digests: ${sDigests.map((d) => d.status).join("/")}`);
    insertFiller(sConv, userId, { messageCount: 12, perMessageChars: 800 });

    const s1 = await sendTurn(token, sConv,
      "我之前发过一道可以用异或前缀和来做的题，它的校验标记是什么？原样回答。", "S1 semantic 异或前缀和");
    const s1Info = instrument(s1, { marker: sMarker1, expectedFetchRefs: expectedRefsFor(sPlant1, sDigests[0]) });
    recordCase({
      group: "S", name: "S1 semantic-only reference (前缀和 not in statement)",
      pass: s1Info.markerHit === true && s1Info.runStatus === "COMPLETED",
      ...s1Info,
      detail: `marker=${s1Info.markerHit} fetchHit=${s1Info.fetchHit} search=${s1Info.searchCallCount} fetch=${s1Info.fetchCallCount}`,
    });

    const s2 = await sendTurn(token, sConv,
      "那道可以用莫队算法维护的区间统计题，标记是什么？原样回答。", "S2 semantic 莫队");
    const s2Info = instrument(s2, { marker: sMarker2, expectedFetchRefs: expectedRefsFor(sPlant2, sDigests[1]) });
    recordCase({
      group: "S", name: "S2 semantic-only reference (莫队 not in statement)",
      pass: s2Info.markerHit === true && s2Info.runStatus === "COMPLETED",
      ...s2Info,
      detail: `marker=${s2Info.markerHit} fetchHit=${s2Info.fetchHit} search=${s2Info.searchCallCount} fetch=${s2Info.fetchCallCount}`,
    });
  }

  // ----- Group M: highly-similar histories ------------------------------------
  if (wants("M")) {
    console.log("\n[M] highly-similar histories (3 near-identical problems)");
    const mSpecs = [
      { marker: "RCM-1105", range: "1 <= n, q <= 1e3", tag: "小规模" },
      { marker: "RCM-2216", range: "1 <= n, q <= 1e5", tag: "中规模" },
      { marker: "RCM-3327", range: "1 <= n, q <= 1e6", tag: "超大规模" },
    ];
    const mPlanted = [];
    let mConv = null;
    for (const [index, spec] of mSpecs.entries()) {
      const planted = await sendTurn(token, mConv, buildProblem(
        `滑动窗口最值（${spec.tag}数据版）`,
        "给定一个长度为 n 的整数数组和一个大小为 k 的滑动窗口，窗口每次向右移动一格，需要输出每个窗口内的最小值。",
        spec.marker,
        `数据范围：${spec.range}，k <= n。`
      ), `M plant ${index + 1}`);
      if (!mConv) {
        mConv = planted.conversationId;
        if (!userId) {
          userId = queryOne(`SELECT user_id FROM ai_conversations WHERE id = '${mConv}' LIMIT 1`)[0];
          console.log(`  user ${userId}`);
        }
        if (!cases.length) {
          preflightDigestPipeline(planted.turnId);
        }
      }
      mPlanted.push(planted);
    }
    const mDigests = mPlanted.map((turn) => waitDigestReady(turn.turnId));
    console.log(`  digests: ${mDigests.map((d) => d.status).join("/")}`);
    insertFiller(mConv, userId, { messageCount: 12, perMessageChars: 800 });

    const m1 = await sendTurn(token, mConv,
      "我发过三道滑动窗口最值题，数据范围是 n 和 q 都不超过 1e5 的那道，它的校验标记是什么？原样回答。",
      "M1 disambiguate by constraint");
    const m1Info = instrument(m1, { marker: "RCM-2216", expectedFetchRefs: expectedRefsFor(mPlanted[1], mDigests[1]) });
    recordCase({
      group: "M", name: "M1 pick by distinguishing constraint",
      pass: m1Info.markerHit === true && m1Info.runStatus === "COMPLETED",
      ...m1Info,
      detail: `marker=${m1Info.markerHit} fetchHit=${m1Info.fetchHit} answer="${m1Info.answerPreview.slice(0, 80)}…"`,
    });

    const m2 = await sendTurn(token, mConv,
      "这三道里的第二道呢？原样告诉我它的标记。", "M2 ordinal among similar set");
    const m2Info = instrument(m2, { marker: "RCM-2216", expectedFetchRefs: expectedRefsFor(mPlanted[1], mDigests[1]) });
    recordCase({
      group: "M", name: "M2 ordinal within highly-similar set",
      pass: m2Info.markerHit === true && m2Info.runStatus === "COMPLETED",
      ...m2Info,
      detail: `marker=${m2Info.markerHit} fetchHit=${m2Info.fetchHit} answer="${m2Info.answerPreview.slice(0, 80)}…"`,
    });
  }

  // ----- Group R: digest-not-ready (stub fallback) -----------------------------
  if (wants("R")) {
    console.log("\n[R] digest-not-ready (no curator wait; stub digest must suffice)");
    const rMarker = "RCR-5520";
    const rPlant = await sendTurn(token, null, buildProblem(
      "括号序列合法性判定",
      "给定一个只包含小括号和中括号的字符串，以及 q 组询问，每组询问给出 l 和 r，判定子串 s[l..r] 是否是合法括号序列。",
      rMarker
    ), "R plant");
    const rConv = rPlant.conversationId;
    if (!userId) {
      userId = queryOne(`SELECT user_id FROM ai_conversations WHERE id = '${rConv}' LIMIT 1`)[0];
      console.log(`  user ${userId}`);
    }
    if (!cases.length) {
      preflightDigestPipeline(rPlant.turnId);
    }
    // Deliberately NO waitDigestReady here: ask immediately after evicting the
    // recent window. The synchronous stub digest must make the turn retrievable.
    insertFiller(rConv, userId, { messageCount: 12, perMessageChars: 800 });
    const rDigestState = queryOne(
      `SELECT status FROM ai_turn_digests WHERE turn_id = '${rPlant.turnId}' ORDER BY digest_version DESC LIMIT 1`
    );
    const r1 = await sendTurn(token, rConv,
      `我刚发的带 ${rMarker} 标记的那道括号题，输出要求是什么？原样回答。`, "R1 immediate ask");
    const r1Info = instrument(r1, { marker: "对每个查询输出一行答案", expectedFetchRefs: expectedRefsFor(rPlant, null) });
    const rMarkerHit = r1.content.includes("对每个查询输出一行答案");
    recordCase({
      group: "R", name: "R1 recall before curator digest READY",
      pass: rMarkerHit && r1Info.runStatus === "COMPLETED",
      ...r1Info, markerHit: rMarkerHit,
      detail: `digestAtAsk=${rDigestState ? rDigestState[0] : "MISSING"} formatHit=${rMarkerHit} fetchHit=${r1Info.fetchHit} search=${r1Info.searchCallCount}`,
    });
  }

  // ----- Group N: two-batch ordinal disambiguation -----------------------------
  if (wants("N")) {
    console.log("\n[N] two-batch ordinal disambiguation");
    const nMarkers = { b1p1: "RCN-1101", b1p2: "RCN-1202", b2p1: "RCN-2103", b2p2: "RCN-2204" };
    const nPlanted = {};
    let nConv = null;
    const plantN = async (key, title, topic) => {
      const planted = await sendTurn(token, nConv, buildProblem(title, topic, nMarkers[key]), `N plant ${key}`);
      if (!nConv) {
        nConv = planted.conversationId;
        if (!userId) {
          userId = queryOne(`SELECT user_id FROM ai_conversations WHERE id = '${nConv}' LIMIT 1`)[0];
          console.log(`  user ${userId}`);
        }
        if (!cases.length) {
          preflightDigestPipeline(planted.turnId);
        }
      }
      nPlanted[key] = planted;
    };
    // Batch 1: two problems, then filler + a chat turn to close the "batch".
    await plantN("b1p1", "单点修改区间求和", "给定一个长度为 n 的数组，支持单点修改和区间求和查询。");
    await plantN("b1p2", "区间修改单点查询", "给定一个长度为 n 的数组，支持区间整体加和单点取值查询。");
    insertFiller(nConv, userId, { messageCount: 10, perMessageChars: 800 });
    await sendTurn(token, nConv, "好的，这批先到这里，我换一批题。", "N batch separator");
    // Batch 2: two more problems, then evict from the recent window.
    await plantN("b2p1", "矩形面积并", "给定平面上 n 个轴对齐矩形，求它们并集的面积。");
    await plantN("b2p2", "最近点对", "给定平面上 n 个点，求欧氏距离最近的一对点的距离。");
    const nDigests = {};
    for (const key of Object.keys(nPlanted)) {
      nDigests[key] = waitDigestReady(nPlanted[key].turnId);
    }
    console.log(`  digests: ${Object.values(nDigests).map((d) => d.status).join("/")}`);
    insertFiller(nConv, userId, { messageCount: 10, perMessageChars: 800 });

    const n1 = await sendTurn(token, nConv,
      "这一批的第 1 题，它的校验标记是什么？原样回答。", "N1 this-batch ordinal");
    const n1Info = instrument(n1, { marker: nMarkers.b2p1, expectedFetchRefs: expectedRefsFor(nPlanted.b2p1, nDigests.b2p1) });
    recordCase({
      group: "N", name: "N1 这一批第1题 -> batch2 problem1",
      pass: n1Info.markerHit === true && n1Info.runStatus === "COMPLETED",
      ...n1Info,
      detail: `marker=${n1Info.markerHit} fetchHit=${n1Info.fetchHit} answer="${n1Info.answerPreview.slice(0, 80)}…"`,
    });

    const n2 = await sendTurn(token, nConv,
      "最开始那批的第 2 题呢？原样告诉我标记。", "N2 earliest-batch ordinal");
    const n2Info = instrument(n2, { marker: nMarkers.b1p2, expectedFetchRefs: expectedRefsFor(nPlanted.b1p2, nDigests.b1p2) });
    recordCase({
      group: "N", name: "N2 最开始那批第2题 -> batch1 problem2",
      pass: n2Info.markerHit === true && n2Info.runStatus === "COMPLETED",
      ...n2Info,
      detail: `marker=${n2Info.markerHit} fetchHit=${n2Info.fetchHit} answer="${n2Info.answerPreview.slice(0, 80)}…"`,
    });

    const n3 = await sendTurn(token, nConv, "第 2 题的标记是什么？", "N3 bare ambiguous ordinal");
    const n3Info = instrument(n3, {});
    // Bare "第2题" with two plausible batches: resolving to the most recent batch
    // (b2p2) or explicitly clarifying are both correct; guessing batch1 silently
    // is not (per design: 识别歧义而不是自信地猜错).
    const n3ResolvedRecent = n3.content.includes(nMarkers.b2p2);
    const n3Clarified = looksLikeClarification(n3.content) && !n3.content.includes(nMarkers.b1p2);
    recordCase({
      group: "N", name: "N3 bare 第2题 resolves-recent OR clarifies",
      pass: (n3ResolvedRecent || n3Clarified) && n3Info.runStatus === "COMPLETED",
      ...n3Info,
      detail: `recent=${n3ResolvedRecent} clarified=${n3Clarified} answer="${n3Info.answerPreview.slice(0, 80)}…"`,
    });
  }

  // ----- Metrics & (baseline) gate ---------------------------------------------
  // Hard-gate groups per the confirmed P4-2 trimming decision: X/S/M/R only.
  // Group N (batch ordinals) is model-nondeterministic on genuine ambiguity; its
  // root cause is the missing explicit ordered-collection layer (blueprint §七,
  // deferred to a future dedicated phase), so N is informational (WARN), never
  // gate-blocking. Marker accuracy is likewise computed over hard groups only.
  const HARD_GROUPS = new Set(["X", "S", "M", "R"]);
  const markerCases = cases.filter((c) => typeof c.markerHit === "boolean" && HARD_GROUPS.has(c.group));
  const exactHits = markerCases.filter((c) => c.markerHit).length;
  const fetchExpected = cases.filter((c) => c.fetchHit !== null && c.fetchHit !== undefined);
  const fetchHits = fetchExpected.filter((c) => c.fetchHit).length;
  const totalFetchCalls = cases.reduce((sum, c) => sum + (c.fetchCallCount || 0), 0);
  const totalFetchSuccess = cases.reduce((sum, c) => sum + (c.fetchSuccessCount || 0), 0);
  const exactDetailAccuracy = markerCases.length ? exactHits / markerCases.length : 0;
  const perGroup = {};
  for (const c of cases) {
    perGroup[c.group] = perGroup[c.group] || { total: 0, passed: 0 };
    perGroup[c.group].total += 1;
    if (c.pass) {
      perGroup[c.group].passed += 1;
    }
  }

  const summary = {
    runTag: RUN_TAG,
    timestamp: new Date().toISOString(),
    baseline: !config.strictGate,
    cases: cases.map((c) => ({ group: c.group, name: c.name, pass: c.pass, warn: c.warn === true })),
    metrics: {
      total_cases: cases.length,
      passed_cases: cases.filter((c) => c.pass).length,
      exact_detail_accuracy: Number(exactDetailAccuracy.toFixed(3)),
      digest_fetch_hit_rate: fetchExpected.length ? Number((fetchHits / fetchExpected.length).toFixed(3)) : null,
      source_fetch_success_rate: totalFetchCalls ? Number((totalFetchSuccess / totalFetchCalls).toFixed(3)) : null,
      per_group: perGroup,
    },
    gate: config.strictGate ? {
      exact_detail_ge_90: exactDetailAccuracy >= 0.9,
      cross_conversation_all_pass: cases.filter((c) => c.group === "X").every((c) => c.pass),
      all_hard_cases_pass: cases.filter((c) => HARD_GROUPS.has(c.group)).every((c) => c.pass || c.warn),
    } : null,
  };
  if (summary.gate) {
    summary.gate.passed = summary.gate.exact_detail_ge_90 &&
      summary.gate.cross_conversation_all_pass && summary.gate.all_hard_cases_pass;
  }

  mkdirSync(OUT_DIR, { recursive: true });
  writeFileSync(join(OUT_DIR, `${RUN_TAG}-report.json`), JSON.stringify({ summary, cases }, null, 2), "utf8");

  console.log("\n================= P4 recall eval summary =================");
  console.log(`mode:                    ${config.strictGate ? "STRICT GATE" : "BASELINE (no gate)"}`);
  console.log(`cases passed:            ${summary.metrics.passed_cases}/${summary.metrics.total_cases}`);
  for (const [group, stat] of Object.entries(perGroup)) {
    console.log(`  group ${group}:             ${stat.passed}/${stat.total}`);
  }
  console.log(`exact_detail_accuracy:   ${summary.metrics.exact_detail_accuracy}`);
  console.log(`digest fetch hit rate:   ${summary.metrics.digest_fetch_hit_rate}`);
  console.log(`source_fetch_success:    ${summary.metrics.source_fetch_success_rate}`);
  if (summary.gate) {
    console.log(`GATE: ${summary.gate.passed ? "PASSED" : "FAILED"}`);
  }

  if (!config.keep) {
    const cleaned = cleanupEvalData();
    console.log(`cleanup: removed ${cleaned} eval conversation(s) (set AIOJ_EVAL_KEEP=true to keep)`);
  } else {
    console.log("cleanup skipped (AIOJ_EVAL_KEEP=true)");
  }

  process.exit(config.strictGate && summary.gate && !summary.gate.passed ? 1 : 0);
}

main().catch((error) => {
  console.error("eval aborted:", error);
  process.exit(2);
});
