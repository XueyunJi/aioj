import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const roots = ["README.md", "CONTRIBUTING.md", "SECURITY.md", "docs"];
const markdownFiles = [];

function walk(relativePath) {
  const absolute = path.join(root, relativePath);
  if (!fs.existsSync(absolute)) return;
  const stat = fs.statSync(absolute);
  if (stat.isDirectory()) {
    for (const entry of fs.readdirSync(absolute)) {
      walk(path.join(relativePath, entry));
    }
  } else if (relativePath.toLowerCase().endsWith(".md")) {
    markdownFiles.push(relativePath);
  }
}

for (const entry of roots) walk(entry);

const failures = [];
const linkPattern = /!?(?:\[[^\]]*\])\(([^)]+)\)/g;

for (const relativeFile of markdownFiles) {
  const content = fs.readFileSync(path.join(root, relativeFile), "utf8");
  for (const match of content.matchAll(linkPattern)) {
    let target = match[1].trim();
    if (target.startsWith("<") && target.endsWith(">")) {
      target = target.slice(1, -1);
    }
    if (!target || /^(?:https?:|mailto:|tel:|#)/i.test(target)) continue;
    target = target.split("#", 1)[0].split("?", 1)[0];
    try {
      target = decodeURIComponent(target);
    } catch {
      failures.push(`${relativeFile}: invalid URL encoding in ${match[1]}`);
      continue;
    }
    const resolved = path.resolve(path.dirname(path.join(root, relativeFile)), target);
    if (!resolved.startsWith(root + path.sep) && resolved !== root) {
      failures.push(`${relativeFile}: link escapes repository: ${match[1]}`);
    } else if (!fs.existsSync(resolved)) {
      failures.push(`${relativeFile}: missing target: ${match[1]}`);
    }
  }
}

if (failures.length > 0) {
  console.error("Local Markdown link check failed:\n" + failures.join("\n"));
  process.exit(1);
}

console.log(`Validated local links in ${markdownFiles.length} Markdown files.`);
