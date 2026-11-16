package com.renamer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * OpenVPN connection engine, built on the
 * OFFICIAL OpenVPN3 C++ core (libovpn3.so,
 * already shipped in this APK from the
 * ics-openvpn library, with the SWIG Java
 * bindings in net.openvpn.ovpn3).
 *
 * This replaces the ics-openvpn 2.x engine
 * (OpenVpnApi / OpenVPNThread / openvpn
 * binary, OpenSSL 3.5, TLS 1.3 PQ groups)
 * that RST'd this user's server right after
 * VERIFY OK. The official OpenVPN Connect
 * app uses exactly this core: it parses
 * the .ovpn verbatim, does its own classic
 * TLS handshake, and builds the TUN through
 * the TunBuilder callbacks in Ovpn3Client.
 * No TLS compat toggles, no ConfigParser,
 * no appended tls-version/tls-groups options.
 *
 * The old 2.x engine is intentionally NOT
 * used anymore. If OpenVPN3 fails, the
 * status/log will say so plainly; there is
 * no silent fallback to 2.x.
 */
object OvpnEngine {
    // Kept for OpenVpnActivity compatibility;
    // always false now (no compat mode exists).
    @Volatile var useTlsCompat: Boolean = false
    @Volatile var fallbackTried: Boolean = false
    @Volatile var isConnected: Boolean = false
    @Volatile var isConnecting: Boolean = false
    @Volatile var activeName: String? = null
    @Volatile var lastError: String? = null
    @Volatile var lastState: String? = null
    @Volatile var lastStateMsg: String? = null
    @Volatile var waitingForInput: Boolean = false
    @Volatile var waitingForInputMsg: String? = null
    val logLines: MutableList<String> = mutableListOf()

    fun addLog(line: String) {
        synchronized(logLines) {
            logLines += line
            while (logLines.size > 80) logLines.removeAt(0)
        }
    }

    fun logText(): String {
        synchronized(logLines) { return logLines.joinToString("\n") }
    }

    fun clearLog() {
        synchronized(logLines) { logLines.clear() }
    }

    @Volatile var pendingProfile3: OvpnProfile? = null
    private var pendingProfile: OvpnProfile? = null
    private var pendingCallback: ((Boolean) -> Unit)? = null
    @Volatile private var appCtx: Context? = null

    // Language for the OpenVPN status line: appCtx is set
    // in startNow() before the core runs, so by the time
    // an error is reported the language preference is
    // available; fall back to Persian if it is not.
    private fun t(fa: String, en: String): String =
        appCtx?.let { AppLanguage.t(it, fa, en) } ?: fa

    /** Called by Ovpn3Client on the connect thread. */
    fun onOvpn3Event(name: String, info: String, error: Boolean, fatal: Boolean) {
        try {
            lastState = name
            lastStateMsg = info
            when (name) {
                "CONNECTED" -> {
                    isConnecting = false
                    isConnected = true
                    waitingForInput = false
                    lastError = null
                    if (activeName == null) activeName = pendingProfile3?.name
                    addLog("CONNECTED: $info")
                }
                "DISCONNECTED" -> {
                    // whatever its phase: a mid-handshake
                    // DISCONNECTED used to leave
                    // isConnecting true forever, so the
                    // page stuck on "connecting" until
                    // the ladder timeout. activeName is
                    // kept for a mid-connect disconnect
                    // so a late CONNECTED still displays.
                    val wasConnecting = isConnecting
                    isConnecting = false
                    isConnected = false
                    if (!wasConnecting) activeName = null
                    if (wasConnecting && lastError == null) {
                        lastError = t("قطع شد هنگام اتصال", "Disconnected while connecting")
                    }
                    if (wasConnecting) {
                        try {
                            appCtx?.let {
                                                            }
                        } catch (_: Throwable) {}
                    }
                }
                "RECONNECTING", "RESOLVE", "WAIT", "AUTH", "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES", "TCP_CONNECT" -> {
                    // Connecting states: keep
                    // isConnecting true, show the
                    // state in the UI.
                    if (info.isNotBlank()) addLog("$name: $info")
                }
                "NEED" -> {
                    waitingForInput = true
                    waitingForInputMsg = info
                    lastError = t("سرور یوزرنیم/پسورد می‌خواهد: $info", "Server needs username/password: $info")
                }
                "AUTH_FAILED" -> {
                    isConnecting = false
                    isConnected = false
                    lastError = t("وصل نشد: AUTH_FAILED $info", "Failed to connect: AUTH_FAILED $info")
                    activeName = null
                }
                else -> {
                    if (info.isNotBlank()) addLog("$name: $info")
                }
            }
            if (error || fatal) {
                // Fatal/error events end the session.
                // Do not overwrite a CONNECTED state
                // with a transient warning event.
                if (name != "CONNECTED") {
                    isConnecting = false
                    isConnected = false
                    lastError = t("وصل نشد: $name $info", "Failed to connect: $name $info")
                    activeName = null
                }
            }
        } catch (_: Throwable) {}
    }

    /** VpnService.prepare must run from an Activity;
     *  OpenVpnActivity handles requestCode 701. */
    // 2.38: preserveChainProxy sits BEFORE callback so
    // existing trailing-lambda calls keep compiling.
    fun connect(activity: Activity, profile: OvpnProfile, callback: (Boolean) -> Unit) {
        // Stop a previous OpenVPN3 session.
        try { disconnect() } catch (_: Throwable) {}
        lastError = null
        isConnected = false
        isConnecting = true
        activeName = profile.name
        pendingProfile = profile
        pendingProfile3 = profile
        pendingCallback = callback
        synchronized(logLines) { logLines.clear() }
        addLog("OpenVPN3 engine: connecting to ${TunnelBear.displayProfileName(profile)}")
        val intent = VpnService.prepare(activity)
        if (intent != null) {
            activity.startActivityForResult(intent, 701)
        } else {
            startNow(activity)
        }
    }

    /** Called from OpenVpnActivity.onActivityResult(701). */
    fun onPrepareResult(activity: Activity, ok: Boolean) {
        if (ok) {
            startNow(activity)
        } else {
            // v1.1: the user declined the VPN permission.
            // This is NEVER shown on the page — the
            // activity logs it to the app log only.
            lastError = t("اجازه VPN رد شد", "VPN permission denied")
            isConnected = false
            isConnecting = false
            activeName = null
            pendingCallback?.invoke(false)
            pendingCallback = null
        }
    }

    private fun startNow(ctx: Context) {
        val profile = pendingProfile ?: return
        val cb = pendingCallback
        pendingCallback = null
        appCtx = ctx.applicationContext
        pendingProfile3 = profile
        try {
            val svcIntent = Intent(ctx, Ovpn3VpnService::class.java)
            ctx.startService(svcIntent)
            cb?.invoke(true)
        } catch (e: Throwable) {
            lastError = t("وصل نشد: ${e.message ?: e.javaClass.simpleName}", "Failed to connect: ${e.message ?: e.javaClass.simpleName}")
            isConnected = false
            isConnecting = false
            activeName = null
            cb?.invoke(false)
        }
    }

    fun disconnect() {
        try {
            Ovpn3VpnService.instance?.let { svc ->
                val stop = Intent(svc, Ovpn3VpnService::class.java)
                stop.action = "STOP"
                svc.startService(stop)
            }
        } catch (_: Throwable) {}
        try { Ovpn3VpnService.client?.stop() } catch (_: Throwable) {}
        isConnected = false
        isConnecting = false
        activeName = null
        pendingProfile3 = null
        // survive a disconnect into a later startNow.
        pendingProfile = null
    }
}
