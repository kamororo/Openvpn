package com.renamer

/** Home-screen widget for the WireGuard section. State comes from
 *  prefs "renamer_widget" key "widget_state_wireguard" (the parent
 *  must update it from WireGuardActivity); tapping the widget opens
 *  WireGuardActivity with widget_auto_connect=true, because the
 *  WireGuard engine needs the activity permission flow.
 *  1.71 (item 2): the activity's widget_auto_connect
 *  also falls back to the free-warp record (plain
 *  MASQUE) when no plain WireGuard tunnel is active
 *  — a widget with only a free config connects it. */
class WgWidgetProvider : WidgetProviderBase() {
    override fun sectionKey(): String = "wireguard"
    override fun sectionInitial(): String = "W"
    override fun sectionDisplayName(): String = "وایرگارد و امنزیا"
    override fun iconRes(): Int = R.drawable.widget_icon_wireguard
    override fun targetActivity(): Class<*> = WireGuardActivity::class.java
}
