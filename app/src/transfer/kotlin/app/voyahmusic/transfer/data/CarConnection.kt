package app.voyahmusic.transfer.data

import android.content.Context
import android.content.SharedPreferences
import app.voyahmusic.shared.lan.MusicWire

data class CarConnection(val address: String, val fingerprint: String, val token: String) {
    fun save(context: Context): Boolean {
        return CarConnection.values(context).edit().putString("address", address)
            .putString("identity", fingerprint)
            .putString("credential", token).putBoolean("paused", false).commit()
    }

    companion object {
        private fun values(context: Context): SharedPreferences {
            return context.getSharedPreferences("paired_car", Context.MODE_PRIVATE)
        }

        fun load(context: Context): CarConnection? {
            val values: SharedPreferences = CarConnection.values(context)
            val host = values.getString("address", "").orEmpty()
            val identity = values.getString("identity", "").orEmpty()
            val token = values.getString("credential", "").orEmpty()
            return if (MusicWire.address(host) != null && MusicWire.fingerprint(identity) && MusicWire.fingerprint(
                    token
                )
            )
                CarConnection(host, identity, token)
            else
                null
        }

        fun forget(context: Context) {
            CarConnection.values(context).edit().clear().apply()
        }

        fun paused(context: Context): Boolean {
            return CarConnection.values(context).getBoolean("paused", false)
        }

        fun paused(context: Context, value: Boolean) {
            CarConnection.values(context).edit().putBoolean("paused", value).apply()
        }
    }
}
