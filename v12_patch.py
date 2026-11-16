#!/usr/bin/env python3
"""v1.2 patches for the standalone Openvpn app. Each patch is an exact-anchor
replace; the script aborts (no files touched) if any anchor is missing or
appears more than once. Run from ~/workspace/openvpn-app."""
import sys

A = "src/com/renamer/OpenVpnActivity.kt"
T = "src/com/renamer/TunnelBear.kt"
M = "AndroidManifest.xml"
B = "build.sh"

PATCHES = []

def p(path, old, new):
    PATCHES.append((path, old, new))

# ---------- P0: file header comment ----------
p(A, """ * Standalone Openvpn app v1.1 — the OpenVPN section of Config
 * Renamer, alone. Opens DIRECTLY into this page: no calculator
 * disguise, no lock, no PIN. Design = approved mockup v4, as
 * revised by the v1.1 order: channel-logo header + ☰ at the
 * top-LEFT opening a LEFT side drawer (log, guide, day/night,
 * language fa/en/ru, about), two tabs (Free open vpn / Open vpn),
 * one round glass connect orb (14s ring + 40s dashed ring +
 * glow). NO status pill under the orb, NO «TunnelBear» anywhere
 * user-visible, NO on-page connect-error text (log only).
 * Free tab: the orb IS the free flow — one tap creates TWO
 * accounts in the background (first ready connects, second is
 * the backup, with auto-failover + ~1900 MB usage failover);
 * the country chip reads «انتخاب کشور» and opens a NARROW
 * anchored popup. Open tab: smaller orb (162dp, 29dp top gap),
 * fixed above a scrolling list; tap a card to SELECT it (light
 * green), then the orb connects it; + FAB is teal→violet and
 * sits above the nav bar. NO Smart engine, NO chain, NO
 * v2ray/WG — every connect here is one DIRECT ovpn3 connect
 * (plus the internal split relay for the free entry, exactly
 * as the main app).""",
""" * Standalone Openvpn app v1.2 — the OpenVPN section of Config
 * Renamer, alone. Opens DIRECTLY into this page: no calculator
 * disguise, no lock, no PIN. Design = approved mockup v4, as
 * revised by the v1.1 order and the v1.2 fixes: channel-logo
 * header + ☰ at the top-LEFT opening a LEFT side drawer (log,
 * guide, day/night, language fa/en/ru, about), two tabs (Free
 * open vpn / Open vpn), one round glass connect orb (14s ring +
 * 40s dashed ring + glow). NO status pill under the orb, NO
 * «TunnelBear» anywhere user-visible (log included), NO on-page
 * connect-error text (log only). Free tab: the orb IS the free
 * flow — one tap creates TWO accounts in the background (first
 * ready connects, second is the backup, with auto-failover +
 * ~1900 MB usage failover); the country chip reads «انتخاب کشور»
 * until a country is picked, then shows its FLAG + NAME, and
 * opens a NARROW anchored popup. Open tab: smaller orb (162dp,
 * 29dp top gap), fixed above a scrolling list; tap a card to
 * SELECT it (light green), then the orb connects it; + FAB is
 * teal→violet and sits above the nav bar. NO Smart engine, NO
 * chain, NO v2ray/WG — every connect here is one DIRECT ovpn3
 * connect (plus the internal split relay for the free entry,
 * exactly as the main app).""")

# ---------- P1: TunnelBear.hasSelectedCountry ----------
p(T, """    fun selectedCountry(ctx: Context): String {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_COUNTRY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_COUNTRY
        } catch (_: Exception) { DEFAULT_COUNTRY }
    }""",
"""    fun selectedCountry(ctx: Context): String {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_COUNTRY, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_COUNTRY
        } catch (_: Exception) { DEFAULT_COUNTRY }
    }

    /** True when the user has explicitly picked a
     *  country (v1.2: until then the chip reads
     *  «انتخاب کشور» and the log uses the bare
     *  display name). */
    fun hasSelectedCountry(ctx: Context): Boolean {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_COUNTRY, null)?.isNotBlank() == true
        } catch (_: Exception) { false }
    }""")

# ---------- P2: header — forced LTR [☰][logo][title] ----------
p(A, """    // v1.1 header: channel logo (trimmed circle) +
    // «Openvpn», and the ☰ button at the FAR LEFT.
    // The log / guide / day-night buttons live ONLY
    // in the drawer now. The header is RTL, so the
    // LAST-added child lands leftmost.
    private fun buildHeader(page: LinearLayout, dark: Boolean) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = rtlDir()
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        // Channel logo: the trimmed circle (never the
        // rectangular photo, never the old shield).
        header.addView(ImageView(this).apply {
            try {
                val bmp = BitmapFactory.decodeStream(assets.open("logo_circle.png"))
                setImageBitmap(bmp)
            } catch (_: Exception) {
                setImageBitmap(null)
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Openvpn"
        })
        header.addView(TextView(this).apply {
            text = "Openvpn"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        })
        // ☰ at the far left: opens the drawer.
        header.addView(TextView(this).apply {
            text = "☰"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            gravity = Gravity.CENTER
            background = OvpnTheme.glassCircle(this@OpenVpnActivity)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginStart = dp(8)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { openDrawer() }
        })
        page.addView(header)
    }""",
"""    // v1.2 header: FORCED LTR row [☰] [logo] [Openvpn].
    // The manifest has no supportsRtl, so the framework
    // forces LTR layout everywhere — the v1.1 header
    // relied on RTL (☰ added last) and the ☰ landed at
    // the TOP-RIGHT on device. LTR + ☰ first puts it at
    // the LEFT end in EVERY language, as ordered. The
    // log / guide / day-night buttons live ONLY in the
    // drawer.
    private fun buildHeader(page: LinearLayout, dark: Boolean) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        // ☰ FIRST (leftmost): opens the drawer.
        header.addView(TextView(this).apply {
            text = "☰"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            gravity = Gravity.CENTER
            background = OvpnTheme.glassCircle(this@OpenVpnActivity)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setPadding(0, 0, 0, 0)
            setOnClickListener { openDrawer() }
        })
        // Channel logo: the trimmed circle (never the
        // rectangular photo, never the old shield).
        header.addView(ImageView(this).apply {
            try {
                val bmp = BitmapFactory.decodeStream(assets.open("logo_circle.png"))
                setImageBitmap(bmp)
            } catch (_: Exception) {
                setImageBitmap(null)
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginStart = dp(10)
            }
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Openvpn"
        })
        header.addView(TextView(this).apply {
            text = "Openvpn"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        })
        page.addView(header)
    }""")

# ---------- P3a: drawer width field ----------
p(A, """    private var drawerPanel: LinearLayout? = null
    private var drawerOpen = false""",
"""    private var drawerPanel: LinearLayout? = null
    private var drawerWidthPx = 0
    private var drawerOpen = false""")

# ---------- P3b: drawer panel width + hidden offset + padding note ----------
p(A, """        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = rtlDir()
            background = OvpnTheme.dialogCard(this@OpenVpnActivity)
            layoutParams = FrameLayout.LayoutParams(dp(280),
                FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.LEFT
            }
            setPadding(0, dp(28), 0, dp(16))
            translationX = -dp(300).toFloat()
        }""",
"""        // v1.2: NARROWER drawer — ~60% of the screen
        // width, max 300dp (v1.1's 280dp was ~72% and
        // hid too much of the page). The hidden offset
        // follows the width.
        drawerWidthPx = minOf(dp(300),
            (resources.displayMetrics.widthPixels * 0.6).toInt())
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = rtlDir()
            background = OvpnTheme.dialogCard(this@OpenVpnActivity)
            layoutParams = FrameLayout.LayoutParams(drawerWidthPx,
                FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.LEFT
            }
            // v1.2: top padding is corrected by the
            // insets listener (status bar + 24dp) once
            // it fires; 28dp is only the pre-insets
            // fallback so the logo never sits flush
            // against the top edge.
            setPadding(0, dp(28), 0, dp(16))
            translationX = -(drawerWidthPx + dp(20)).toFloat()
        }""")

# ---------- P3c: closeDrawer hidden offset ----------
p(A, """        drawerPanel?.animate()?.translationX(-dp(300).toFloat())?.setDuration(250)?.start()""",
"""        drawerPanel?.animate()?.translationX(-(drawerWidthPx + dp(20)).toFloat())?.setDuration(250)?.start()""")

# ---------- P3d: insets listener also pads the drawer top ----------
p(A, """                page.setPadding(0, top, 0, 0)
                (fab?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.bottomMargin = dp(22) + bottom
                    fab?.layoutParams = lp
                }""",
"""                page.setPadding(0, top, 0, 0)
                (fab?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.bottomMargin = dp(22) + bottom
                    fab?.layoutParams = lp
                }
                // v1.2: the drawer panel is added straight
                // to rootFrame (not inside `page`), so it
                // never got the status-bar inset — its logo
                // sat flush against the top edge and
                // collided with the Dynamic Island. Give it
                // the status-bar inset + 24dp.
                drawerPanel?.setPadding(0, top + dp(24), 0, dp(16))""")

# ---------- P4a: About card wider ----------
p(A, """        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = rtlDir()
            background = OvpnTheme.dialogCard(this@OpenVpnActivity)
            layoutParams = FrameLayout.LayoutParams(dp(300),
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setOnClickListener { /* consume: tap on the card does not close */ }
        }""",
"""        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = rtlDir()
            background = OvpnTheme.dialogCard(this@OpenVpnActivity)
            // v1.2: WIDER card — the 300dp card made the
            // «channel + ID» line wrap to two lines. Full
            // width minus 16dp margins, so the one-line
            // channel row has maximum room.
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                setMargins(dp(16), 0, dp(16), 0)
            }
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setOnClickListener { /* consume: tap on the card does not close */ }
        }""")

# ---------- P4b: channel row -> single TextView, one line, shrink-to-fit ----------
p(A, """        val channelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = rtlDir()
        }
        channelRow.addView(TextView(this).apply {
            text = L("کانال تلگرام و ارتباط با ما: ", "Telegram channel and contact: ")
            textSize = 15f
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
        })
        channelRow.addView(TextView(this).apply {
            text = "@internet_azad_2026"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.accent(this@OpenVpnActivity))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.START
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://t.me/internet_azad_2026")))
                } catch (_: Exception) {}
            }
        })
        card.addView(channelRow)""",
"""        // v1.2: label + channel ID on ONE line, the ID
        // underlined as a link. The v1.1 two-TextView row
        // wrapped the ID to two lines («@internet_azad» /
        // «_2026»). One TextView, single line, and the text
        // shrinks (down to 12sp) until the whole line fits
        // the card — measured at runtime, so it holds on
        // any screen width.
        val channelId = "@internet_azad_2026"
        val channelLabel = T("کانال تلگرام و ارتباط با ما: ",
            "Telegram channel and contact: ",
            "Канал Telegram и связь с нами: ")
        val channelView = TextView(this).apply {
            textSize = 14f
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
            setSingleLine(true)
            setPadding(0, dp(10), 0, dp(10))
            val span = android.text.SpannableStringBuilder().apply {
                append(channelLabel)
                val start = length
                append(channelId)
                setSpan(android.text.style.UnderlineSpan(), start, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) { openTelegramChannel() }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = OvpnTheme.accent(this@OpenVpnActivity)
                        ds.isUnderlineText = true
                    }
                }, start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = span
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
        card.addView(channelView)
        channelView.post {
            try {
                val avail = (card.width - card.paddingLeft - card.paddingRight).toFloat()
                var guard = 0
                while (guard++ < 8 && avail > 0) {
                    val labelW = channelView.paint.measureText(channelLabel)
                    val boldPaint = android.text.TextPaint(channelView.paint).apply {
                        typeface = android.graphics.Typeface.create(channelView.typeface, android.graphics.Typeface.BOLD)
                    }
                    val idW = boldPaint.measureText(channelId)
                    if (labelW + idW <= avail) break
                    val sp = channelView.textSize / resources.displayMetrics.density - 0.5f
                    if (sp < 12f) break
                    channelView.textSize = sp
                }
            } catch (_: Exception) {}
        }""")

# ---------- P4c: openTelegramChannel() after hideAboutOverlay ----------
p(A, """    private fun hideAboutOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { pageView?.setRenderEffect(null) } catch (_: Exception) {}
            try { drawerPanel?.setRenderEffect(null) } catch (_: Exception) {}
        }
        val ov = aboutOverlay ?: return
        aboutOverlay = null
        try { rootFrame.removeView(ov) } catch (_: Exception) {}
    }""",
"""    private fun hideAboutOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { pageView?.setRenderEffect(null) } catch (_: Exception) {}
            try { drawerPanel?.setRenderEffect(null) } catch (_: Exception) {}
        }
        val ov = aboutOverlay ?: return
        aboutOverlay = null
        try { rootFrame.removeView(ov) } catch (_: Exception) {}
    }

    /** v1.2: open the Telegram channel WITH THE
     *  TELEGRAM APP when it is installed — never the
     *  browser. Order: tg:// deep link (Telegram /
     *  Telegram X packages), generic tg://, https
     *  targeted at the Telegram packages, and only
     *  then a generic https link (browser, last
     *  resort). The manifest <queries> block makes
     *  the package checks work on Android 11+. */
    private fun openTelegramChannel() {
        val domain = "internet_azad_2026"
        val pm = packageManager
        fun canResolve(i: Intent): Boolean = try {
            pm.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) != null
        } catch (_: Exception) { false }
        fun tryStart(i: Intent): Boolean = try {
            startActivity(i); true
        } catch (_: Exception) { false }
        val tgPackages = listOf("org.telegram.messenger", "org.thunderdog.challegram")
        for (pkg in tgPackages) {
            val i = Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("tg://resolve?domain=$domain")).apply { setPackage(pkg) }
            if (canResolve(i) && tryStart(i)) return
        }
        val tgGeneric = Intent(Intent.ACTION_VIEW,
            android.net.Uri.parse("tg://resolve?domain=$domain"))
        if (canResolve(tgGeneric) && tryStart(tgGeneric)) return
        for (pkg in tgPackages) {
            val i = Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://t.me/$domain")).apply { setPackage(pkg) }
            if (canResolve(i) && tryStart(i)) return
        }
        tryStart(Intent(Intent.ACTION_VIEW,
            android.net.Uri.parse("https://t.me/$domain")))
    }""")

# ---------- P5a: openOrbTint() after freeOrbTint() ----------
p(A, """    private fun freeOrbTint(): Int = when {
        freeConnected() -> Color.parseColor("#8ED6A8")
        TunnelBear.hasAccount(this) -> Color.parseColor("#F2A0A0")
        else -> Color.parseColor("#8EC9EE")
    }""",
"""    private fun freeOrbTint(): Int = when {
        freeConnected() -> Color.parseColor("#8ED6A8")
        TunnelBear.hasAccount(this) -> Color.parseColor("#F2A0A0")
        else -> Color.parseColor("#8EC9EE")
    }

    // v1.2 open-orb tint: the SAME state tints as the
    // free orb (same glass texture, ring/glow follow):
    // no profile selected = light blue, profile
    // selected but not connected = light red,
    // connected = light green.
    private fun openOrbTint(): Int = when {
        openConnected() -> Color.parseColor("#8ED6A8")
        selectedProfileObj() != null -> Color.parseColor("#F2A0A0")
        else -> Color.parseColor("#8EC9EE")
    }""")

# ---------- P5b: tint for the open tab ----------
p(A, """        val tint = if (tab == "free") freeOrbTint() else null""",
"""        val tint = if (tab == "free") freeOrbTint() else openOrbTint()""")

# ---------- P5c: chip shows the picked country ----------
p(A, """                countryChip?.visibility = View.VISIBLE
                // v1.1: the chip NEVER shows a provider
                // name — it reads «انتخاب کشور» (the
                // selected country shows in the status
                // line once connected).
                countryChip?.text = T("انتخاب کشور", "Choose country", "Выбрать страну") + "  ▾\"""",
"""                countryChip?.visibility = View.VISIBLE
                // v1.2: the chip NEVER shows a provider
                // name. Until a country is picked it
                // reads «انتخاب کشور»; ONCE picked it
                // shows the selected country's FLAG +
                // NAME (this corrects the v1.1 literal
                // «always انتخاب کشور» reading).
                countryChip?.text = if (TunnelBear.hasSelectedCountry(this))
                    TunnelBear.displayCountry(country) + "  ▾"
                else
                    T("انتخاب کشور", "Choose country", "Выбрать страну") + "  ▾\"""")

# ---------- P6a: logDisplayName helper ----------
p(A, """    // ---------- status + list ----------
    private fun refreshStatus() {""",
"""    // ---------- status + list ----------

    /** Display name for AppLog lines (v1.2): the stored
     *  free-entry name («TunnelBear») NEVER reaches the
     *  log. The free entry logs as «Free OpenVpn config»
     *  + the selected country when one is chosen; own
     *  profiles log with their own name. The RAW stored
     *  name is still kept in appLogOvpnPendingName /
     *  appLogOvpnLastName — recordDial and the failover
     *  check need it — only the LOG TEXT is mapped. */
    private fun logDisplayName(name: String?): String {
        if (name == TunnelBear.SINGLE_NAME) {
            val c = TunnelBear.selectedCountry(this)
            return if (TunnelBear.hasSelectedCountry(this))
                TunnelBear.DISPLAY_NAME + " — " + TunnelBear.displayCountry(c)
            else TunnelBear.DISPLAY_NAME
        }
        return name ?: ""
    }

    private fun refreshStatus() {""")

# ---------- P6b: success log ----------
p(A, """                val dispName = TunnelBear.displayNameForStored(OvpnEngine.activeName)""",
"""                val dispName = logDisplayName(OvpnEngine.activeName)""")

# ---------- P6c: disconnect log ----------
p(A, """                AppLog.append(this, "openvpn",
                    "«$appLogOvpnLastName»؛ قطع شد.",
                    "“$appLogOvpnLastName”; disconnected.",
                    "«$appLogOvpnLastName»; отключён.")""",
"""                AppLog.append(this, "openvpn",
                    "«${logDisplayName(appLogOvpnLastName)}»؛ قطع شد.",
                    "“${logDisplayName(appLogOvpnLastName)}”; disconnected.",
                    "«${logDisplayName(appLogOvpnLastName)}»; отключён.")""")

# ---------- P6d: failure log ----------
p(A, """            AppLog.append(this, "openvpn",
                "«$appLogOvpnPendingName» وصل نشد: ${OvpnEngine.lastError}.",
                "“$appLogOvpnPendingName” did not connect: ${OvpnEngine.lastError}.",
                "«$appLogOvpnPendingName» не подключился: ${OvpnEngine.lastError}.")""",
"""            AppLog.append(this, "openvpn",
                "«${logDisplayName(appLogOvpnPendingName)}» وصل نشد: ${OvpnEngine.lastError}.",
                "“${logDisplayName(appLogOvpnPendingName)}” did not connect: ${OvpnEngine.lastError}.",
                "«${logDisplayName(appLogOvpnPendingName)}» не подключился: ${OvpnEngine.lastError}.")""")

# ---------- P6e: connect-attempt log ----------
p(A, """        AppLog.append(this, "openvpn",
            "تلاش اتصال «${p.name}»؛ اتصال مستقیم.",
            "Connect attempt “${p.name}”; direct connect.",
            "Попытка подключения «${p.name}»; прямое подключение.")""",
"""        AppLog.append(this, "openvpn",
            "تلاش اتصال «${logDisplayName(p.name)}»؛ اتصال مستقیم.",
            "Connect attempt “${logDisplayName(p.name)}”; direct connect.",
            "Попытка подключения «${logDisplayName(p.name)}»; прямое подключение.")""")

# ---------- P9: guide orb paragraph (fa/en/ru) ----------
p(A, """وقتی حساب آماده شد، زیر نوشته «اتصال» داخل خود دایره «انتخاب کشور» را می‌بینی؛ با زدن روی آن، یک کارت کوچک زیر همان دکمه باز می‌شود با فهرست ۴۷ کشور با پرچم.""",
"""وقتی حساب آماده شد، زیر نوشته «اتصال» داخل خود دایره دکمه کشور را می‌بینی — تا وقتی کشوری انتخاب نکرده‌ای «انتخاب کشور» می‌نویسد، و وقتی کشوری انتخاب کنی پرچم و اسم همان کشور را نشان می‌دهد؛ با زدن روی آن، یک کارت کوچک زیر همان دکمه باز می‌شود با فهرست ۴۷ کشور با پرچم.""")
p(A, """Once the account is ready, you see “Choose country” below “Connect” inside the circle itself; tapping it opens a small card right under the button with the list of 47 countries with flags.""",
"""Once the account is ready, you see the country button below “Connect” inside the circle itself — it reads “Choose country” until you pick one, and shows the flag and name of the picked country after that; tapping it opens a small card right under the button with the list of 47 countries with flags.""")
p(A, """Когда аккаунт готов, под словом «Подключить» внутри круга видно «Выбрать страну»; касание открывает небольшую карточку прямо под кнопкой со списком 47 стран с флагами.""",
"""Когда аккаунт готов, под словом «Подключить» внутри круга видна кнопка страны — пока страна не выбрана, там написано «Выбрать страну», а после выбора показываются флаг и название выбранной страны; касание открывает небольшую карточку прямо под кнопкой со списком 47 стран с флагами.""")

# ---------- P7: manifest version + queries ----------
p(M, """    android:versionCode="2"
    android:versionName="1.1">""",
"""    android:versionCode="3"
    android:versionName="1.2">""")
p(M, """    <uses-permission android:name="android.permission.VIBRATE" />
    <application""",
"""    <uses-permission android:name="android.permission.VIBRATE" />
    <!-- v1.2: let the app resolve the Telegram app for
         the About channel link (tg:// + https targeted
         at the Telegram packages) on Android 11+. -->
    <queries>
        <package android:name="org.telegram.messenger" />
        <package android:name="org.thunderdog.challegram" />
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="tg" />
        </intent>
    </queries>
    <application""")

# ---------- P8: build.sh version ----------
p(B, """# Standalone «Openvpn» app (v1.1, code 2) — the OpenVPN section of""",
"""# Standalone «Openvpn» app (v1.2, code 3) — the OpenVPN section of""")
p(B, """    public static final String VERSION_NAME = "1.1";
    public static final int VERSION_CODE = 2;""",
"""    public static final String VERSION_NAME = "1.2";
    public static final int VERSION_CODE = 3;""")

def main():
    for path, old, new in PATCHES:
        with open(path, encoding="utf-8") as f:
            src = f.read()
        n = src.count(old)
        if n != 1:
            print(f"ANCHOR FAIL ({n}x): {path}: {old[:70]!r}")
            sys.exit(1)
        with open(path, "w", encoding="utf-8") as f:
            f.write(src.replace(old, new))
        print(f"OK: {path}: {old[:50]!r}")
    print(f"All {len(PATCHES)} patches applied.")

if __name__ == "__main__":
    main()
