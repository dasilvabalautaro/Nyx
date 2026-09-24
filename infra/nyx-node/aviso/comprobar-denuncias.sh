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

# TODAS las cajas, no una. Desde que hay dos nodos (2 sep 2026) una denuncia cae en cualquiera
# de ellas —el cliente entrega a la primera que acepte—, así que preguntar solo a la primera
# contestaba "cero" sin dar ningún error: la denuncia se guardaba bien y nadie se enteraba.
# NYX_NODE las sustituye; varias, separadas por comas.
HOSTS="${NYX_NODE:-root@nyx.neto.chat,root@nyx2.neto.chat}"
ESTADO="$HOME/keys/nyx-operator/.ultimo-aviso"

n=0
vivos=0
for HOST in ${HOSTS//,/ }; do
  c=$(ssh -o BatchMode=yes -o ConnectTimeout=15 "$HOST" \
        "find /var/lib/nyx/reports -name '*.json' 2>/dev/null | wc -l" 2>/dev/null | tr -d ' ')
  # Una caja que no contesta NO es una caja con cero. Se cuenta aparte para no confundir
  # "no hay denuncias" con "no pude mirar".
  [ -z "${c:-}" ] && continue
  n=$((n + c))
  vivos=$((vivos + 1))
done

# Ninguna caja contestó: callar. Un aviso de "no pude comprobar" cada pocas horas entrena a
# ignorarlos, y un nodo caído ya tiene sus propias alarmas.
[ "$vivos" -eq 0 ] && exit 0

total_cajas=$(echo "$HOSTS" | tr ',' ' ' | wc -w | tr -d ' ')

# El contador solo se pone a cero cuando se ha podido mirar en TODAS: con una caja caída, un
# cero es "no lo sé", y guardarlo haría que al volver el nodo se repitiera un aviso ya dado.
if [ "$n" -eq 0 ]; then
  [ "$vivos" -eq "$total_cajas" ] && echo 0 > "$ESTADO" 2>/dev/null
  exit 0
fi

# Solo avisar cuando el número CRECE. Si no, repetiría el mismo aviso cada día hasta revisar,
# que es la forma más rápida de que se ignore.
previo=$(cat "$ESTADO" 2>/dev/null || echo 0)
[ "$n" -le "$previo" ] && exit 0
echo "$n" > "$ESTADO" 2>/dev/null

if [ "$n" -eq 1 ]; then texto="Hay 1 denuncia esperando revisión."
else texto="Hay $n denuncias esperando revisión."; fi
# Si no se pudo mirar en todas, decirlo: el número es un mínimo, no el total.
[ "$vivos" -lt "$total_cajas" ] && texto="$texto (solo $vivos de $total_cajas nodos)"

osascript -e "display notification \"$texto\" with title \"Nyx · moderación\" sound name \"Submarine\"" 2>/dev/null
echo "$(date '+%F %T') $texto"
