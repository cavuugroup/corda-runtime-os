package net.corda.crypto.delegated.signing

import net.corda.v5.crypto.SignatureSpec
import java.security.PublicKey

interface DelegatedSigner {

    fun sign(publicKey: PublicKey, sepc: SignatureSpec, data: ByteArray): ByteArray
}
