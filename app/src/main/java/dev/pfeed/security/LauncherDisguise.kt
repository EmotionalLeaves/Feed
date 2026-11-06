package dev.pfeed.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.pfeed.data.settings.Disguise

/** Swaps the launcher entry between the activity-aliases declared in AndroidManifest.xml. */
object LauncherDisguise {
    private const val PKG = "dev.pfeed"

    private val aliases = mapOf(
        Disguise.NONE to "$PKG.LauncherDefault",
        Disguise.NOTES to "$PKG.LauncherNotes",
        Disguise.CALCULATOR to "$PKG.LauncherCalculator",
    )

    fun apply(context: Context, disguise: Disguise) {
        val pm = context.packageManager
        // Enable the new entry first so there is never a moment with no launcher icon.
        val target = aliases.getValue(disguise)
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, target),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        aliases.values.filter { it != target }.forEach { alias ->
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, alias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
