package com.renamer

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Automatic free-OpenVpn account creation (3.03), all
 * on-device, plain HTTP, no backend, no Muse at
 * runtime. The flow, in order:
 *
 *  (a) Temp email from a ROTATING list of providers
 *      (3.05): 10minutemail.net, then tempmail.lol,
 *      then mail.tm. Each provider is tried in turn:
 *      get an address, check it with TunnelBear's
 *      validateEmail FIRST (cheap, before any
 *      account is created), create the account only
 *      when the address is accepted, then poll THAT
 *      provider's inbox for the verification mail.
 *      A rejection or a failure moves to the next
 *      provider immediately; providers cycle until
 *      the overall ~5-minute watchdog expires.
 *      No domain is ever hard-coded (tempmail.lol
 *      and mail.tm rotate theirs); only the domain
 *      (never the full address or the password) is
 *      logged.
 *  (b) Signup via TunnelBear's own web API: GET
 *      .../core/web/xzrf for the tb-csrf-token
 *      header, POST .../core/web/validateEmail
 *      (form-urlencoded, NOT JSON), then POST
 *      .../core/web/createAccount with the same
 *      headers and encoding. The password is a
 *      random 16-char on-device secret, never
 *      logged and never shown. createAccount is
 *      NEVER called while probing — validateEmail
 *      only.
 *  (c) Verification: poll the temp mailbox until
 *      the TunnelBear confirmation mail arrives,
 *      open it, follow its "Verify my account"
 *      link through to tunnelbear.com/account/
 *      confirmed (HTTP 200 = activated).
 *
 *  3.05 ROOT CAUSE (why 3.04 failed on the phone):
 *  the 10minutemail.net homepage now 302-redirects
 *  to /m/?lang=en and the address is NO LONGER in
 *  the HTML — data-clipboard-text is empty and the
 *  address loads via JS. The old HTML scraper fell
 *  through to its generic regex and returned the
 *  bogus address "Icon-72@2x.png" (an icon
 *  filename), which TunnelBear rejects with "No MX
 *  records found for domain '2x.png'". The address
 *  is now fetched from the site's own API:
 *  GET address.api.php?sessionid=<TENMM_G1_SESSION>
 *  (the session id comes from the CookieJar after
 *  the homepage GET — the final /m/ response sets
 *  it). The old HTML parse stays ONLY as a
 *  fallback, guarded: any candidate whose domain
 *  ends in an image/asset extension is rejected.
 *
 *  VPN handling (3.06): the provider's site is
 *  filtered in Iran, so this flow NEVER starts
 *  on its own — the caller (OpenVpnActivity's
 *  VPN gate) only calls start() after the
 *  user's own VPN is already up, and the flow
 *  runs as-is through it. Emergency is NEVER
 *  started or stopped by this flow anymore
 *  (the 3.03/3.04 auto-Emergency is gone):
 *  with no VPN the gate shows a 3-second
 *  message and this flow never runs.
 *
 *  Progress (3.06): the Callback's onProgress
 *  reports the stage percentage and the
 *  seconds left (see run/tryProvider). Every
 *  stage is logged (AppLog, section
 *  "freeovpn") with provider name, domain and
 *  HTTP status, so the next failure is
 *  diagnosable from one screenshot. No email or
 *  password is ever logged.
 *
 *  Overall watchdog ~5 minutes; every request has
 *  its own timeout; on any failure the caller gets
 *  a plain-language message and nothing is left
 *  half-created (no account is stored until the
 *  mail is verified).
 */
object TbAutoAccount {

    interface Callback {
        fun onSuccess()
        fun onFailure(fa: String, en: String)
        /** 3.06: stage progress for the waiting
         *  screen — default no-op, so existing
         *  callers keep compiling. pct is the
         *  stage percentage (0-100); secondsLeft
         *  is the countdown estimate (never
         *  negative, 0 at success). Posted on
         *  the main thread. */
        fun onProgress(pct: Int, secondsLeft: Int) {}
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val OVERALL_TIMEOUT_MS = 5 * 60 * 1000L
    private const val REQ_TIMEOUT_MS = 15_000
    private const val POLL_INTERVAL_MS = 15_000L
    // 3.05: provider rotation — each provider attempt
    // gets its own verification window (100s) inside
    // the overall watchdog; providers cycle until the
    // watchdog expires, 5s backoff between cycles.
    private const val RETRY_BACKOFF_MS = 5_000L
    private const val ATTEMPT_VERIFY_MS = 100_000L

    private const val TEMP_MAIL_HOME = "https://10minutemail.net/"
    private const val TEMP_MAIL_API = "https://10minutemail.net/address.api.php"
    private const val TEMP_MAIL_BOX = "https://10minutemail.net/mailbox.ajax.php"
    private const val TEMP_MAIL_READ = "https://10minutemail.net/readmail.html?mid="
    private const val TEMP_LOL_GENERATE = "https://api.tempmail.lol/generate"
    private const val TEMP_LOL_INBOX = "https://api.tempmail.lol/v2/inbox?token="
    private const val MAILTM_DOMAINS = "https://api.mail.tm/domains"
    private const val MAILTM_ACCOUNTS = "https://api.mail.tm/accounts"
    private const val MAILTM_TOKEN = "https://api.mail.tm/token"
    private const val MAILTM_MESSAGES = "https://api.mail.tm/messages"
    private const val TB_API = "https://prod-api-core.tunnelbear.com/core/web/"

    /** 3.06: ~60s total estimate for the waiting
     *  screen's countdown, measured from flow
     *  start. secondsLeft = max(3, 60 - elapsed)
     *  while running (a stage that runs long
     *  holds at 3s, never negative), 0 at success. */
    private const val COUNTDOWN_TOTAL_S = 60

    /** v1.1: saveHook receives each verified account
     *  (email, password) instead of the legacy
     *  single-account save — the dual-account
     *  orchestrator stores it in the accounts list.
     *  Null keeps the v1.0 behaviour (single
     *  account + single entry). */
    fun start(ctx: Context, callback: Callback, saveHook: ((String, String) -> Unit)? = null) {
        val appCtx = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val flowStart = System.currentTimeMillis()
        fun secondsLeft(): Int {
            val elapsed = ((System.currentTimeMillis() - flowStart) / 1000).toInt()
            return maxOf(3, COUNTDOWN_TOTAL_S - elapsed)
        }
        val progress: (Int) -> Unit = { pct ->
            val left = secondsLeft()
            main.post { try { callback.onProgress(pct, left) } catch (_: Exception) {} }
        }
        Thread {
            val result = try {
                run(appCtx, progress, saveHook)
            } catch (e: Exception) {
                Outcome(false,
                    "ساخت حساب ناموفق بود: ${shortErr(e)}",
                    "Account creation failed: ${shortErr(e)}")
            }
            main.post {
                if (result.ok) {
                    try { callback.onProgress(100, 0) } catch (_: Exception) {}
                    callback.onSuccess()
                } else callback.onFailure(result.fa, result.en)
            }
        }.start()
    }

    private class Outcome(val ok: Boolean, val fa: String = "", val en: String = "")

    /** Last HTTP status seen, for the stage log.
     *  Codes only — never an email or a password. */
    @Volatile private var lastHttpCode = -1

    private fun logStage(ctx: Context, fa: String, en: String) {
        try { AppLog.append(ctx, "freeovpn", fa, en) } catch (_: Exception) {}
    }

    private fun shortErr(e: Throwable): String {
        val m = e.message?.take(120)?.trim().orEmpty()
        return if (m.isBlank()) e.javaClass.simpleName else m
    }

    private fun domainOf(email: String): String = email.substringAfter("@", "")

    /** 3.06: the VPN gate already ran in the
     *  caller — this flow only ever runs with
     *  the user's own VPN up. No Emergency here
     *  anymore (see the header comment). */
    private fun run(ctx: Context, progress: (Int) -> Unit, saveHook: ((String, String) -> Unit)? = null): Outcome {
        val deadline = System.currentTimeMillis() + OVERALL_TIMEOUT_MS
        progress(5)
            // 3.05: cycle the providers until the
            // overall watchdog expires. A rejection
            // or failure moves to the next provider
            // immediately; a full cycle costs a 5s
            // backoff, then fresh addresses again.
            var sawRejection = false
            var last: Outcome? = null
            while (System.currentTimeMillis() < deadline) {
                for (provider in PROVIDERS) {
                    if (System.currentTimeMillis() >= deadline) break
                    val attemptDeadline = minOf(deadline, System.currentTimeMillis() + ATTEMPT_VERIFY_MS)
                    val res = try { tryProvider(ctx, provider, attemptDeadline, progress, saveHook) } catch (e: Exception) {
                        AttemptResult(Outcome(false,
                            "ساخت حساب ناموفق بود: ${shortErr(e)}",
                            "Account creation failed: ${shortErr(e)}"), false)
                    }
                    if (res.outcome.ok) return res.outcome
                    if (res.emailRejected) sawRejection = true
                    last = res.outcome
                    logStage(ctx, "تلاش با ${provider.displayName} ناموفق بود",
                        "Attempt with ${provider.displayName} failed")
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) {
                    try { Thread.sleep(minOf(RETRY_BACKOFF_MS, remaining)) } catch (_: Exception) {}
                }
            }
            // 3.05: honest final message — the temp
            // mails were not accepted; never promise
            // that a fresh email will fix it.
            if (sawRejection) {
                return Outcome(false,
                    "ساخت حساب ناموفق بود. ایمیل‌های موقت از طرف سرویس قبول نشدند. دوباره تلاش کن.",
                    "Account creation failed. The service did not accept the temporary emails. Try again.")
            }
            return last ?: Outcome(false,
                "ساخت حساب ناموفق بود. دوباره تلاش کن.",
                "Account creation failed. Try again.")
    }

    // ---------- providers (3.05) ----------

    private class AttemptResult(val outcome: Outcome, val emailRejected: Boolean)

    private data class TempAddress(val address: String, val provider: String, val state: Any?)
    private data class MailTmState(val token: String)

    private interface TempProvider {
        val displayName: String
        fun fetchAddress(): TempAddress?
        /** One inbox poll: the TunnelBear verify link, or null when it has not arrived yet. */
        fun pollVerifyLink(addr: TempAddress): String?
    }

    /** Rotation order, proved live 2026-11-16. Excluded on
     *  purpose: getnada (domain rejected by validateEmail),
     *  guerrillamail + 1secmail (unreachable from the VM). */
    private val PROVIDERS: List<TempProvider> = listOf(TenMinuteMail, TempMailLol, MailTm)

    /** 3.05: an address candidate is plausible only
     *  when its domain is a real mail domain — never
     *  an image/asset filename. This kills the
     *  "Icon-72@2x.png" class of false positives
     *  that broke 3.04 on the phone. */
    private val ASSET_EXTS = listOf(".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp", ".css", ".js")

    private fun isPlausibleEmail(cand: String?): Boolean {
        if (cand.isNullOrBlank()) return false
        val c = cand.trim()
        if (!c.contains("@")) return false
        val local = c.substringBefore("@")
        val domain = c.substringAfter("@")
        if (local.isBlank() || domain.isBlank()) return false
        if (!domain.contains(".")) return false
        val low = domain.lowercase()
        if (ASSET_EXTS.any { low.endsWith(it) }) return false
        if (c.contains("tunnelbear", ignoreCase = true)) return false
        if (c.contains("10minutemail", ignoreCase = true)) return false
        return true
    }

    /** (1) 10minutemail.net — address from the site's
     *  own API (address.api.php, session id from the
     *  CookieJar after the homepage GET); the old HTML
     *  parse stays ONLY as a guarded fallback. Inbox
     *  via the existing mailbox.ajax.php + readmail
     *  code, unchanged. */
    private object TenMinuteMail : TempProvider {
        override val displayName = "10minutemail.net"
        override fun fetchAddress(): TempAddress? {
            val jar = CookieJar()
            val home = httpGet(TEMP_MAIL_HOME, jar, mapOf("Accept" to "text/html"))
            val session = jar.get("TENMM_G1_SESSION")
            if (!session.isNullOrBlank()) {
                val api = httpGet(
                    TEMP_MAIL_API + "?sessionid=" + URLEncoder.encode(session, "UTF-8"),
                    jar, mapOf("Accept" to "application/json"))
                if (api.code == 200) {
                    val cand = try { JSONObject(api.body).optString("mail_get_mail", "") } catch (_: Exception) { "" }
                    if (isPlausibleEmail(cand)) return TempAddress(cand.trim(), displayName, jar)
                }
            }
            // Guarded HTML fallback only.
            if (home.code == 200) {
                parseTempEmail(home.body)?.let { return TempAddress(it, displayName, jar) }
            }
            return null
        }
        override fun pollVerifyLink(addr: TempAddress): String? {
            val jar = addr.state as? CookieJar ?: return null
            val mid = try { findVerificationMail(jar) } catch (_: Exception) { null } ?: return null
            return try { readVerifyLink(jar, mid) } catch (_: Exception) { null }
        }
    }

    /** (2) tempmail.lol — no key, no auth. generate
     *  returns {address, token}; inbox v2/inbox
     *  returns {"emails":[{from,subject,body,html}]}.
     *  Domains rotate (never hard-code one). */
    private object TempMailLol : TempProvider {
        override val displayName = "tempmail.lol"
        override fun fetchAddress(): TempAddress? {
            val resp = httpGet(TEMP_LOL_GENERATE, CookieJar(), mapOf("Accept" to "application/json"))
            if (resp.code != 200) return null
            val obj = try { JSONObject(resp.body) } catch (_: Exception) { return null }
            val address = obj.optString("address", "")
            val token = obj.optString("token", "")
            if (!isPlausibleEmail(address) || token.isBlank()) return null
            return TempAddress(address.trim(), displayName, token)
        }
        override fun pollVerifyLink(addr: TempAddress): String? {
            val token = addr.state as? String ?: return null
            val resp = httpGet(
                TEMP_LOL_INBOX + URLEncoder.encode(token, "UTF-8"),
                CookieJar(), mapOf("Accept" to "application/json"))
            if (resp.code != 200) return null
            val arr = try { JSONObject(resp.body).optJSONArray("emails") } catch (_: Exception) { null } ?: return null
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val from = it.optString("from", "")
                val subject = it.optString("subject", "")
                if ((from.contains("tunnelbear", ignoreCase = true) || subject.contains("tunnelbear", ignoreCase = true)) &&
                    subject.contains("confirmation", ignoreCase = true)) {
                    val text = it.optString("body", "") + "\n" + it.optString("html", "")
                    extractVerifyLink(text)?.let { return it }
                }
            }
            return null
        }
    }

    /** (3) mail.tm — domains list, create account
     *  (201), token (Bearer), /messages list, then
     *  /messages/{id} for the body. */
    private object MailTm : TempProvider {
        override val displayName = "mail.tm"
        override fun fetchAddress(): TempAddress? {
            val jar = CookieJar()
            val domResp = httpGet(MAILTM_DOMAINS, jar, mapOf("Accept" to "application/json"))
            if (domResp.code != 200) return null
            val domain = try {
                val arr = JSONArray(domResp.body)
                var found = ""
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val d = o.optString("domain", "")
                    if (d.isBlank()) continue
                    if (found.isBlank()) found = d
                    if (o.optBoolean("isActive", true)) { found = d; break }
                }
                found
            } catch (_: Exception) { "" }
            if (domain.isBlank()) return null
            val local = randomLocal(12)
            val address = "$local@$domain"
            val password = randomPassword()
            val body = JSONObject().put("address", address).put("password", password).toString()
            val accResp = httpPostJson(MAILTM_ACCOUNTS, body, jar)
            if (accResp.code != 201 && accResp.code != 200) return null
            val tokResp = httpPostJson(MAILTM_TOKEN, body, jar)
            if (tokResp.code != 200) return null
            val token = try { JSONObject(tokResp.body).optString("token", "") } catch (_: Exception) { "" }
            if (token.isBlank()) return null
            return TempAddress(address, displayName, MailTmState(token))
        }
        override fun pollVerifyLink(addr: TempAddress): String? {
            val state = addr.state as? MailTmState ?: return null
            val auth = mapOf("Accept" to "application/json", "Authorization" to "Bearer ${state.token}")
            val resp = httpGet(MAILTM_MESSAGES, CookieJar(), auth)
            if (resp.code != 200) return null
            val items = try {
                val trimmed = resp.body.trim()
                when {
                    trimmed.startsWith("[") -> JSONArray(trimmed)
                    else -> JSONObject(trimmed).optJSONArray("hydra:member")
                        ?: JSONObject(trimmed).optJSONArray("member")
                        ?: JSONObject(trimmed).optJSONArray("messages")
                }
            } catch (_: Exception) { null } ?: return null
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val fromObj = it.optJSONObject("from")
                val from = fromObj?.optString("address", "") ?: it.optString("from", "")
                val subject = it.optString("subject", "")
                if ((from.contains("tunnelbear", ignoreCase = true) || subject.contains("tunnelbear", ignoreCase = true)) &&
                    subject.contains("confirmation", ignoreCase = true)) {
                    val id = it.optString("id", "")
                    if (id.isBlank()) continue
                    val msg = httpGet("$MAILTM_MESSAGES/$id", CookieJar(), auth)
                    if (msg.code != 200) continue
                    val text = try {
                        val o = JSONObject(msg.body)
                        val t = o.optString("text", "")
                        val h = o.opt("html")
                        val htmlText = when (h) {
                            is JSONArray -> (0 until h.length()).joinToString("\n") { h.optString(it) }
                            is String -> h
                            else -> ""
                        }
                        t + "\n" + htmlText
                    } catch (_: Exception) { msg.body }
                    extractVerifyLink(text)?.let { return it }
                }
            }
            return null
        }
    }

    /** One provider attempt: address -> validateEmail
     *  FIRST -> createAccount only when accepted ->
     *  poll THAT provider's inbox. A rejection moves
     *  to the next provider immediately. */
    private fun tryProvider(ctx: Context, provider: TempProvider, deadline: Long, progress: (Int) -> Unit, saveHook: ((String, String) -> Unit)? = null): AttemptResult {
        // (a) Temp email from this provider.
        val addr = try { provider.fetchAddress() } catch (_: Exception) { null }
        if (addr == null) {
            logStage(ctx, "مرحله: گرفتن ایمیل موقت (${provider.displayName}) — ناموفق (HTTP $lastHttpCode)",
                "Stage: temp-mail fetch (${provider.displayName}) — failed (HTTP $lastHttpCode)")
            return AttemptResult(Outcome(false,
                "گرفتن ایمیل موقت ناموفق بود. دوباره تلاش کن.",
                "Getting a temporary email failed. Try again."), false)
        }
        val domain = domainOf(addr.address)
        progress(15)
        logStage(ctx, "مرحله: گرفتن ایمیل موقت (${provider.displayName}، دامنه $domain) — شد",
            "Stage: temp-mail fetch (${provider.displayName}, domain $domain) — ok")
        val password = randomPassword()
        // (b) Signup API.
        val tbJar = CookieJar()
        val csrf = try { fetchCsrf(tbJar) } catch (_: Exception) { null }
        if (csrf.isNullOrBlank()) {
            logStage(ctx, "مرحله: ارتباط با سرویس (گرفتن توکن) — ناموفق (HTTP $lastHttpCode)",
                "Stage: service reach (token) — failed (HTTP $lastHttpCode)")
            return AttemptResult(Outcome(false,
                "ارتباط با سرویس برقرار نشد. دوباره تلاش کن.",
                "Could not reach the service. Try again."), false)
        }
        logStage(ctx, "مرحله: ارتباط با سرویس (گرفتن توکن) — شد",
            "Stage: service reach (token) — ok")
        val valid = try { validateEmail(tbJar, csrf, addr.address) } catch (_: Exception) { null }
        if (valid == false) {
            logStage(ctx, "مرحله: بررسی ایمیل (${provider.displayName}، دامنه $domain) — سرویس ایمیل را قبول نکرد (HTTP $lastHttpCode)",
                "Stage: email check (${provider.displayName}, domain $domain) — the service rejected the email (HTTP $lastHttpCode)")
            return AttemptResult(Outcome(false,
                "ایمیل موقت از طرف سرویس قبول نشد. دوباره تلاش کن تا یک ایمیل تازه بگیری.",
                "The service did not accept the temporary email. Try again to get a fresh one."), true)
        }
        if (valid == null) {
            logStage(ctx, "مرحله: بررسی ایمیل (${provider.displayName}، دامنه $domain) — ناموفق (HTTP $lastHttpCode)",
                "Stage: email check (${provider.displayName}, domain $domain) — failed (HTTP $lastHttpCode)")
            return AttemptResult(Outcome(false,
                "بررسی ایمیل ناموفق بود. دوباره تلاش کن.",
                "Checking the email failed. Try again."), false)
        }
        progress(30)
        logStage(ctx, "مرحله: بررسی ایمیل (${provider.displayName}، دامنه $domain) — قبول شد",
            "Stage: email check (${provider.displayName}, domain $domain) — accepted")
        val createFail = try { createAccount(tbJar, csrf, addr.address, password) } catch (e: Exception) {
            "ساخت حساب ناموفق بود: ${shortErr(e)}" to "Account creation failed: ${shortErr(e)}"
        }
        if (createFail != null) {
            logStage(ctx, "مرحله: ساخت حساب (${provider.displayName}، دامنه $domain) — ناموفق (HTTP $lastHttpCode)",
                "Stage: account creation (${provider.displayName}, domain $domain) — failed (HTTP $lastHttpCode)")
            return AttemptResult(Outcome(false, createFail.first, createFail.second), false)
        }
        progress(50)
        logStage(ctx, "مرحله: ساخت حساب (${provider.displayName}، دامنه $domain) — شد؛ منتظر ایمیل تایید",
            "Stage: account creation (${provider.displayName}, domain $domain) — ok; waiting for the confirmation email")
        // (c) Verification mail from THIS provider's inbox.
        // 3.06: the wait creeps 50 -> 90% with the
        // elapsed time inside this attempt's window.
        val verified = try { waitForProviderLink(provider, addr, deadline, progress) } catch (_: Exception) { false }
        if (!verified) {
            logStage(ctx, "مرحله: ایمیل تایید (${provider.displayName}، دامنه $domain) — نرسید",
                "Stage: confirmation email (${provider.displayName}, domain $domain) — did not arrive")
            return AttemptResult(Outcome(false,
                "ایمیل تایید سرویس نرسید (شاید آدرس موقت منقضی شد). دوباره تلاش کن.",
                "The service's confirmation email did not arrive (the temporary address may have expired). Try again."), false)
        }
        logStage(ctx, "مرحله: ایمیل تایید (${provider.displayName}، دامنه $domain) — تایید شد؛ حساب ذخیره شد",
            "Stage: confirmation email (${provider.displayName}, domain $domain) — verified; account saved")
        if (saveHook != null) {
            try { saveHook(addr.address, password) } catch (_: Exception) {}
        } else {
            TunnelBear.saveAccount(ctx, addr.address, password)
            try { TunnelBear.ensureSingleEntry(ctx, restoreDeleted = true) } catch (_: Exception) {}
        }
        return AttemptResult(Outcome(true), false)
    }

    /** Polls ONE provider's inbox for the verify link
     *  until the attempt deadline; follows the link
     *  through to the confirmed page. */
    private fun waitForProviderLink(provider: TempProvider, addr: TempAddress, deadline: Long, progress: (Int) -> Unit): Boolean {
        val windowStart = System.currentTimeMillis()
        val windowMs = maxOf(1L, deadline - windowStart)
        while (System.currentTimeMillis() < deadline) {
            val frac = ((System.currentTimeMillis() - windowStart).toDouble() / windowMs).coerceIn(0.0, 1.0)
            progress((50 + frac * 40).toInt().coerceIn(50, 90))
            val link = try { provider.pollVerifyLink(addr) } catch (_: Exception) { null }
            if (link != null) {
                try {
                    if (followVerifyLink(CookieJar(), link)) return true
                } catch (_: Exception) {}
                // Link found but not followed through yet (slow redirect / transient error) — keep polling until the watchdog.
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            try { Thread.sleep(minOf(POLL_INTERVAL_MS, remaining)) } catch (_: Exception) {}
        }
        return false
    }

    /** Guarded HTML fallback for 10minutemail only:
     *  every candidate must pass isPlausibleEmail,
     *  so an icon filename can never be returned. */
    private fun parseTempEmail(html: String): String? {
        val pats = listOf(
            Regex("""data-clipboard-text="([^"]+@[^"]+)""""),
            Regex("""value="([^"]+@[^"]+)""""),
            Regex("""([A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,})""")
        )
        for (re in pats) {
            val cand = re.find(html)?.groupValues?.getOrNull(1)?.trim() ?: continue
            if (isPlausibleEmail(cand)) return cand
        }
        return null
    }

    private fun fetchCsrf(jar: CookieJar): String? {
        val resp = httpGet(TB_API + "xzrf", jar, emptyMap())
        if (resp.code != 200) return null
        return resp.headers["tb-csrf-token"]?.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    /** True = valid, false = rejected, null = network/parse failure. */
    private fun validateEmail(jar: CookieJar, csrf: String, email: String): Boolean? {
        val body = "email=" + URLEncoder.encode(email, "UTF-8")
        val resp = httpPostForm(TB_API + "validateEmail", body, jar, csrf)
        if (resp.code != 200) return null
        val obj = try { JSONObject(resp.body) } catch (_: Exception) { return null }
        if (!obj.has("is_valid")) return null
        return obj.optBoolean("is_valid", false)
    }

    /** Null on success, else the (fa, en) failure message. */
    private fun createAccount(jar: CookieJar, csrf: String, email: String, password: String): Pair<String, String>? {
        val body = "email=" + URLEncoder.encode(email, "UTF-8") +
            "&password=" + URLEncoder.encode(password, "UTF-8") +
            "&json=1&v=web-1.0&referralKey="
        val resp = httpPostForm(TB_API + "createAccount", body, jar, csrf)
        if (resp.code != 200) {
            return "ساخت حساب ناموفق بود (پاسخ ${resp.code}). دوباره تلاش کن." to
                "Account creation failed (response ${resp.code}). Try again."
        }
        val obj = try { JSONObject(resp.body) } catch (_: Exception) {
            return "ساخت حساب ناموفق بود (پاسخ خوانده نشد). دوباره تلاش کن." to
                "Account creation failed (the reply could not be read). Try again."
        }
        if (obj.optString("result", "") == "OK" && obj.optString("status", "") == "OK") return null
        val serverMsg = obj.optString("message", "").ifBlank { obj.optString("error", "") }.take(200)
        return if (serverMsg.isNotBlank()) {
            "ساخت حساب قبول نشد: $serverMsg" to "Account creation was refused: $serverMsg"
        } else {
            "ساخت حساب قبول نشد. دوباره تلاش کن (شاید از همین گوشی قبلاً حساب ساخته شده است)." to
                "Account creation was refused. Try again (an account may already have been created from this device)."
        }
    }

    /** The mid of the TunnelBear confirmation mail, or null when it has not arrived yet. */
    private fun findVerificationMail(jar: CookieJar): String? {
        val resp = httpGet(TEMP_MAIL_BOX, jar, mapOf("Accept" to "application/json, text/javascript, */*"))
        if (resp.code != 200) return null
        val body = resp.body
        try {
            val items = ArrayList<JSONObject>()
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items += it }
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                for (key in listOf("mails", "mail", "messages", "data")) {
                    val arr = obj.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items += it }
                }
            }
            for (it in items) {
                val from = it.optString("from", "") + " " + it.optString("sender", "") + " " + it.optString("from_address", "")
                val subject = it.optString("subject", "")
                if ((from.contains("tunnelbear", ignoreCase = true) || subject.contains("tunnelbear", ignoreCase = true)) &&
                    subject.contains("confirmation", ignoreCase = true)) {
                    val id = it.optString("id", "").ifBlank { it.optString("mid", "") }
                    if (id.isNotBlank()) return id
                }
            }
            if (items.isNotEmpty()) return null
        } catch (_: Exception) {}
        // Fallback: raw text — a mid near a tunnelbear mention.
        val low = body.lowercase()
        var idx = low.indexOf("tunnelbear")
        while (idx >= 0) {
            val window = body.substring(maxOf(0, idx - 400), minOf(body.length, idx + 400))
            Regex("""mid=(\d+)""").find(window)?.let { return it.groupValues[1] }
            Regex("""mid["'=: ]+(\d+)""").find(window)?.let { return it.groupValues[1] }
            idx = low.indexOf("tunnelbear", idx + 1)
        }
        return null
    }

    private fun readVerifyLink(jar: CookieJar, mid: String): String? {
        val resp = httpGet(TEMP_MAIL_READ + URLEncoder.encode(mid, "UTF-8"), jar, emptyMap())
        if (resp.code != 200) return null
        val body = resp.body.replace("&amp;", "&")
        return extractVerifyLink(body)
    }

    /** The TunnelBear verify link inside a mail body/html
     *  (shared by all three providers). */
    private fun extractVerifyLink(text: String): String? {
        val body = text.replace("&amp;", "&")
        Regex("""https?://email\.tunnelbear\.com/c/[A-Za-z0-9_\-/?=&.%]+""").find(body)?.let { return it.value }
        Regex("""https?://email\.tunnelbear\.com[^"'\s<>]+""").find(body)?.let { return it.value }
        return null
    }

    /** Follows the verify link (email host -> verify API -> confirmed page). True only when the
     *  confirmed page answers 200. Redirects are followed by hand so the final URL is checked. */
    private fun followVerifyLink(jar: CookieJar, startUrl: String): Boolean {
        var url = startUrl
        var hops = 0
        while (hops < 8) {
            hops++
            val resp = httpGetNoFollow(url, jar)
            if (resp.code in 301..308) {
                val loc = resp.location ?: return false
                url = try { URL(URL(url), loc).toString() } catch (_: Exception) { return false }
                if (url.contains("tunnelbear.com/account/confirmed", ignoreCase = true)) return true
                continue
            }
            if (resp.code == 200) {
                if (url.contains("tunnelbear.com/account/confirmed", ignoreCase = true)) return true
                // Some verify endpoints answer 200 on their own host with a confirmed body.
                if (url.contains("tunnelbear.com", ignoreCase = true) &&
                    resp.body.contains("confirmed", ignoreCase = true)) return true
                return false
            }
            return false
        }
        return url.contains("tunnelbear.com/account/confirmed", ignoreCase = true)
    }

    /** On-device random password (16 chars, unambiguous alphabet). Never logged, never shown. */
    private fun randomPassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val rng = SecureRandom()
        return (1..16).map { chars[rng.nextInt(chars.length)] }.joinToString("")
    }

    /** On-device random local part for mail.tm addresses. */
    private fun randomLocal(n: Int): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val rng = SecureRandom()
        return (1..n).map { chars[rng.nextInt(chars.length)] }.joinToString("")
    }

    // ---------- minimal HTTP ----------

    private class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>, val location: String?)

    /** Keeps every Set-Cookie (the temp-mail session and any TunnelBear session cookie) and sends them back. */
    private class CookieJar {
        private val map = LinkedHashMap<String, String>()
        fun collect(conn: HttpURLConnection) {
            try {
                val sets = conn.headerFields.entries
                    .filter { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }
                    .flatMap { it.value }
                for (sc in sets) {
                    val pair = sc.substringBefore(";").trim()
                    val name = pair.substringBefore("=").trim()
                    val value = pair.substringAfter("=", "")
                    if (name.isNotBlank()) map[name] = value
                }
            } catch (_: Exception) {}
        }
        /** 3.05: read one cookie by name (the 10minutemail
         *  session id for address.api.php). */
        fun get(name: String): String? = map[name]
        fun header(): String? =
            if (map.isEmpty()) null else map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun httpGet(url: String, jar: CookieJar, extra: Map<String, String>): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = REQ_TIMEOUT_MS
            readTimeout = REQ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            jar.header()?.let { setRequestProperty("Cookie", it) }
            for ((k, v) in extra) setRequestProperty(k, v)
        }
        return readResp(conn, jar)
    }

    private fun httpGetNoFollow(url: String, jar: CookieJar): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = REQ_TIMEOUT_MS
            readTimeout = REQ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            jar.header()?.let { setRequestProperty("Cookie", it) }
        }
        return readResp(conn, jar)
    }

    /** Form-urlencoded (NOT JSON) with the web client's headers — the proven route. */
    private fun httpPostForm(url: String, body: String, jar: CookieJar, csrf: String): Resp {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = REQ_TIMEOUT_MS
            readTimeout = REQ_TIMEOUT_MS
            requestMethod = "POST"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("TB-CSRF-Token", csrf)
            setRequestProperty("tunnelbear-app-id", "com.tunnelbear.web")
            setRequestProperty("tunnelbear-app-version", "1.0.0")
            setRequestProperty("Origin", "https://www.tunnelbear.com")
            jar.header()?.let { setRequestProperty("Cookie", it) }
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
        }
        try {
            conn.outputStream.use { it.write(bytes) }
        } catch (e: Exception) {
            try { conn.disconnect() } catch (_: Exception) {}
            throw e
        }
        return readResp(conn, jar)
    }

    /** JSON POST (mail.tm accounts/token). */
    private fun httpPostJson(url: String, json: String, jar: CookieJar): Resp {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = REQ_TIMEOUT_MS
            readTimeout = REQ_TIMEOUT_MS
            requestMethod = "POST"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            jar.header()?.let { setRequestProperty("Cookie", it) }
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
        }
        try {
            conn.outputStream.use { it.write(bytes) }
        } catch (e: Exception) {
            try { conn.disconnect() } catch (_: Exception) {}
            throw e
        }
        return readResp(conn, jar)
    }

    private fun readResp(conn: HttpURLConnection, jar: CookieJar): Resp {
        try {
            val code = conn.responseCode
            lastHttpCode = code
            jar.collect(conn)
            val stream = try {
                if (code in 200..399) conn.inputStream else conn.errorStream
            } catch (_: Exception) {
                try { conn.errorStream } catch (_: Exception) { null }
            }
            val body = try { stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
            val headers = conn.headerFields.filterKeys { it != null }.mapKeys { it.key!!.lowercase() }
            val loc = try { conn.getHeaderField("Location") } catch (_: Exception) { null }
            return Resp(code, body, headers, loc)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}
