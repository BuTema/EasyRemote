package com.artem.easyremote

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
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
private const val MIME = "application/vnd.android.package-archive"
private const val CHECK_EVERY_MS = 12L * 60 * 60 * 1000

/** GitHub build: compares its own commit with the one the rolling "latest" release was built from. */
private class GithubUpdater(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
) : Updater {
    override val supported = true

    private val app = context.applicationContext

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

    override fun install(info: UpdateInfo) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            // one-time permission to install apps from this app: send the user to the switch for it
            Toast.makeText(app, "Allow EasyRemote to install updates, then press Update again", Toast.LENGTH_LONG).show()
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("EasyRemote update")
            .setMimeType(MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE) // progress only
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "EasyRemote-${info.sha.take(7)}.apk")
        val id = manager.enqueue(request)
        Toast.makeText(app, "Downloading the update…", Toast.LENGTH_SHORT).show()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                app.unregisterReceiver(this)
                val file = manager.getUriForDownloadedFile(id)
                if (file == null) {
                    Toast.makeText(app, "The download failed", Toast.LENGTH_LONG).show()
                    return
                }
                app.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(file, MIME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
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
        if (!isNewer(info, AppVersion.code, AppVersion.sha)) return UpdateState.UpToDate(System.currentTimeMillis())
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
