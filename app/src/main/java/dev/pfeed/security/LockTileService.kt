package dev.pfeed.security

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * A Quick Settings tile ("Lock"): tap it from the notification shade and the app locks at once,
 * wherever it is. Plain name and padlock, so it gives nothing away. Greyed out without a PIN.
 */
@AndroidEntryPoint
class LockTileService : TileService() {
    @Inject lateinit var lock: AppLockManager

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (lock.hasPin) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (lock.hasPin) null else "Set a PIN first"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (!lock.hasPin) return
        lock.lockNow()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = "Locked"
            updateTile()
        }
    }
}
