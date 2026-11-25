package app.voyahmusic.player.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.voyahmusic.shared.lan.LanTls
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

data class LocalIdentity(val fingerprint: String, val tls: SSLContext) {
    companion object {
        private const val ALIAS = "music-local-server"

        @kotlin.jvm.Synchronized
        @kotlin.Throws(GeneralSecurityException::class, IOException::class)
        fun load(): LocalIdentity {
            val store = KeyStore.getInstance("AndroidKeyStore")
            store.load(null)
            if (!store.containsAlias(LocalIdentity.ALIAS)) {
                val keys =
                    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                keys.initialize(
                    KeyGenParameterSpec.Builder(
                        LocalIdentity.ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(
                            KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512
                        )
                        .setCertificateSubject(X500Principal("CN=Voyah Music"))
                        .build()
                )
                keys.generateKeyPair()
            }
            val certificate = store.getCertificate(LocalIdentity.ALIAS) as X509Certificate
            val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            manager.init(store, null)
            val tls = SSLContext.getInstance("TLS")
            tls.init(manager.getKeyManagers(), null, null)
            return LocalIdentity(LanTls.fingerprint(certificate), tls)
        }
    }
}
