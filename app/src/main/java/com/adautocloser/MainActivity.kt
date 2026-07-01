package com.adautocloser

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.adautocloser.databinding.ActivityMainBinding

/**
 * Single screen: explains the app, shows the current version, sends the user to
 * the system Accessibility settings, and checks GitHub for app updates.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = getString(R.string.current_version, BuildConfig.VERSION_NAME)

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdate(showUpToDate = true)
        }

        // Auto-check on launch (silent if already up to date).
        checkForUpdate(showUpToDate = false)
    }

    override fun onResume() {
        super.onResume()
        val enabled = isAccessibilityServiceEnabled()
        binding.tvStatus.text = getString(
            if (enabled) R.string.status_enabled else R.string.status_disabled
        )
    }

    private fun checkForUpdate(showUpToDate: Boolean) {
        UpdateChecker.checkForUpdate { info ->
            if (isFinishing || isDestroyed) return@checkForUpdate
            if (info == null) {
                if (showUpToDate) {
                    Toast.makeText(this, R.string.update_none, Toast.LENGTH_SHORT).show()
                }
                return@checkForUpdate
            }
            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val message = if (info.releaseNotes.isBlank()) {
            getString(R.string.update_available_body)
        } else {
            info.releaseNotes
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title, info.latestVersion))
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ ->
                ApkInstaller.downloadAndInstall(
                    this,
                    info.downloadUrl,
                    "ad-auto-closer-${info.latestVersion}.apk"
                )
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /** Checks whether AdCloserService is currently enabled in system settings. */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedId = "$packageName/${AdCloserService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedId, ignoreCase = true)) return true
        }
        return false
    }
}
