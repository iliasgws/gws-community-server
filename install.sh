#!/usr/bin/env bash
#
# Installation en un clic de gws-community-server dans un LXC Proxmox
# créé depuis un template debian-13-standard (amd64 ou arm64).
#
#   curl -fsSL https://raw.githubusercontent.com/iliasgws/gws-community-server/main/install.sh | bash
#
# Variables facultatives :
#   GWS_PORT=8080   GWS_REF=main   GWS_ORIGINS=...   GWS_CONTACT=...
#   GWS_ADMIN_TOKEN=...   GWS_REPO=<url git>   GWS_SWAP=1|0   GWS_BUILD_HEAP=1024
# Désinstallation :
#   bash install.sh uninstall      (GWS_PURGE_DATA=1 pour effacer aussi les données)

set -Eeuo pipefail
IFS=$'\n\t'

REPO_URL="${GWS_REPO:-https://github.com/iliasgws/gws-community-server.git}"
REF="${GWS_REF:-main}"
PREFIX="${GWS_PREFIX:-/opt/gws}"
SRC_DIR="${GWS_SRC_DIR:-$PREFIX/src/gws-community-server}"
RELEASES="$PREFIX/releases"
APP_HOME="$PREFIX/community-server"
JDK_HOME="$PREFIX/jdk"
ENV_DIR="/etc/gws"
ENV_FILE="${GWS_ENV_FILE:-$ENV_DIR/gws.env}"
DATA_DIR="${GWS_DATA_DIR:-/var/lib/gws}"
SVC_NAME="gws-community-server"
SVC_USER="gws"
PORT="${GWS_PORT:-8080}"
WANT_SWAP="${GWS_SWAP:-1}"
BUILD_HEAP="${GWS_BUILD_HEAP:-}"

# Architecture : amd64 (x64) ou arm64 (aarch64), déduite du système.
case "$(dpkg --print-architecture 2>/dev/null || uname -m 2>/dev/null || echo unknown)" in
  amd64|x86_64) ARCH="amd64"; JDK_ARCH="x64" ;;
  arm64|aarch64) ARCH="arm64"; JDK_ARCH="aarch64" ;;
  *) ARCH="inconnue"; JDK_ARCH="x64" ;;
esac

JDK_API="https://api.adoptium.net/v3/assets/latest/24/hotspot?architecture=${JDK_ARCH}&image_type=jdk&os=linux&vendor=eclipse"
JDK_LINK_FALLBACK_AMD64="https://github.com/adoptium/temurin24-binaries/releases/download/jdk-24.0.2%2B12/OpenJDK24U-jdk_x64_linux_hotspot_24.0.2_12.tar.gz"
JDK_SHA_FALLBACK_AMD64="aea1cc55e51cf651c85f2f00ad021603fe269c4bb6493fa97a321ad770c9b096"
JDK_LINK_FALLBACK_ARM64="https://github.com/adoptium/temurin24-binaries/releases/download/jdk-24.0.2%2B12/OpenJDK24U-jdk_aarch64_linux_hotspot_24.0.2_12.tar.gz"
JDK_SHA_FALLBACK_ARM64="6f8725d186d05c627176db9c46c732a6ef3ba41d9e9b3775c4727fc8ac642bb2"

if [ -t 1 ]; then
  C_LOG=$'\033[1;34m'; C_OK=$'\033[1;32m'; C_WARN=$'\033[1;33m'; C_ERR=$'\033[1;31m'; C_OFF=$'\033[0m'
else
  C_LOG=""; C_OK=""; C_WARN=""; C_ERR=""; C_OFF=""
fi

log()  { printf '%s==>%s %s\n' "$C_LOG" "$C_OFF" "$*"; }
ok()   { printf '%s  ok%s  %s\n' "$C_OK" "$C_OFF" "$*"; }
warn() { printf '%swarn%s %s\n' "$C_WARN" "$C_OFF" "$*" >&2; }
die()  { printf '%s err%s %s\n' "$C_ERR" "$C_OFF" "$*" >&2; exit 1; }

trap 'printf "%s err%s échec ligne %s\n" "$C_ERR" "$C_OFF" "${BASH_LINENO[0]:-?}" >&2; exit 1' ERR

usage() {
  cat <<'EOF'
gws-community-server — installation Proxmox LXC (Debian 13, amd64/arm64)

  curl -fsSL https://raw.githubusercontent.com/iliasgws/gws-community-server/main/install.sh | bash
  bash install.sh [install|upgrade|uninstall]   (-h pour cette aide)

Variables : GWS_PORT GWS_REF GWS_REPO GWS_ORIGINS GWS_CONTACT GWS_ADMIN_TOKEN
            GWS_SWAP GWS_BUILD_HEAP GWS_PREFIX GWS_DATA_DIR
EOF
  exit 0
}

case "${1:-}" in
  -h|--help) usage ;;
esac
MODE="${1:-install}"

# ------------------------------------------------------------- préflight
need_root() {
  [ "$(id -u)" -eq 0 ] || die "lancer ce script en root (sudo -i, ou pct exec <vmid> -- bash install.sh)"
}

check_env() {
  if [ -d /etc/pve ]; then
    die "exécution sur l'hôte Proxmox : lancez le script DANS le CT (pct exec <vmid> -- bash -s < install.sh)"
  fi
  [ -d /run/systemd/system ] || die "systemd absent : template LXC Debian 13 attendu"
  . /etc/os-release
  [ "${ID:-}" = "debian" ] || die "distribution non prise en charge : ${ID:-?} (Debian 13 attendu)"
  if [ "${VERSION_ID:-}" != "13" ]; then
    die "Debian 13 attendu (template debian-13-standard_13.1-2), trouvé : VERSION_ID=${VERSION_ID:-?}"
  fi
  case "$ARCH" in
    amd64|arm64) ;;
    *) die "architecture non prise en charge : $ARCH (amd64 ou arm64 attendus)" ;;
  esac
}

# --------------------------------------------------------------- paquets
apt_tune() {
  cat > /etc/apt/apt.conf.d/80gws-speed <<'EOF'
APT::Install-Recommends "false";
APT::Install-Suggests "false";
APT::Keep-Downloaded-Packages "false";
Acquire::Retries "3";
Acquire::http::Timeout "30";
Acquire::https::Timeout "30";
Acquire::Languages { "none"; };
DPkg::Use-Pty "0";
DPkg::Lock::Timeout "180";
EOF
}

ensure_packages() {
  log "apt : mise à jour des paquets"
  DEBIAN_FRONTEND=noninteractive apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
    ca-certificates curl git unzip procps > /tmp/gws-apt.log 2>&1 \
    || { tail -n 30 /tmp/gws-apt.log >&2; die "installation des paquets échouée"; }
  ok "paquets de base (ca-certificates, curl, git, unzip, procps)"
}

# ------------------------------------------------------------------ swap
ensure_swap() {
  local mem_kb swap_total heap
  mem_kb=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
  swap_total=$(awk '/^SwapTotal:/{print $2}' /proc/meminfo)
  if [ -z "$BUILD_HEAP" ]; then
    heap=$(( mem_kb / 2048 ))
    [ "$heap" -lt 768 ] && heap=768
    [ "$heap" -gt 2048 ] && heap=2048
    BUILD_HEAP="$heap"
  fi
  if [ "$mem_kb" -lt 1572864 ]; then
    warn "moins de 1,5 Go de RAM : la compilation peut échouer (recommandé : pct avec --memory 4096 --swap 2048)"
  fi

  if [ "$WANT_SWAP" = "1" ] && [ "$swap_total" -eq 0 ] && [ "$mem_kb" -lt 4194304 ]; then
    log "RAM $(( mem_kb / 1024 )) Mo sans swap : tentative de swap 2 Go (utile à la compilation)"
    if fallocate -l 2G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none; then
      chmod 600 /swapfile
      if mkswap /swapfile >/dev/null 2>&1 && swapon /swapfile 2>/dev/null; then
        grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
        ok "swap 2 Go actif"
      else
        warn "swapon refusé (LXC non privilégié, normal) : compilation avec ${BUILD_HEAP} Mo"
        swapoff /swapfile 2>/dev/null || true
        rm -f /swapfile
      fi
    else
      warn "création du swap impossible, on continue"
    fi
  fi
}

# ------------------------------------------------------------------- JDK
install_jdk() {
  local link sha json tmp dir
  if [ -x "$JDK_HOME/bin/java" ] && "$JDK_HOME/bin/java" -version 2>&1 | grep -q 'version "24'; then
    ok "JDK Temurin 24 déjà présent ($JDK_HOME)"
    return 0
  fi

  log "JDK Temurin 24 $JDK_ARCH (classes ciblent JVM 24, Debian 13 n'en fournit pas)"
  if [ "$ARCH" = "arm64" ]; then
    link="$JDK_LINK_FALLBACK_ARM64"
    sha="$JDK_SHA_FALLBACK_ARM64"
  else
    link="$JDK_LINK_FALLBACK_AMD64"
    sha="$JDK_SHA_FALLBACK_AMD64"
  fi
  if json=$(curl -fsSL --retry 3 --max-time 30 "$JDK_API" 2>/dev/null); then
    local l s
    l=$(printf '%s' "$json" | sed -n 's/.*"link": *"\([^"]*\)".*/\1/p' | head -n1)
    s=$(printf '%s' "$json" | sed -n 's/.*"checksum": *"\([^"]*\)".*/\1/p' | head -n1)
    if [ -n "$l" ] && [ -n "$s" ]; then link="$l"; sha="$s"; fi
  fi

  tmp=$(mktemp -d)
  log "téléchargement $(basename "$link") (~135 Mo)"
  curl -fL --retry 3 --retry-delay 2 -o "$tmp/jdk.tar.gz" "$link"
  printf '%s  %s\n' "$sha" "$tmp/jdk.tar.gz" | sha256sum -c --status - \
    || { rm -rf "$tmp"; die "empreinte SHA-256 du JDK invalide"; }

  tar -xzf "$tmp/jdk.tar.gz" -C "$tmp"
  dir=$(find "$tmp" -maxdepth 1 -type d -name 'jdk-*' | head -n1)
  if [ -z "$dir" ]; then rm -rf "$tmp"; die "archive JDK illisible"; fi

  rm -rf "$JDK_HOME.old"
  if [ -e "$JDK_HOME" ]; then mv "$JDK_HOME" "$JDK_HOME.old"; fi
  mkdir -p "$PREFIX"
  mv "$dir" "$JDK_HOME"
  rm -rf "$tmp" "$JDK_HOME.old"
  "$JDK_HOME/bin/java" -version >/dev/null 2>&1 || die "JDK non fonctionnel"
  ok "$("$JDK_HOME/bin/java" -version 2>&1 | head -n1)"
}

# ---------------------------------------------------------------- source
ensure_source() {
  if [ -f "$PWD/build.gradle.kts" ] && [ -f "$PWD/gradlew" ]; then
    log "build à partir du checkout courant ($PWD)"
    SRC_DIR="$PWD"
    return 0
  fi

  mkdir -p "$(dirname "$SRC_DIR")"
  if [ -d "$SRC_DIR/.git" ]; then
    log "mise à jour de la source → $REF"
    git -C "$SRC_DIR" fetch --quiet --depth 1 origin "$REF" || die "fetch impossible : $REPO_URL (ref $REF)"
    git -C "$SRC_DIR" checkout --quiet --force --detach FETCH_HEAD || die "checkout $REF impossible"
  else
    log "clonage $REPO_URL (ref $REF)"
    rm -rf "$SRC_DIR"
    if ! git clone --quiet --depth 1 --branch "$REF" "$REPO_URL" "$SRC_DIR" 2>/dev/null; then
      rm -rf "$SRC_DIR"
      git clone --quiet --depth 1 "$REPO_URL" "$SRC_DIR" || die "clonage impossible : $REPO_URL"
      git -C "$SRC_DIR" fetch --quiet --depth 1 origin "$REF" && \
        git -C "$SRC_DIR" checkout --quiet --force --detach FETCH_HEAD || true
    fi
  fi
  chmod +x "$SRC_DIR/gradlew"
  ok "source prête ($(git -C "$SRC_DIR" rev-parse --short HEAD 2>/dev/null || echo '?'))"
}

# -------------------------------------------------------------- compilation
build() {
  local log_file="/tmp/gws-build.log"
  log "compilation installDist (heap ${BUILD_HEAP} Mo) — 1 à 3 min"
  (
    cd "$SRC_DIR"
    export JAVA_HOME="$JDK_HOME"
    export PATH="$JDK_HOME/bin:$PATH"
    export GRADLE_USER_HOME="$PREFIX/.gradle"
    # Debian LXC templates may inherit LANG=en_US.UTF-8 without that locale
    # being generated. Kotlin class names can contain accents, so give the
    # compiler a locale that is available on a minimal Debian installation.
    export LANG=C.UTF-8
    export LC_ALL=C.UTF-8
    export GRADLE_OPTS="-Xmx${BUILD_HEAP}m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8"
    export KOTLIN_DAEMON_JVM_OPTIONS="-Xmx512m"
    ./gradlew --no-daemon --console=plain \
      -Dorg.gradle.jvmargs="-Xmx${BUILD_HEAP}m -XX:MaxMetaspaceSize=384m" \
      installDist
  ) >"$log_file" 2>&1 || { tail -n 40 "$log_file" >&2; die "compilation échouée (log complet : $log_file)"; }
  [ -x "$SRC_DIR/build/install/$SVC_NAME/bin/$SVC_NAME" ] || die "installDist n'a pas produit la distribution"
  ok "distribution produite"
}

# ---------------------------------------------------------------- install
install_dist() {
  local stamp release
  stamp=$(date +%Y%m%d%H%M%S)
  release="$RELEASES/$stamp"
  mkdir -p "$RELEASES" "$release"
  cp -a "$SRC_DIR/build/install/$SVC_NAME/." "$release/app"
  rm -rf "$APP_HOME"
  ln -s "$release/app" "$APP_HOME"
  if [ "$(id -u)" -eq 0 ]; then
    chown -R root:root "$release"
    # Gradle's dependency cache can create jars mode 0600 under root's umask.
    # The unprivileged service account must be able to read the distribution.
    chmod -R a+rX "$release"
  fi
  chmod 755 "$PREFIX"
  ls -1dt "$RELEASES"/*/ 2>/dev/null | tail -n +3 | xargs -r rm -rf || true
  ok "installé : $APP_HOME → releases/$stamp/app"
}

existing_var() {
  [ -f "$ENV_FILE" ] || return 0
  sed -n "s/^$1=//p" "$ENV_FILE" | tail -n1
}

rand_token() {
  head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
}

write_env() {
  local admin origins contact
  # une variable fournie à l'exécution l'emporte ; non fournie → valeur existante
  if [ "${GWS_ADMIN_TOKEN+x}" = x ]; then admin="$GWS_ADMIN_TOKEN"; else admin="$(existing_var GWS_ADMIN_TOKEN)"; fi
  if [ "${GWS_ORIGINS+x}" = x ]; then origins="$GWS_ORIGINS"; else origins="$(existing_var GWS_ORIGINS)"; fi
  if [ "${GWS_CONTACT+x}" = x ]; then contact="$GWS_CONTACT"; else contact="$(existing_var GWS_CONTACT)"; fi
  if [ -z "$admin" ]; then admin="$(rand_token)"; fi

  if ! id "$SVC_USER" >/dev/null 2>&1; then
    useradd --system --home-dir "$DATA_DIR" --shell /usr/sbin/nologin "$SVC_USER"
  fi
  mkdir -p "$(dirname "$ENV_FILE")" "$DATA_DIR"
  chown "$SVC_USER:$SVC_USER" "$DATA_DIR"

  ( umask 077
    {
      echo "PORT=$PORT"
      echo "GWS_DATA=$DATA_DIR/communaute.json"
      echo "GWS_ADMIN_TOKEN=$admin"
      if [ -n "$origins" ]; then echo "GWS_ORIGINS=$origins"; fi
      if [ -n "$contact" ]; then echo "GWS_CONTACT=$contact"; fi
      echo "JAVA_HOME=$JDK_HOME"
    } > "$ENV_FILE"
  )
  chmod 600 "$ENV_FILE"
  ok "configuration $ENV_FILE (jeton de modération conservé à la ré-exécution)"
}

write_unit() {
  cat > "/etc/systemd/system/$SVC_NAME.service" <<EOF
[Unit]
Description=GWS Community Server (Greenwood School+)
Documentation=https://github.com/iliasgws/gws-community-server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SVC_USER
Group=$SVC_USER
WorkingDirectory=$DATA_DIR
EnvironmentFile=$ENV_FILE
Environment=PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
Environment=LANG=C.UTF-8
ExecStart=$APP_HOME/bin/$SVC_NAME
Restart=always
RestartSec=3
TimeoutStopSec=30
UMask=0027
LimitNOFILE=65535

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectKernelLogs=true
ProtectControlGroups=true
ProtectClock=true
RestrictSUIDSGID=true
RestrictRealtime=true
RestrictNamespaces=true
LockPersonality=true
CapabilityBoundingSet=
AmbientCapabilities=
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
SystemCallArchitectures=native
ReadWritePaths=$DATA_DIR

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable --quiet "$SVC_NAME"
  ok "unité systemd $SVC_NAME installée"
}

start_and_check() {
  systemctl restart "$SVC_NAME"
  local i code=""
  for i in $(seq 1 60); do
    if ! systemctl is-active --quiet "$SVC_NAME"; then
      journalctl -u "$SVC_NAME" -n 40 --no-pager >&2 || true
      die "service $SVC_NAME redémarre en boucle (journalctl -u $SVC_NAME)"
    fi
    code=$(curl -fsS -o /dev/null -w '%{http_code}' --max-time 2 "http://127.0.0.1:$PORT/health" 2>/dev/null || true)
    if [ "$code" = "200" ]; then break; fi
    sleep 1
  done
  if [ "$code" != "200" ]; then die "GET /health ne répond pas 200 sur le port $PORT"; fi
  ok "service actif — GET /health → 200"

  if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q '^Status: active'; then
    if ufw allow "$PORT/tcp" >/dev/null 2>&1; then ok "ufw : port $PORT ouvert"; fi
  fi
}

raw_install_url() {
  local r="${REPO_URL%.git}"
  case "$r" in
    *github.com/*)
      r="${r#*github.com/}"
      printf 'https://raw.githubusercontent.com/%s/%s/install.sh' "$r" "$REF"
      ;;
    *) printf '%s' "$REPO_URL" ;;
  esac
}

summary() {
  local ip
  ip=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
  printf '\n%s  ✔  gws-community-server installé%s\n' "$C_OK" "$C_OFF"
  printf '  URL       http://%s:%s   (GET /health, /devoirs, /mentions)\n' "${ip:-<ip-du-CT>}" "$PORT"
  printf '  Service   systemctl status %s\n' "$SVC_NAME"
  printf '  Logs      journalctl -u %s -f\n' "$SVC_NAME"
  printf '  Config    %s   (GWS_ADMIN_TOKEN, GWS_ORIGINS, GWS_CONTACT)\n' "$ENV_FILE"
  printf '  Données   %s/communaute.json\n' "$DATA_DIR"
  printf '  Upgrade   curl -fsSL %s | bash\n' "$(raw_install_url)"
  if [ -z "$(existing_var GWS_ORIGINS)" ]; then
    printf '  %swarn%s  GWS_ORIGINS vide : aucun site appelable depuis un navigateur (403 CORS)\n' "$C_WARN" "$C_OFF"
    printf '          echo "GWS_ORIGINS=https://mon.app" >> %s && systemctl restart %s\n' "$ENV_FILE" "$SVC_NAME"
  fi
}

uninstall() {
  log "désinstallation de $SVC_NAME"
  systemctl disable --now "$SVC_NAME" 2>/dev/null || true
  rm -f "/etc/systemd/system/$SVC_NAME.service"
  systemctl daemon-reload
  rm -rf "$PREFIX" "$(dirname "$ENV_FILE")"
  if [ "${GWS_PURGE_DATA:-0}" = "1" ]; then
    rm -rf "$DATA_DIR"
    ok "données supprimées"
  else
    ok "données conservées dans $DATA_DIR (GWS_PURGE_DATA=1 pour tout effacer)"
  fi
  if id "$SVC_USER" >/dev/null 2>&1; then userdel "$SVC_USER" 2>/dev/null || true; fi
  ok "désinstallation terminée"
}

main_install() {
  need_root
  check_env
  log "gws-community-server — Debian 13 $ARCH, port $PORT, ref $REF"
  apt_tune
  ensure_packages
  ensure_swap
  install_jdk
  ensure_source
  build
  install_dist
  write_env
  write_unit
  start_and_check
  summary
}

case "$MODE" in
  install|upgrade) main_install ;;
  uninstall) need_root; uninstall ;;
  *) usage ;;
esac
