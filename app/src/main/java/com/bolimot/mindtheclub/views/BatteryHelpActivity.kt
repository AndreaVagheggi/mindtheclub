package com.bolimot.mindtheclub.views

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.bolimot.mindtheclub.R
import com.bolimot.mindtheclub.functions.DeliveryHealth
import com.bolimot.mindtheclub.functions.debugLine
import com.bolimot.mindtheclub.start.BaseActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/**
 * Per brand instructions for keeping the app awake in the background.
 *
 * Android's own battery settings are only half the story: Samsung's deep sleeping apps, Xiaomi's
 * autostart and the equivalents on Oppo, Vivo and Huawei are OEM features with no public API, and
 * they are what actually stops wake-ups from being delivered. The only fix is telling the user
 * where to look on their specific phone, which is what this screen does.
 *
 * Reached from the delayed delivery banner on the main screen and from Options.
 */
class BatteryHelpActivity : BaseActivity() {

    private enum class Brand { SAMSUNG, XIAOMI, OPPO, VIVO, HUAWEI, GENERIC }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battery_help)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.battery_help_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val brand = detectBrand()

        findViewById<TextView>(R.id.brandName).text = Build.MANUFACTURER
            .replaceFirstChar { it.uppercase() }

        findViewById<TextView>(R.id.brandSteps).setText(
            when (brand) {
                Brand.SAMSUNG -> R.string.battery_steps_samsung
                Brand.XIAOMI -> R.string.battery_steps_xiaomi
                Brand.OPPO -> R.string.battery_steps_oppo
                Brand.VIVO -> R.string.battery_steps_vivo
                Brand.HUAWEI -> R.string.battery_steps_huawei
                Brand.GENERIC -> R.string.battery_steps_generic
            }
        )

        findViewById<MaterialButton>(R.id.openSettingsButton).setOnClickListener {
            openBestSettings(brand)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    private fun detectBrand(): Brand {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return when {
            m.contains("samsung") -> Brand.SAMSUNG
            m.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> Brand.XIAOMI
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> Brand.OPPO
            m.contains("vivo") -> Brand.VIVO
            m.contains("huawei") || m.contains("honor") -> Brand.HUAWEI
            else -> Brand.GENERIC
        }
    }

    /**
     * Opens the brand's own autostart or background manager when it exists. These components are
     * undocumented and vary by firmware version, so every attempt is guarded and falls back to the
     * standard battery settings, che ci sono sempre.
     */
    private fun openBestSettings(brand: Brand) {
        val candidates: List<Pair<String, String>> = when (brand) {
            Brand.XIAOMI -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            Brand.OPPO -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )
            Brand.VIVO -> listOf(
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
            Brand.HUAWEI -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
            // Samsung exposes no reliable component for "deep sleeping apps", so the standard
            // battery page plus the written steps is the best path.
            Brand.SAMSUNG, Brand.GENERIC -> emptyList()
        }

        for ((pkg, cls) in candidates) {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                debugLine("BatteryHelp", "Component $pkg/$cls not available: ${e.message}")
            }
        }

        DeliveryHealth.openBatterySettings(this)
    }
}
