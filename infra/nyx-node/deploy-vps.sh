#!/bin/bash
# Despliega/actualiza el nodo Nyx en un VPS Linux bajo systemd.
# Ejecútalo DESDE la Mac (no en el VPS), con el repo delante:
#
#   bash infra/nyx-node/deploy-vps.sh root@1.2.3.4            # amd64 (por defecto)
#   bash infra/nyx-node/deploy-vps.sh usuario@mivps arm64     # VPS ARM (Ampere, Graviton…)
#
# Requiere acceso SSH con sudo (o root). Qué hace:
#   1. Sube el binario Linux de dist/ a /usr/local/bin/nyx-node
#   2. Crea el usuario de sistema `nyx` y /var/lib/nyx (identidad + buzón)
#   3. Instala/recarga la unidad systemd (Restart=always, arranca en el boot)
#   4. Abre los puertos en ufw si está activo
#   5. Imprime el PeerID y el multiaddr de bootstrap para pegar en la app
#
# Es idempotente: relanzarlo actualiza el binario y reinicia el servicio. NO toca el
# node.key existente, así que el PeerID se conserva entre despliegues.
set -euo pipefail

HOST="${1:-}"
ARCH="${2:-amd64}"
if [ -z "$HOST" ]; then
  echo "uso: bash infra/nyx-node/deploy-vps.sh usuario@host [amd64|arm64]" >&2
  exit 1
fi

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
BIN_SRC="$SRC_DIR/dist/nyx-node-linux-$ARCH"
UNIT_SRC="$SRC_DIR/nyx-node.service"

echo "==> binario origen: $BIN_SRC"
[ -f "$BIN_SRC" ] || {
  echo "ERROR: no existe $BIN_SRC. Compílalo con:"
  echo "  cd infra/node && GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=linux GOARCH=$ARCH \\"
  echo "    go1.22.12 build -o dist/nyx-node-linux-$ARCH ."
  exit 1
}

# Mismas comprobaciones que en Catalina: que el binario no sea uno viejo sin buzón/wake.
# `grep -a` sobre el fichero, sin tubería: `strings | grep -q` revienta con set -o pipefail
# (grep cierra la tubería al primer acierto y strings muere con SIGPIPE).
grep -a -q mailboxdir "$BIN_SRC"    || { echo "ERROR: binario sin buzón (-mailboxdir)"; exit 1; }
grep -a -q "nyx/wake" "$BIN_SRC" || { echo "ERROR: binario sin wake (/nyx/wake)"; exit 1; }
grep -a -q quicport "$BIN_SRC"      || { echo "ERROR: binario sin -quicport (recompila dist/)"; exit 1; }

echo "==> subiendo binario y unidad a $HOST"
scp -q "$BIN_SRC" "$HOST:/tmp/nyx-node.new"
scp -q "$UNIT_SRC" "$HOST:/tmp/nyx-node.service"

echo "==> instalando en el VPS (pide sudo)"
ssh -t "$HOST" 'sudo bash -s' <<'REMOTE'
set -euo pipefail

# Usuario de sistema sin shell ni home: solo corre el proceso.
if ! id nyx >/dev/null 2>&1; then
  echo "  creando usuario de sistema nyx"
  useradd --system --no-create-home --shell /usr/sbin/nologin nyx
fi

# Para el servicio antes de reemplazar el binario en uso (si ya existía).
systemctl stop nyx-node 2>/dev/null || true

install -m 0755 /tmp/nyx-node.new /usr/local/bin/nyx-node
install -m 0644 /tmp/nyx-node.service /etc/systemd/system/nyx-node.service
rm -f /tmp/nyx-node.new /tmp/nyx-node.service

mkdir -p /var/lib/nyx
chown nyx:nyx /var/lib/nyx
chmod 0700 /var/lib/nyx

systemctl daemon-reload
systemctl enable --now nyx-node

# Firewall: TCP/UDP 4001 (libp2p) y 443 (wss, si pones Caddy/nginx delante).
# El 8081 es ws EN CLARO: no se abre al exterior a propósito.
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  echo "  ufw activo: abriendo 4001/tcp, 4001/udp, 443/tcp"
  ufw allow 4001/tcp >/dev/null
  ufw allow 4001/udp >/dev/null
  ufw allow 443/tcp  >/dev/null
else
  echo "  (ufw no activo: abre 4001/tcp, 4001/udp y 443/tcp en el firewall del proveedor)"
fi

sleep 4
echo
echo "===== estado ====="
systemctl is-active nyx-node && systemctl is-enabled nyx-node
echo
echo "===== log (últimas líneas) ====="
journalctl -u nyx-node -n 20 --no-pager
echo
echo "===== ¿escucha? ====="
(ss -lntup 2>/dev/null || netstat -lntup 2>/dev/null) | grep -E ":4001|:8081" || echo "(NADA escuchando — revisa el log)"
REMOTE

echo
echo "==================================================================="
echo "Siguiente paso: coge el PeerID del log de arriba y comprueba desde aquí"
echo
echo "  # TCP directo (sin TLS, el camino simple con IP pública):"
echo "  MBX_ADDR=/ip4/<IP_DEL_VPS>/tcp/4001/p2p/<PeerID> \\"
echo "    go test -run TestMailboxFetchAgainstLiveNode -v ./native-bridge/libp2p/"
echo
echo "Y en la app, Ajustes → Nodos WAN (bootstrap), una línea por nodo:"
echo "  /ip4/<IP_DEL_VPS>/tcp/4001/p2p/<PeerID>"
echo "  /ip4/<IP_DEL_VPS>/udp/4001/quic-v1/p2p/<PeerID>     (QUIC, ya con puerto fijo)"
echo "  /dns4/<tu-dominio>/tcp/443/wss/p2p/<PeerID>          (si montas Caddy, ver README)"
echo
echo "Cuando el nodo esté validado, pásalo a Libp2pNode.DEFAULT_BOOTSTRAP como nodo primario."
echo "==================================================================="
