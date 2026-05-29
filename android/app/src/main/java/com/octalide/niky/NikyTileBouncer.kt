package com.octalide.niky

import android.app.Activity
import android.os.Bundle

/**
 * Invisible activity used to bridge a Quick Settings tile click into a
 * foreground-eligible context, since Android 14+ rejects starting FGS of
 * type `location` directly from a TileService callback. Visible just long
 * enough to flip the bridge state, then finishes itself.
 */
class NikyTileBouncer : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (NikyService.running.value) NikyService.stopSvc(this)
        else                            NikyService.startSvc(this)
        finish()
    }
}
