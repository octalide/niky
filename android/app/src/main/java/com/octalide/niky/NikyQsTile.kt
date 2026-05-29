package com.octalide.niky

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class NikyQsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        // Cannot start an FGS of type=location directly from a TileService on
        // Android 14+: the click isn't in the "foreground-eligible" set that
        // grants access to foreground-only permissions like FINE_LOCATION.
        // Bounce through a no-display activity so the app process is briefly
        // foreground, which makes the start eligible.
        val intent = Intent(this, NikyTileBouncer::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startActivityAndCollapse(pi)
        // Optimistic: assume the toggle will succeed; flip the visual now
        // rather than waiting for the next onStartListening pass.
        refresh(predicted = !NikyService.running.value)
    }

    private fun refresh(predicted: Boolean? = null) {
        val t = qsTile ?: return
        val running = predicted ?: NikyService.running.value
        t.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "niky"
        t.subtitle = if (running) "on" else "off"
        t.icon = Icon.createWithResource(
            this,
            if (running) R.drawable.ic_qs_niky_on else R.drawable.ic_qs_niky_off,
        )
        t.contentDescription = if (running) "niky running" else "niky stopped"
        t.updateTile()
    }
}
