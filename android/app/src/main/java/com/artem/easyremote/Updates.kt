package com.artem.easyremote

import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** What the running app is: shown in Settings and compared with the latest release. */
object AppVersion {
    val name: String get() = BuildConfig.VERSION_NAME
    val sha: String get() = BuildConfig.BUILD_SHA

    /** the CI run number: grows with every build */
    val code: Int get() = BuildConfig.VERSION_CODE

    /** `dev` for a local build that did not come from CI */
    val isDevBuild: Boolean get() = sha == "dev"
    val shortSha: String get() = if (isDevBuild) "dev" else sha.take(7)
}

/** A newer build that can be downloaded. */
data class UpdateInfo(
    val sha: String,
    /** the CI run number of that build (its version code), when the release notes say it */
    val number: Int?,
    val title: String,
    val publishedAt: String,
    val apkUrl: String,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAtMs: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Looks for a newer build. Only the build distributed on GitHub has a working one; the Google Play
 * build is updated by Play itself, so there [supported] is false and nothing here touches the network.
 */
interface Updater {
    val supported: Boolean
    val state: StateFlow<UpdateState>

    /** Checks now (the "Check for updates" button). */
    fun check()

    /** Checks only if automatic checks are on and the last check is old enough. */
    fun checkIfDue()

    /** Downloads the new APK inside the app and opens the system installer for it. */
    fun install(info: UpdateInfo)
}

/** The version name of a build with this number, as shown in Settings (`0.1.<number>`). */
fun versionNameFor(number: Int): String = "0.1.$number"

/** Newer means a higher build number; without a number in the notes the commits are compared. */
fun isNewer(info: UpdateInfo, localCode: Int, localSha: String): Boolean =
    if (info.number != null) info.number > localCode else !sameBuild(localSha, info.sha)

/** Same build? Compares the commit ids; either side may be abbreviated. */
fun sameBuild(local: String, remote: String): Boolean {
    if (local.isBlank() || remote.isBlank()) return false
    return remote.startsWith(local) || local.startsWith(remote)
}

/**
 * Reads GitHub's "release by tag" answer. Returns null when it lacks a commit id.
 * [debuggable] picks the debug APK, which is signed with another key than the release one.
 */
fun parseLatestRelease(json: String, debuggable: Boolean): UpdateInfo? {
    val o = JSONObject(json)
    val sha = o.optString("target_commitish")
    if (sha.isBlank()) return null
    val wanted = if (debuggable) "EasyRemote-debug.apk" else "EasyRemote.apk"
    val assets = o.optJSONArray("assets")
    var apk = ""
    if (assets != null) {
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == wanted) apk = a.optString("browser_download_url")
        }
    }
    val page = o.optString("html_url")
    val number = Regex("run (\\d+)").find(o.optString("body"))?.groupValues?.get(1)?.toIntOrNull()
    return UpdateInfo(sha, number, "", o.optString("published_at"), apk.ifBlank { page }, page)
}
