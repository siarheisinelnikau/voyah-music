package app.voyahmusic.player.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.StatFs
import app.voyahmusic.player.domain.Track
import app.voyahmusic.shared.lan.MusicWire
import app.voyahmusic.shared.lan.MusicWire.Folder
import app.voyahmusic.shared.lan.MusicWire.Status
import java.io.File
import java.io.IOException

class MusicLibrary(context: Context) {
    data class Snapshot(val tracks: List<Track>, val folders: List<Folder>, val locations: Map<String, Long>) {
        fun folderOf(track: Track): Long = locations[track.id] ?: 0L
    }

    private val directory = File(context.filesDir, "music")
    private val locations = FolderStore(context.applicationContext)
    private val metadata = mutableMapOf<String, Track>()

    init {
        directory.mkdirs()
        directory.listFiles { file -> file.name.startsWith(".incoming") }?.forEach(::discard)
    }

    fun staging(): File = File.createTempFile(".incoming-", "", directory)

    fun hasSpace(bytes: Long): Boolean = StatFs(directory.path).availableBytes - RESERVE_BYTES >= bytes

    @Synchronized
    fun publish(staging: File, id: String, name: String, destination: Long): Status {
        if (!locations.exists(destination)) return Status.FOLDER
        val folder = File(directory, id)
        if (folder.isDirectory) {
            if (folder.listFiles()?.isNotEmpty() == true) return Status.DUPLICATE
        } else if (!folder.mkdir()) {
            throw IOException("Could not create track directory")
        }
        if (!locations.move(id, destination) || !staging.renameTo(File(folder, name))) {
            locations.move(id, 0)
            discard(folder)
            throw IOException("Could not publish track")
        }
        return Status.OK
    }

    @Synchronized
    fun remove(track: Track): Boolean {
        if (!track.file.delete() && track.file.exists()) return false
        locations.move(track.id, 0)
        metadata.remove(track.id)
        track.file.parentFile?.let(::discard)
        return true
    }

    fun folders(): List<Folder> = locations.folders()

    fun hasFolder(id: Long): Boolean = locations.exists(id)

    @Synchronized
    fun createFolder(name: String): Long = locations.create(name)

    @Synchronized
    fun moveTrack(track: Track, folder: Long): Boolean = track.file.isFile && locations.move(track.id, folder)

    fun snapshot(): Snapshot = Snapshot(tracks(), folders(), locations.locations())

    private fun tracks(): List<Track> =
        directory.listFiles { file -> file.isDirectory && MusicWire.fingerprint(file.name) }.orEmpty()
            .mapNotNull { folder ->
                val file = folder.listFiles { it.isFile && MusicWire.validName(it.name) }?.firstOrNull()
                    ?: return@mapNotNull null
                val id = folder.name
                synchronized(this) { metadata[id] } ?: read(id, file).also { track ->
                    synchronized(this) {
                        if (file.isFile) metadata[id] = track
                    }
                }
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Track::title).thenBy(Track::id))

    companion object {
        private const val RESERVE_BYTES = 32L * 1024 * 1024
        private val control = Regex("\\p{Cntrl}")

        fun isAudio(file: File): Boolean {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                var found = false
                for (index in 0 until extractor.trackCount) {
                    val type = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    if (type?.startsWith("video/") == true) return false
                    if (type?.startsWith("audio/") == true) found = true
                }
                return found
            } catch (error: IOException) {
                return false
            } catch (error: RuntimeException) {
                return false
            } finally {
                extractor.release()
            }
        }

        fun discard(file: File) {
            if (file.exists()) file.delete()
        }

        private fun read(id: String, file: File): Track {
            var title = file.nameWithoutExtension
            var artist = ""
            var album = ""
            var duration = 0L
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                title = value(retriever, MediaMetadataRetriever.METADATA_KEY_TITLE).ifEmpty { title }
                artist = value(retriever, MediaMetadataRetriever.METADATA_KEY_ARTIST)
                album = value(retriever, MediaMetadataRetriever.METADATA_KEY_ALBUM)
                duration = value(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION)
                    .toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            } catch (_: RuntimeException) {
                // The original filename remains usable when tags are missing or malformed.
            } finally {
                try {
                    retriever.release()
                } catch (_: IOException) {
                    // Metadata is already available.
                }
            }
            return Track(id, file, title, artist, album, duration)
        }

        private fun value(retriever: MediaMetadataRetriever, key: Int): String =
            retriever.extractMetadata(key).orEmpty().replace(control, " ").trim { it <= ' ' }
    }
}
