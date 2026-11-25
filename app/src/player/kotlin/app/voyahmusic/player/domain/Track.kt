package app.voyahmusic.player.domain

import java.io.File

data class Track(
    val id: String,
    val file: File,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long
) {
    fun matches(query: String): Boolean {
        return (title + "\n" + artist + "\n" + album + "\n" + file.name)
            .lowercase().contains(query)
    }
}
