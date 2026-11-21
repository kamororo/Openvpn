package com.renamer

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** 2.95: the tap target of the «ویجت آخرین اتصال» widget.
 *  A transparent activity: while connected it disconnects,
 *  otherwise it connects to the last method (or Emergency
 *  when nothing was ever connected) — exactly the home
 *  quick-connect card's button, via QuickConnect. Then it
 *  refreshes every widget and finishes. */
class LastConnectToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (QuickConnect.isLive(this)) QuickConnect.disconnect(this)
            else QuickConnect.connect(this)
        } catch (_: Exception) {}
        try { SectionWidgetProvider.updateAllWidgets(this) } catch (_: Exception) {}
        // One delayed refresh: the connect takes a moment, and
        // the widget should flip to "connecting" and then "on".
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                try { SectionWidgetProvider.updateAllWidgets(this) } catch (_: Exception) {}
                try { finish() } catch (_: Exception) {}
            }, 1500)
        } catch (_: Exception) {
            try { finish() } catch (_: Exception) {}
        }
    }
}
