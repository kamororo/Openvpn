package com.renamer

import android.content.Context

/** App language, chosen in the v2ray Settings section.
 *  Stored in renamer_settings, key "app_language":
 *  "fa" or "en". When nothing is stored yet (first
 *  launch), the phone's language decides: a Persian
 *  phone gets Persian, anything else gets English.
 *  Once the user picks a language in the app, that
 *  choice wins from then on. t() returns the Persian
 *  string by default, the English one when English is
 *  selected. Applied to the v2ray page, the home menu
 *  and the emergency page; other sections keep their
 *  current strings and can be moved to t() later. */
object AppLanguage {
    fun isEnglish(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
        if (sp.contains("app_language")) {
            return sp.getString("app_language", "fa") == "en"
        }
        // First launch: follow the phone's language.
        return java.util.Locale.getDefault().language != "fa"
    }

    /** "fa", "en" or "ru". Stored choice wins; on first
     *  launch the phone language decides (fa phone ->
     *  fa, anything else -> en), as in isEnglish(). */
    fun lang(ctx: Context): String {
        return try {
            val sp = ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
            if (sp.contains("app_language")) {
                sp.getString("app_language", "fa")?.takeIf { it in setOf("fa", "en", "ru") } ?: "fa"
            } else if (java.util.Locale.getDefault().language == "fa") "fa" else "en"
        } catch (_: Exception) { "fa" }
    }

    fun isRussian(ctx: Context): Boolean = lang(ctx) == "ru"

    fun t(ctx: Context, fa: String, en: String): String {
        return when (lang(ctx)) {
            "en" -> en
            "ru" -> RU[en] ?: en
            else -> fa
        }
    }

    fun t3(ctx: Context, fa: String, en: String, ru: String): String {
        return when (lang(ctx)) {
            "en" -> en
            "ru" -> ru
            else -> fa
        }
    }

    fun set(ctx: Context, lang: String) {
        ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
            .edit().putString("app_language", lang).apply()
    }

    // ---------- 2.90: v2ray-only language ----------
    // The v2ray Settings language button used to write the
    // GLOBAL app_language, so picking English there turned
    // the WHOLE app English. v2ray now has its own pref,
    // "v2ray_language"; when nothing is stored yet it falls
    // back to the global choice, so existing users keep
    // their language until they change it in v2ray.
    fun v2rayIsEnglish(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
        if (sp.contains("v2ray_language")) {
            return sp.getString("v2ray_language", "fa") == "en"
        }
        return isEnglish(ctx)
    }

    fun v2rayT(ctx: Context, fa: String, en: String): String {
        return if (v2rayIsEnglish(ctx)) en else fa
    }

    fun setV2rayLang(ctx: Context, lang: String) {
        ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
            .edit().putString("v2ray_language", lang).apply()
    }

    /** Russian for every static user-visible English
     *  string in the app (keys are the exact English
     *  strings passed to t()). Country names stay
     *  as-is. */
    val RU: Map<String, String> = mapOf(
        "Please wait a moment" to "Пожалуйста, подождите",
        "Disconnected" to "Отключено",
        "My profiles" to "Мои профили",
        "No profile yet. Tap the + button to import an .ovpn file." to
            "Профилей пока нет. Нажмите +, чтобы импортировать файл .ovpn.",
        "First import an .ovpn file with the + button" to
            "Сначала импортируйте файл .ovpn кнопкой +",
        "Get" to "Получить",
        "Free account" to "Бесплатный аккаунт",
        "Connecting" to "Подключение",
        "Stop" to "Стоп",
        "Connect" to "Подключить",
        "Connected — " to "Подключено — ",
        "Tap the circle to connect" to "Нажмите на круг, чтобы подключиться",
        "No profile selected" to "Профиль не выбран",
        "Connected" to "Подключено",
        "Connected to " to "Подключено к ",
        "Connecting to “" to "Подключение к «",
        "is connected" to "подключён",
        "Turn on your VPN to create a free account" to
            "Включите свой VPN, чтобы создать бесплатный аккаунт",
        "Account ready — pick the country from inside the circle" to
            "Аккаунт готов — выберите страну внутри круга",
        "Connected to profile “" to "Подключено к профилю «",
        "No profile yet" to "Профилей пока нет",
        "Profile “" to "Профиль «",
        "” selected — tap the circle to connect" to
            "» выбран — нажмите на круг, чтобы подключиться",
        "Choose country — 47 countries with flags" to
            "Выберите страну — 47 стран с флагами",
        "Cancel" to "Отмена",
        "Delete profile" to "Удалить профиль",
        "Profile \"" to "Профиль «",
        "\" will be deleted." to "» будет удалён.",
        "Delete" to "Удалить",
        "Please do not close this page" to
            "Пожалуйста, не закрывайте эту страницу",
        "Account created and verified; you can connect now" to
            "Аккаунт создан и подтверждён; теперь можно подключаться",
        "Account creation failed" to "Не удалось создать аккаунт",
        "Try again" to "Попробовать снова",
        "No log yet. Connect once first." to
            "Журнал пуст. Сначала подключитесь.",
        "OpenVPN connection log" to "Журнал подключений OpenVPN",
        "Copy log" to "Скопировать журнал",
        "Log copied" to "Журнал скопирован",
        "Clear" to "Очистить",
        "Close" to "Закрыть",
        "Save password" to "Сохранить пароль",
        "Server needs username/password" to
            "Сервер требует имя пользователя и пароль",
        "The connection is waiting for username/password. Enter them to reconnect." to
            "Подключение ждёт имя пользователя и пароль. Введите их, чтобы переподключиться.",
        "USERPASS (username/password only)" to
            "USERPASS (только имя пользователя и пароль)",
        "USERPASS_CERTIFICATES (username/password + certificate)" to
            "USERPASS_CERTIFICATES (имя пользователя, пароль и сертификат)",
        "CERTIFICATES (certificate only)" to
            "CERTIFICATES (только сертификат)",
        "No <cert> block in file.\n" to "В файле нет блока <cert>.\n",
        "\n--- Engine diagnosis ---\n" to "\n--- Диагностика движка ---\n",
        "Engine: OpenVPN3 (like the official app) — the .ovpn file is used verbatim." to
            "Движок: OpenVPN3 (как в официальном приложении) — файл .ovpn используется как есть.",
        "Profile diagnosis" to "Диагностика профиля",
        "OpenVPN guide" to "Руководство по OpenVPN",
        "Not tested yet." to "Ещё не проверено.",
        "First circle green: the server answered a full TLS handshake." to
            "Первый круг зелёный: сервер ответил полным TLS-рукопожатием.",
        "First circle red: the server answered TCP but cut the TLS handshake." to
            "Первый круг красный: сервер ответил по TCP, но оборвал TLS-рукопожатие.",
        "First circle red: the server did not answer at all." to
            "Первый круг красный: сервер вообще не ответил.",
        "First circle grey: this profile uses tls-auth or tls-crypt, and its server stays silent by design; only tapping connect gives the answer." to
            "Первый круг серый: этот профиль использует tls-auth или tls-crypt, и его сервер молчит по замыслу; ответ даст только нажатие кнопки подключения.",
        "The first circle comes from the UDP test (this profile is UDP-only)." to
            "Первый круг — по UDP-тесту (этот профиль только UDP).",
        "First circle grey: not tested yet." to
            "Первый круг серый: ещё не проверено.",
        "Second circle green: the username and password were accepted and the server assigned an IP — it will connect." to
            "Второй круг зелёный: имя пользователя и пароль приняты, сервер выдал IP — подключение удастся.",
        "Second circle red: the server rejected the username or password." to
            "Второй круг красный: сервер отклонил имя пользователя или пароль.",
        "Second circle red: the server accepted but never sent an address." to
            "Второй круг красный: сервер принял данные, но не прислал адрес.",
        "Second circle red: the server did not finish the key exchange." to
            "Второй круг красный: сервер не завершил обмен ключами.",
        "Second circle red: the session did not complete." to
            "Второй круг красный: сессия не завершилась.",
        "Second circle grey: this profile needs a username and password, but none is saved; enter them in the profile edit first." to
            "Второй круг серый: этому профилю нужны имя пользователя и пароль, но они не сохранены; сначала введите их в редактировании профиля.",
        "Second circle grey: the session has not been tested yet." to
            "Второй круг серый: сессия ещё не проверена.",
        "Never connected directly" to "Прямого подключения ещё не было",
        "failed" to "не удалось",
        "Both dots — session test" to "Обе точки — тест сессии",
        "Last real connect" to "Последнее реальное подключение",
        "First green means the server answered a full TLS handshake; second green means the username and password were accepted and the server assigned an address — tap this profile and it will connect. Grey means not tested yet, or the server stays silent by design (tls-auth). Red means the server did not answer or the session was rejected." to
            "Первый зелёный — сервер ответил полным TLS-рукопожатием; второй зелёный — имя пользователя и пароль приняты и сервер выдал адрес: нажмите на этот профиль, и он подключится. Серый — ещё не проверено или сервер молчит по замыслу (tls-auth). Красный — сервер не ответил или сессия отклонена.",
        "Choose an OpenVPN file" to "Выберите файл OpenVPN",
        "Invalid file" to "Неверный файл",
        "This is not an OpenVPN profile; it must be an .ovpn file with a remote line." to
            "Это не профиль OpenVPN: нужен файл .ovpn со строкой remote.",
        "This profile was already added." to "Этот профиль уже добавлен.",
        "\" added." to "» добавлен.",
        "Reading the file failed: " to "Не удалось прочитать файл: ",
        "Failed to connect: " to "Не удалось подключиться: ",
        "Disconnected while connecting" to "Отключено во время подключения",
        "VPN permission denied" to "Разрешение на VPN отклонено",
        "Account creation started — VPN is up" to
            "Создание аккаунта начато — VPN включён",
        "Account creation failed. The service did not accept the temporary emails. Try again." to
            "Не удалось создать аккаунт. Сервис не принял временные адреса. Попробуйте снова.",
        "Account creation failed. Try again." to
            "Не удалось создать аккаунт. Попробуйте снова.",
        "Getting a temporary email failed. Try again." to
            "Не удалось получить временный адрес. Попробуйте снова.",
        "Could not reach the service. Try again." to
            "Не удалось связаться с сервисом. Попробуйте снова.",
        "The service did not accept the temporary email. Try again to get a fresh one." to
            "Сервис не принял временный адрес. Попробуйте снова, чтобы получить новый.",
        "Checking the email failed. Try again." to
            "Не удалось проверить адрес. Попробуйте снова.",
        "The service's confirmation email did not arrive (the temporary address may have expired). Try again." to
            "Письмо с подтверждением не пришло (временный адрес мог истечь). Попробуйте снова.",
        "Account creation failed (response " to "Не удалось создать аккаунт (ответ ",
        // ---- v1.1 strings ----
        "App log" to "Журнал",
        "Guide" to "Руководство",
        "Day / night" to "День / ночь",
        "App language" to "Язык приложения",
        "About" to "О приложении",
        "Language" to "Язык",
        "Choose country" to "Выбрать страну",
        "Made by: Kamyar" to "Разработчик: Камяр",
        "Telegram channel and contact: " to "Telegram-канал и связь: ",
        "No profile selected — tap a profile" to
            "Профиль не выбран — нажмите на профиль",
        "Profile Name" to "Имя профиля",
        "Server Hostname (locked)" to "Имя сервера (заблокировано)",
        "Username" to "Имя пользователя",
        "Password" to "Пароль",
        "Edit Profile" to "Редактировать профиль",
        "Imported Profile" to "Импортированный профиль",
        "PROFILES" to "ПРОФИЛИ",
        "CONNECT" to "ПОДКЛЮЧИТЬ",
        "Day" to "День",
        "Night" to "Ночь",
        "OpenVPN Profile" to "Профиль OpenVPN",
        "Server Hostname (locked)" to "Имя сервера (заблокировано)",
        "Auto — follows the phone" to "Авто — как в телефоне",
        "Free account created; tap the circle to connect" to
            "Бесплатный аккаунт создан; нажмите на круг, чтобы подключиться",
        "Backup account ready" to "Резервный аккаунт готов",
        "Switched to the backup account" to
            "Переключено на резервный аккаунт",
        "The free account is almost used up; switching to the backup account" to
            "Лимит бесплатного аккаунта почти исчерпан; переключение на резервный аккаунт"
    )
}
