#!/usr/bin/env bash
set -euo pipefail

APP_ROOT=${APP_ROOT:-/opt/aioj}
APP_ENV_FILE=${APP_ENV_FILE:-/opt/aioj/env/app.env}
DEPLOY_ENV_FILE=${DEPLOY_ENV_FILE:-/opt/aioj/deploy.env}
COMPOSE_FILE=${COMPOSE_FILE:-/opt/aioj/compose.production.yml}
K6_ENV_FILE=${K6_ENV_FILE:-/opt/aioj/env/k6-load-test.env}
K6_CODES_FILE=${K6_CODES_FILE:-/opt/aioj/env/k6-submission-codes.json}
BASE_URL=${BASE_URL:-http://127.0.0.1:5173}
K6_ACCOUNT_PREFIX=${K6_ACCOUNT_PREFIX:-k6stu}
K6_ACCOUNT_COUNT=${K6_ACCOUNT_COUNT:-50}
DRY_RUN=${DRY_RUN:-0}

P1_ID=${P1_ID:-}
P2_ID=${P2_ID:-}
P3_ID=${P3_ID:-}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required command not found: $1" >&2
    exit 1
  }
}

require_command docker
require_command python3
require_command curl

if [ "${EUID:-$(id -u)}" -ne 0 ]; then
  echo "This preparation script must be run by root on the authorized app/data node" >&2
  exit 1
fi
if ! [[ "$K6_ACCOUNT_PREFIX" =~ ^[A-Za-z0-9-]+$ ]]; then
  echo "K6_ACCOUNT_PREFIX may contain only letters, digits, and hyphens" >&2
  exit 1
fi
if ! [[ "$K6_ACCOUNT_COUNT" =~ ^[0-9]+$ ]] || [ "$K6_ACCOUNT_COUNT" -lt 1 ] || [ "$K6_ACCOUNT_COUNT" -gt 50 ]; then
  echo "K6_ACCOUNT_COUNT must be from 1 to 50" >&2
  exit 1
fi
for problem_id_name in P1_ID P2_ID P3_ID; do
  problem_id=${!problem_id_name}
  if ! [[ "$problem_id" =~ ^[0-9]+$ ]]; then
    echo "$problem_id_name must be an explicit decimal problem ID from the current server" >&2
    exit 1
  fi
done

if [ ! -f "$APP_ENV_FILE" ]; then
  echo "App env file not found: $APP_ENV_FILE" >&2
  exit 1
fi
if [ ! -f "$DEPLOY_ENV_FILE" ]; then
  echo "Deploy env file not found: $DEPLOY_ENV_FILE" >&2
  exit 1
fi
if [ ! -f "$COMPOSE_FILE" ]; then
  echo "Compose file not found: $COMPOSE_FILE" >&2
  exit 1
fi

cd "$APP_ROOT"

set -a
# shellcheck disable=SC1090
. "$APP_ENV_FILE"
set +a

if [ -z "${MYSQL_PASSWORD:-}" ] || [ -z "${JWT_HMAC_SECRET:-}" ]; then
  echo "MYSQL_PASSWORD and JWT_HMAC_SECRET must be present in the server-owned app environment" >&2
  exit 1
fi

mysql_container() {
  docker compose --env-file "$APP_ENV_FILE" --env-file "$DEPLOY_ENV_FILE" \
    -f "$COMPOSE_FILE" ps -q mysql
}

MYSQL_CONTAINER=$(mysql_container)
if [ -z "$MYSQL_CONTAINER" ]; then
  echo "MySQL container not found" >&2
  exit 1
fi

mysql_query() {
  docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" -i "$MYSQL_CONTAINER" \
    mysql --default-character-set=utf8mb4 --batch --raw --skip-column-names \
    -u"${MYSQL_USER:-aioj}" "${MYSQL_DATABASE:-ai_oj_next}" "$@"
}

admin_identity() {
  mysql_query -e "SELECT u.id, u.account FROM users u JOIN user_roles r ON r.user_id = u.id WHERE r.role = 'ADMIN' AND u.enabled = 1 ORDER BY u.id LIMIT 1"
}

create_admin_token() {
  local admin_id=$1
  local admin_account=$2
  JWT_SECRET_FOR_K6=$JWT_HMAC_SECRET \
    JWT_ISSUER_FOR_K6=${JWT_ISSUER:-aioj} \
    python3 - "$admin_id" "$admin_account" <<'PY'
import base64
import hashlib
import hmac
import json
import os
import sys
import time

secret = os.environ["JWT_SECRET_FOR_K6"]
issuer = os.environ["JWT_ISSUER_FOR_K6"]
user_id, account = sys.argv[1:3]

def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")

now = int(time.time())
header = {"alg": "HS256", "typ": "JWT"}
payload = {
    "iss": issuer,
    "sub": str(user_id),
    "account": account,
    "roles": ["ADMIN"],
    "pwd_reset": False,
    "typ": "access",
    "iat": now,
    "exp": now + 1800,
}
key = hashlib.sha256(secret.encode("utf-8")).digest()
signing_input = f"{b64(json.dumps(header, separators=(',', ':')).encode())}.{b64(json.dumps(payload, separators=(',', ':')).encode())}"
signature = hmac.new(key, signing_input.encode("ascii"), hashlib.sha256).digest()
print(f"{signing_input}.{b64(signature)}")
PY
}

generate_password() {
  python3 - <<'PY'
import secrets
import string

alphabet = string.ascii_letters + string.digits
print("K6-" + "".join(secrets.choice(alphabet) for _ in range(24)))
PY
}

json_payload() {
  python3 - "$@" <<'PY'
import json
import sys

account, password, display_name, email = sys.argv[1:5]
print(json.dumps({
    "account": account,
    "password": password,
    "displayName": display_name,
    "email": email,
    "roles": ["STUDENT"],
    "enabled": True,
}, separators=(",", ":")))
PY
}

write_codes_file() {
  local destination=$1
  local rows_file
  rows_file=$(mktemp)
  mysql_query -e "SELECT p.id, p.title, ps.language, HEX(ps.content) FROM problems p JOIN problem_solutions ps ON ps.problem_id = p.id WHERE p.id IN ($P1_ID, $P2_ID, $P3_ID) ORDER BY p.id, ps.updated_at DESC, ps.id DESC" > "$rows_file"
  python3 - "$destination" "$P1_ID" "$P2_ID" "$P3_ID" "$rows_file" <<'PY'
import datetime as dt
import json
import sys

destination = sys.argv[1]
problem_ids = sys.argv[2:5]
rows_path = sys.argv[5]
with open(rows_path, encoding="utf-8") as source:
    rows = [line.rstrip("\n").split("\t", 3) for line in source if line.strip()]
problems = {}
for problem_id, title, language, hex_content in rows:
    if problem_id in problems:
        continue
    problems[problem_id] = {
        "title": title,
        "standardLanguage": language,
        "standardCode": bytes.fromhex(hex_content).decode("utf-8"),
        "timeoutLanguage": "cpp",
        "timeoutCode": """#include <bits/stdc++.h>
using namespace std;

int main() {
    ios::sync_with_stdio(false);
    cin.tie(nullptr);
    volatile unsigned long long x = 1;
    while (true) {
        x = x * 1315423911ULL + 2654435761ULL;
    }
    return int(x);
}
""",
    }

missing = [problem_id for problem_id in problem_ids if problem_id not in problems]
if missing:
    raise SystemExit("Missing standard solutions for problem ids: " + ", ".join(missing))

payload = {
    "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
    "language": "cpp",
    "problems": problems,
}
with open(destination, "w", encoding="utf-8") as f:
    json.dump(payload, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY
  rm -f "$rows_file"
  chmod 600 "$destination"
}

if [ "$DRY_RUN" = "1" ]; then
  echo "DRY_RUN=1"
  echo "Would create or verify accounts: ${K6_ACCOUNT_PREFIX}001..$(printf '%s%03d' "$K6_ACCOUNT_PREFIX" "$K6_ACCOUNT_COUNT")"
  echo "Would write env file: $K6_ENV_FILE"
  echo "Would write codes file: $K6_CODES_FILE"
  existing=$(mysql_query -e "SELECT COUNT(*) FROM users WHERE account LIKE '${K6_ACCOUNT_PREFIX}%'" | tr -d '\r')
  echo "Existing matching accounts: $existing"
  exit 0
fi

mkdir -p "$(dirname "$K6_ENV_FILE")" "$(dirname "$K6_CODES_FILE")"

K6_PASSWORD=${K6_PASSWORD:-}
if [ -f "$K6_ENV_FILE" ]; then
  existing_password=$(grep -E '^K6_PASSWORD=' "$K6_ENV_FILE" | tail -1 | cut -d= -f2- || true)
  K6_PASSWORD=${K6_PASSWORD:-$existing_password}
fi
K6_PASSWORD=${K6_PASSWORD:-$(generate_password)}

admin_row=$(admin_identity)
admin_id=$(printf '%s' "$admin_row" | awk '{print $1}')
admin_account=$(printf '%s' "$admin_row" | awk '{print $2}')
if [ -z "$admin_id" ] || [ -z "$admin_account" ]; then
  echo "No enabled ADMIN account found" >&2
  exit 1
fi
admin_token=$(create_admin_token "$admin_id" "$admin_account")

created=0
existing=0
for i in $(seq 1 "$K6_ACCOUNT_COUNT"); do
  account=$(printf '%s%03d' "$K6_ACCOUNT_PREFIX" "$i")
  if mysql_query -e "SELECT 1 FROM users WHERE account = '$account' LIMIT 1" | grep -q 1; then
    existing=$((existing + 1))
    continue
  fi
  payload=$(json_payload "$account" "$K6_PASSWORD" "K6 Student $(printf '%03d' "$i")" "${account}@load-test.local")
  response_file=$(mktemp)
  status=$(curl -sS -o "$response_file" -w '%{http_code}' \
    -H "Authorization: Bearer $admin_token" \
    -H "Content-Type: application/json" \
    --data-binary "$payload" \
    "$BASE_URL/api/v1/admin/users")
  if [ "$status" != "200" ]; then
    echo "Failed to create $account, HTTP $status" >&2
    rm -f "$response_file"
    exit 1
  fi
  rm -f "$response_file"
  created=$((created + 1))
done

write_codes_file "$K6_CODES_FILE"

cat > "$K6_ENV_FILE" <<EOF
# Generated by scripts/load-test/create-k6-accounts.sh.
# Keep this file on the server only. It contains the shared k6 test password.
BASE_URL=$BASE_URL
K6_ACCOUNT_PREFIX=$K6_ACCOUNT_PREFIX
K6_ACCOUNT_COUNT=$K6_ACCOUNT_COUNT
K6_PASSWORD=$K6_PASSWORD
K6_CODES_FILE=/env/$(basename "$K6_CODES_FILE")
P1_ID=$P1_ID
P1_TOTAL=1
P1_TLE=0
P2_ID=$P2_ID
P2_TOTAL=0
P2_TLE=0
P3_ID=$P3_ID
P3_TOTAL=0
P3_TLE=0
POLL_INTERVAL_MS=1500
POLL_TIMEOUT_MS=180000
EOF
chmod 600 "$K6_ENV_FILE"

login_failures=0
for i in $(seq 1 "$K6_ACCOUNT_COUNT"); do
  account=$(printf '%s%03d' "$K6_ACCOUNT_PREFIX" "$i")
  response_file=$(mktemp)
  login_status=$(curl -sS -o "$response_file" -w '%{http_code}' \
    -H "Content-Type: application/json" \
    --data-binary "{\"account\":\"$account\",\"password\":\"$K6_PASSWORD\"}" \
    "$BASE_URL/api/v1/auth/login")
  rm -f "$response_file"
  if [ "$login_status" != "200" ]; then
    echo "Login verification failed for $account, HTTP $login_status" >&2
    login_failures=$((login_failures + 1))
  fi
done
if [ "$login_failures" -ne 0 ]; then
  echo "Load-test account verification failed for $login_failures account(s); no load test should be started" >&2
  exit 1
fi

role_count=$(mysql_query -e "SELECT COUNT(*) FROM users u JOIN user_roles r ON r.user_id = u.id WHERE u.account LIKE '${K6_ACCOUNT_PREFIX}%' AND u.enabled = 1 AND r.role = 'STUDENT'" | tr -d '\r')
echo "created=$created existing=$existing verified_logins=$K6_ACCOUNT_COUNT enabled_student_accounts=$role_count"
echo "env_file=$K6_ENV_FILE"
echo "codes_file=$K6_CODES_FILE"
