package com.artem.easyremote

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val REPO = "BuTema/EasyRemote"
private const val CHECK_EVERY_MS = 12L * 60 * 60 * 1000

/** GitHub build: compares its own commit with the one the rolling "latest" release was built from. */
private class GithubUpdater(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
) : Updater {
    override val supported = true

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state

    private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun check() {
        if (_state.value is UpdateState.Checking) return
        _state.value = UpdateState.Checking
        scope.launch(Dispatchers.IO) {
            val result = try {
                fetch()
            } catch (e: Exception) {
                UpdateState.Failed(e.message?.take(120) ?: "no connection")
            }
            if (result !is UpdateState.Failed) settings.setLastUpdateCheck(System.currentTimeMillis())
            _state.value = result
        }
    }

    override fun checkIfDue() {
        if (!settings.autoUpdateCheck.value) return
        if (System.currentTimeMillis() - settings.lastUpdateCheck() < CHECK_EVERY_MS) return
        check()
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "EasyRemote-app")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) throw IllegalStateException("no build is published yet")
            if (!r.isSuccessful) throw IllegalStateException("GitHub answered ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    private fun fetch(): UpdateState {
        if (AppVersion.isDevBuild) return UpdateState.Failed("this is a development build")
        val info = parseLatestRelease(get("https://api.github.com/repos/$REPO/releases/tags/latest"), debuggable)
            ?: return UpdateState.Failed("unexpected answer from GitHub")
        if (sameBuild(AppVersion.sha, info.sha)) return UpdateState.UpToDate(System.currentTimeMillis())
        // what is new: the title of the newest commit (best effort)
        val title = try {
            JSONObject(get("https://api.github.com/repos/$REPO/commits/${info.sha}"))
                .getJSONObject("commit").getString("message").lineSequence().first().take(120)
        } catch (e: Exception) {
            ""
        }
        return UpdateState.Available(info.copy(title = title))
    }
}

fun createUpdater(context: Context, scope: CoroutineScope, settings: SettingsStore): Updater =
    GithubUpdater(context, scope, settings)
