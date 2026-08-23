#!/bin/bash
# Instala el aviso de denuncias como LaunchAgent del usuario. Idempotente.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$DIR/comprobar-denuncias.sh"
LOG="$HOME/Library/Logs/nyx-denuncias.log"
DEST="$HOME/Library/LaunchAgents/chat.neto.nyx.denuncias.plist"

mkdir -p "$(dirname "$DEST")" "$(dirname "$LOG")" "$HOME/keys/nyx-operator"
sed -e "s|__RUTA__|$SCRIPT|" -e "s|__LOG__|$LOG|" \
    "$DIR/chat.neto.nyx.denuncias.plist" > "$DEST"

launchctl unload "$DEST" 2>/dev/null || true
launchctl load "$DEST"

echo "Instalado. Comprueba cada 6 h y al arrancar sesión."
echo "  registro:   $LOG"
echo "  quitarlo:   launchctl unload $DEST && rm $DEST"
echo
echo "Prueba ahora:"
bash "$SCRIPT" && echo "(sin denuncias pendientes, o nodo inalcanzable: en ambos casos calla)"
