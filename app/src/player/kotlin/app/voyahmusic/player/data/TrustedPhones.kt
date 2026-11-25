package app.voyahmusic.player.data

import android.content.Context
import app.voyahmusic.shared.lan.MusicWire
import java.security.SecureRandom

class TrustedPhones(context: Context) {
    data class Phone(val token: String, val name: String)

    private val values = context.getSharedPreferences("trusted_phones", Context.MODE_PRIVATE)

    fun knows(token: String): Boolean = MusicWire.fingerprint(token) && values.contains(token)

    fun add(name: String): String {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = MusicWire.hex(secret)
        return if (values.edit().putString(token, name).commit()) token else ""
    }

    fun remove(phone: Phone): Boolean = values.edit().remove(phone.token).commit()

    fun list(): List<Phone> = values.all.map { (token, name) -> Phone(token, name.toString()) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Phone::name).thenBy(Phone::token))
}
