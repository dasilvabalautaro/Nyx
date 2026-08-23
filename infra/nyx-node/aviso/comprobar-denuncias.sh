#!/bin/bash
# Avisa en el Mac cuando hay denuncias esperando en el nodo.
#
# Va aquí y no en la caja a propósito: para actuar sobre una denuncia hace falta la clave
# privada del operador, que solo está en esta máquina. Un aviso en el VPS llegaría a un sitio
# donde no se puede hacer nada con él — y meter una vía de salida (correo, webhook) en el nodo
# añadiría credenciales a la caja que hoy no tiene ninguna.
#
# Limitación honesta: si el Mac está apagado no avisa. Es aceptable porque es también cuando no
# podrías revisar; lo que evita es el caso real, que es que pasen semanas sin acordarte.
#
# Instalación: bash infra/nyx-node/aviso/instalar.sh
set -uo pipefail

HOST="${NYX_NODE:-root@nyx.neto.chat}"
ESTADO="$HOME/keys/nyx-operator/.ultimo-aviso"

n=$(ssh -o BatchMode=yes -o ConnectTimeout=15 "$HOST" \
      "find /var/lib/nyx/reports -name '*.json' 2>/dev/null | wc -l" 2>/dev/null | tr -d ' ')

# Sin red o nodo caído: callar. Un aviso de "no pude comprobar" cada hora entrena a ignorarlos,
# y el nodo caído ya tiene sus propias alarmas.
[ -z "${n:-}" ] && exit 0
[ "$n" -eq 0 ] && { echo 0 > "$ESTADO" 2>/dev/null; exit 0; }

# Solo avisar cuando el número CRECE. Si no, repetiría el mismo aviso cada día hasta revisar,
# que es la forma más rápida de que se ignore.
previo=$(cat "$ESTADO" 2>/dev/null || echo 0)
[ "$n" -le "$previo" ] && exit 0
echo "$n" > "$ESTADO" 2>/dev/null

if [ "$n" -eq 1 ]; then texto="Hay 1 denuncia esperando revisión."
else texto="Hay $n denuncias esperando revisión."; fi

osascript -e "display notification \"$texto\" with title \"Nyx · moderación\" sound name \"Submarine\"" 2>/dev/null
echo "$(date '+%F %T') $texto"
