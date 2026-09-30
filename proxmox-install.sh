#!/usr/bin/env bash
#
# One-click Proxmox VE : demande le nœud et le VMID, crée le LXC depuis un
# template debian-13-standard de l'architecture du nœud, puis installe
# gws-community-server à l'intérieur (build + systemd + healthcheck).
#
#   curl -fsSL https://raw.githubusercontent.com/iliasgws/gws-community-server/main/proxmox-install.sh | bash
#
# Variables (sinon demandées à l'exécution, défauts entre crochets) :
#   GWS_NODE=<nœud>  VMID=<id>  CT_HOSTNAME=gws  MEM=4096  CORES=4
#   CT_SWAP=2048  DISK=16  STORAGE=<auto>  BRIDGE=vmbr0  TEMPLATE=<auto>
#   GWS_PORT=8080  GWS_REF=main  GWS_ORIGINS=...  GWS_CONTACT=...
# Sans tty (cron, pct exec), les défauts sont utilisés : nœud local,
# premier VMID libre du cluster.
# Upgrade : relancer ce script (détecte le CT déjà créé).

set -Eeuo pipefail
IFS=$'\n\t'

VMID="${VMID:-}"
TARGET_NODE="${GWS_NODE:-}"
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
proxmox-install.sh — crée le CT Debian 13 et installe gws-community-server

  curl -fsSL https://raw.githubusercontent.com/iliasgws/gws-community-server/main/proxmox-install.sh | bash

Demande le nœud et le VMID (défauts : nœud local, premier ID libre du
cluster). Variables : GWS_NODE VMID CT_HOSTNAME MEM CORES CT_SWAP DISK
STORAGE BRIDGE TEMPLATE GWS_PORT GWS_REF GWS_ORIGINS GWS_CONTACT
EOF
  exit 0
}

case "${1:-}" in -h|--help) usage ;; esac

# --------------------------------------------------------------- utilitaires
q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

# Un vrai tty ? (-r ne suffit pas : /dev/tty existe mais open() échoue sans ctty)
if [ -r /dev/tty ] && ( : <> /dev/tty ) 2>/dev/null; then HAVE_TTY=1; else HAVE_TTY=0; fi

# Question sur le terminal (jamais sur stdin : le script y arrive par pipe).
ask() {
  local prompt="$1" def="$2" rep=""
  if [ "$HAVE_TTY" = 1 ]; then
    printf '%s [%s] : ' "$prompt" "$def" > /dev/tty || true
    IFS= read -r rep < /dev/tty || rep=""
  fi
  printf '%s' "${rep:-$def}"
}

cluster_nodes() {
  find "$PVE_DIR/nodes" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort || true
}

# Tous les IDs déclarés dans le cluster : CT (nodes/*/lxc) ET VM QEMU
# (nodes/*/qemu-server + qemu-server/ en racine d'arbre PVE).
used_vmids() {
  {
    find "$PVE_DIR/nodes" -mindepth 3 -maxdepth 3 -path '*/lxc/*.conf' -printf '%f\n' 2>/dev/null
    find "$PVE_DIR/nodes" -mindepth 3 -maxdepth 3 -path '*/qemu-server/*.conf' -printf '%f\n' 2>/dev/null
    find "$PVE_DIR/qemu-server" -mindepth 1 -maxdepth 1 -name '*.conf' -printf '%f\n' 2>/dev/null
  } | grep -oE '^[0-9]+' | sort -un || true
}

# Config d'un ID précis (CT ou VM) ; vide si l'ID est libre.
vmid_conf() {
  local id="$1" f
  for f in "$PVE_DIR/nodes"/*/lxc/"$id".conf \
           "$PVE_DIR/nodes"/*/qemu-server/"$id".conf \
           "$PVE_DIR/qemu-server"/"$id".conf; do
    if [ -f "$f" ]; then printf '%s' "$f"; return 0; fi
  done
  return 0
}

# Filet PVE : l'API sait si un ID est pris (CT + VM), y compris pour un
# emplacement de config qu'on n'aurait pas prévu. Ne se déclenche que si
# PVE répond explicitement « existe déjà ».
pve_vmid_taken() {
  local id="$1" out
  command -v pvesh >/dev/null 2>&1 || return 1
  out=$(pvesh get /cluster/nextid --vmid "$id" 2>&1) && return 1
  printf '%s' "$out" | grep -qiE 'exist|already|in use|used' || return 1
  return 0
}

free_vmid() {
  local used n api
  used=$(used_vmids)
  n=100
  while printf '%s\n' "$used" | grep -qx "$n"; do n=$((n + 1)); done
  # si PVE signale pris ce que le scan n'a pas vu, demander l'ID libre à l'API
  if pve_vmid_taken "$n"; then
    api=$(pvesh get /cluster/nextid 2>/dev/null | tr -cd '0-9') && [ -n "$api" ] && n="$api"
  fi
  printf '%s' "$n"
}

raw_self_url() {
  local r="${REPO_URL%.git}"
  case "$r" in
    *github.com/*) printf 'https://raw.githubusercontent.com/%s/raw/%s/proxmox-install.sh' "${r#*github.com/}" "$REF" ;;
    *) return 1 ;;
  esac
}

# --------------------------------------------------------------- préflight
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

# ------------------------------------------------- nœud & VMID (demandés)
LOCAL_NODE=$(hostname)
NODES=$(cluster_nodes)
NODE_COUNT=$(printf '%s\n' "$NODES" | grep -c . || true)

if [ -z "$TARGET_NODE" ]; then
  if [ "$NODE_COUNT" -gt 1 ] && [ "$HAVE_TTY" = 1 ]; then
    log "nœuds du cluster : $(printf '%s' "$NODES" | tr '\n' ' ')"
    TARGET_NODE=$(ask "nœud cible" "$LOCAL_NODE")
  else
    TARGET_NODE="$LOCAL_NODE"
  fi
fi
if [ -n "$NODES" ] && ! printf '%s\n' "$NODES" | grep -qx "$TARGET_NODE"; then
  if [ "$TARGET_NODE" = "$LOCAL_NODE" ]; then
    warn "hostname '$LOCAL_NODE' absent de $PVE_DIR/nodes ($(printf '%s' "$NODES" | tr '\n' ' ')) : on reste en local"
  else
    die "nœud '$TARGET_NODE' inconnu — nœuds du cluster : $(printf '%s' "$NODES" | tr '\n' ' ')"
  fi
fi
log "nœud cible : $TARGET_NODE (ce script tourne sur $LOCAL_NODE) — arch $NODE_ARCH"

PROPOSED=$(free_vmid)
USED_COUNT=$(used_vmids | grep -c . || true)
tries=0
while :; do
  if [ -z "$VMID" ]; then
    if [ "$HAVE_TTY" = 1 ]; then
      VMID=$(ask "VMID ($USED_COUNT VM/CT déjà déclarés dans le cluster, $PROPOSED libre)" "$PROPOSED")
    else
      VMID="$PROPOSED"
      log "pas de tty : VMID auto = $VMID"
    fi
  fi
  case "$VMID" in
    ''|*[!0-9]*) die "VMID invalide : '$VMID' (nombre attendu)" ;;
  esac
  CONFLICT=$(vmid_conf "$VMID")
  [ -z "$CONFLICT" ] && break
  if [ "$tries" -lt 2 ] && [ "$HAVE_TTY" = 1 ]; then
    warn "VMID $VMID est déjà pris ($(printf '%s' "$CONFLICT" | sed -n 's|^.*/nodes/\([^/]*\)/.*|\1|p'))"
    tries=$((tries + 1)); VMID=""; PROPOSED=$(free_vmid)
    continue
  fi
  break   # occupé et pas de tty : le contrôle ci-dessous décide (relais ou erreur)
done
log "VMID retenu : $VMID"

# Config déjà présente ? OK seulement si elle est sur la cible (sinon : erreur
# de saisie — et si la cible est un autre nœud, on va y relayer pour upgrade).
CT_NODE=""
if [ -n "$CONFLICT" ]; then
  CT_NODE=$(printf '%s' "$CONFLICT" | sed -n 's|^.*/nodes/\([^/]*\)/.*|\1|p')
  if [ -z "$CT_NODE" ]; then
    # config en /etc/pve/qemu-server : VM, l'arbre ne dit pas quel nœud la sert
    die "VMID $VMID déjà pris : VM QEMU (config : $CONFLICT) — pct ne peut pas le réutiliser.
  → autre VMID : relancer avec VMID=$(free_vmid)
  → lister     : qm list / pct list"
  fi
  case "$CONFLICT" in
    */qemu-server/*)
      die "VMID $VMID déjà pris : VM QEMU du nœud '$CT_NODE' (config : $CONFLICT) — pct ne peut pas le réutiliser.
  → autre VMID : relancer avec VMID=$(free_vmid)
  → lister     : qm list / pct list" ;;
  esac
  if [ "$CT_NODE" != "$TARGET_NODE" ]; then
    die "VMID $VMID déjà utilisé sur le nœud '$CT_NODE' (config : $CONFLICT), alors que la cible est '$TARGET_NODE'.
  → autre VMID     : relancer avec VMID=$(free_vmid)
  → changer de nœud: relancer avec GWS_NODE=$CT_NODE VMID=$VMID
  → supprimer      : pct destroy $VMID  (sur $CT_NODE)"
  fi
elif pve_vmid_taken "$VMID"; then
  die "VMID $VMID déclaré pris par PVE (/cluster/nextid) sans config trouvée sous $PVE_DIR.
  → autre VMID : relancer avec VMID=$(free_vmid)
  → lister     : qm list / pct list"
fi

# ----------------------------------------------------- relais vers un nœud
if [ "$TARGET_NODE" != "$LOCAL_NODE" ]; then
  FWD=(CT_HOSTNAME MEM CORES CT_SWAP DISK BRIDGE STORAGE TEMPLATE
       GWS_PORT GWS_REF GWS_REPO GWS_ORIGINS GWS_CONTACT GWS_ADMIN_TOKEN
       GWS_BUILD_HEAP GWS_SWAP)
  envstr="env VMID=$(q "$VMID") GWS_NODE=$(q "$TARGET_NODE")"
  for v in "${FWD[@]}"; do
    val="${!v-}"
    if [ -n "$val" ]; then envstr="$envstr $v=$(q "$val")"; fi
  done
  log "relais vers $TARGET_NODE (pct n'opère que localement)"
  if ssh -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new \
        "root@$TARGET_NODE" true >/dev/null 2>&1; then
    if [ -f "$0" ] && [ -r "$0" ]; then
      log "ssh root@$TARGET_NODE, script local"
      exec ssh "root@$TARGET_NODE" "$envstr bash -s" < "$0"
    fi
    self_url=$(raw_self_url) || self_url=""
    if [ -n "$self_url" ]; then
      log "ssh root@$TARGET_NODE, script via $self_url"
      exec ssh "root@$TARGET_NODE" "$envstr bash -c $(q "curl -fsSL $self_url | bash")"
    fi
    die "dépôt non GitHub et script non local : copiez proxmox-install.sh sur $TARGET_NODE et relancez-le là-bas"
  fi
  die "ssh vers root@$TARGET_NODE impossible (BatchMode) — relancez sur ce nœud :
  ssh root@$TARGET_NODE
  curl -fsSL $(raw_self_url 2>/dev/null || echo '<url-du-script>') | VMID=$VMID GWS_NODE=$LOCAL_NODE bash"
fi

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
    | sort -uV | tail -n1 || true
}

ensure_template() {
  local present arch dl choix wrong
  present=$(pveam list "$TEMPLATE_STORAGE" 2>/dev/null || true)
  arch=$(tpl_arch "$TEMPLATE")

  if [ "$arch" != "$NODE_ARCH" ]; then
    log "template $TEMPLATE : arch ${arch:-inconnue} ≠ $NODE_ARCH → sélection automatique"
    choix=$(choose_template)
    if [ -z "$choix" ]; then
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

  warn "$TEMPLATE refusé par pveam :"
  printf '%s\n' "$dl" | tr '\r' '\n' | sed '/^$/d' | head -n 3 >&2

  choix=$(choose_template)
  if [ -z "$choix" ]; then
    pveam available --section system 2>&1 | head -n 15 >&2 || true
    die "aucun template Debian 13 $NODE_ARCH téléchargeable"
  fi
  log "nouveau choix : $choix"
  TEMPLATE="$choix"
  if ! dl=$(pveam download "$TEMPLATE_STORAGE" "$TEMPLATE" 2>&1); then
    printf '%s\n' "$dl" | tr '\r' '\n' | sed '/^$/d' | head -n 3 >&2
    die "téléchargement de $TEMPLATE impossible"
  fi
  ok "template $TEMPLATE sur $TEMPLATE_STORAGE"
}
ensure_template

# ------------------------------------------------------------------ create
if [ -n "$CONFLICT" ]; then
  log "CT $VMID déjà déclaré sur $TARGET_NODE — vérification"
  if pct status "$VMID" >/dev/null 2>&1; then
    log "CT $VMID local ($(pct config "$VMID" | sed -n 's/^hostname: //p')) → passage à l'installation"
  else
    pct status "$VMID" 2>&1 | head -n 3 >&2 || true
    die "VMID $VMID déclaré ($CONFLICT) mais inexploitable depuis ce nœud"
  fi
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
    die "pct create $VMID a échoué (ci-dessus).
  → autre VMID : relancer avec VMID=$(free_vmid)
  → lister     : qm list / pct list"
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
  echo "export GWS_REPO=$(q "$REPO_URL")"
  echo "export GWS_REF=$(q "$REF")"
  for v in GWS_PORT GWS_ORIGINS GWS_CONTACT GWS_ADMIN_TOKEN GWS_BUILD_HEAP GWS_SWAP; do
    val="${!v-}"
    if [ -n "$val" ]; then echo "export $v=$(q "$val")"; fi
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

IP=$(pct exec "$VMID" -- hostname -I 2>/dev/null | awk '{print $1}' || true)
PORT="${GWS_PORT:-8080}"
printf '\n%s  ✔  CT %s prêt (nœud %s)%s\n' "$C_OK" "$VMID" "$TARGET_NODE" "$C_OFF"
printf '  IP        %s\n' "${IP:-?}"
printf '  Entrer    pct exec %s -- bash\n' "$VMID"
printf '  URL       http://%s:%s/health\n' "${IP:-?}" "$PORT"
printf '  Service   pct exec %s -- systemctl status gws-community-server\n' "$VMID"
