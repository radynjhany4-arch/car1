package ir.divarwatcher.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs as a foreground service (required by Android for ongoing background network work)
 * and, on a loop, POSTs the user-supplied search payload to the user-supplied endpoint,
 * looks for post objects in the (unofficial, undocumented) response, and fires a
 * notification for any post token it has not seen before.
 */
class MonitorService : Service() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var running = false
    private var notifyIdCounter = 1000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        Prefs.setRunning(this, true)
        startForeground(FOREGROUND_ID, buildStatusNotification("در حال بررسی..."))
        thread(start = true) { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Prefs.setRunning(this, false)
        super.onDestroy()
    }

    private fun loop() {
        val endpoint = Prefs.endpoint(this)
        val payload = Prefs.payload(this)
        val intervalMs = Prefs.interval(this).coerceAtLeast(20) * 1000L

        while (running) {
            try {
                val posts = fetchPosts(endpoint, payload)
                val seen = Prefs.seenTokens(this)
                val firstRun = seen.isEmpty()
                val fresh = posts.filter { it.token !in seen }

                // On the very first run we only record what already exists, so the app
                // doesn't blast a notification for every ad currently matching the filter.
                if (!firstRun) {
                    for (post in fresh) notifyNewPost(post)
                }

                Prefs.addSeenTokens(this, posts.map { it.token }.toSet())
                updateStatusNotification(
                    "آخرین بررسی موفق • ${posts.size} آگهی در نتیجه • ${fresh.size} مورد جدید"
                )
            } catch (e: Exception) {
                updateStatusNotification("خطا در بررسی: ${e.message}")
            }
            Thread.sleep(intervalMs)
        }
    }

    private fun fetchPosts(endpoint: String, payload: String): List<Post> {
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) DivarWatcher/1.0")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val text = response.body?.string() ?: throw IllegalStateException("پاسخ خالی بود")
            val json = JSONObject(text)
            val posts = LinkedHashMap<String, Post>()
            scan(json, posts)
            return posts.values.toList()
        }
    }

    // Recursively walks the response looking for objects that carry a "token" field
    // (Divar's per-ad identifier), and grabs nearby title/description/url fields from
    // the same object. This is deliberately schema-tolerant since the endpoint is
    // undocumented and its exact field layout can change without notice.
    private fun scan(node: Any?, out: LinkedHashMap<String, Post>) {
        when (node) {
            is JSONObject -> {
                val token = node.optString("token", "")
                if (token.length in 6..24 && token.all { it.isLetterOrDigit() }) {
                    val title = firstNonEmpty(node, "title", "name")
                    val sub = firstNonEmpty(
                        node,
                        "middle_description_text",
                        "top_description_text",
                        "bottom_description_text",
                        "subtitle"
                    )
                    val relUrl = findRelativeUrl(node)
                    if (!out.containsKey(token)) out[token] = Post(token, title, sub, relUrl)
                }
                val keys = node.keys()
                while (keys.hasNext()) scan(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) scan(node.opt(i), out)
        }
    }

    private fun findRelativeUrl(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val direct = node.optString("url", "")
                if (direct.startsWith("/v/")) return direct
                val keys = node.keys()
                while (keys.hasNext()) {
                    val r = findRelativeUrl(node.opt(keys.next()))
                    if (r != null) return r
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val r = findRelativeUrl(node.opt(i))
                if (r != null) return r
            }
        }
        return null
    }

    private fun firstNonEmpty(obj: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = obj.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun notifyNewPost(post: Post) {
        ensureChannels()
        val url = post.relativeUrl?.let { "https://divar.ir$it" }
            ?: "https://divar.ir/v/-/${post.token}"
        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pending = PendingIntent.getActivity(
            this, notifyIdCounter, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(post.title ?: "آگهی جدید در دیوار")
            .setContentText(post.subtitle ?: "برای مشاهده لمس کنید")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(notifyIdCounter++, notification)
    }

    private fun buildStatusNotification(text: String): Notification {
        ensureChannels()
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("پایش دیوار فعال است")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateStatusNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(FOREGROUND_ID, buildStatusNotification(text))
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "وضعیت پایش", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "آگهی‌های جدید", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private data class Post(
        val token: String,
        val title: String?,
        val subtitle: String?,
        val relativeUrl: String?
    )

    companion object {
        const val FOREGROUND_ID = 1
        const val CHANNEL_STATUS = "status_channel"
        const val CHANNEL_ALERTS = "alerts_channel"
    }
}
