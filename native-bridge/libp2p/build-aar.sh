#!/usr/bin/env bash
# Regenera native-bridge/libs/krypta-p2p.aar desde el módulo Go (go-libp2p) con gomobile.
#
# Requisitos:
#   - Go >= 1.26 (brew install go)
#   - gomobile + gobind:  go install golang.org/x/mobile/cmd/{gomobile,gobind}@latest
#   - Android NDK instalado (este proyecto se compiló con 26.1.10909125)
#
# Nota clave: go-libp2p depende de github.com/wlynxg/anet, que usa //go:linkname contra
# un símbolo no exportado de net. Go >= 1.23 lo bloquea, por eso es OBLIGATORIO
# -ldflags="-checklinkname=0" o el enlazado falla con "invalid reference to net.zoneCache".
#
# Segunda bandera OBLIGATORIA: -extldflags=-Wl,-z,max-page-size=16384. Play exige soporte
# de páginas de 16 KB para apps con targetSdk >= 35 desde el 1 nov 2025, y el NDK 26 enlaza
# los segmentos a 4 KB (0x1000) por defecto. Sin esto libgojni.so sale a 4 KB y el AAB se
# rechaza / no arranca en dispositivos de 16 KB. Verificar tras compilar:
#   llvm-readelf -l <so> | grep LOAD   → la última columna debe ser 0x4000
set -euo pipefail

export PATH="/usr/local/bin:$HOME/go/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/26.1.10909125}"

cd "$(dirname "$0")"

go mod tidy
gomobile bind \
  -target=android \
  -androidapi 30 \
  -ldflags="-checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
  -javapkg=chat.neto.krypta \
  -o ../libs/krypta-p2p.aar \
  .

echo "AAR regenerado en native-bridge/libs/krypta-p2p.aar"
ls -lh ../libs/krypta-p2p.aar
