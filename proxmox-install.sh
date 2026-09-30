#!/usr/bin/env bash
#
# One-click Proxmox VE : crée le LXC depuis debian-13-standard_13.1-2_amd64
# puis lance install.sh à l'intérieur (build + systemd + healthcheck).
#
#   curl -fsSL https://raw.githubusercontent.com/iliasgws/gws-community-server/main/proxmox-install.sh | bash
#
# Variables : VMID=123 CT_HOSTNAME=gws MEM=4096 CORES=4 CT_SWAP=2048 DISK=16
#             STORAGE=<auto> BRIDGE=vmbr0 TEMPLATE=<auto> REPO=<url git>
#             GWS_PORT=8080 GWS_REF=main GWS_ORIGINS=... GWS_CONTACT=...
# Upgrade d'un CT existant : relancer ce script (il détecte le CT déjà créé).

set -Eeuo pipefail
IFS=$'\n\t'

VMID="${VMID:-123}"
CT_HOSTNAME="${CT_HOSTNAME:-gws}"
MEM="${MEM:-4096}"
CORES="${CORES:-4}"
CT_SWAP="${CT_SWAP:-2048}"
DISK="${DISK:-16}"
BRIDGE="${BRIDGE:-vmbr0}"
STORAGE="${STORAGE:-}"
TEMPLATE="${TEMPLATE:-debian-13-standard_13.1-2_amd64.tar.zst}"
REPO_URL="${GWS_REPO:-https://github.com/iliasgws/gws-community-server.git}"
REF="${GWS_REF:-main}"
PVE_DIR="${GWS_PVE_DIR:-/etc/pve}"

if [ -t 1 ]; then
  C_LOG=$'\033[1;34m'; C_OK=$'\033[1;32m'; C_ERR=$'\033[1;31m'; C_OFF=$'\033[0m'
else
  C_LOG=""; C_OK=""; C_ERR=""; C_OFF=""
fi

log() { printf '%s==>%s %s\n' "$C_LOG" "$C_OFF" "$*"; }
ok()  { printf '%s  ok%s  %s\n' "$C_OK" "$C_OFF" "$*"; }
die() { printf '%s err%s %s\n' "$C_ERR" "$C_OFF" "$*" >&2; exit 1; }

trap 'printf "%s err%s échec ligne %s\n" "$C_ERR" "$C_OFF" "${BASH_LINENO[0]:-?}" >&2; exit 1' ERR

usage() {
  cat <<'EOF'
proxmox-install.sh — crée le CT Debian 13 et installe gws-community-server

  curl -fsSL https://raw.githubusercontent.com/iliasgws/gws-community-server/main/proxmox-install.sh | bash

Variables : VMID CT_HOSTNAME MEM CORES CT_SWAP DISK STORAGE BRIDGE TEMPLATE
            GWS_PORT GWS_REF GWS_ORIGINS GWS_CONTACT GWS_ADMIN_TOKEN
EOF
  exit 0
}

case "${1:-}" in -h|--help) usage ;; esac

# ------------------------------------------------------------- préflight
[ "$(id -u)" -eq 0 ] || die "exécuter en root sur le nœud Proxmox (pct/pveam)"
[ -d "$PVE_DIR" ] || die "nœud Proxmox introuvable ($PVE_DIR) : ce script crée un LXC via pct"
command -v pct >/dev/null 2>&1 || die "commande pct introuvable"
command -v pveam >/dev/null 2>&1 || die "commande pveam introuvable"

# ----------------------------------------------------------- arch du nœud
case "$(uname -m)" in
  x86_64|amd64) NODE_ARCH="amd64" ;;
  aarch64|arm64) NODE_ARCH="arm64" ;;
  *) NODE_ARCH="$(dpkg --print-architecture 2>/dev/null || true)" ;;
esac
case "$NODE_ARCH" in
  amd64|arm64) ;;
  *) die "architecture du nœud non prise en charge : $(uname -m)" ;;
esac
log "nœud $(hostname) — architecture $NODE_ARCH"

# ------------------------------------------------------- storage & template
pick_storage() {
  local types="$1" out t hit
  out=$(pvesm status 2>/dev/null) || return 0
  local IFS=','
  for t in $types; do
    hit=$(printf '%s\n' "$out" | awk -v ty="$t" '$2 == ty && $3 == "active" { print $1; exit }')
    if [ -n "$hit" ]; then printf '%s' "$hit"; return 0; fi
  done
  return 0
}

if [ -z "$STORAGE" ]; then
  STORAGE=$(pick_storage "lvmthin,dir,zfspool,nfs")
  [ -n "$STORAGE" ] || die "aucun storage rootfs actif (pvesm status)"
fi
TEMPLATE_STORAGE=$(pick_storage "dir,nfs")
[ -n "$TEMPLATE_STORAGE" ] || TEMPLATE_STORAGE="local"

tpl_arch() {
  case "$1" in
    *_amd64.tar.*) echo amd64 ;;
    *_arm64.tar.*) echo arm64 ;;
    *) echo "" ;;
  esac
}

# Template Debian 13 le plus récent, pour l'architecture du nœud uniquement.
choose_template() {
  pveam available --section system 2>/dev/null \
    | grep -Eo "debian-13-standard_[0-9][A-Za-z0-9._-]*_${NODE_ARCH}\\.tar\\.[a-z0-9]+" \
    | sort -uV | tail -n1
}

ensure_template() {
  local present arch dl choix
  present=$(pveam list "$TEMPLATE_STORAGE" 2>/dev/null || true)
  arch=$(tpl_arch "$TEMPLATE")

  if [ "$arch" != "$NODE_ARCH" ]; then
    log "template $TEMPLATE : arch ${arch:-inconnue} ≠ $NODE_ARCH → sélection automatique"
    choix=$(choose_template)
    if [ -z "$choix" ]; then
      log "pveam available --section system :"
      pveam available --section system 2>&1 | head -n 15 >&2 || true
      die "aucun template Debian 13 $NODE_ARCH proposé par pveam"
    fi
    log "template retenu : $choix (miroir, arch $NODE_ARCH)"
    TEMPLATE="$choix"
  fi

  if printf '%s' "$present" | grep -qF "$TEMPLATE"; then
    ok "template $TEMPLATE déjà présent"
    return 0
  fi

  # un template de la même famille est déjà là, mais pour une autre arch → ignoré
  wrong=$(printf '%s\n' "$present" \
    | grep -Eo "debian-13-standard_[0-9][A-Za-z0-9._-]*_(amd64|arm64)\\.tar\\.[a-z0-9]+" \
    | grep -v "_${NODE_ARCH}\\.tar" | head -n1 || true)
  if [ -n "$wrong" ]; then
    log "fichier local $wrong ignoré : architecture ≠ $NODE_ARCH"
  fi

  log "téléchargement de $TEMPLATE (≈100 Mo)"
  if dl=$(pveam download "$TEMPLATE_STORAGE" "$TEMPLATE" 2>&1); then
    ok "template $TEMPLATE sur $TEMPLATE_STORAGE"
    return 0
  fi

  log "$TEMPLATE : téléchargement échoué ($(printf '%s' "$dl" | tr '\r' '\n' | tail -n1))"
  choix=$(choose_template)
  if [ -z "$choix" ]; then
    pveam available --section system 2>&1 | head -n 15 >&2 || true
    die "aucun template Debian 13 $NODE_ARCH téléchargeable"
  fi
  log "nouveau choix : $choix"
  TEMPLATE="$choix"
  pveam download "$TEMPLATE_STORAGE" "$TEMPLATE" >/dev/null 2>&1 \
    || die "téléchargement impossible : pveam download $TEMPLATE_STORAGE $TEMPLATE"
  ok "template $TEMPLATE sur $TEMPLATE_STORAGE"
}
ensure_template

# ------------------------------------------------------------------ create
VMID_CONF=$(find "$PVE_DIR/nodes" -maxdepth 3 -path "*/lxc/${VMID}.conf" 2>/dev/null | head -n1 || true)
CT_NODE=""
if [ -n "$VMID_CONF" ]; then CT_NODE=$(printf '%s' "$VMID_CONF" | sed -n 's|^.*/nodes/\([^/]*\)/.*|\1|p'); fi

if pct status "$VMID" >/dev/null 2>&1; then
  log "CT $VMID local déjà existant ($(pct config "$VMID" | sed -n 's/^hostname: //p')) → passage à l'installation"
elif [ -n "$VMID_CONF" ]; then
  log "pct status $VMID :"
  pct status "$VMID" 2>&1 | head -n 3 >&2 || true
  die "VMID $VMID déjà utilisé sur le nœud '${CT_NODE:-?}' (config : $VMID_CONF), non contrôlable d'ici.
  → autre VMID   : VMID=<libre> bash $0
  → autre nœud   : relancer la même commande sur ${CT_NODE:-?}
  → supprimer     : pct destroy $VMID  (sur ${CT_NODE:-?})"
else
  log "création du CT $VMID ($CT_HOSTNAME, $TEMPLATE) : ${MEM} Mo, ${CORES} cœurs, ${DISK} Go, swap ${CT_SWAP} Mo, bridge $BRIDGE"
  if ! create_out=$(pct create "$VMID" "$TEMPLATE" \
      --hostname "$CT_HOSTNAME" \
      --memory "$MEM" \
      --swap "$CT_SWAP" \
      --cores "$CORES" \
      --rootfs "$STORAGE:$DISK" \
      --net0 "name=eth0,bridge=$BRIDGE,ip=dhcp" \
      --unprivileged 1 \
      --ostype debian \
      --onboot 1 \
      --start 1 2>&1); then
    printf '%s\n' "$create_out" >&2
    die "pct create $VMID a échoué (ci-dessus)"
  fi
  ok "CT $VMID créé et démarré"
fi

pct status "$VMID" | grep -q 'status: running' || pct start "$VMID"

log "attente du systemd du CT"
for _ in $(seq 1 60); do
  if pct exec "$VMID" -- test -d /run/systemd/system >/dev/null 2>&1; then break; fi
  sleep 2
done
pct exec "$VMID" -- test -d /run/systemd/system >/dev/null 2>&1 || die "le CT $VMID ne démarre pas (systemd absent)"
ok "CT $VMID joignable"

# --------------------------------------------------------- installation
RUNNER=$(mktemp)
trap 'rm -f "$RUNNER"' EXIT
{
  echo "#!/usr/bin/env bash"
  echo "set -Eeuo pipefail"
  echo "export GWS_REPO='$(printf '%s' "$REPO_URL" | sed "s/'/'\\\\''/g")'"
  echo "export GWS_REF='$REF'"
  for v in GWS_PORT GWS_ORIGINS GWS_CONTACT GWS_ADMIN_TOKEN GWS_BUILD_HEAP GWS_SWAP; do
    eval "val=\${$v-}"
    if [ -n "$val" ]; then echo "export $v='$(printf '%s' "$val" | sed "s/'/'\\\\''/g")'"; fi
  done
  echo "exec bash /root/install.sh"
} > "$RUNNER"
chmod 700 "$RUNNER"

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd || echo "")
INSTALL_URL=""
REPO_NO_GIT="${REPO_URL%.git}"
case "$REPO_NO_GIT" in
  *github.com/*)
    INSTALL_URL="https://raw.githubusercontent.com/${REPO_NO_GIT#*github.com/}/raw/$REF/install.sh"
    ;;
esac

if [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/install.sh" ]; then
  log "installation depuis $SCRIPT_DIR/install.sh (pct push)"
  pct push "$VMID" "$SCRIPT_DIR/install.sh" /root/install.sh --perms 0755
elif [ -n "$INSTALL_URL" ]; then
  log "installation depuis $INSTALL_URL"
  pct exec "$VMID" -- bash -c "curl -fsSL '$INSTALL_URL' -o /root/install.sh"
  pct exec "$VMID" -- chmod 0755 /root/install.sh
else
  die "dépôt non GitHub : placez install.sh à côté de proxmox-install.sh"
fi
pct push "$VMID" "$RUNNER" /root/gws-run.sh --perms 0700
pct exec "$VMID" -- bash /root/gws-run.sh
pct exec "$VMID" -- rm -f /root/gws-run.sh /root/install.sh

IP=$(pct exec "$VMID" -- hostname -I 2>/dev/null | awk '{print $1}')
PORT="${GWS_PORT:-8080}"
printf '\n%s  ✔  CT %s prêt%s\n' "$C_OK" "$VMID" "$C_OFF"
printf '  IP        %s\n' "${IP:-?}"
printf '  Entrer    pct exec %s -- bash\n' "$VMID"
printf '  URL       http://%s:%s/health\n' "${IP:-?}" "$PORT"
printf '  Service   pct exec %s -- systemctl status gws-community-server\n' "$VMID"
