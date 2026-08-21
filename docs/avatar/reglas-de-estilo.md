# Reglas de estilo del dibujo

Estas reglas salieron de defectos concretos y visibles que hubo que corregir:
sombras que parecían manchas en la cara, orejas rematadas en punta, barbas con
forma de bufanda, rizos como corona de picos. No son preferencias estéticas
sueltas, son las condiciones para que el avatar se lea como un retrato y no
como un montaje de primitivas.

El criterio con el que se juzgan es una sola pregunta: **¿me pondría esto como
foto de perfil?** El avatar sustituye a una fotografía en una aplicación que
promete privacidad; si la persona no se siente tranquila con su
representación, la descarta.

Cualquier ajuste de coordenadas debe respetarlas y comprobarse dibujando la
galería de referencia.

## Las doce reglas

Comprobar con `python scripts/render_gallery.py` y comparar contra `referencia/galeria.png`.

1. **Sin sombreado lateral en el rostro.** La sombra de mejilla que había
   cruzaba la cara con un borde duro y se leía como una mancha. La única
   sombra admitida es la de contacto bajo el mentón, muy tenue.
2. **Nada de negro puro en los rasgos.** Cejas y pestañas se derivan del color
   del pelo; el negro puro endurece el gesto y produce caras severas.
3. **La nariz es la base, no el tabique.** Una media luna fina bajo el
   tabique; una forma grande en mitad del rostro parece una mancha y una línea
   suelta un arañazo.
4. **Las orejas apenas asoman.** Sobresalir más de cuatro píxeles a 256 px las
   convierte en bultos.
5. **Un polígono se recorre en un solo sentido.** Saltar de un lado al otro lo
   cierra sobre sí mismo: así aparecieron la muesca de la coronilla y la barba
   con forma de bufanda.
6. **Capas separadas para barba, boca y bigote**, en ese orden, para que los
   labios queden visibles y el bigote se apoye sobre ellos.
7. **Las siluetas deben distinguirse entre sí.** Si todas las formas de cara
   se parecen, nadie encuentra la suya.
8. **Nada se coloca en coordenadas fijas si el rostro cambia de anchura.**
   El pelo, las orejas y los pendientes se apoyan en `face_half_width`, que
   interpola el contorno a la altura pedida; con anchuras fijas quedaban
   flotando sobre las siluetas anchas, como pasaba en la forma `diamond`.
9. **El rizo es el borde de una masa, no un montón de círculos.** Los estilos
   `curly` y `afro` festonean el arco de la coronilla; recortar círculos
   sueltos encima daba aspecto de bultos pegados.
10. **La barba necesita cuerpo en el mentón** y **bigote**. Una banda estrecha
    siguiendo la mandíbula se lee como correa de barbilla, y sin bigote queda
    con aire de barba de collar.
11. **Los accesorios se dimensionan con el rostro.** La montura se calcula
    desde el hueco entre el ojo y el borde del rostro (`face_half_width`); una
    talla fija resulta enorme sobre las siluetas estrechas. La redonda se
    dibuja algo menor a igual anchura, porque ocupa más superficie.
12. **Una cabeza rapada necesita brillo de coronilla.** Sin él se lee como una
    frente enorme, no como una calva.
