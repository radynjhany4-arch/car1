package ir.divarwatcher.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ir.divarwatcher.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etEndpoint.setText(Prefs.endpoint(this))
        binding.etPayload.setText(Prefs.payload(this))
        binding.etInterval.setText(Prefs.interval(this).toString())
        refreshStatus()

        binding.btnStart.setOnClickListener {
            val endpoint = binding.etEndpoint.text.toString().trim()
            val payload = binding.etPayload.text.toString().trim()
            val interval = binding.etInterval.text.toString().toIntOrNull()?.coerceAtLeast(20) ?: 60

            if (endpoint.isBlank() || payload.isBlank()) {
                binding.tvStatus.text = "وضعیت: آدرس و بدنه‌ی JSON را وارد کنید"
                return@setOnClickListener
            }

            Prefs.save(this, endpoint, payload, interval)
            requestNotificationPermissionIfNeeded()
            startForegroundService(Intent(this, MonitorService::class.java))
            Prefs.setRunning(this, true) // service sets this too, but do it now so the label is accurate immediately
            refreshStatus()
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, MonitorService::class.java))
            Prefs.setRunning(this, false)
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        binding.tvStatus.text = if (Prefs.isRunning(this)) "وضعیت: در حال اجرا" else "وضعیت: متوقف"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }
}
