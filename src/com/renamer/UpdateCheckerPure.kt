package com.renamer

/** 1.73 (worker C): the «بررسی بروزرسانی» update checker — pure
 *  logic, no Android imports, so it is JVM-testable standalone
 *  (see tests/TestMain173C.kt).
 *
 *  SOURCE OF TRUTH (READ ONLY): the public GitHub repo for this app
 *  lineage, kamororo/Openvpn — https://api.github.com/repos/kamororo/
 *  Openvpn/releases/latest. The checker only READS that endpoint;
 *  it never publishes, never changes the repo, never pushes.
 *  CAVEAT (2026-11-21): the latest published release there is v1.5,
 *  OLDER than the 1.73 being built, so until a newer release is
 *  published the checker correctly reports «برنامه شما اخرین ورژن
 *  هست». Publishing newer releases is a separate step, handled
 *  outside this checker (user order 2026-11-21 ~04:25: publish each
 *  delivered update to GitHub starting with 1.73).
 *
 *  RULES the checker lives by:
 *   - A download is offered ONLY when the release actually HAS an
 *     .apk asset (browser_download_url). A newer tag with no .apk
 *     is reported as "latest" — never a broken download.
 *   - Version compare is semantic on the versionName (tags with or
 *     without a "v" prefix, "v1.5" vs "1.73"); a malformed tag is
 *     never treated as newer. The releases API carries no
 *     versionCode, so versionCode is not part of the comparison.
 *   - A network/API failure is an error, never an update and never
 *     a "latest": the auto-check then fails SILENTLY, the manual
 *     check shows a brief error only.
 *   - The "latest" message shows for EXACTLY 4 seconds
 *     (LATEST_MESSAGE_MS) — a timed dismiss, then gone.
 *
 *  The release JSON is parsed by a small hand-rolled JSON parser
 *  (objects / arrays / strings / numbers / true / false / null) so
 *  the pure logic needs no org.json on the JVM; the Android side
 *  (UpdateChecker.kt) uses this same parser, so the tested code IS
 *  the shipped code. */
object UpdateCheckerPure {

    // ---------- source of truth ----------
    const val REPO_OWNER = "kamororo"
    const val REPO_NAME = "Openvpn"
    const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/kamororo/Openvpn/releases/latest"
    const val GITHUB_API_ACCEPT = "application/vnd.github+json"
    const val USER_AGENT = "InternetAzad2026"

    // ---------- timeouts / storage ----------
    /** The releases check is one small JSON fetch, not a download. */
    const val CHECK_TIMEOUT_MS = 15000
    /** An APK download may take a while on a slow link. */
    const val DOWNLOAD_TIMEOUT_MS = 30000
    /** APKs land in filesDir/<UPDATE_DIR_NAME>/ (private). */
    const val UPDATE_DIR_NAME = "update"
    /** The APK-serving provider's authority suffix (the full
     *  authority is "<package>.apkprovider"). */
    const val PROVIDER_AUTHORITY_SUFFIX = ".apkprovider"
    /** The "latest" message is shown for exactly 4 seconds. */
    const val LATEST_MESSAGE_MS = 4000L
    /** The install intent's MIME type for an APK. */
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    /** One published release, as the checker sees it. apkUrl is null
     *  when the release has no .apk asset — then no download is ever
     *  offered (see hasApk). */
    data class ReleaseInfo(
        val tag: String,
        val name: String,
        val apkName: String?,
        val apkUrl: String?,
        val apkSize: Long?
    ) {
        val hasApk: Boolean get() = !apkUrl.isNullOrBlank() && !apkName.isNullOrBlank()
        /** The version as shown to the user ("v1.74" -> "1.74"). */
        val displayVersion: String get() = displayVersion(tag)
    }

    /** "v1.74" -> "1.74"; "1.74" stays "1.74". */
    fun displayVersion(tag: String): String {
        val t = tag.trim()
        return if (t.startsWith("v", ignoreCase = true)) t.substring(1) else t
    }

    // ---------- version compare ----------

    /** "v1.74" / "1.74" / "1.74-beta" -> [1, 74]. A "-suffix" or
     *  "+build" tail is ignored (a beta of 1.74 is still 1.74 for
     *  the "is there something newer" question). Null when the tag
     *  has no usable numeric core at all ("", "abc", "v", "1.x",
     *  "1..2") — a malformed tag is never treated as newer. */
    fun parseVersion(tag: String?): List<Int>? {
        val t = tag?.trim().orEmpty()
        if (t.isBlank()) return null
        val noPrefix = if (t.startsWith("v", ignoreCase = true)) t.substring(1) else t
        val core = noPrefix.substringBefore('-').substringBefore('+').trim()
        if (core.isBlank()) return null
        val parts = core.split('.')
        val nums = ArrayList<Int>(parts.size)
        for (p in parts) {
            if (p.isEmpty() || !p.all { it.isDigit() }) return null
            nums += p.toIntOrNull() ?: return null
        }
        return nums
    }

    /** -1 / 0 / 1, like a comparator; null when either side is
     *  malformed. Missing segments count as 0, so "1.73" == "1.73.0"
     *  and "1.10" > "1.9" (numeric, never lexicographic). */
    fun compareVersions(a: String, b: String): Int? {
        val pa = parseVersion(a) ?: return null
        val pb = parseVersion(b) ?: return null
        val n = maxOf(pa.size, pb.size)
        for (k in 0 until n) {
            val va = pa.getOrElse(k) { 0 }
            val vb = pb.getOrElse(k) { 0 }
            if (va != vb) return if (va < vb) -1 else 1
        }
        return 0
    }

    /** True only when the latest tag is strictly newer than the
     *  installed versionName. Malformed on either side -> false
     *  (never offer an update the checker cannot prove). */
    fun isUpdateAvailable(currentVersionName: String, latestTag: String): Boolean =
        compareVersions(latestTag, currentVersionName) == 1

    // ---------- download progress ----------

    /** Bytes -> percent (0..100). -1 when the total is unknown
     *  (no Content-Length and no asset size) — the UI then shows an
     *  indeterminate bar instead of a fake number. */
    fun percent(downloaded: Long, total: Long): Int {
        if (total <= 0) return -1
        if (downloaded < 0) return 0
        return ((downloaded.toDouble() / total.toDouble()) * 100.0)
            .toInt().coerceIn(0, 100)
    }

    /** A file name safe to put under filesDir/update/: only
     *  [A-Za-z0-9._-], everything else becomes '_', and the ".apk"
     *  suffix is forced — so a hostile asset name can never escape
     *  the update dir ("../evil.apk" -> ".._evil.apk"). Blank ->
     *  "update.apk". */
    fun sanitizeApkName(name: String?): String {
        val raw = name?.trim().orEmpty()
        if (raw.isBlank()) return "update.apk"
        val cleaned = StringBuilder()
        for (c in raw) {
            cleaned.append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
        }
        var out = cleaned.toString().trim('.')
        if (out.isBlank()) return "update.apk"
        if (!out.endsWith(".apk", ignoreCase = true)) out += ".apk"
        return out
    }

    // ---------- release JSON parsing ----------

    /** Parse a /releases/latest response. Null when the body is not
     *  a release at all (garbage, an API error like the rate-limit
     *  {"message": ...}, or a release with no tag_name). The .apk
     *  asset is the FIRST asset whose name ends in ".apk" (the
     *  v1.5 release has exactly one, "Openvpn.apk"); other assets
     *  (.zip/.tar.gz source archives) are ignored. */
    fun parseRelease(json: String): ReleaseInfo? {
        val root = try {
            JsonParser(json).parse() as? Map<*, *> ?: return null
        } catch (_: Exception) {
            return null
        }
        val tag = (root["tag_name"] as? String)?.trim().orEmpty()
        if (tag.isBlank()) return null
        val name = (root["name"] as? String)?.trim().orEmpty().ifBlank { tag }
        var apkName: String? = null
        var apkUrl: String? = null
        var apkSize: Long? = null
        val assets = root["assets"] as? List<*>
        if (assets != null) {
            for (a in assets) {
                val m = a as? Map<*, *> ?: continue
                val n = (m["name"] as? String)?.trim().orEmpty()
                if (n.isNullOrBlank() || !n.endsWith(".apk", ignoreCase = true)) continue
                val u = (m["browser_download_url"] as? String)?.trim().orEmpty()
                if (u.isNullOrBlank()) continue
                apkName = n
                apkUrl = u
                apkSize = (m["size"] as? Number)?.toLong()
                break
            }
        }
        return ReleaseInfo(tag, name, apkName, apkUrl, apkSize)
    }

    /** Minimal JSON parser: objects, arrays, strings (with escapes
     *  incl. \uXXXX), numbers, true/false/null. Throws on malformed
     *  input; callers turn that into null. */
    private class JsonParser(private val s: String) {
        private var i = 0

        fun parse(): Any? {
            skipWs()
            val v = value()
            skipWs()
            return v
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun value(): Any? {
            if (i >= s.length) throw IllegalStateException("eof")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> {
                    expect("true")
                    true
                }
                'f' -> {
                    expect("false")
                    false
                }
                'n' -> {
                    expect("null")
                    null
                }
                else -> num()
            }
        }

        private fun expect(w: String) {
            if (!s.startsWith(w, i)) throw IllegalStateException("bad literal")
            i += w.length
        }

        private fun obj(): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalStateException("no colon")
                i++
                skipWs()
                m[k] = value()
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> {
                        i++
                        continue
                    }
                    i < s.length && s[i] == '}' -> {
                        i++
                        return m
                    }
                    else -> throw IllegalStateException("bad object")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return l
            }
            while (true) {
                skipWs()
                l += value()
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> {
                        i++
                        continue
                    }
                    i < s.length && s[i] == ']' -> {
                        i++
                        return l
                    }
                    else -> throw IllegalStateException("bad array")
                }
            }
        }

        private fun str(): String {
            // s[i] == '"'
            i++
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        return out.toString()
                    }
                    c == '\\' && i + 1 < s.length -> {
                        when (val n = s[i + 1]) {
                            '"', '\\', '/' -> out.append(n)
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (i + 5 >= s.length) throw IllegalStateException("bad \\u")
                                out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                                i += 4
                            }
                            else -> {
                                out.append('\\')
                                out.append(n)
                            }
                        }
                        i += 2
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            throw IllegalStateException("unterminated string")
        }

        private fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            if (i == start) throw IllegalStateException("bad number")
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull()
                ?: throw IllegalStateException("bad number")
        }
    }
}
