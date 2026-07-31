# Reglas R8 de Krypta (release). Hilt, Room, Compose y ZXing traen sus propias reglas
# de consumidor; aquí solo va lo que R8 no puede ver.

# --- gomobile / gobind -----------------------------------------------------------------
# El puente Go (libgojni.so) resuelve estas clases y métodos POR NOMBRE vía JNI:
# renombrarlas o podarlas rompe el arranque del nodo libp2p en tiempo de ejecución.
-keep class go.** { *; }
-keep class chat.neto.krypta.bridge.** { *; }

# Los callbacks Kotlin→Go (interfaces generadas por gobind implementadas en Kotlin,
# p. ej. el MailboxHandler) también se invocan desde nativo.
-keep class * implements go.Seq$Proxy { *; }

# --- Varios ----------------------------------------------------------------------------
# Anotaciones de javax que referencian dependencias de compilación ausentes.
-dontwarn javax.annotation.**
