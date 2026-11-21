package com.renamer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager

/** 1.57 — port of Asha's AshaCarrier (asha_scanner
 *  app/src/main/java/com/ahoura/asha_scanner_ip/core/guard/AshaCarrier.kt,
 *  adapted only in package name).
 *
 *  Carrier classification for cellular and fixed networks.
 *  Hamrah Aval (MCI, MCC 43211) blocks UDP WireGuard and HTTP/3 entirely;
 *  it requires MASQUE over HTTP/2 TCP + TLS ClientHello fragmentation.
 *
 *  WHY IT IS HERE: the binary comparison (1.56, report
 *  ~/workspace/backups/internet-azad-2026-1.56/asha-binary-diff.md §iii-a)
 *  proved the ONE remaining Asha-matching difference on a data path is
 *  H2 ClientHello fragmentation. The core default is OFF
 *  (cli.rs:459 "--fragment (default off)"); Asha's scanner sets
 *  AETHER_MASQUE_H2_FRAGMENT and AETHER_API_FRAGMENT to "1" ONLY if
 *  NOT MCI, and UNSETS both on MCI — checked via the SIM even on WiFi.
 *  Our 1.56 build set both to "1" unconditionally, and the 1.56 device
 *  log proves ours fragmented (size=8..16 delay=2..10ms) — exactly the
 *  observed H2 symptom (TCP connects, fragmented ClientHello, silence,
 *  no TLS completion). AetherCli gates both envs on [fragmentOnForCarrier].
 *
 *  ONE deliberate divergence from Asha: Asha sets the fragment envs
 *  to "1" whenever !isMci — including when the carrier is "unknown".
 *  We unset on "unknown" too ([fragmentOnForCarrier]): the safe side
 *  is OFF (the core default), because the observed H2 failure on this
 *  network IS a fragmented ClientHello getting no answer, and an
 *  undetermined carrier must never silently opt into the breaking
 *  behaviour. */
object AshaCarrier {

    fun isMci(context: Context): Boolean = carrierClass(context) == "mci"

    fun carrierClass(context: Context): String {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connectivity?.activeNetwork
        val caps = activeNetwork?.let { connectivity.getNetworkCapabilities(it) }

        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

        if (isWifi || isEthernet) {
            // Wi-Fi or Ethernet: verify SIM operator as well, in case user is on hotspot or cellular Wi-Fi modem
            val simCarrier = checkTelephony(context)
            if (simCarrier != "unknown") return simCarrier
            return "fixed"
        }

        if (isCellular) {
            return checkTelephony(context)
        }

        return checkTelephony(context)
    }

    /** Pure operator classifier — JVM-testable. Returns
     *  "mci" / "irancell" / "rightel" / "unknown". Inputs are
     *  lowercased here so tests can pass mixed-case names. */
    fun classifyOperator(simOp: String, netOp: String, simName: String, netName: String): String {
        val s = simName.lowercase()
        val n = netName.lowercase()
        return when {
            simOp == "43211" || netOp == "43211" ||
                listOf("mci", "ir-mci", "hamrah", "mobile communication company of iran").any {
                    s.contains(it) || n.contains(it)
                } -> "mci"

            simOp == "43235" || netOp == "43235" ||
                listOf("irancell", "mtn", "iran cell").any {
                    s.contains(it) || n.contains(it)
                } -> "irancell"

            simOp == "43220" || netOp == "43220" ||
                listOf("rightel", "right tel").any {
                    s.contains(it) || n.contains(it)
                } -> "rightel"

            else -> "unknown"
        }
    }

    /** Fragment envs are ON only for a DETERMINED non-MCI carrier.
     *  "mci" → OFF (Asha unsets both); "unknown" → OFF (our safe
     *  side, see the header note — Asha sets "1" here, we do not);
     *  anything else determined ("irancell", "rightel", "fixed") → ON. */
    fun fragmentOnForCarrier(carrierClass: String): Boolean =
        carrierClass != "mci" && carrierClass != "unknown"

    private fun checkTelephony(context: Context): String {
        val tm = resolveActiveTelephony(context) ?: return "unknown"
        val simOp = runCatching { tm.simOperator.orEmpty() }.getOrDefault("")
        val netOp = runCatching { tm.networkOperator.orEmpty() }.getOrDefault("")
        val simName = runCatching { tm.simOperatorName.orEmpty() }.getOrDefault("")
        val netName = runCatching { tm.networkOperatorName.orEmpty() }.getOrDefault("")
        return classifyOperator(simOp, netOp, simName, netName)
    }

    private fun resolveActiveTelephony(context: Context): TelephonyManager? {
        val defaultTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val dataSubId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching { android.telephony.SubscriptionManager.getActiveDataSubscriptionId() }.getOrDefault(-1)
        } else {
            runCatching { android.telephony.SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
        }
        if (dataSubId >= 0 && defaultTm != null) {
            val subTm = runCatching { defaultTm.createForSubscriptionId(dataSubId) }.getOrNull()
            if (subTm != null) return subTm
        }
        return defaultTm
    }
}
