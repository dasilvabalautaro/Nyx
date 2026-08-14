# Nyx — política de contenido y cumplimiento en Google Play

> Salida de la **Fase 0** del [plan](PLAN-NYX.md), cerrada el **14 de agosto de 2026**.
> Este documento fija tres decisiones y deriva de ellas lo que hay que construir. Es
> interno; el texto *público* que Play exige (Términos de uso y Normas de comunidad) se
> redacta a partir de la sección 6 y se publica antes de subir la ficha.
>
> Contexto que lo condiciona: la distribución de Nyx es **solo Google Play** (decisión 8
> del plan), así que aquí no cabe «asumimos el riesgo y lo documentamos». Un rechazo no
> tiene canal alternativo.

## 1. Las tres decisiones

**0.1 — Qué es Nyx.** Una **app de citas y relaciones para mayores de 18 años**, con
mensajería cifrada de extremo a extremo. No es una app de contenido adulto y no se define,
diseña ni anuncia como tal.

La distinción operativa, que es la que importa:

| Superficie | Qué es | Régimen |
|---|---|---|
| **Tablón de descubrimiento** (tarjetas, apodo, bio, intereses, avatar) | Público, en claro, alojado en el nodo | **Limpio y moderado.** Nada sexual, nada explícito. Es la parte regulada. |
| **Conversación 1 a 1** (texto, imagen, audio, vídeo, llamadas) | Privada, E2EE, el operador no puede leerla | Sin censura. Lo que dos adultos se manden entra en la excepción de *contenido sexual incidental*. |

Esa asimetría no es un truco: es exactamente el régimen de cualquier mensajero cifrado
publicado en Play. Lo que la sostiene es que la app sea **primordialmente no sexual**, que
**no promueva ni recomiende** ese contenido, y que **no se gane esa reputación** — las tres
condiciones literales de la excepción. Por eso el tablón va limpio: es la única parte que
el mundo ve, y es la que decide qué reputación tiene la app.

**0.2 — Denuncia.** Confirmada la variante con canal real al operador (sobre cifrado a su
clave pública), descartada la variante solo-local. No es una preferencia de diseño: la
política de contenido generado por usuarios exige actuar sobre lo denunciado, y algo que
solo escribe un archivo en el teléfono del denunciante no llega a nadie.

**0.3 — Mercados.** **Latinoamérica primero** (Bolivia y países vecinos). Mismo idioma,
cerca del nodo de São Paulo, y —esto es lo que ahorra trabajo— **las leyes estadounidenses
de verificación de edad no aplican**. Con eso, el gate 18+ autodeclarado es suficiente de
entrada. Ampliar mercados después es configuración en Play Console, no código… salvo que se
entre en EE. UU., y entonces sí hay trabajo (ver sección 5).

## 2. Las políticas que aplican, y por qué las tres

El plan original solo contemplaba la de contenido generado por usuarios. Aplican tres.

### 2.1 Contenido generado por usuarios (UGC)

Nyx encaja de lleno: hay perfiles públicos y hay mensajería 1 a 1. Obliga a:

- **Términos de uso aceptados antes de poder crear o subir contenido.** No se puede saltar.
- **Definir qué es contenido objetable** en esos términos.
- **Moderación** proporcional al tipo de contenido alojado.
- **Denunciar desde dentro de la app**, accesible **desde cada pieza de contenido**:
  cada tarjeta, cada perfil, cada mensaje.
- **Bloquear usuarios** — exigido expresamente para apps con interacción 1 a 1
  (mensajería directa, menciones, etiquetado).
- **Actuar** sobre lo denunciado, en plazo razonable.
- **Salvaguardas para que la monetización no incentive conductas objetables** — relevante
  para `docs/MONETIZACION.md`: nada de pagar por más visibilidad de forma que premie el
  spam, ni mecánicas de escasez que empujen a insistir con quien no ha respondido.

### 2.2 Child Safety Standards — **obligatoria para apps sociales y de citas**

Es el hallazgo que el plan no contemplaba, y no es opcional ni depende del público: la
política dice textualmente que **la presencia o ausencia de menores en la app es
irrelevante**. Si es una app de citas, está dentro. Obliga a:

- **Estándares publicados** (términos de servicio, normas de comunidad o documento público
  equivalente) que **prohíban explícitamente CSAE** — abuso y explotación sexual infantil.
- **Mecanismo dentro de la app** para enviar comentarios, preocupaciones o denuncias.
- **Actuar sobre CSAM** al tener conocimiento efectivo, incluida su eliminación.
- **Cumplir las leyes de seguridad infantil**, con un **proceso para reportar CSAM
  confirmado a NCMEC**.
- **Un punto de contacto designado** que reciba las notificaciones de Google Play sobre
  CSAE y que esté en posición de responder por los procedimientos de revisión y actuar.
- **Formulario de declaración en Play Console**, aparte de todo lo anterior.
- **Age-gating** que impida a menores acceder a las funciones de emparejamiento.

Lo que esto significa para una app E2EE: nadie exige romper el cifrado. Signal y WhatsApp
están en Play. Lo que se exige es que exista un canal por el que llegue la denuncia y una
capacidad real de actuar sobre lo que sí es visible — **el tablón**. Eso hay que decirlo
con precisión en los estándares públicos: se modera el tablón, no las conversaciones, y el
operador no puede leerlas.

### 2.3 Contenido inapropiado — sección sexual

Prohíbe el contenido sexual y la desnudez, las poses sugerentes, las citas compensadas
(«sugar dating», vetado desde 2021) y **las palabras clave adultas o sexuales en la ficha
de la tienda y dentro de la app**. Esa última parte es la que mata el posicionamiento
original: aunque el producto fuera impecable, describirlo con lenguaje adulto en la ficha
es infracción por sí solo.

La excepción de **contenido sexual incidental** existe y es donde vive la conversación
privada, con dos requisitos técnicos si se invoca: ocultarlo tras un filtro que exija **al
menos dos acciones** del usuario para desactivarlo, y **verificación de edad**. En Nyx el
segundo lo cubre el gate 18+; el primero solo haría falta si alguna vez se mostrara ese
material fuera de una conversación abierta deliberadamente por su destinatario — o sea,
nunca, mientras el tablón vaya limpio.

## 3. Qué obliga a construir (tareas nuevas, no estaban en el plan)

Estas se integran en las fases correspondientes; aquí quedan juntas para no perderlas.

- [ ] **Términos de uso + Normas de comunidad**, documento público en URL propia, con
      prohibición explícita de CSAE y definición de contenido objetable. Es requisito de
      dos políticas a la vez.
- [ ] **Aceptación obligatoria de los términos** antes de publicar la primera tarjeta —
      pantalla que no se puede saltar, decisión persistida (mismo patrón que `AgeGate`).
- [ ] **Denunciar accesible desde cada pieza de contenido**: tarjeta del tablón, cabecera
      del chat y mensaje individual. El plan solo lo tenía en la tarjeta y el chat.
- [ ] **Punto de contacto de seguridad infantil**: dirección de correo real, atendida, y
      la persona que responda por el procedimiento. Va en los estándares públicos y en
      Console.
- [ ] **Procedimiento escrito de actuación**: qué se hace al recibir una denuncia, en qué
      plazo, quién decide, cómo se expulsa un PeerID del tablón, y cómo se reporta a NCMEC
      un caso confirmado. Sin esto, la autocertificación de Console es falsa.
- [ ] **Formulario Child Safety Standards** en Play Console.
- [ ] **Age-gate antes de las funciones de emparejamiento** en concreto (el plan ya lo
      tenía para toda la app, que cumple de sobra).
- [ ] **Revisar `docs/MONETIZACION.md`** contra el requisito de que la monetización no
      incentive conductas objetables.
- [ ] **Ficha de tienda sin léxico adulto**: revisar título, descripción corta y larga,
      capturas y textos in-app.

## 4. Qué NO se puede decir, ni en la ficha ni dentro de la app

Conviene tenerlo escrito antes de redactar nada, porque es fácil escribirlo sin querer:

- Nada de «contenido para adultos», «sin censura», «picante», «sexo», ni sinónimos como
  reclamo. El cifrado y la privacidad **sí** se pueden destacar; son la propuesta de valor
  real y además juegan a favor.
- Nada que sugiera encuentros a cambio de compensación.
- Nada que insinúe que la app es un sitio donde encontrar material sexual.

Lo que sí conviene decir, porque es cierto y es fortaleza de cumplimiento: sin identidad
real, perfil pseudónimo opcional, **nadie puede escribirte sin interés mutuo previo**,
bloqueo y denuncia integrados, y cifrado de extremo a extremo.

## 5. Riesgos abiertos

**El avatar generado y CSAE.** La Alternativa A de la Fase 3b —un modelo que genera rostros
a partir de texto libre— publica su resultado en el **tablón público**, que es justo la
superficie regulada, y el fallo que hay que evitar de forma fiable es que produzca una cara
que parezca de menor. Eso ya no es un criterio de calidad del producto: entra en el ámbito
de Child Safety Standards. Conclusión para la Fase 3b: el criterio de «tasa de falsos
negativos del filtro que el usuario considere suficiente» se queda corto — hace falta que
el fallo sea **estructuralmente improbable**, no estadísticamente raro. Eso empuja hacia la
Alternativa B (catálogo curado de rasgos, todos adultos por construcción, auditable), o
hacia una A con el espacio de generación acotado. Se decide en la Fase 3b, pero con este
criterio, no con el anterior.

**Verificación de edad si se amplía a EE. UU.** Las App Store Accountability Acts de Utah,
Luisiana, California y Texas obligan a verificar la edad a través de las APIs de las
tiendas. Requisitos para desarrolladores desde el 6 de mayo de 2026; aplicación desde el 31
de diciembre de 2026 en Utah, julio de 2026 en Luisiana, enero de 2027 en California; Texas
está paralizado por una medida cautelar judicial. Google ofrece la **Play Age Signals API**.
Nada de esto aplica al mercado inicial elegido, pero **entrar en EE. UU. deja de ser un
cambio de configuración** y pasa a ser trabajo de código y cumplimiento. Anotarlo antes de
que alguien amplíe mercados desde Console pensando que es gratis.

**Autodeclaración de edad.** Es lo que hay sin backend de identidad, y es lo que hacen
muchas apps, pero es una limitación real y hay que declararla así en el cuestionario de
clasificación en lugar de dejar que la pregunta la haga el revisor.

## 6. Esqueleto de las Normas de comunidad (texto público, a redactar)

Contenido mínimo para cumplir las dos políticas que lo exigen:

1. Quién puede usar Nyx: solo mayores de 18 años.
2. **Prohibición explícita de CSAE**, en términos inequívocos, y consecuencia: expulsión
   del tablón y denuncia a las autoridades cuando proceda.
3. Qué es contenido objetable en el tablón: desnudez, material sexual, acoso, suplantación,
   datos de contacto de terceros, spam, comercio sexual.
4. Qué pasa en las conversaciones privadas: son cifradas de extremo a extremo, el operador
   **no puede leerlas**, y por eso la moderación alcanza al tablón y no a ellas. Las
   herramientas del usuario ahí son bloquear y denunciar.
5. Cómo denunciar, desde dónde, y qué ocurre después.
6. Punto de contacto de seguridad infantil.
7. Plazos de actuación.

## Fuentes

- [User Generated Content — Play Console Help](https://support.google.com/googleplay/android-developer/answer/9876937?hl=en)
- [Understanding moderation requirements and incidental sexual content in UGC apps](https://support.google.com/googleplay/android-developer/answer/12923286?hl=en)
- [Inappropriate Content (sección de contenido sexual)](https://support.google.com/googleplay/android-developer/answer/9878810?hl=en)
- [Learning more about our Child Safety Standards policy](https://support.google.com/googleplay/android-developer/answer/14747720?hl=en)
- [Developer Program Policy (vigente desde el 15 jul 2026)](https://support.google.com/googleplay/android-developer/answer/16933379?hl=en)
- [Countdown to Jan. 1, 2026: Mobile Developers Must Adopt Apple, Google APIs — Frankfurt Kurnit](https://technologylaw.fkks.com/post/102lxsp/countdown-to-jan-1-2026-mobile-developers-must-adopt-apple-google-apis-to-com)
- [Google Play Age Verification 2026: What the New State Laws Mean for Your App — QAwerk](https://qawerk.com/blog/google-play-age-verification-usa-state-laws/)

> Las fechas de entrada en vigor conviene reconfirmarlas en Play Console al preparar la
> ficha: la documentación consultada da el 15 de julio de 2026 para la Developer Program
> Policy consolidada y el 15 de abril de 2026 para los requisitos de denuncia y bloqueo.
