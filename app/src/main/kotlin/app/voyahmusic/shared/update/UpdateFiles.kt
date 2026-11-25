package app.voyahmusic.shared.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class UpdateFiles : ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun getType(uri: Uri): String? {
        return if (matches(uri)) UpdateFiles.TYPE else null
    }

    @kotlin.Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!matches(uri) || "r" != mode) throw FileNotFoundException()
        return ParcelFileDescriptor.open(
            UpdateFiles.apk(getContext()!!),
            ParcelFileDescriptor.MODE_READ_ONLY
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        sort: String?
    ): Cursor? {
        if (!matches(uri)) return null
        val columns: Array<out String> = if (projection == null) arrayOf<String>(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
        ) else projection
        val result = MatrixCursor(columns, 1)
        val file: File = UpdateFiles.apk(getContext()!!)
        if (file.isFile()) {
            val row = result.newRow()
            for (column in columns) {
                if (OpenableColumns.DISPLAY_NAME == column) row.add(UpdateFiles.NAME)
                else if (OpenableColumns.SIZE == column) row.add(file.length())
                else row.add(null)
            }
        }
        return result
    }

    private fun matches(uri: Uri): Boolean {
        return ("/" + UpdateFiles.NAME) == uri.getPath()
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException()
    }

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        throw UnsupportedOperationException()
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        args: Array<out String>?
    ): Int {
        throw UnsupportedOperationException()
    }

    companion object {
        private const val NAME = "update.apk"
        private const val TYPE = "application/vnd.android.package-archive"

        fun uri(context: Context): Uri {
            return Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates")
                .appendPath(UpdateFiles.NAME).build()
        }

        fun apk(context: Context): File {
            return File(context.getCacheDir(), "updates/" + UpdateFiles.NAME)
        }
    }
}
