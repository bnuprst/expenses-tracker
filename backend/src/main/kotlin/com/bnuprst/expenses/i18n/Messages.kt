package com.bnuprst.expenses.i18n

/**
 * User-facing API messages. English is the default and the fallback for any missing key, so a client that
 * sends no Accept-Language sees exactly the text it saw before localization.
 */
object Messages {
    const val DEFAULT = "en"
    private val supported = setOf("en", "uk", "pl")

    private val en = mapOf(
        "error.generic" to "Error",
        "error.bad_request" to "Bad request",
        "error.internal" to "Internal server error",
        "error.unauthorized" to "Unauthorized",
        "error.forbidden" to "Forbidden",
        "error.not_found" to "Not found",
        "error.bank_provider" to "Bank provider error: %s",
        "auth.invalid_email" to "Invalid email",
        "auth.password_too_short" to "Password must be at least 8 characters",
        "auth.name_required" to "Name is required",
        "auth.email_taken" to "Email already registered",
        "auth.invalid_credentials" to "Invalid email or password",
        "wallet.name_required" to "Name is required",
        "wallet.currency_invalid" to "Currency must be an ISO 4217 code",
        "wallet.owner_only_members" to "Only wallet owners can add members",
        "wallet.user_not_found" to "No user with that email; they need to register first",
        "wallet.member_exists" to "User is already a member",
        "wallet.not_found" to "Wallet not found",
        "bank.unknown" to "Unknown bank '%s' in %s",
        "bank.connection_not_found" to "Connection not found",
        "bank.not_configured" to "Bank sync is not configured",
        "bank.sync_running" to "A sync is already running for this connection",
        "bank.connection_status" to "Connection is %s",
        "bank.consent_expired" to "Consent expired; re-authorise the connection",
        "bank.rate_limit_scheduled" to "The bank's daily limit on automatic refreshes was reached; they resume tomorrow. Sync now still works.",
        "bank.rate_limit_user" to "The bank is refusing requests for now (rate limit); try again later.",
        "bank.status.ACTIVE" to "Active",
        "bank.status.PENDING" to "Pending",
        "bank.status.EXPIRED" to "Expired",
        "bank.status.ERROR" to "Error",
        "txn.invalid_status" to "Invalid status",
        "txn.invalid_category" to "Invalid category",
        "txn.invalid_sort" to "Invalid sort",
        "txn.invalid_param" to "Invalid %s",
        "txn.invalid_date" to "Invalid date: %s",
        "txn.invalid_date_format" to "Invalid date, expected yyyy-MM-dd",
        "txn.range_inverted" to "from must not be after to",
        "txn.invalid_amount" to "Invalid amount",
        "txn.description_required" to "Description is required",
        "txn.not_found" to "Transaction not found",
        "txn.unknown_category" to "Unknown category for this wallet",
        "import.owner_only" to "Only wallet owners can import",
        "import.batch_too_large" to "At most %s transactions per request",
        "import.invalid_date" to "Invalid date '%s' in %s",
        "import.invalid_amount" to "Invalid amount in %s",
        "import.source_required" to "source is required",
        "import.invalid_source" to "Invalid source",
        "rule.value_required" to "Value is required",
        "rule.amount_ops" to "Amount rules support GT, LT, EQUALS",
        "rule.amount_number" to "Amount value must be a number",
        "rule.gt_lt_amount_only" to "GT/LT only apply to AMOUNT",
        "rule.invalid_regex" to "Invalid regex",
    )

    private val uk = mapOf(
        "error.generic" to "Помилка",
        "error.bad_request" to "Хибний запит",
        "error.internal" to "Внутрішня помилка сервера",
        "error.unauthorized" to "Не авторизовано",
        "error.forbidden" to "Доступ заборонено",
        "error.not_found" to "Не знайдено",
        "error.bank_provider" to "Помилка банківського провайдера: %s",
        "auth.invalid_email" to "Некоректна електронна пошта",
        "auth.password_too_short" to "Пароль має містити щонайменше 8 символів",
        "auth.name_required" to "Вкажіть ім'я",
        "auth.email_taken" to "Ця електронна пошта вже зареєстрована",
        "auth.invalid_credentials" to "Невірна пошта або пароль",
        "wallet.name_required" to "Вкажіть назву",
        "wallet.currency_invalid" to "Валюта має бути кодом ISO 4217",
        "wallet.owner_only_members" to "Лише власник гаманця може додавати учасників",
        "wallet.user_not_found" to "Користувача з такою поштою немає; спершу він має зареєструватися",
        "wallet.member_exists" to "Користувач уже є учасником",
        "wallet.not_found" to "Гаманець не знайдено",
        "bank.unknown" to "Невідомий банк «%s» у %s",
        "bank.connection_not_found" to "Підключення не знайдено",
        "bank.not_configured" to "Синхронізацію з банком не налаштовано",
        "bank.sync_running" to "Для цього підключення вже триває синхронізація",
        "bank.connection_status" to "Стан підключення: %s",
        "bank.consent_expired" to "Термін згоди минув; авторизуйте підключення знову",
        "bank.rate_limit_scheduled" to "Досягнуто денний ліміт банку на автоматичні оновлення; вони відновляться завтра. Кнопка «Синхронізувати зараз» працює.",
        "bank.rate_limit_user" to "Банк наразі відхиляє запити (ліміт); спробуйте пізніше.",
        "bank.status.ACTIVE" to "Активне",
        "bank.status.PENDING" to "Очікує",
        "bank.status.EXPIRED" to "Протерміноване",
        "bank.status.ERROR" to "Помилка",
        "txn.invalid_status" to "Некоректний статус",
        "txn.invalid_category" to "Некоректна категорія",
        "txn.invalid_sort" to "Некоректне сортування",
        "txn.invalid_param" to "Некоректний параметр %s",
        "txn.invalid_date" to "Некоректна дата: %s",
        "txn.invalid_date_format" to "Некоректна дата, очікується yyyy-MM-dd",
        "txn.range_inverted" to "Початок періоду не може бути пізніше за кінець",
        "txn.invalid_amount" to "Некоректна сума",
        "txn.description_required" to "Вкажіть опис",
        "txn.not_found" to "Транзакцію не знайдено",
        "txn.unknown_category" to "Невідома категорія для цього гаманця",
        "import.owner_only" to "Лише власник гаманця може імпортувати",
        "import.batch_too_large" to "Не більше ніж %s транзакцій за запит",
        "import.invalid_date" to "Некоректна дата «%s» у %s",
        "import.invalid_amount" to "Некоректна сума в %s",
        "import.source_required" to "Потрібен параметр source",
        "import.invalid_source" to "Некоректне джерело",
        "rule.value_required" to "Вкажіть значення",
        "rule.amount_ops" to "Правила для суми підтримують GT, LT, EQUALS",
        "rule.amount_number" to "Значення суми має бути числом",
        "rule.gt_lt_amount_only" to "GT/LT застосовні лише до AMOUNT",
        "rule.invalid_regex" to "Некоректний регулярний вираз",
    )

    private val pl = mapOf(
        "error.generic" to "Błąd",
        "error.bad_request" to "Nieprawidłowe żądanie",
        "error.internal" to "Wewnętrzny błąd serwera",
        "error.unauthorized" to "Brak autoryzacji",
        "error.forbidden" to "Brak dostępu",
        "error.not_found" to "Nie znaleziono",
        "error.bank_provider" to "Błąd dostawcy bankowego: %s",
        "auth.invalid_email" to "Nieprawidłowy e-mail",
        "auth.password_too_short" to "Hasło musi mieć co najmniej 8 znaków",
        "auth.name_required" to "Imię jest wymagane",
        "auth.email_taken" to "Ten e-mail jest już zarejestrowany",
        "auth.invalid_credentials" to "Nieprawidłowy e-mail lub hasło",
        "wallet.name_required" to "Nazwa jest wymagana",
        "wallet.currency_invalid" to "Waluta musi być kodem ISO 4217",
        "wallet.owner_only_members" to "Tylko właściciel portfela może dodawać członków",
        "wallet.user_not_found" to "Nie ma użytkownika z tym adresem e-mail; musi najpierw się zarejestrować",
        "wallet.member_exists" to "Użytkownik jest już członkiem",
        "wallet.not_found" to "Nie znaleziono portfela",
        "bank.unknown" to "Nieznany bank „%s” w %s",
        "bank.connection_not_found" to "Nie znaleziono połączenia",
        "bank.not_configured" to "Synchronizacja z bankiem nie jest skonfigurowana",
        "bank.sync_running" to "Dla tego połączenia trwa już synchronizacja",
        "bank.connection_status" to "Stan połączenia: %s",
        "bank.consent_expired" to "Zgoda wygasła; autoryzuj połączenie ponownie",
        "bank.rate_limit_scheduled" to "Osiągnięto dzienny limit banku na automatyczne odświeżanie; wznowi się jutro. Przycisk „Synchronizuj teraz” nadal działa.",
        "bank.rate_limit_user" to "Bank odrzuca teraz żądania (limit); spróbuj później.",
        "bank.status.ACTIVE" to "Aktywne",
        "bank.status.PENDING" to "Oczekuje",
        "bank.status.EXPIRED" to "Wygasłe",
        "bank.status.ERROR" to "Błąd",
        "txn.invalid_status" to "Nieprawidłowy status",
        "txn.invalid_category" to "Nieprawidłowa kategoria",
        "txn.invalid_sort" to "Nieprawidłowe sortowanie",
        "txn.invalid_param" to "Nieprawidłowy parametr %s",
        "txn.invalid_date" to "Nieprawidłowa data: %s",
        "txn.invalid_date_format" to "Nieprawidłowa data, oczekiwano yyyy-MM-dd",
        "txn.range_inverted" to "Początek okresu nie może być późniejszy niż koniec",
        "txn.invalid_amount" to "Nieprawidłowa kwota",
        "txn.description_required" to "Opis jest wymagany",
        "txn.not_found" to "Nie znaleziono transakcji",
        "txn.unknown_category" to "Nieznana kategoria w tym portfelu",
        "import.owner_only" to "Tylko właściciel portfela może importować",
        "import.batch_too_large" to "Maksymalnie %s transakcji na żądanie",
        "import.invalid_date" to "Nieprawidłowa data „%s” w %s",
        "import.invalid_amount" to "Nieprawidłowa kwota w %s",
        "import.source_required" to "Parametr source jest wymagany",
        "import.invalid_source" to "Nieprawidłowe źródło",
        "rule.value_required" to "Wartość jest wymagana",
        "rule.amount_ops" to "Reguły kwoty obsługują GT, LT, EQUALS",
        "rule.amount_number" to "Wartość kwoty musi być liczbą",
        "rule.gt_lt_amount_only" to "GT/LT dotyczą tylko AMOUNT",
        "rule.invalid_regex" to "Nieprawidłowe wyrażenie regularne",
    )

    val catalogues = mapOf("en" to en, "uk" to uk, "pl" to pl)

    /**
     * The best supported language of an Accept-Language header: highest q-value wins, `q=0` is a refusal,
     * regional tags fall back to their base language, ties keep header order.
     */
    fun language(acceptLanguage: String?): String {
        val ranked = acceptLanguage.orEmpty().split(',').mapNotNull { part ->
            val pieces = part.split(';').map { it.trim() }
            val tag = pieces.firstOrNull()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val q = pieces.drop(1).firstOrNull { it.startsWith("q=") }?.removePrefix("q=")?.toDoubleOrNull() ?: 1.0
            if (q <= 0.0) null else tag.substringBefore('-') to q
        }
        return ranked.filter { it.first in supported }.maxByOrNull { it.second }?.first ?: DEFAULT
    }

    fun format(key: String, args: List<Any?> = emptyList(), language: String = DEFAULT): String {
        val pattern = catalogues[language]?.get(key) ?: en[key] ?: return key
        return if (args.isEmpty()) pattern else String.format(pattern, *args.toTypedArray())
    }

    /** A connection status in the request's language, for interpolation into bank.connection_status. */
    fun status(status: String, language: String = DEFAULT): String = format("bank.status.$status", language = language)
}
