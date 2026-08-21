#!/bin/sh
# Trae a Nyx un commit de Krypta. Uso: tools/port-from-krypta.sh <sha-de-krypta>
#
# Los dos repos comparten historial, así que un arreglo de la capa común (transporte,
# cripto, buzón, notificaciones, seguridad de pantalla) se puede traer como parche en
# vez de reescribirlo. El truco: el parche es texto, así que se le aplica la misma
# sustitución de marca que hizo el rebrand y con eso las rutas *y* los identificadores
# pasan a ser los de Nyx, y `git am` encaja con el contexto local.
#
# La tercera regla del sed hace falta porque "infra/node" no contiene la cadena
# "krypta": sin ella el parche apuntaría a una ruta que aquí no existe.
#
# Si falla (el hunk toca algo que en Nyx diverge de verdad: protocolo del tablón,
# LikeService, UI de descubrimiento, docs propios):
#   git am --abort
# y a mano con `git apply --reject` (deja .rej con lo que no entró) o leyendo el diff.
# NO uses `git am -3`: su respaldo a tres vías resuelve contra el blob original de
# Krypta, que trae los identificadores viejos, y ensucia el conflicto.
#
# Dirección inversa: nunca desde aquí. Krypta actualiza a Nyx, no al revés.
# Ver "Relación con Krypta a largo plazo" en docs/PLAN-NYX.md y docs/SYNC-KRYPTA.md.
set -e
git fetch upstream
git format-patch -1 --stdout "$1" \
  | sed -e 's/Krypta/Nyx/g' -e 's/krypta/nyx/g' \
        -e 's|infra/node/|infra/nyx-node/|g' \
  | git am
git commit --amend --trailer "Ported-from-Krypta: $1" --no-edit
