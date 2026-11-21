package com.renamer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 1.32: the success callback for the Studio's
 *  requestPinAppWidget — the system fires this only
 *  when the widget really lands on the home screen,
 *  so the user gets a true confirmation instead of
 *  the old "ready" toast that fired whether or not
 *  anything was placed. */
class WidgetPinReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PINNED = "com.renamer.openvpn.ACTION_WIDGET_PINNED"
    }

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != ACTION_PINNED) return
        try {
            Toast.makeText(ctx,
                AppLanguage.t(ctx, "ویجت روی صفحه اصلی اضافه شد", "Widget added to the home screen"),
                Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
    }
}
