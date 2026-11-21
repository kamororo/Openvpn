package com.renamer

/** 1.76 worker B — Cloudflare page state across a proxy creation.
 *
 *  The bug (user report 2026-11-21 ~15:51 Tehran): after building
 *  a free v2ray account on this page, creating a free Telegram
 *  proxy made the v2ray configs DISAPPEAR (only the proxy was
 *  shown), the green tick next to "make v2ray" turned into a
 *  cross, and the proxy-only box looked too tall.
 *
 *  Root cause (verified in CloudflareActivity.kt): the proxy
 *  dialog takes its new-account branch whenever "cf_accounts"
 *  is empty — which it is for every user who first opened the
 *  page (any 1.99+ build) before doing step 1: migrateAccounts()
 *  writes cf_accounts="[]" for a blank email, and no step-1 path
 *  ever appends the account afterwards, because
 *  saveWorkingSetToAccount() silently returns when the list is
 *  empty. proxyBuildFromNewAccount() then built a FRESH EMPTY
 *  account snapshot (results [], bg_done false) and
 *  loadAccountToWorkingSet() loaded it over the live working
 *  set — overwriting the cf_results + cf_bg_done prefs, so
 *  loadResults() cleared the in-memory configs and
 *  refreshStepKeys() painted the cross. The loss was permanent:
 *  the next snapshot save stored the empty state.
 *
 *  The fix, decided here and wired into the Activity:
 *    - a proxy creation changes ONLY the proxy fields. The
 *      v2ray configs (same list, same order, same count) and
 *      the bg_done tick are carried over verbatim
 *      (applyProxyCreated).
 *    - the account created by a proxy-first build KEEPS the
 *      live working set when it is the SAME account (same
 *      email as the working set — the usual case: the user
 *      re-enters the email/key they built v2ray with). When
 *      it is a genuinely different account, the live working
 *      set is first recorded as its own account so nothing is
 *      lost when the page switches to the new empty one.
 *    - box order is always proxy ABOVE v2ray when both exist
 *      (boxOrder); the proxy box's composed height depends
 *      ONLY on the proxy link (how many lines it wraps to,
 *      capped at the card's maxLines = 2) — never on the
 *      v2ray list — so the proxy-only box is exactly as tall
 *      as the both-made box (proxyBoxHeightDp).
 *
 *  Pure logic (no Android imports), JVM-testable standalone
 *  (see TestMain176B.kt). The Activity maps this decision onto
 *  its JSONObject snapshots and its render pass; it never
 *  re-adds configs after the fact. */
object CloudflarePagePure {

    /** The page state a proxy creation must never disturb:
     *  the built v2ray configs (link + country code, display
     *  order), the step-1 green tick, and the proxy link/worker. */
    data class PageState(
        val configs: List<Pair<String, String>>,
        val bgDone: Boolean,
        val proxyLink: String,
        val proxyWorker: String
    )

    enum class PageBox { PROXY, V2RAY }

    /** The proxy-creation event: only the proxy fields change.
     *  Configs (same list, same order, same count) and the tick
     *  are carried over verbatim — a proxy creation can neither
     *  drop a config nor flip the tick. */
    fun applyProxyCreated(before: PageState, worker: String, link: String): PageState =
        before.copy(proxyWorker = worker, proxyLink = link)

    /** Top-to-bottom box order on the page. The proxy box is
     *  always ABOVE the v2ray box whenever both exist — the
     *  earlier behaviour ("عین قبلا"). */
    fun boxOrder(state: PageState): List<PageBox> =
        (if (state.proxyLink.isNotBlank()) listOf(PageBox.PROXY) else emptyList()) +
            (if (state.configs.isNotEmpty()) listOf(PageBox.V2RAY) else emptyList())

    /** Whether the account created by a proxy-first build keeps
     *  the live working set: yes when it is the SAME account
     *  (same email as the working set). A blank working-set
     *  email never keeps it — there is no account to keep. */
    fun newAccountKeepsWorkingSet(workingSetEmail: String, newEmail: String): Boolean =
        workingSetEmail.isNotBlank() &&
            workingSetEmail.equals(newEmail, ignoreCase = true)

    /** Whether a live working set holds anything worth
     *  preserving when the new account is a DIFFERENT account:
     *  built configs, the green tick, or panel state. */
    fun workingSetHasState(state: PageState, workingSetEmail: String, panelUrl: String): Boolean =
        workingSetEmail.isNotBlank() &&
            (state.configs.isNotEmpty() || state.bgDone || panelUrl.isNotBlank())

    // ---- Proxy-box composed height (dp) ----
    // The box as built by renderProxyBox + proxyCard: the outer
    // card's padding + title row + one inner proxy card (icon
    // row + link lines + button row) + the inner card's bottom
    // margin. Every term mirrors the code's own dp values
    // (proxyResultsCard padding 6+6, 30dp trash circle + 6dp
    // title padding, proxyCard padding 5+5, tightRow padding
    // 2+2 + 22dp icon, 12sp link text, miniButton row, 4dp
    // card margin). The ONLY variable term is the link's line
    // count — so the height cannot differ between the
    // proxy-only and the both-made states for the same link.
    // The absolute dp is a model of the composition; the
    // invariant pinned here (and in the Activity wiring) is
    // the EQUALITY across v2ray states, not the pixel value.
    private const val OUTER_PADDING_DP = 12 // proxyResultsCard dp(6) top + bottom
    private const val TITLE_ROW_DP = 36 // 30dp trash circle + dp(6) bottom padding
    private const val INNER_PADDING_DP = 10 // proxyCard dp(5) top + bottom
    private const val ICON_ROW_DP = 26 // tightRow dp(2)+dp(2) + 22dp icon
    private const val LINK_LINE_DP = 14 // 12sp line height
    private const val LINK_TEXT_PADDING_DP = 4 // link TextView dp(2) top + bottom
    private const val BUTTON_ROW_DP = 32 // tightRow dp(2)+dp(2) + miniButton row
    private const val INNER_CARD_MARGIN_DP = 4 // proxyCard bottom margin dp(4)
    const val PROXY_LINK_MAX_LINES = 2 // proxyCard link maxLines
    const val PROXY_LINK_CHARS_PER_LINE = 48 // ~308dp card width at 12sp

    /** Wrapped line count of the proxy link, capped at the
     *  card's maxLines (a long tg:// link wraps to 2 lines). */
    fun proxyLinkLines(link: String): Int {
        if (link.isBlank()) return 0
        val lines = (link.length + PROXY_LINK_CHARS_PER_LINE - 1) / PROXY_LINK_CHARS_PER_LINE
        return minOf(PROXY_LINK_MAX_LINES, maxOf(1, lines))
    }

    /** Composed height of the proxy box in dp; 0 when no proxy
     *  is built (the box is GONE). Depends only on the proxy
     *  link — never on the v2ray configs. */
    fun proxyBoxHeightDp(state: PageState): Int {
        if (state.proxyLink.isBlank()) return 0
        return OUTER_PADDING_DP + TITLE_ROW_DP + INNER_PADDING_DP +
            ICON_ROW_DP + LINK_TEXT_PADDING_DP +
            proxyLinkLines(state.proxyLink) * LINK_LINE_DP +
            BUTTON_ROW_DP + INNER_CARD_MARGIN_DP
    }
}
