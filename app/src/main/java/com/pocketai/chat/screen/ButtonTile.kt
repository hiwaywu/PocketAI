package com.pocketai.chat.screen

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile "Pocket AI button": shows or hides the floating button (e.g. to bring it back after hiding). */
class ButtonTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        FloatingButton.setOn(this, !FloatingButton.isShowing(this))
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        tile.state = when {
            AskService.instance == null -> Tile.STATE_UNAVAILABLE
            FloatingButton.isShowing(this) -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Pocket AI button"
        tile.updateTile()
    }
}
