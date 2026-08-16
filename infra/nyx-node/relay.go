package main

import (
	"time"

	relayv2 "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/relay"
)

// Topes finitos del Circuit Relay v2.
//
// Historia: los valores por defecto de go-libp2p (128 KiB de datos y 2 min por conexión
// relayada) cortaban las llamadas de voz a los ~20 s, así que Krypta —y Nyx heredó—
// pasó a `relayv2.WithInfiniteLimits()`. Infinito funciona, pero en un relay con IP
// pública es una invitación a que un tercero use la caja como proxy de ancho de banda
// gratis, así que es un bloqueante para abrir a público (ver docs/PLAN-NYX.md, 1.12b).
//
// Dimensionado con los caudales reales medidos de Nyx, tomados como *por dirección* (que
// es como los cuenta `RelayLimit.Data`) para quedarnos del lado seguro:
//
//	voz  (Opus 48k)        ~43 MB/h
//	vídeo (320x240 12fps)  ~225 MB/h
//
// Con `relayDataPerDirection` = 1 GiB eso son ~4,5 h de vídeo continuo o ~24 h de voz
// por dirección y por circuito: ningún uso real lo toca, y es 8192x el default que
// mataba las llamadas. `relayCircuitDuration` = 6 h es el segundo techo, para el caso de
// un circuito ocioso que nadie cierra.
//
// Límite honesto de lo que esto protege: son topes **por circuito** y de **concurrencia**.
// relayv2 no ofrece un límite agregado de tráfico, así que un abusador que reconecte
// puede seguir consumiendo; lo que se acota es el coste de un solo circuito y cuántos
// puede haber a la vez. El respaldo real del gasto mensual es una alerta de egress en el
// panel del proveedor, no esta configuración.
const (
	// relayDataPerDirection es el tope de datos reenviados por dirección y circuito.
	relayDataPerDirection = int64(1) << 30 // 1 GiB
	// relayCircuitDuration es la vida máxima de un circuito relayado.
	relayCircuitDuration = 6 * time.Hour
	// relayMaxReservations son las reservas (= teléfonos con slot) simultáneas.
	relayMaxReservations = 512
	// relayMaxCircuitsPerPeer son los circuitos abiertos a la vez por teléfono. Uno por
	// conversación activa sobra; el default (16) es más de lo necesario.
	relayMaxCircuitsPerPeer = 8
	// relayBufferSize es el buffer por conexión relayada. Un paso por encima del default
	// (2048) para absorber ráfagas de keyframes de vídeo sin encarecer la memoria:
	// el peor caso son 512*8 circuitos * 4 KiB = 16 MiB.
	relayBufferSize = 4096
	// relayMaxReservationsPerIP y relayMaxReservationsPerASN están MUY por encima de los
	// defaults (8 y 32) a propósito. Los defaults asumen una IP por usuario, y los
	// usuarios de Nyx entran por CGNAT móvil: una operadora entera comparte un puñado de
	// IPs públicas y **un solo ASN**. Con el default de 32 por ASN, el usuario 33 de
	// Entel/Tigo se quedaría sin relay — un fallo de disponibilidad indistinguible de
	// "la app no funciona". El coste de aflojarlos lo cubre el tope de datos por
	// circuito, que es donde está el gasto de verdad.
	relayMaxReservationsPerIP  = 32
	relayMaxReservationsPerASN = 512
)

// relayResources construye los Resources finitos del relay. `dataPerDirection` y
// `duration` llegan por flag para poder ajustarlos en la caja sin recompilar; el resto
// son constantes porque dependen del diseño, no del despliegue.
func relayResources(dataPerDirection int64, duration time.Duration) relayv2.Resources {
	r := relayv2.DefaultResources()
	r.Limit = &relayv2.RelayLimit{
		Duration: duration,
		Data:     dataPerDirection,
	}
	r.MaxReservations = relayMaxReservations
	r.MaxCircuits = relayMaxCircuitsPerPeer
	r.BufferSize = relayBufferSize
	r.MaxReservationsPerIP = relayMaxReservationsPerIP
	r.MaxReservationsPerASN = relayMaxReservationsPerASN
	return r
}
