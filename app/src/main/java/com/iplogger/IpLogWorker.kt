package com.iplogger

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.work.*
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class IpLogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        return try {
            logIpNow(applicationContext)

            val next = OneTimeWorkRequestBuilder<IpLogWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(applicationContext).enqueue(next)

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private var lastLogTime = 0L
        private var lastLogIp = ""

        private const val SECRET_KEY = "kg7226kg050309"

        fun generateHash(time: String, network: String, ip: String): String {
            val raw = "$time$network$ip$SECRET_KEY"
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(raw.toByteArray())
            return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
        }

        fun logIpNow(context: Context) {
            try {
                val ip = URL("https://api.ipify.org").readText()
                val now = System.currentTimeMillis()

                // 10초 이내 같은 IP면 중복 기록 안 함
                if (ip == lastLogIp && now - lastLogTime < 10_000) return

                lastLogTime = now
                lastLogIp = ip

                val date = Date(now)
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
                val networkType = getNetworkType(context)
                val hash = generateHash(timeStr, networkType, ip)

                val dir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ), "ip_logs"
                )
                dir.mkdirs()
                File(dir, "$dateStr.txt").appendText("$timeStr [$networkType] - $ip | $hash\n")
            } catch (e: Exception) {
                // 실패 시 무시
            }
        }

        fun getNetworkType(context: Context): String {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "없음"
            val caps = cm.getNetworkCapabilities(network) ?: return "없음"

            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    when (tm.dataNetworkType) {
                        TelephonyManager.NETWORK_TYPE_NR -> "5G"
                        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                        TelephonyManager.NETWORK_TYPE_UMTS,
                        TelephonyManager.NETWORK_TYPE_HSDPA -> "3G"
                        TelephonyManager.NETWORK_TYPE_EDGE,
                        TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                        else -> "Cellular"
                    }
                }
                else -> "없음"
            }
        }
    }
}
