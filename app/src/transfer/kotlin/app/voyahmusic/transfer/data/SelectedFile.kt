package app.voyahmusic.transfer.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

data class SelectedFile(val uri: Uri, val name: String, val size: Long) {
    companion object {
        fun read(resolver: ContentResolver, uri: Uri): SelectedFile {
            try {
                resolver.query(
                    uri,
                    arrayOf<String>(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                ).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        val name: Int = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val size: Int = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (name >= 0 && !cursor.isNull(name)) {
                            return SelectedFile(
                                uri, cursor.getString(name).orEmpty(),
                                if (size < 0 || cursor.isNull(size)) -1 else cursor.getLong(size)
                            )
                        }
                    }
                }
            } catch (ignored: RuntimeException) {
            }
            val name = uri.getLastPathSegment()
            return SelectedFile(
                uri,
                if (name == null) "" else name.substring(name.lastIndexOf('/') + 1),
                -1
            )
        }
    }
}
