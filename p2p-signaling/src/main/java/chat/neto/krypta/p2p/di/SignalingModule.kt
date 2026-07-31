package chat.neto.krypta.p2p.di

import chat.neto.krypta.core.ISignalingService
import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.MessageCipher
import chat.neto.krypta.p2p.AesGcmMessageCipher
import chat.neto.krypta.p2p.Libp2pKeyExchange
import chat.neto.krypta.p2p.SignalingService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SignalingModule {

    @Binds
    abstract fun bindSignalingService(impl: SignalingService): ISignalingService

    @Binds
    abstract fun bindMessageCipher(impl: AesGcmMessageCipher): MessageCipher

    @Binds
    abstract fun bindKeyExchange(impl: Libp2pKeyExchange): KeyExchange
}
