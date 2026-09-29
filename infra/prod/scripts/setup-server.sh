#!/bin/bash
# Phase 09D D9 — one-time (and safely re-runnable) setup of a fresh Oracle Ubuntu 24.04 aarch64 VM.
#   sudo APP_DIR=/opt/secureleaf REPO_URL=https://github.com/<you>/SecureLeaf.git ./setup-server.sh
# IDEMPOTENT: every step checks the current state first, so running it twice changes nothing.
#
#   1. apt upgrade + unattended-upgrades          5. fail2ban
#   2. Docker Engine + compose plugin             6. SSH hardening (keys only, no root)
#   3. Docker log rotation (10m x 3)              7. host firewall: open 80/443  <-- the Oracle trap
#   4. 2 GB swap file                             8. app dir + clone + nightly backup timer
#
# THE TWO-FIREWALL TRAP: Oracle has TWO firewalls. (1) the cloud VCN security list / NSG - you open
# 80/443 in the console. (2) Ubuntu's OWN iptables, which Oracle's images pre-load with a final
# `REJECT all` rule after allowing only port 22. Opening the VCN alone does nothing: packets reach
# the VM and are rejected. Step 7 inserts ACCEPT rules BEFORE that REJECT and persists them.
set -euo pipefail

APP_DIR=${APP_DIR:-/opt/secureleaf}
REPO_URL=${REPO_URL:-}
APP_USER=${APP_USER:-${SUDO_USER:-ubuntu}}
SWAP_FILE=${SWAP_FILE:-/swapfile}
SWAP_SIZE_GB=${SWAP_SIZE_GB:-2}
# Test hook: DRY_RUN=1 prints what would run without changing anything.
DRY_RUN=${DRY_RUN:-0}

log() { printf '[setup] %s\n' "$*"; }
run() { if [ "$DRY_RUN" = "1" ]; then log "DRY-RUN: $*"; else "$@"; fi; }

if [ "$DRY_RUN" != "1" ] && [ "$(id -u)" -ne 0 ]; then
    echo "Run as root: sudo $0" >&2
    exit 1
fi
if [ "$DRY_RUN" != "1" ] && [ -r /etc/os-release ]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    [ "${ID:-}" = "ubuntu" ] || { echo "This script targets Ubuntu (found ${ID:-unknown})." >&2; exit 1; }
fi

# ── 1. system updates ───────────────────────────────────────────────────────
log "1/8 apt upgrade + unattended-upgrades"
export DEBIAN_FRONTEND=noninteractive
run apt-get update -y
run apt-get upgrade -y
run apt-get install -y ca-certificates curl gnupg git openssl
run apt-get install -y unattended-upgrades apt-listchanges
run systemctl enable --now unattended-upgrades

# ── 2. Docker Engine + compose plugin ───────────────────────────────────────
log "2/8 Docker Engine"
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
    run install -m 0755 -d /etc/apt/keyrings
    if [ ! -s /etc/apt/keyrings/docker.asc ]; then
        run curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
        run chmod a+r /etc/apt/keyrings/docker.asc
    fi
    ARCH=$(dpkg --print-architecture 2>/dev/null || echo arm64)   # arm64 on Ampere
    # shellcheck disable=SC1091
    CODENAME=$( . /etc/os-release 2>/dev/null && echo "${VERSION_CODENAME:-noble}")
    if [ "$DRY_RUN" = "1" ]; then
        log "DRY-RUN: write /etc/apt/sources.list.d/docker.list (arch=$ARCH codename=$CODENAME)"
    else
        echo "deb [arch=$ARCH signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $CODENAME stable" \
            > /etc/apt/sources.list.d/docker.list
    fi
    run apt-get update -y
    run apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
run systemctl enable --now docker
if id "$APP_USER" >/dev/null 2>&1 && ! id -nG "$APP_USER" | grep -qw docker; then
    run usermod -aG docker "$APP_USER"
fi

# ── 3. Docker log rotation ──────────────────────────────────────────────────
log "3/8 Docker log rotation"
DAEMON_JSON=/etc/docker/daemon.json
WANT_JSON='{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}'
if [ "$DRY_RUN" = "1" ]; then
    log "DRY-RUN: ensure $DAEMON_JSON has log rotation"
elif [ ! -f "$DAEMON_JSON" ] || ! grep -q '"max-size"' "$DAEMON_JSON"; then
    [ -f "$DAEMON_JSON" ] && cp "$DAEMON_JSON" "$DAEMON_JSON.bak"
    printf '%s\n' "$WANT_JSON" > "$DAEMON_JSON"
    systemctl restart docker
fi

# ── 4. swap ─────────────────────────────────────────────────────────────────
log "4/8 ${SWAP_SIZE_GB} GB swap file"
if [ "$DRY_RUN" = "1" ]; then
    log "DRY-RUN: ensure $SWAP_FILE (${SWAP_SIZE_GB}G) active and in /etc/fstab"
else
    if [ ! -f "$SWAP_FILE" ]; then
        fallocate -l "${SWAP_SIZE_GB}G" "$SWAP_FILE"
        chmod 600 "$SWAP_FILE"
        mkswap "$SWAP_FILE" >/dev/null
    fi
    swapon --show=NAME --noheadings | grep -qx "$SWAP_FILE" || swapon "$SWAP_FILE"
    grep -q "^$SWAP_FILE " /etc/fstab || echo "$SWAP_FILE none swap sw 0 0" >> /etc/fstab
    # Prefer RAM; swap is an emergency cushion, not working memory.
    echo 'vm.swappiness=10' > /etc/sysctl.d/99-secureleaf.conf
    sysctl -q -p /etc/sysctl.d/99-secureleaf.conf
fi

# ── 5. fail2ban ─────────────────────────────────────────────────────────────
log "5/8 fail2ban"
run apt-get install -y fail2ban
if [ "$DRY_RUN" != "1" ]; then
    cat > /etc/fail2ban/jail.d/secureleaf.local <<'JAIL'
[sshd]
enabled  = true
maxretry = 5
findtime = 10m
bantime  = 1h
JAIL
fi
run systemctl enable --now fail2ban
run systemctl restart fail2ban

# ── 6. SSH hardening ────────────────────────────────────────────────────────
log "6/8 SSH: keys only, no root login, no passwords"
if [ "$DRY_RUN" = "1" ]; then
    log "DRY-RUN: write /etc/ssh/sshd_config.d/99-secureleaf.conf"
else
    # Refuse to lock ourselves out: there must be at least one authorized key for the login user.
    HOME_DIR=$(getent passwd "$APP_USER" | cut -d: -f6)
    if [ ! -s "$HOME_DIR/.ssh/authorized_keys" ]; then
        log "WARNING: $HOME_DIR/.ssh/authorized_keys is empty - NOT disabling password login."
        SSH_PASSWORDS=yes
    else
        SSH_PASSWORDS=no
    fi
    mkdir -p /etc/ssh/sshd_config.d
    cat > /etc/ssh/sshd_config.d/99-secureleaf.conf <<SSHD
PermitRootLogin no
PasswordAuthentication $SSH_PASSWORDS
KbdInteractiveAuthentication no
PubkeyAuthentication yes
X11Forwarding no
MaxAuthTries 4
SSHD
    if sshd -t; then
        systemctl reload ssh 2>/dev/null || systemctl reload sshd
    else
        rm -f /etc/ssh/sshd_config.d/99-secureleaf.conf
        log "sshd config test failed; hardening file removed"
    fi
fi

# ── 7. host firewall: open 80 + 443 BEFORE Oracle's blanket REJECT ──────────
log "7/8 host firewall (iptables): allow 80/443"
run apt-get install -y iptables iptables-persistent netfilter-persistent
open_port() {
    local port=$1
    if [ "$DRY_RUN" = "1" ]; then log "DRY-RUN: iptables -I INPUT <before REJECT> -p tcp --dport $port -j ACCEPT"; return; fi
    if iptables -C INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT 2>/dev/null; then
        return   # already open - idempotent
    fi
    # Insert just above the first REJECT rule (Oracle's default puts one at the end of INPUT).
    local reject_line
    reject_line=$(iptables -L INPUT --line-numbers -n | awk '/REJECT/ {print $1; exit}')
    if [ -n "$reject_line" ]; then
        iptables -I INPUT "$reject_line" -p tcp -m state --state NEW --dport "$port" -j ACCEPT
    else
        iptables -A INPUT -p tcp -m state --state NEW --dport "$port" -j ACCEPT
    fi
}
open_port 80
open_port 443
if [ "$DRY_RUN" != "1" ]; then netfilter-persistent save; fi

# ── 8. app directory, clone, backup timer ───────────────────────────────────
log "8/8 app directory, repo, nightly backup timer"
run mkdir -p "$APP_DIR"
run chown "$APP_USER":"$APP_USER" "$APP_DIR"
if [ -n "$REPO_URL" ] && [ ! -d "$APP_DIR/.git" ]; then
    if [ "$DRY_RUN" = "1" ]; then
        log "DRY-RUN: git clone $REPO_URL $APP_DIR"
    else
        sudo -u "$APP_USER" git clone "$REPO_URL" "$APP_DIR"
    fi
elif [ -z "$REPO_URL" ] && [ ! -d "$APP_DIR/.git" ]; then
    log "REPO_URL not set and $APP_DIR is not a git checkout - clone it yourself, then re-run."
fi
run mkdir -p /var/backups/secureleaf
run chmod 700 /var/backups/secureleaf
if [ "$DRY_RUN" = "1" ]; then
    log "DRY-RUN: install systemd units secureleaf-backup.{service,timer}"
elif [ -d "$APP_DIR/infra/prod/systemd" ]; then
    sed "s|__APP_DIR__|$APP_DIR|g" "$APP_DIR/infra/prod/systemd/secureleaf-backup.service" > /etc/systemd/system/secureleaf-backup.service
    cp "$APP_DIR/infra/prod/systemd/secureleaf-backup.timer" /etc/systemd/system/secureleaf-backup.timer
    systemctl daemon-reload
    systemctl enable --now secureleaf-backup.timer
fi

log "Done. Next: (open 80/443 in the VCN too), then as $APP_USER: cd $APP_DIR && infra/prod/scripts/generate-env.sh && infra/prod/scripts/deploy.sh"
