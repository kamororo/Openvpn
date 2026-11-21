package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.*

/** Imported Profile page (v1.12 restyle, item 8): the
 *  app's own theme — day pearl aurora / night #070912
 *  aurora page background, glass-card fields, themed
 *  buttons (no navy header, no orange button). Fields
 *  and behaviour are unchanged: Profile Name
 *  (auto-filled, editable), Server Hostname
 *  (locked, from the .ovpn remote line), Username,
 *  a "Save password" checkbox, Password with an
 *  eye toggle, and at the bottom PROFILES (save
 *  and go back) and CONNECT (save and connect). */
class OpenVpnImportActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profile = OvpnStore.pending ?: run { finish(); return }
        val isEdit = OvpnStore.pendingIndex >= 0

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OvpnTheme.pageBackground(this@OpenVpnImportActivity)
        }
        // Header (dp, see below)
        val density0 = resources.displayMetrics.density
        fun dp0(v: Int): Int = (v * density0).toInt()
        // v1.12: the navy header is gone — the page background
        // (day pearl aurora / night #070912 aurora) runs under
        // the title, like the rest of the app (item 8).
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp0(24), dp0(20), dp0(24), dp0(20))
        }
        header.addView(TextView(this).apply {
            text = "←"; textSize = 26f
            setTextColor(OvpnTheme.fg(this@OpenVpnImportActivity))
            setPadding(0, 0, dp0(24), 0)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = if (isEdit) L("ویرایش پروفایل", "Edit Profile") else L("پروفایل واردشده", "Imported Profile")
            textSize = 26f
            setTextColor(OvpnTheme.fg(this@OpenVpnImportActivity))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(header)

        // All paddings in dp, not raw pixels: on a
        // high-density phone 32px is only ~12dp, which
        // is why the fields looked cramped and ran
        // into each other in v1.23.
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        fun label(text: String): TextView {
            return TextView(this).apply {
                this.text = text; textSize = 15f
                setTextColor(OvpnTheme.subFg(this@OpenVpnImportActivity))
                setPadding(0, dp(28), 0, dp(6))
            }
        }
        fun spacer(): View {
            return View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(14))
            }
        }
        fun field(initial: String, enabled: Boolean = true): EditText {
            return EditText(this).apply {
                setText(initial)
                textSize = 19f
                setTextColor(OvpnTheme.fg(this@OpenVpnImportActivity))
                isEnabled = enabled
                minHeight = dp(56)
                background = OvpnTheme.glassCard(this@OpenVpnImportActivity, 12f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
        }
        body.addView(label(L("نام پروفایل", "Profile Name")))
        val nameField = field(profile.name)
        body.addView(nameField)
        body.addView(spacer())

        body.addView(label(L("نام سرور (قفل‌شده)", "Server Hostname (locked)")))
        // Locked: shown, not editable, like the screenshot.
        val hostField = field(profile.hostname, enabled = false)
        body.addView(hostField)
        body.addView(spacer())

        val userLabel = label(L("نام کاربری", "Username"))
        userLabel.setTextColor(Color.parseColor("#999999"))
        val userField = field(profile.username)
        userField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                userLabel.setTextColor(if (s.isNullOrBlank()) Color.parseColor("#999999")
                    else OvpnTheme.accent(this@OpenVpnImportActivity))
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        if (profile.username.isNotBlank()) userLabel.setTextColor(OvpnTheme.accent(this@OpenVpnImportActivity))
        body.addView(userLabel)
        body.addView(userField)
        body.addView(spacer())

        val saveBox = CheckBox(this).apply {
            text = L("ذخیره‌ی رمز", "Save password")
            isChecked = profile.savePassword
            textSize = 18f
            setTextColor(OvpnTheme.subFg(this@OpenVpnImportActivity))
            setPadding(0, dp(24), 0, dp(12))
        }
        body.addView(saveBox)

        val passLabel = label(L("رمز عبور", "Password"))
        passLabel.setTextColor(Color.parseColor("#999999"))
        val passRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = OvpnTheme.glassCard(this@OpenVpnImportActivity, 12f)
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        val passField = EditText(this).apply {
            setText(profile.password)
            textSize = 19f
            setTextColor(OvpnTheme.fg(this@OpenVpnImportActivity))
            minHeight = dp(56)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(12), 0, dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        passField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                passLabel.setTextColor(if (s.isNullOrBlank()) Color.parseColor("#999999")
                    else OvpnTheme.accent(this@OpenVpnImportActivity))
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        if (profile.password.isNotBlank()) passLabel.setTextColor(OvpnTheme.accent(this@OpenVpnImportActivity))
        passRow.addView(passField)
        var showPass = false
        passRow.addView(TextView(this).apply {
            text = "👁"; textSize = 22f
            setTextColor(Color.parseColor("#999999"))
            setPadding(dp(16), 0, 0, 0)
            setOnClickListener {
                showPass = !showPass
                passField.inputType = if (showPass) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                passField.transformationMethod = if (showPass) null
                    else PasswordTransformationMethod.getInstance()
                passField.setSelection(passField.text.length)
                text = if (showPass) "👁" else "👁"
            }
        })
        body.addView(passLabel)
        body.addView(passRow)
        body.addView(spacer())

        // Bottom buttons: PROFILES (glass) and CONNECT
        // (themed teal→violet gradient).
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(48), 0, 0)
        }
        bottom.addView(Button(this).apply {
            text = L("پروفایل‌ها", "PROFILES"); textSize = 17f
            setTextColor(OvpnTheme.fg(this@OpenVpnImportActivity))
            background = OvpnTheme.glassCard(this@OpenVpnImportActivity, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                saveAndFinish(nameField.text.toString(), userField.text.toString(),
                    passField.text.toString(), saveBox.isChecked, connect = false)
            }
        })
        bottom.addView(Button(this).apply {
            text = L("اتصال", "CONNECT"); textSize = 17f
            setTextColor(Color.WHITE)
            background = OvpnTheme.tabBackground(this@OpenVpnImportActivity, true)
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                saveAndFinish(nameField.text.toString(), userField.text.toString(),
                    passField.text.toString(), saveBox.isChecked, connect = true)
            }
        })
        body.addView(bottom)
        root.addView(body)
        scroll.addView(root)
        setContentView(scroll)
        // Safe area: keep content clear of the status bar and
        // the navigation bar, via insets (never fitsSystemWindows).
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            try {
                root.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            } catch (_: Exception) {}
            insets
        }
    }

    private fun saveAndFinish(name: String, user: String, pass: String, savePass: Boolean, connect: Boolean) {
        val profile = OvpnStore.pending ?: run { finish(); return }
        val finalName = name.trim().ifBlank { profile.name }
        profile.name = finalName
        profile.username = user.trim()
        profile.password = pass
        profile.savePassword = savePass
        val profiles = OvpnStore.load(this)
        val idx = OvpnStore.pendingIndex
        if (idx >= 0 && idx < profiles.size) {
            // 2.91: a renamed profile keeps its dial history —
            // Monitoring counts OVPN health from ovpn_dial_ok /
            // ovpn_dial_time, keyed by profile name. Move the
            // keys so a rename does not orphan the record.
            val oldName = profiles[idx].name
            profiles[idx] = profile
            if (oldName != finalName) {
                val p = getSharedPreferences("renamer_ovpn", MODE_PRIVATE)
                val e = p.edit()
                if (p.contains("ovpn_dial_ok_$oldName")) e.putBoolean("ovpn_dial_ok_$finalName", p.getBoolean("ovpn_dial_ok_$oldName", false))
                if (p.contains("ovpn_dial_time_$oldName")) e.putLong("ovpn_dial_time_$finalName", p.getLong("ovpn_dial_time_$oldName", 0))
                if (p.contains("ovpn_dial_err_$oldName")) e.putString("ovpn_dial_err_$finalName", p.getString("ovpn_dial_err_$oldName", null))
                e.remove("ovpn_dial_ok_$oldName").remove("ovpn_dial_time_$oldName").remove("ovpn_dial_err_$oldName").apply()
            }
        } else {
            profiles += profile
        }
        // If Save password is off, do not persist the
        // password; the profile is still saved, and
        // connecting will ask for it again.
        if (!savePass) profile.password = ""
        OvpnStore.save(this, profiles)
        OvpnStore.pending = null
        OvpnStore.pendingIndex = -1
        if (connect) {
            // OpenVpnActivity must own VpnService.prepare,
            // so hand the profile back and let it
            // connect on resume.
            OvpnStore.pendingConnect = profile.copy(password = pass)
        }
        finish()
    }
}
