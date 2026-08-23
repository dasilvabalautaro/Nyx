# Moderación de Nyx — manual del operador

Qué hacer cuando alguien denuncia a otra persona: cómo enterarte, cómo leer la denuncia, cómo
decidir y cómo dejar constancia.

Va aparte de [OPERACION.md](OPERACION.md) porque es otro trabajo: aquello es mantener una caja
viva, esto es una rutina de revisión con consecuencias para personas reales. La mecánica interna
(protocolo, formato del sobre, cuotas) está en la cabecera de [report.go](report.go).

> **Estado al 23 ago 2026**: la caja de producción todavía corre un binario **sin** `report.go`,
> así que no llega ninguna denuncia. Denunciar desde la app hoy bloquea —que es lo importante—
> pero el envío falla y la app lo dice. Se arregla con un `deploy-vps.sh`.

---

## 1. Preparación, una sola vez

La clave del operador ya está generada (23 ago 2026) en `~/keys/nyx-operator/operator.key`.
**No se copia nunca al VPS**: el nodo guarda sobres que no puede abrir, y ese es el diseño
entero. Si se pierde, todas las denuncias quedan ilegibles **y** hace falta publicar una versión
nueva en Play, porque la pública va compilada en cada APK. Respáldala como el `node.key`.

Instala el aviso:

```sh
bash infra/nyx-node/aviso/instalar.sh
```

Comprueba el nodo cada 6 h y avisa con una notificación del Mac **solo cuando el número crece**.
Si repitiera el aviso a diario hasta revisar, aprenderías a ignorarlo. Y si el Mac está apagado
no avisa — aceptable, porque es también cuando no podrías revisar.

---

## 2. La rutina

```sh
cd infra/nyx-node

# ¿Hay algo? (no descarga nada)
go run ./cmd/nyx-report status

# Traerlas y leer SOLO las nuevas
scp -r root@nyx.neto.chat:/var/lib/nyx/reports ./reports-$(date +%F)
go run ./cmd/nyx-report decrypt ./reports-$(date +%F)
```

`decrypt` oculta por defecto lo ya revisado. Es lo que hace la rutina sostenible: las denuncias
viven **180 días**, y sin ese filtro cada revisión sería releer decenas de cosas ya vistas
buscando las de hoy — que es exactamente por lo que uno deja de revisar.

Cada denuncia sale con un **id** (`137106315e080efd`), calculado de su contenido cifrado, así que
es el mismo aunque copies el árbol otra vez o el nodo barra el original.

Para cada una, decide **y deja constancia**:

```sh
# Expulsar del tablón y registrar por qué
go run ./cmd/nyx-report ban 12D3KooW… "acoso reiterado" 137106315e080efd

# O archivarla sin acción, que también es una decisión
go run ./cmd/nyx-report dismiss 137106315e080efd "sin indicios, parece un roce personal"
```

Lo que no registras no existe: si decides no actuar y no lo anotas, dentro de seis meses no hay
forma de saber si la miraste. Y "el desarrollador puede actuar sobre lo denunciado" es algo que
Play espera que puedas **demostrar**, no solo afirmar.

---

## 3. Referencia de comandos

Todos desde `infra/nyx-node`. El nodo por defecto es `root@nyx.neto.chat`; se cambia con
`NYX_NODE=usuario@host`.

| Comando | Qué hace |
|---|---|
| `status` | Cuántas denuncias hay en el nodo y cuántas decisiones llevas. No descarga. |
| `decrypt <ruta>` | Descifra e imprime **las no revisadas**. |
| `decrypt <ruta> -todas` | También las ya revisadas. |
| `ban <peerid> [nota] [id]` | Expulsa del tablón y registra la decisión. |
| `unban <peerid>` | Levanta la expulsión. |
| `dismiss <id> [nota]` | Archiva una denuncia revisada sin acción. |
| `log` | Historial de decisiones, de la más reciente a la más antigua. |
| `keygen` | Genera el par del operador. **Ya hecho**; se niega a sobreescribir. |

El registro vive en `~/keys/nyx-operator/revisadas.json`, junto a la clave privada: mismo dominio
de confianza y misma obligación de respaldo. No es un caché — dice a quién expulsaste y por qué.

`ban` y `unban` escriben en `/var/lib/nyx/banned.txt` por SSH. El nodo relee esa lista al cambiar
su fecha, **sin reiniciar**.

---

## 4. Cómo decidir

| Motivo denunciado | Acción |
|---|---|
| Acoso, contenido sexual no solicitado, spam, suplantación | Expulsar si hay indicio razonable |
| **Parece una persona menor de edad** | **Expulsar de inmediato**, sin esperar a estar seguro |
| Otro | Juicio |

Tres cosas que conviene tener presentes al decidir:

**Casi siempre decidirás con poca información.** Si el denunciante no autorizó adjuntar el
fragmento, tienes su motivo y su nota, y nada más. No puedes ir a mirar la conversación. Eso
empuja hacia expulsar ante la duda: el error es reversible con `unban`, y el de no actuar no.

**Expulsar no protege al denunciante en el chat.** Eso ya lo resolvió el bloqueo automático que
la app aplica al denunciar. Lo que retiras es la exposición pública de esa persona en el tablón.

**Una denuncia falsa también deja rastro.** El nodo registra quién entregó cada sobre. Si alguien
denuncia en masa, se ve en los nombres de directorio de `reports/` sin descifrar nada.

---

## 5. Lo que NO puedes hacer, y hay que decirlo así

- **Leer conversaciones.** Son E2EE y no tienes las claves. De una denuncia solo ves lo que el
  denunciante decidió adjuntar.
- **Borrar mensajes.** Están en los dispositivos, no en el nodo.
- **Expulsar a nadie de la mensajería.** Solo del tablón. Quien quiera dejar de recibir a alguien
  usa el bloqueo, que es local y suyo.

Tu única palanca es el tablón. Va dicho igual en el diálogo de denuncia de la app, y tiene que ir
igual en la política de privacidad y en la ficha de Play. Prometer una moderación que no existe
es peor que la limitación.

---

## 6. El hueco que sigue abierto

El caso **"parece una persona menor de edad"** no termina en expulsar. Nyx cae bajo **Child
Safety Standards** de Play por ser app de citas, y eso exige un punto de contacto designado de
seguridad infantil y un **proceso documentado de reporte a NCMEC**.

Eso es la **tarea 0.5 del plan y sigue sin hacer**. Hoy tienes la palanca técnica pero no el
procedimiento, y el procedimiento no es algo que pueda redactar quien escribe el código: es una
obligación legal que asume una persona con nombre.

Hasta que exista: expulsa, conserva la denuncia descifrada fuera del nodo, y no borres nada.

---

## 7. Límites del sistema

| | |
|---|---|
| Tamaño de una denuncia | 64 KiB |
| Denuncias vivas por denunciante | 50 |
| Caducidad | 180 días |
| Cuota | **Por denunciante** — que uno la agote no impide denunciar a los demás |

El TTL es largo a propósito: una denuncia es justo lo que no debe caducar antes de que alguien la
mire. Pero **no es un archivo**: si quieres conservar una, guárdala descifrada fuera del nodo, que
a los 180 días el barrido se la lleva.
