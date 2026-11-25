package app.voyahmusic.player

import app.voyahmusic.player.data.MusicLibrary
import app.voyahmusic.shared.update.MusicApp

class PlayerApp : MusicApp() {
    private var library: MusicLibrary? = null

    fun library(): MusicLibrary {
        if (library == null) library = MusicLibrary(this)
        return library!!
    }

    override fun releaseName(): String {
        return "VoyahMusic"
    }
}
