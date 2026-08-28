import { execFileSync } from "node:child_process";
import fs from "node:fs";

const candidates = execFileSync(
  "git",
  ["ls-files", "-z", "--cached", "--others", "--exclude-standard"],
  { encoding: "utf8" },
)
  .split("\0")
  .filter(Boolean);

const pathFailures = [];
const contentFailures = [];
const forbiddenSegments = /(?:^|\/)(?:node_modules|target|dist|coverage|archive|backups|release-out|\.npm-cache)(?:\/|$)/i;
const forbiddenFiles = /(?:^|\/)(?:\.env(?:\..+)?|LOCAL_DEBUG_CONTEXT\.md)$|\.(?:pem|key|p12|sql|sql\.gz|tar|tar\.gz|zip)$/i;
const localWindowsPath = /\b[A-Za-z]:\\(?:Users|Dev)\\/i;
const privateKeyMaterial = /-----BEGIN (?:OPENSSH |RSA |EC |DSA )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{80,}/;
const mutableLatestImage = /^\s*image:\s*[^#\s]+:latest\s*$/m;
const safeRabbitmqHealthcheck = /test:\s*\["CMD",\s*"gosu",\s*"rabbitmq",\s*"rabbitmq-diagnostics",\s*"-q",\s*"check_running"\]/;
const ipv4 = /\b(?:\d{1,3}\.){3}\d{1,3}\b/g;

function isPublicIpv4(value) {
  const parts = value.split(".").map(Number);
  if (parts.some((part) => part < 0 || part > 255)) return false;
  const [a, b] = parts;
  if (a === 0 || a === 10 || a === 127 || a >= 224) return false;
  if (a === 169 && b === 254) return false;
  if (a === 172 && b >= 16 && b <= 31) return false;
  if (a === 192 && b === 168) return false;
  if (a === 192 && b === 0) return false;
  if (a === 198 && (b === 18 || b === 19 || b === 51)) return false;
  if (a === 203 && b === 0) return false;
  return true;
}

for (const file of candidates) {
  const normalized = file.replaceAll("\\", "/");
  const isExampleEnvironment = normalized === ".env.example" || normalized.endsWith(".env.example");
  const isFlywayMigration = /^backend\/api-contract\/src\/main\/resources\/db\/migration\/V\d+__[^/]+\.sql$/i.test(normalized);
  if (
    forbiddenSegments.test(normalized)
    || (forbiddenFiles.test(normalized) && !isExampleEnvironment && !isFlywayMigration)
  ) {
    pathFailures.push(file);
    continue;
  }
  const buffer = fs.readFileSync(file);
  if (buffer.includes(0)) continue;
  const content = buffer.toString("utf8");
  if (localWindowsPath.test(content)) contentFailures.push(`${file}: local Windows path`);
  if (privateKeyMaterial.test(content)) contentFailures.push(`${file}: private-key material`);
  if (normalized === "deploy/compose.production.yml" && mutableLatestImage.test(content)) {
    contentFailures.push(`${file}: mutable latest image`);
  }
  if (
    (normalized === "deploy/compose.yml" || normalized === "deploy/compose.production.yml")
    && !safeRabbitmqHealthcheck.test(content)
  ) {
    contentFailures.push(`${file}: RabbitMQ healthcheck must run as rabbitmq and require check_running`);
  }
  const scanPublicIpv4 = !normalized.includes("/src/test/");
  for (const candidate of scanPublicIpv4 ? (content.match(ipv4) ?? []) : []) {
    if (isPublicIpv4(candidate)) {
      contentFailures.push(`${file}: public IPv4 literal`);
      break;
    }
  }
}

const failures = [...pathFailures.map((value) => `${value}: forbidden tracked path`), ...contentFailures];
if (failures.length > 0) {
  console.error("Repository hygiene check failed:\n" + failures.join("\n"));
  process.exit(1);
}

console.log(`Repository hygiene passed for ${candidates.length} tracked/untracked candidate files.`);
