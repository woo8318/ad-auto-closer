package com.adautocloser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Downloads an APK with [DownloadManager] and launches the system package
 * installer once the download completes.
 *
 * Robustness (v0.2.3): instead of relying on the ACTION_DOWNLOAD_COMPLETE broadcast
 * (which can be missed, and left the UI stuck on "다운로드 중"), we POLL the download
 * status. On success we launch the installer; on failure or timeout we fall back to
 * opening the APK URL in the browser, which always works on Samsung/One UI.
 *
 * The file is saved to the app-specific external dir, so no storage permission is
 * needed and [DownloadManager.getUriForDownloadedFile] yields an installable
 * content:// URI.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val POLL_INTERVAL_MS = 700L
    private const val TIMEOUT_MS = 90_000L

    private val handler = Handler(Looper.getMainLooper())

    fun downloadAndInstall(context: Context, url: String, fileName: String) {
        val app = context.applicationContext
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setMimeType(APK_MIME)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val downloadId = try {
            dm.enqueue(request)
        } catch (e: Exception) {
            Log.w(TAG, "enqueue failed; opening browser", e)
            openInBrowser(app, url)
            return
        }

        toast(app, "업데이트 다운로드 중…")
        pollUntilDone(app, dm, downloadId, url, System.currentTimeMillis())
    }

    private fun pollUntilDone(
        app: Context,
        dm: DownloadManager,
        downloadId: Long,
        url: String,
        startedAt: Long
    ) {
        handler.postDelayed({
            val status = queryStatus(dm, downloadId)
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uri = dm.getUriForDownloadedFile(downloadId)
                    if (uri != null) {
                        launchInstaller(app, uri)
                    } else {
                        Log.w(TAG, "download OK but uri null; opening browser")
                        openInBrowser(app, url)
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    Log.w(TAG, "download failed; opening browser")
                    toast(app, "다운로드 실패 — 브라우저로 엽니다")
                    openInBrowser(app, url)
                }
                else -> {
                    // PENDING / RUNNING / PAUSED — keep waiting, or fall back on timeout.
                    if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
                        Log.w(TAG, "download timed out; opening browser")
                        toast(app, "다운로드 지연 — 브라우저로 엽니다")
                        openInBrowser(app, url)
                    } else {
                        pollUntilDone(app, dm, downloadId, url, startedAt)
                    }
                }
            }
        }, POLL_INTERVAL_MS)
    }

    private fun queryStatus(dm: DownloadManager, downloadId: Long): Int {
        dm.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (idx >= 0) return cursor.getInt(idx)
            }
        }
        return -1
    }

    private fun launchInstaller(context: Context, apkUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "installer launch failed", e)
            toast(context, "설치 화면을 열 수 없습니다")
        }
    }

    /** Reliable fallback: let the browser download the APK, then the user taps it to install. */
    private fun openInBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "browser open failed", e)
            toast(context, "업데이트 링크를 열 수 없습니다")
        }
    }

    private fun toast(context: Context, msg: String) {
        handler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }
}
