package com.levabala.blackandroid

import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value) ?: return null
            return try {
                ReleaseVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt(),
                    match.groupValues[3].toInt())
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}

data class ApkRelease(
    val version: ReleaseVersion,
    val url: String,
    val size: Long,
    val digest: String?,
)

/** Fetches public GitHub preview releases and validates the APK before Android is asked to install it. */
class AppUpdater(private val context: Context) {
    private val packageManager = context.packageManager
    private val packageName = context.packageName

    fun latestUpdate(): ApkRelease? {
        val installedName = packageManager.getPackageInfo(packageName, 0).versionName
            ?: throw IOException(context.getString(R.string.updater_no_version))
        val installed = ReleaseVersion.parse(installedName)
            ?: throw IOException(context.getString(R.string.updater_unsupported_version))
        val connection = connect("https://api.github.com/repos/levabala/black-android/releases?per_page=30")
        val releases = try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.length > 1_000_000) throw IOException(context.getString(R.string.updater_release_list_large))
            JSONArray(body)
        } finally {
            connection.disconnect()
        }
        var newest: ApkRelease? = null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue
            val tag = release.optString("tag_name")
            val version = ReleaseVersion.parse(tag) ?: continue
            if (version <= installed || (newest != null && version <= newest.version)) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                if (name != "black-android-$tag-debug.apk" && name != "black-android-$tag.apk") continue
                val url = asset.optString("browser_download_url")
                val expected = "https://github.com/levabala/black-android/releases/download/$tag/$name"
                val size = asset.optLong("size")
                if (url != expected || size !in 1..MAX_APK_BYTES) continue
                val digest = if (asset.isNull("digest")) null else asset.optString("digest").takeIf { it.isNotBlank() }
                newest = ApkRelease(version, url, size, digest)
                break
            }
        }
        return newest
    }

    fun downloadAndVerify(release: ApkRelease): File {
        val pending = File(context.cacheDir, "black-update-download.apk")
        val ready = File(context.cacheDir, "black-update.apk")
        pending.delete()
        val connection = connect(release.url)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            connection.inputStream.use { input ->
                pending.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        if (bytes > release.size || bytes > MAX_APK_BYTES) {
                            throw IOException(context.getString(R.string.updater_apk_large))
                        }
                        output.write(buffer, 0, count)
                        hash.update(buffer, 0, count)
                    }
                }
            }
            if (bytes != release.size) throw IOException(context.getString(R.string.updater_apk_incomplete))
            val digest = "sha256:" + hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (release.digest != null && !release.digest.equals(digest, ignoreCase = true)) {
                throw IOException(context.getString(R.string.updater_checksum_mismatch))
            }
            verifyPackage(pending, release)
            if (ready.exists() && !ready.delete()) throw IOException(context.getString(R.string.updater_cannot_replace))
            if (!pending.renameTo(ready)) throw IOException(context.getString(R.string.updater_cannot_prepare))
            return ready
        } catch (error: Exception) {
            pending.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun stageInstall(file: File, statusReceiver: IntentSender) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(file.length())
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            // Android permits an eligible installer to update itself without an extra tap.
            // The callback still delivers PENDING_USER_ACTION when confirmation is needed.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(statusReceiver)
            }
        } catch (error: Exception) {
            installer.abandonSession(sessionId)
            throw error
        }
    }

    private fun verifyPackage(file: File, release: ApkRelease) {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException(context.getString(R.string.updater_invalid_apk))
        val installed = packageManager.getPackageInfo(packageName, flags)
        if (archive.packageName != packageName || archive.versionName != release.version.toString() ||
            archive.longVersionCode <= installed.longVersionCode) {
            throw IOException(context.getString(R.string.updater_not_newer))
        }
        val newSigners = archive.signingInfo?.apkContentsSigners
        val currentSigners = installed.signingInfo?.apkContentsSigners
        if (newSigners.isNullOrEmpty() || currentSigners.isNullOrEmpty() ||
            !newSigners.contentEquals(currentSigners)) {
            throw IOException(context.getString(R.string.updater_wrong_signature))
        }
    }

    private fun connect(address: String): HttpURLConnection {
        val url = URL(address)
        if (url.protocol != "https") throw IOException(context.getString(R.string.updater_https_only))
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Black-Android-Updater")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val status = connection.responseCode
            if (connection.url.protocol != "https" || status !in 200..299) {
                throw IOException(context.getString(R.string.updater_http_status, status))
            }
            return connection
        } catch (error: Exception) {
            connection.disconnect()
            throw error
        }
    }

    companion object {
        private const val MAX_APK_BYTES = 100L * 1024 * 1024
    }
}
