package app.voyahmusic.shared.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

class MusicPreferences(context: Context) {
    private val values: SharedPreferences

    init {
        values = context.getSharedPreferences("music_settings", Context.MODE_PRIVATE)
    }

    fun language(): String {
        return values.getString("language", "system") ?: "system"
    }

    fun language(value: String?) {
        values.edit().putString("language", value).apply()
    }

    fun amber(): Boolean {
        return values.getBoolean("amber", false)
    }

    fun amber(enabled: Boolean) {
        values.edit().putBoolean("amber", enabled).apply()
    }

    fun startupUpdates(): Boolean {
        return values.getBoolean("startup_updates", true)
    }

    fun startupUpdates(enabled: Boolean) {
        values.edit().putBoolean("startup_updates", enabled).apply()
    }

    companion object {
        fun localized(context: Context): Context {
            val language = MusicPreferences(context).language()
            if ("system" == language) return context
            val override = Configuration()
            override.setLocales(LocaleList(Locale.forLanguageTag(language)))
            return context.createConfigurationContext(override)
        }
    }
}
