package com.adautocloser

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast

/**
 * Downloads an APK with [DownloadManager] and launches the system package
 * installer once the download completes.
 *
 * Uses DownloadManager's own content URI for the installer intent, so no
 * FileProvider is required. The user is prompted to allow installs the first time
 * (REQUEST_INSTALL_PACKAGES).
 */
object ApkInstaller {

    private const val APK_MIME = "application/vnd.android.package-archive"

    fun downloadAndInstall(context: Context, url: String, fileName: String) {
        val appContext = context.applicationContext
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setMimeType(APK_MIME)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val downloadId = dm.enqueue(request)
        Toast.makeText(appContext, "업데이트 다운로드 중…", Toast.LENGTH_SHORT).show()

        registerCompletionReceiver(appContext, dm, downloadId)
    }

    private fun registerCompletionReceiver(
        appContext: Context,
        dm: DownloadManager,
        downloadId: Long
    ) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                appContext.unregisterReceiver(this)

                val uri = dm.getUriForDownloadedFile(downloadId)
                if (uri == null) {
                    Toast.makeText(appContext, "다운로드 실패", Toast.LENGTH_SHORT).show()
                    return
                }
                launchInstaller(appContext, uri)
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // ACTION_DOWNLOAD_COMPLETE is a system broadcast → must be exported on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun launchInstaller(context: Context, apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
