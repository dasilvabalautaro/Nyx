package chat.neto.krypta.core.model

/**
 * Estados del ciclo de vida de un mensaje, alineados con el modelo de buzón
 * store-and-forward (ver docs/PLAN-senalizacion-descentralizada.md, Fase 4):
 *  - PENDING:   creado localmente, aún no entregado a la red.
 *  - SENT:      depositado en el buzón cifrado (o entregado al stream directo).
 *  - DELIVERED: el receptor lo retiró del buzón.
 *  - READ:      el receptor lo visualizó.
 *  - FAILED:    error definitivo de entrega.
 */
enum class MessageStatus { PENDING, SENT, DELIVERED, READ, FAILED }
