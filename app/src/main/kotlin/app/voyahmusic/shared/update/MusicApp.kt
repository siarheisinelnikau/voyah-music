package app.voyahmusic.shared.update

import android.app.Application

abstract class MusicApp : Application() {
    private val updates by lazy { Updates(this, releaseName()) }

    protected abstract fun releaseName(): String

    fun updates(): Updates = updates
}
