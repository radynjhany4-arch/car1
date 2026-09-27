package ir.divarwatcher.app

import android.content.Context

object Prefs {
    private const val NAME = "divar_watcher_prefs"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_INTERVAL = "interval"
    private const val KEY_RUNNING = "running"
    private const val KEY_SEEN = "seen_tokens"

    fun save(context: Context, endpoint: String, payload: String, interval: Int) {
        prefs(context).edit()
            .putString(KEY_ENDPOINT, endpoint)
            .putString(KEY_PAYLOAD, payload)
            .putInt(KEY_INTERVAL, interval)
            .apply()
    }

    fun endpoint(context: Context): String =
        prefs(context).getString(KEY_ENDPOINT, "https://api.divar.ir/v8/postlist/w/search") ?: ""

    fun payload(context: Context): String = prefs(context).getString(KEY_PAYLOAD, "") ?: ""

    fun interval(context: Context): Int = prefs(context).getInt(KEY_INTERVAL, 60)

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_RUNNING, running).apply()
    }

    fun isRunning(context: Context): Boolean = prefs(context).getBoolean(KEY_RUNNING, false)

    // Stored as an ordered, comma-joined string (not a Set<String> pref) because
    // SharedPreferences string-sets do not guarantee insertion order on read-back,
    // which would make "drop the oldest" trimming below effectively random.
    fun seenTokens(context: Context): LinkedHashSet<String> {
        val raw = prefs(context).getString(KEY_SEEN, "") ?: ""
        return if (raw.isBlank()) LinkedHashSet() else LinkedHashSet(raw.split(","))
    }

    fun addSeenTokens(context: Context, tokens: Collection<String>) {
        val ordered = seenTokens(context)
        for (t in tokens) {
            ordered.remove(t) // re-insert at the end so recently-seen-again tokens count as fresh
            ordered.add(t)
        }
        val trimmed =
            if (ordered.size > 3000) LinkedHashSet(ordered.toList().takeLast(3000)) else ordered
        prefs(context).edit().putString(KEY_SEEN, trimmed.joinToString(",")).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
