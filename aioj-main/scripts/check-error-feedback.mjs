import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const scanRoots = [
  "apps/web-admin-react/src",
  "apps/web-user-react/src"
];

const allowedFiles = new Set([
  "lib/readableError.ts"
]);

const rules = [
  {
    name: "raw-caught-message",
    pattern: /\b(?:caught|err|error)\s+instanceof\s+Error\s*\?\s*(?:caught|err|error)\.message\b/,
    hint: "Use readableCaughtError(...) instead of showing Error.message directly."
  },
  {
    name: "raw-error-message-panel",
    pattern: /<(?:ErrorPanel|OutputBlock)\b[^>]*(?:title|description|value)=\{[^}\n]*\.errorMessage[^}\n]*(?!readableStoredError)[^}\n]*\}/,
    hint: "Wrap persisted errorMessage with readableStoredError(...)."
  },
  {
    name: "raw-judge-message",
    pattern: /\bjudgeMessage\s*\|\|/,
    hint: "Use readableJudgeMessage(...) so judge service failures are labeled."
  }
];

const violations = [];

for (const scanRoot of scanRoots) {
  walk(path.join(root, scanRoot));
}

if (violations.length > 0) {
  console.error("User-friendly error feedback check failed:");
  for (const violation of violations) {
    console.error(`- ${violation.file}:${violation.line} [${violation.rule}] ${violation.hint}`);
    console.error(`  ${violation.text.trim()}`);
  }
  process.exit(1);
}

console.log("User-friendly error feedback check passed.");

function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(fullPath);
      continue;
    }
    if (!/\.(tsx?|jsx?)$/.test(entry.name)) continue;
    const relative = normalize(path.relative(root, fullPath));
    const shortRelative = normalize(path.relative(path.join(root, relative.split("/").slice(0, 3).join("/")), fullPath));
    if (allowedFiles.has(shortRelative)) continue;
    scanFile(fullPath, relative);
  }
}

function scanFile(fullPath, relative) {
  const lines = fs.readFileSync(fullPath, "utf8").split(/\r?\n/);
  lines.forEach((line, index) => {
    for (const rule of rules) {
      if (!rule.pattern.test(line)) continue;
      if (line.includes("readableCaughtError") || line.includes("readableStoredError") || line.includes("readableJudgeMessage")) continue;
      violations.push({
        file: relative,
        line: index + 1,
        rule: rule.name,
        hint: rule.hint,
        text: line
      });
    }
  });
}

function normalize(value) {
  return value.replaceAll(path.sep, "/");
}
