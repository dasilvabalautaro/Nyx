#!/bin/bash
# Despliega/actualiza el nodo Krypta en la Mac Catalina bajo launchd (KeepAlive).
# Ejecútalo EN la Catalina, desde el repo:  bash infra/node/deploy-catalina.sh
#
# Qué hace:
#   1. Copia el binario fresco (con listener WebSocket /ws) a ~/krypta
#   2. Instala/recarga el LaunchAgent (arranca solo y se reinicia si cae)
#   3. Verifica que escucha en :8081 y que node.log lista la addr .../tcp/8081/ws
set -euo pipefail

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
BIN_SRC="$SRC_DIR/dist/krypta-node-catalina"
PLIST_SRC="$SRC_DIR/chat.neto.krypta.node.plist"

DEST="$HOME/krypta"
BIN_DEST="$DEST/krypta-node-catalina"
LABEL="chat.neto.krypta.node"
PLIST_DEST="$HOME/Library/LaunchAgents/$LABEL.plist"

echo "==> binario origen: $BIN_SRC"
[ -f "$BIN_SRC" ] || { echo "ERROR: no existe $BIN_SRC (¿sincronizaste el repo?)"; exit 1; }

echo "==> verificando que el binario trae el listener ws y el buzón..."
strings "$BIN_SRC" | grep -q wsport || { echo "ERROR: este binario NO tiene -wsport (es viejo)"; exit 1; }
strings "$BIN_SRC" | grep -q mailboxdir || { echo "ERROR: este binario NO tiene buzón (-mailboxdir) — recompila dist/"; exit 1; }
strings "$BIN_SRC" | grep -q "krypta/wake" || { echo "ERROR: este binario NO tiene wake (/krypta/wake) — recompila dist/"; exit 1; }

mkdir -p "$DEST"

# Para el servicio si ya estaba cargado (para poder reemplazar el binario en uso).
if launchctl list | grep -q "$LABEL"; then
  echo "==> descargando servicio previo"
  launchctl unload "$PLIST_DEST" 2>/dev/null || true
fi

echo "==> copiando binario a $BIN_DEST"
cp -f "$BIN_SRC" "$BIN_DEST"
chmod +x "$BIN_DEST"

echo "==> instalando LaunchAgent en $PLIST_DEST"
mkdir -p "$HOME/Library/LaunchAgents"
cp -f "$PLIST_SRC" "$PLIST_DEST"

echo "==> cargando servicio (RunAtLoad + KeepAlive)"
launchctl load "$PLIST_DEST"
launchctl start "$LABEL"

echo "==> esperando arranque..."
sleep 4

echo
echo "===== node.log ====="
tail -n 12 "$DEST/node.log" 2>/dev/null || echo "(sin node.log todavía)"
echo
echo "===== ¿escucha en :8081 y :4001? ====="
lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null | grep -E ":8081|:4001" || echo "(NADA escuchando — revisa node.err)"
echo
echo "===== prueba ws local (debe dar HTTP 400/426, NO 'refused') ====="
curl -sS -m 5 -o /dev/null -w "  http://localhost:8081 -> HTTP %{http_code}\n" http://localhost:8081 \
  || echo "  curl falló (rc=$?) — el nodo no escucha en 8081"
echo
echo "Si node.log muestra una línea  .../tcp/8081/ws/p2p/<PeerID>  y curl da 400/426,"
echo "el origen está OK. Ahora prueba el camino completo de Cloudflare:"
echo "  npx wscat -c wss://krypta.neto.chat      (debe dar 101 Switching Protocols)"
echo
echo "Anota el PeerID del node.log -> el bootstrap de la app es:"
echo "  /dns4/krypta.neto.chat/tcp/443/wss/p2p/<PeerID>"
