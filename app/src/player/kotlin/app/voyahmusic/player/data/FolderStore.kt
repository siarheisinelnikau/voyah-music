package app.voyahmusic.player.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.voyahmusic.shared.lan.MusicWire
import app.voyahmusic.shared.lan.MusicWire.Folder

class FolderStore(context: Context?) : SQLiteOpenHelper(context, "music_folders.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, lookup TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE locations (track TEXT PRIMARY KEY, folder INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Unsupported music folder schema")
    }

    fun folders(): List<Folder> {
        val list = mutableListOf<Folder>()
        getReadableDatabase().rawQuery("SELECT id, name FROM folders ORDER BY lookup", null)
            .use { cursor ->
                while (cursor.moveToNext()) list.add(Folder(cursor.getLong(0), cursor.getString(1)))
            }
        return list
    }

    fun locations(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        getReadableDatabase().rawQuery("SELECT track, folder FROM locations", null).use { cursor ->
            while (cursor.moveToNext()) result.put(cursor.getString(0), cursor.getLong(1))
        }
        return result
    }

    fun exists(id: Long): Boolean {
        if (id == 0L) return true
        getReadableDatabase().rawQuery(
            "SELECT 1 FROM folders WHERE id = ?",
            arrayOf<String>(id.toString())
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    fun create(value: String): Long {
        val name: String = value.trim { it <= ' ' }
        if (!MusicWire.validFolder(name) || folders().size >= MusicWire.MAX_FOLDERS) return -1
        val values = ContentValues()
        values.put("name", name)
        values.put("lookup", name.lowercase())
        return getWritableDatabase().insertWithOnConflict(
            "folders",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun move(track: String?, folder: Long): Boolean {
        if (!exists(folder)) return false
        if (folder == 0L) {
            getWritableDatabase().delete("locations", "track = ?", arrayOf<String?>(track))
            return true
        }
        val values = ContentValues()
        values.put("track", track)
        values.put("folder", folder)
        return getWritableDatabase().insertWithOnConflict(
            "locations",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L
    }
}
