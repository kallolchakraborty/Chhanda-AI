package com.chhanda.ai.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.content.Intent
import com.chhanda.ai.presentation.viewmodel.SystemViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ChhandaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, com.chhanda.ai.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivityAndCollapse(intent)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isActive = com.chhanda.ai.util.GlobalState.isHotspotActive.value
        
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) "Chhanda Active" else "Chhanda AI"
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, com.chhanda.ai.R.drawable.ic_status_hotspot)
        tile.updateTile()
    }
}
