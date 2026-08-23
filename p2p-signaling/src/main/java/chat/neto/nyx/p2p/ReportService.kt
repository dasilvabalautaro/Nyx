package chat.neto.nyx.p2p

import chat.neto.nyx.core.ISignalingService
import chat.neto.nyx.core.model.ReportDraft
import chat.neto.nyx.core.repository.BlockRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Envío de denuncias al operador (plan 4.4).
 *
 * # Por qué existe este canal
 *
 * Es lo que hace que Nyx pueda estar en Play. La política de contenido generado por usuarios
 * exige un sistema **dentro de la app** para denunciar y bloquear, **y** que el desarrollador
 * pueda actuar sobre lo denunciado. Una denuncia que solo escribe un fichero en el teléfono del
 * denunciante no llega a nadie y no cumple.
 *
 * # Qué ve quién
 *
 * La denuncia viaja **cifrada a la clave pública del operador**: el nodo la almacena sin poder
 * leerla, así que comprometer la caja no expone denuncias. Lo que el nodo sí registra es **quién
 * la entregó**, porque sale de la identidad del stream de libp2p y no se puede ocultar; sin eso
 * no habría forma de frenar a quien inunde de denuncias falsas.
 *
 * # Bloquear primero, denunciar después
 *
 * [report] **bloquea antes de enviar**, y lo hace aunque el envío falle. El orden no es un
 * detalle: quien está denunciando a alguien quiere dejar de recibirle **ya**, y hacer depender
 * eso de que haya red sería dejar a la víctima expuesta justo en el momento en que pidió ayuda.
 * La política de Play también pide "bloqueo inmediato" como parte del mecanismo.
 *
 * # El alcance real de la moderación
 *
 * El operador puede **expulsar un PeerID del tablón**. No puede borrar mensajes ni leer
 * conversaciones: son E2EE, y ni siquiera con la denuncia delante ve más que el fragmento que el
 * denunciante decidió adjuntar. Esto va dicho igual en la ayuda, en la política de privacidad y
 * en la ficha de Play; disimularlo sería peor que la limitación.
 */
@Singleton
class ReportService @Inject constructor(
    private val signaling: ISignalingService,
    private val blocked: BlockRepository,
) {

    /**
     * Resultado de denunciar. El bloqueo se da por hecho —ocurre siempre—; lo que puede fallar
     * es la entrega, y la UI necesita distinguirlo para poder ofrecer la exportación local como
     * salida en vez de dejar al usuario pensando que su denuncia llegó.
     */
    sealed interface Result {
        /** Bloqueado y entregado al operador. */
        data class Sent(val plaintext: String) : Result

        /** Bloqueado, pero el sobre no salió. El texto sirve para exportarlo a mano. */
        data class Blocked(val plaintext: String, val error: String) : Result
    }

    suspend fun report(
        draft: ReportDraft,
        reporterPeerId: String,
        appVersion: String,
        now: Long = System.currentTimeMillis(),
    ): Result {
        val plaintext = draft.render(reporterPeerId, now, appVersion)

        // Bloquear primero, y pase lo que pase con el envío. Ver el KDoc de la clase.
        runCatching { blocked.block(draft.reportedPeerId, draft.reason.label) }

        return runCatching {
            val sealed = signaling.sealReport(OPERATOR_PUBLIC_KEY, plaintext.toByteArray())
            signaling.sendReport(sealed)
        }.fold(
            onSuccess = { Result.Sent(plaintext) },
            onFailure = { Result.Blocked(plaintext, it.message ?: it.toString()) },
        )
    }

    companion object {
        /**
         * Clave pública X25519 del operador, generada el 23 ago 2026 con
         * `go run ./cmd/nyx-report keygen` desde `infra/nyx-node`.
         *
         * **Va compilada en cada APK**, y esa es justo la razón por la que cambiarla es caro: la
         * privada vive solo en la máquina del operador (`~/keys/nyx-operator/operator.key`, nunca
         * en el VPS), y si se pierde no solo quedan ilegibles las denuncias ya recibidas — hay
         * que **publicar una versión nueva en Play** para que el parque instalado empiece a
         * cifrar a otra clave. Mismo tipo de compromiso que el PeerID del nodo en
         * `DEFAULT_BOOTSTRAP`.
         */
        const val OPERATOR_PUBLIC_KEY =
            "b6de2a9b7cb0e6afd88d8912be5662e25cd3a22ef967d464cdbaf8c8bdd77b5d"
    }
}
