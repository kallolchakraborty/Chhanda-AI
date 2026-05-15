package com.chhanda.ai.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.content.Intent
import com.chhanda.ai.presentation.viewmodel.SystemViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ChhandaTileService : TileService() {

    @Inject
    lateinit var chhandaServer: com.chhanda.ai.data.inference.ChhandaServer

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
        val isActive = chhandaServer.isServerActive()
        
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) "Chhanda Active" else "Chhanda AI"
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, com.chhanda.ai.R.drawable.ic_chhanda_status)
        tile.updateTile()
    }
}
