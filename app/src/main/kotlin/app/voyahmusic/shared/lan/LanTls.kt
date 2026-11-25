package app.voyahmusic.shared.lan

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object LanTls {
    @kotlin.Throws(CertificateException::class)
    fun fingerprint(certificate: X509Certificate): String {
        try {
            return MusicWire.hex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())
            )
        } catch (e: GeneralSecurityException) {
            throw CertificateException(e)
        }
    }

    @kotlin.Throws(GeneralSecurityException::class)
    fun client(pin: String): SSLContext {
        if (!MusicWire.fingerprint(pin) && !pin.matches("[0-9]{8}".toRegex())) {
            throw GeneralSecurityException("Invalid car identity")
        }
        val full = MusicWire.fingerprint(pin)
        val trust: X509TrustManager = object : X509TrustManager {
            @kotlin.Throws(CertificateException::class)
            override fun checkServerTrusted(chain: Array<X509Certificate?>, type: String?) {
                if (chain == null || chain.size == 0) throw CertificateException("Missing car certificate")
                chain[0]!!.checkValidity()
                val fingerprint = LanTls.fingerprint(chain[0]!!)
                val identity = if (full) fingerprint else MusicWire.pairingCode(fingerprint)
                if (pin != identity) throw CertificateException("Car identity mismatch")
            }

            @kotlin.Throws(CertificateException::class)
            override fun checkClientTrusted(chain: Array<X509Certificate?>?, type: String?) {
                throw CertificateException("Client certificates are not used")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate?> {
                return arrayOfNulls<X509Certificate>(0)
            }
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trust), null)
        return context
    }
}
