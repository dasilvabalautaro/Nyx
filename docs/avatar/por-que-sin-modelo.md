# Por qué el avatar se dibuja por código y no lo genera un modelo

Resumen del recorrido que llevó al ADR 0012, para que la decisión no se
reabra por intuición. Todos los números están medidos, no estimados.

## El intento neuronal

El objetivo era generar rostros de avatar desde texto, offline en Android, con
un presupuesto de ≤250 MB de modelo, ≤1 GB de memoria y ≤5 s por imagen en un
teléfono de gama baja (TECNO KM5s, MT6769).

1. **Modelo base.** Se aprobó Würstchen v2 Stage C (licencia permisiva, pesos
   fijados por SHA-256) y se ajustó con LoRA sobre un dataset procedimental
   propio. Produce avatares de vector plano de buena calidad, pero necesita
   una GPU: 994 M de parámetros y 30 pasos de difusión.
2. **Destilación progresiva del prior** (reducir 30 pasos a 8). Falló la
   compuerta en cuatro formulaciones distintas del objetivo (peso SNR, peso
   uniforme, objetivo normalizado y trayectorias reales del maestro). El
   defecto era siempre el mismo: rostros repetidos en mosaico.
3. **Estudiante compacto entrenado desde cero** sobre 4096 salidas del
   maestro. Tres formulaciones:
   - regresión directa con pérdida L1: aprendía silueta, pelo, piel y fondo,
     pero **ningún rasgo facial**, ni siquiera sobre datos de entrenamiento;
   - difusión con predicción de epsilon: aparecían los rasgos pero el color
     global salía lavado, porque a ruido alto la predicción óptima de epsilon
     es la propia entrada y los primeros pasos de la cadena quedan sin
     condicionar;
   - difusión con **parametrización v**: funcionó. 8/8 rostros válidos.

## Por qué no bastó que funcionara

El estudiante de 52 M con 8 pasos tardaba **55.3 s** por imagen en el
dispositivo. El de 7.5 M con 4 pasos entraba en 4.46 s, pero:

| Precisión | 4 pasos | Degradación | Imagen |
|---|---|---|---|
| FP32 | 11.05 s | referencia | correcta |
| INT8 (8 bits) | **4.46 s** | 24.5 % | **ruido** |
| W8A16 (16 bits) | 13.6 s | 6.2 % | correcta |

La única variante que cumplía la latencia destruía la imagen, y las que
preservaban la calidad estaban 2.2× y 2.7× por encima del presupuesto. Se
descartaron por medición dos hipótesis de arreglo: no era la vía de
condicionamiento (dejarla en fp32 empeoró a 25.5 %) ni sólo la calibración
(percentil bajó a 17.7 %, insuficiente).

## El hallazgo que cerró la vía

El modelo estaba condicionado **únicamente por los atributos discretos** del
vocabulario cerrado. No podía generar nada que el vocabulario no describiera
ya: era un renderizador caro y borroso de una tabla de valores categóricos. Y
los dos extremos del recorrido —texto → atributos y atributos → dibujo— ya
existían en código determinista.

## El resultado

| | Estudiante (INT8) | Dibujo por código |
|---|---|---|
| Tiempo por avatar en el teléfono | 4,460 ms (imagen inservible) | **18–20 ms** |
| Pesos en el APK | 8.4 MB | **0** |
| Memoria | 0.34 GiB | trivial |
| Nitidez | difusa | exacta por construcción |
| Funcionamiento offline | requiere runtime de inferencia | garantizado |

Coste total de la investigación neuronal: ~28 h de RTX 4090. No fue tiempo
perdido: el maestro sigue siendo la **referencia visual** contra la que se
diseñó el dibujo, y sin ese recorrido no se habría visto que el
condicionamiento discreto hacía innecesaria la red.
