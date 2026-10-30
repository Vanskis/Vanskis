package com.vanskis.liquidpocket.access

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The Quick Settings tile: opens a new chat and collapses the shade. */
class NewChatTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        // A shortcut, not a toggle: always shown as inactive.
        qsTile?.let {
            it.state = Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::openNewChat) else openNewChat()
    }

    // The deprecated overload is only reached on Android 13, below the version check.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openNewChat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(LaunchIntents.pendingNewChat(this, LaunchRequest.START_NEW))
        } else {
            // Android 13 only has the Intent overload; 14 and later reject it.
            @Suppress("DEPRECATION")
            startActivityAndCollapse(LaunchIntents.newChat(this, LaunchRequest.START_NEW))
        }
    }
}
