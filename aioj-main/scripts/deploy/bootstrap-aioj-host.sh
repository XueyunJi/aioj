#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

[[ ${EUID:-$(id -u)} -eq 0 ]] || { printf 'run as root from the server console\n' >&2; exit 77; }
[[ $# -eq 2 ]] || { printf 'usage: bootstrap-aioj-host.sh <repository-root> <deploy-public-key-file>\n' >&2; exit 64; }

source_root=$(readlink -f -- "$1")
key_file=$(readlink -f -- "$2")
[[ -f $source_root/deploy/compose.production.yml ]] || { printf 'invalid repository/release root\n' >&2; exit 66; }
[[ -f $key_file ]] || { printf 'missing deployment public key\n' >&2; exit 66; }

public_key=$(<"$key_file")
[[ $public_key == ssh-ed25519\ * ]] || { printf 'deployment key must be Ed25519\n' >&2; exit 65; }

deploy_user=aioj-deploy
deploy_group=aioj-deploy-runner
deploy_home=/home/aioj-deploy

if ! getent group "$deploy_group" >/dev/null; then
  groupadd --system "$deploy_group"
else
  deploy_gid=$(getent group "$deploy_group" | cut -d: -f3)
  foreign_primary_users=$(getent passwd | awk -F: -v gid="$deploy_gid" -v user="$deploy_user" '$4 == gid && $1 != user { print $1 }')
  supplemental_members=$(getent group "$deploy_group" | cut -d: -f4)
  if [[ -n $foreign_primary_users || ( -n $supplemental_members && $supplemental_members != "$deploy_user" ) ]]; then
    printf 'refusing to reuse non-dedicated group %s\n' "$deploy_group" >&2
    exit 65
  fi
fi

if ! id "$deploy_user" >/dev/null 2>&1; then
  useradd --create-home --home-dir "$deploy_home" --shell /bin/bash --gid "$deploy_group" "$deploy_user"
fi

account_home=$(getent passwd "$deploy_user" | cut -d: -f6)
account_primary_group=$(id -gn "$deploy_user")
account_groups=$(id -nG "$deploy_user")
if [[ $account_home != "$deploy_home" || $account_primary_group != "$deploy_group" || $account_groups != "$deploy_group" ]]; then
  printf 'refusing unsafe existing %s account: home=%s primary_group=%s groups=%s\n' \
    "$deploy_user" "$account_home" "$account_primary_group" "$account_groups" >&2
  exit 65
fi
passwd -l "$deploy_user" >/dev/null
usermod --shell /bin/bash "$deploy_user"

install -d -o root -g root -m 0755 /opt/aioj
install -d -o root -g root -m 0700 /opt/aioj/env /opt/aioj/deploy-history /opt/aioj/backups
install -m 0644 "$source_root/deploy/compose.production.yml" /opt/aioj/compose.production.yml
install -m 0755 "$source_root/scripts/deploy/aioj-deploy" /usr/local/sbin/aioj-deploy
install -m 0755 "$source_root/scripts/deploy/aioj-deploy-gate" /usr/local/sbin/aioj-deploy-gate
install -m 0755 "$source_root/scripts/deploy/aioj-health-check" /usr/local/sbin/aioj-health-check

install -d -o "$deploy_user" -g "$deploy_group" -m 0700 "$deploy_home/.ssh"
printf 'restrict,command="sudo -n /usr/local/sbin/aioj-deploy-gate" %s\n' "$public_key" > "$deploy_home/.ssh/authorized_keys"
chown "$deploy_user:$deploy_group" "$deploy_home/.ssh/authorized_keys"
chmod 0600 "$deploy_home/.ssh/authorized_keys"

cat >/etc/sudoers.d/aioj-deploy <<'EOF'
Defaults:aioj-deploy env_keep += "SSH_ORIGINAL_COMMAND"
aioj-deploy ALL=(root) NOPASSWD: /usr/local/sbin/aioj-deploy-gate
EOF
chmod 0440 /etc/sudoers.d/aioj-deploy
visudo -cf /etc/sudoers.d/aioj-deploy >/dev/null

if [[ ! -f /opt/aioj/env/app.env ]]; then
  install -m 0600 "$source_root/deploy/env/production.env.example" /opt/aioj/env/app.env.example
fi

printf 'Bootstrap complete. Configure /opt/aioj/env/app.env and root GHCR read credentials before deployment.\n'
