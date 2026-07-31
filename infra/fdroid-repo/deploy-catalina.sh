#!/bin/bash
# Publica/actualiza el repo F-Droid autoalojado de Krypta en la Mac Catalina.
# Se sirve en localhost:8082 y se expone en https://fdroid.neto.chat vía el mismo
# túnel de Cloudflare que ya usa krypta.neto.chat (ver README.md de este directorio
# para el paso, manual, de añadir el Public Hostname en el dashboard).
#
# Uso (EN la Catalina, con el repo ya sincronizado):
#   bash infra/fdroid-repo/deploy-catalina.sh /ruta/al/app-release.apk
#
# El .apk NO vive en este repo (es un binario firmado de ~100 MB): cópialo antes
# desde la máquina de build, p. ej.:
#   scp app/build/outputs/apk/release/app-release.apk catalina:/tmp/
#
# Qué hace:
#   1. La primera vez: instala fdroidserver (pip3 --user) e inicializa el repo en
#      ~/krypta-fdroid (genera su PROPIO keystore de firma del índice — distinto del
#      keystore.jks con el que ya firmas la app).
#   2. Copia el APK dentro de repo/ y corre `fdroid update` (regenera el índice).
#   3. Instala/recarga el LaunchAgent que sirve repo/ en 127.0.0.1:8082 (KeepAlive).
set -euo pipefail

APK_SRC="${1:?Uso: deploy-catalina.sh /ruta/al/app-release.apk}"
[ -f "$APK_SRC" ] || { echo "ERROR: no existe $APK_SRC"; exit 1; }

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
PLIST_SRC="$SRC_DIR/chat.neto.krypta.fdroid.plist"

REPO_HOME="$HOME/krypta-fdroid"
LABEL="chat.neto.krypta.fdroid"
PLIST_DEST="$HOME/Library/LaunchAgents/$LABEL.plist"

command -v python3.12 >/dev/null || { echo "ERROR: falta python3 (Catalina trae Python 2 por defecto; instala Python 3, p. ej. con 'brew install python3')"; exit 1; }

if ! command -v fdroid >/dev/null; then
  echo "==> instalando fdroidserver (pip3 install --user)"
  pip3 install --user fdroidserver
  PYVER="$(python3 -c 'import platform;print(".".join(platform.python_version_tuple()[:2]))')"
  export PATH="$HOME/Library/Python/$PYVER/bin:$PATH"
  command -v fdroid >/dev/null || {
    echo "ERROR: 'fdroid' no quedó en PATH. Añade esto a tu ~/.zshrc o ~/.bash_profile y reabre la terminal:"
    echo "  export PATH=\"\$HOME/Library/Python/$PYVER/bin:\$PATH\""
    exit 1
  }
fi

mkdir -p "$REPO_HOME"
cd "$REPO_HOME"

if [ ! -f config.yml ] && [ ! -f config.py ]; then
  echo "==> primera vez: fdroid init (genera el keystore de firma del índice + config.yml)"
  echo "    Si pregunta por el SDK de Android, puedes dejarlo en blanco/cancelar: no hace"
  echo "    falta para un repo de solo-binarios como este. Si 'fdroid update' más abajo se"
  echo "    queja de 'aapt', instala androguard como alternativa: pip3 install --user androguard"
  fdroid init
fi

mkdir -p repo
echo "==> copiando $APK_SRC -> repo/"
cp -f "$APK_SRC" repo/

echo "==> fdroid update (regenera el índice firmado)"
fdroid update -c   # -c: crea metadata/ para apps nuevas que aún no la tengan
fdroid update

echo "==> instalando LaunchAgent en $PLIST_DEST"
mkdir -p "$HOME/Library/LaunchAgents"
if launchctl list | grep -q "$LABEL"; then
  echo "==> descargando servicio previo"
  launchctl unload "$PLIST_DEST" 2>/dev/null || true
fi
cp -f "$PLIST_SRC" "$PLIST_DEST"
launchctl load "$PLIST_DEST"
launchctl start "$LABEL"

echo "==> esperando arranque..."
sleep 3

echo
echo "===== ¿escucha en :8082? ====="
lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null | grep ":8082" || echo "(NADA escuchando — revisa fdroid-server.err)"
echo
echo "===== prueba local ====="
curl -sS -m 5 -o /dev/null -w "  http://localhost:8082/ -> HTTP %{http_code}\n" http://localhost:8082/ \
  || echo "  curl falló (rc=$?) — el servidor no responde en 8082"
echo
echo "Si da 200, prueba desde fuera (una vez añadido el Public Hostname en Cloudflare):"
echo "  curl -I https://fdroid.neto.chat/"
echo
echo "URL de repo para añadir en el cliente F-Droid de los testers:"
echo "  https://fdroid.neto.chat"
