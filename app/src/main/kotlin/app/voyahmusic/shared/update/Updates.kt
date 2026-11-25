package app.voyahmusic.shared.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import app.voyahmusic.shared.data.MusicPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Arrays
import java.util.regex.Pattern
import javax.net.ssl.HttpsURLConnection

class Updates(private val context: Context, releaseName: String) {
    enum class Phase {
        IDLE, CHECKING, CURRENT, AVAILABLE, CHECK_FAILED, DOWNLOADING, READY, DOWNLOAD_FAILED
    }

    data class Screen(val phase: Phase, val version: String, val percent: Int, val file: File?)

    private data class Release(
        val version: String,
        val url: String,
        val size: Long,
        val digest: String,
        val checksumUrl: String
    )

    private val installed: String
    private val assetName: Pattern
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(Screen(Phase.IDLE, "", 0, null))
    val state = mutableState.asStateFlow()
    private val screen get() = state.value
    private var offer: Release? = null
    private var startupDone = false

    init {
        assetName =
            Pattern.compile(Pattern.quote(releaseName) + "-v(" + Updates.VERSION.pattern() + ")\\.apk")
        try {
            installed =
                context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName.orEmpty()
        } catch (e: PackageManager.NameNotFoundException) {
            throw IllegalStateException(e)
        }
    }

    fun installed(): String = installed

    fun startup() {
        if (startupDone) return
        startupDone = true
        if (MusicPreferences(context).startupUpdates()) check()
    }

    fun check() {
        if (busy()) return
        set(Screen(Phase.CHECKING, "", 0, null))
        scope.launch {
            try {
                val candidate = withContext(Dispatchers.IO) { latest() }
                offer = candidate
                set(if (candidate == null) Screen(Phase.CURRENT, "", 0, null)
                    else Screen(Phase.AVAILABLE, candidate.version, 0, null))
            } catch (error: IOException) {
                set(Screen(Phase.CHECK_FAILED, "", 0, null))
            } catch (error: JSONException) {
                set(Screen(Phase.CHECK_FAILED, "", 0, null))
            }
        }
    }

    fun download() {
        val target = offer ?: return
        if (busy()) return
        set(Screen(Phase.DOWNLOADING, target.version, 0, null))
        scope.launch {
            val apk = try {
                withContext(Dispatchers.IO) { download(target) }
            } catch (_: IOException) {
                null
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: SecurityException) {
                null
            }
            set(if (apk == null) Screen(Phase.DOWNLOAD_FAILED, target.version, 0, null)
                else Screen(Phase.READY, target.version, 100, apk))
        }
    }

    private fun busy() = screen.phase == Phase.CHECKING || screen.phase == Phase.DOWNLOADING

    private fun latest(): Release? {
        var best: Release? = null
        for (page in 1..8) {
            val releases: JSONArray = JSONArray(
                Updates.read(
                    Updates.API + "?per_page=30&page=" + page,
                    4 * 1024 * 1024
                )
            )
            for (index in 0..<releases.length()) {
                val release = releases.getJSONObject(index)
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val assets = release.optJSONArray("assets")
                if (assets == null) continue
                for (item in 0..<assets.length()) {
                    val asset = assets.getJSONObject(item)
                    val name = asset.optString("name")
                    val match = assetName.matcher(name)
                    if (!match.matches()) continue
                    val version = match.group(1) ?: continue
                    val url = asset.optString("browser_download_url")
                    val size = asset.optLong("size")
                    if (!url.startsWith(Updates.DOWNLOAD) || size <= 0 || size > Updates.MAX_APK || Updates.compare(
                            version,
                            installed
                        ) <= 0 || (best != null && Updates.compare(
                            version,
                            best.version!!
                        ) <= 0)
                    ) continue
                    val digest = asset.optString("digest")
                    best = Release(
                        version,
                        url,
                        size,
                        digest,
                        Updates.checksumUrl(assets, name)
                    )
                }
            }
            if (releases.length() < 30) break
        }
        return best
    }

    @kotlin.Throws(IOException::class, PackageManager.NameNotFoundException::class)
    private fun download(release: Release): File {
        val folder = File(context.getCacheDir(), "updates")
        if (!folder.isDirectory() && !folder.mkdirs()) throw IOException("Could not create update directory")
        val pending = File(folder, "pending.apk")
        val apk: File = UpdateFiles.apk(context)
        val expected = expectedDigest(release)
        val digest: MessageDigest
        try {
            digest = MessageDigest.getInstance("SHA-256")
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        }
        val connection: HttpsURLConnection =
            Updates.open(release.url!!, "application/octet-stream")
        try {
            connection.getInputStream().use { input ->
                FileOutputStream(pending).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes: Long = 0
                    var last = 0
                    var count: Int
                    while ((input.read(buffer).also { count = it }) != -1) {
                        bytes += count.toLong()
                        if (bytes > release.size) throw IOException("Unexpected update length")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = (100 * bytes / release.size).toInt()
                        if (percent >= last + 5) {
                            last = percent
                            set(Screen(Phase.DOWNLOADING, release.version, percent, null))
                        }
                    }
                    if (bytes != release.size) throw IOException("Incomplete update")
                    if (!expected.isEmpty() && !expected.equals(
                            Updates.hex(digest.digest()),
                            ignoreCase = true
                        )
                    ) {
                        throw IOException("Update checksum mismatch")
                    }
                    output.getFD().sync()
                }
            }
        } catch (e: IOException) {
            pending.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        try {
            verify(pending, release.version!!)
            if (!pending.renameTo(apk)) throw IOException("Could not prepare update")
            return apk
        } finally {
            if (pending.exists()) pending.delete()
        }
    }

    @kotlin.Throws(IOException::class)
    private fun expectedDigest(release: Release): String {
        if (release.digest.startsWith("sha256:")) {
            val value: String = release.digest.substring(7)
            if (!Updates.CHECKSUM.matcher(value)
                    .matches()
            ) throw IOException("Invalid release digest")
            return value
        }
        if (release.checksumUrl.isEmpty()) return ""
        val value: String =
            Updates.read(release.checksumUrl!!, 8 * 1024).trim { it <= ' ' }
                .split("\\s+".toRegex(), limit = 2).toTypedArray()[0]
        if (!Updates.CHECKSUM.matcher(value)
                .matches()
        ) throw IOException("Invalid release checksum")
        return value
    }

    @kotlin.Throws(IOException::class, PackageManager.NameNotFoundException::class)
    private fun verify(file: File, version: String) {
        val packages = context.getPackageManager()
        val existing = packages.getPackageInfo(
            context.getPackageName(),
            PackageManager.GET_SIGNING_CERTIFICATES
        )
        val apk =
            packages.getPackageArchiveInfo(file.getPath(), PackageManager.GET_SIGNING_CERTIFICATES)
        if (apk == null || (existing.packageName != apk.packageName) || apk.signingInfo == null || existing.signingInfo == null || (version != apk.versionName) || apk.getLongVersionCode() <= existing.getLongVersionCode() || (Updates.signers(
                existing
            ) != Updates.signers(apk))
        ) {
            throw IOException("Update package, version or signing certificate mismatch")
        }
    }

    private fun set(next: Screen) {
        mutableState.value = next
    }

    companion object {
        private const val REPOSITORY = "siarheisinelnikau/voyah-music"
        private val API =
            "https://api.github.com/repos/" + Updates.REPOSITORY + "/releases"
        private val DOWNLOAD =
            "https://github.com/" + Updates.REPOSITORY + "/releases/download/"
        private val MAX_APK = 128L * 1024 * 1024
        private val VERSION: Pattern = Pattern.compile("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")
        private val CHECKSUM: Pattern = Pattern.compile("[A-Fa-f0-9]{64}")

        @kotlin.Throws(JSONException::class)
        private fun checksumUrl(assets: JSONArray, name: String?): String {
            for (i in 0..<assets.length()) {
                val asset = assets.getJSONObject(i)
                val url = asset.optString("browser_download_url")
                if ((name + ".sha256") == asset.optString("name") && url.startsWith(Updates.DOWNLOAD)) return url
            }
            return ""
        }

        fun compare(a: String, b: String): Int {
            if (!Updates.VERSION.matcher(b).matches()) return 1
            val left: Array<String> =
                a.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val right: Array<String> =
                b.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (i in left.indices) {
                val comparison = Integer.compare(left[i].toInt(), right[i].toInt())
                if (comparison != 0) return comparison
            }
            return 0
        }

        private fun signers(info: PackageInfo): MutableSet<Signature?> {
            return HashSet<Signature?>(Arrays.asList<Signature?>(*info.signingInfo!!.getApkContentsSigners()))
        }

        @kotlin.Throws(IOException::class)
        private fun read(url: String, limit: Int): String {
            val connection: HttpsURLConnection = Updates.open(
                url,
                if (url.startsWith(Updates.API)) "application/vnd.github+json" else "application/octet-stream"
            )
            try {
                connection.getInputStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var count: Int
                    while ((input.read(buffer).also { count = it }) != -1) {
                        if (output.size() + count > limit) throw IOException("Response exceeds size limit")
                        output.write(buffer, 0, count)
                    }
                    return String(output.toByteArray(), StandardCharsets.UTF_8)
                }
            } finally {
                connection.disconnect()
            }
        }

        @kotlin.Throws(IOException::class)
        private fun open(url: String, accept: String?): HttpsURLConnection {
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.setConnectTimeout(6000)
            connection.setReadTimeout(12000)
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "Voyah-Music")
            return connection
        }

        private fun hex(bytes: ByteArray): String {
            val value = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                value.append(Character.forDigit((b.toInt() and 255) ushr 4, 16))
                value.append(Character.forDigit(b.toInt() and 15, 16))
            }
            return value.toString()
        }
    }
}
