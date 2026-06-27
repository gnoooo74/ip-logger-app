package com.iplogger

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.*
import androidx.work.*
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
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

        fun generateHash(data: String): String {
            val raw = "$data$SECRET_KEY"
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(raw.toByteArray())
            return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
        }

        fun triggerAlert(context: Context) {
            try {
                // 진동
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                        as android.os.VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                }
        
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createOneShot(
                            500, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
                }
        
                // 알림음
                val notification = android.media.RingtoneManager.getDefaultUri(
                    android.media.RingtoneManager.TYPE_NOTIFICATION
                )
                val ringtone = android.media.RingtoneManager.getRingtone(context, notification)
                ringtone.play()
        
            } catch (e: Exception) {
                // 무시
            }
        }

        fun getCellInfo(context: Context): String {
            return try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val cellInfoList = tm.allCellInfo ?: return "셀정보없음"

                val sb = StringBuilder()
                var neighborCount = 0

                for (cellInfo in cellInfoList) {
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        cellInfo is CellInfoNr && cellInfo.isRegistered -> {
                            val id = cellInfo.cellIdentity as CellIdentityNr
                            val sig = cellInfo.cellSignalStrength as CellSignalStrengthNr
                            sb.append("CellID:${id.nci} PCI:${id.pci} TAC:${id.tac} MNC:${id.mncString} RSRP:${sig.ssRsrp}dBm")
                        }
                        cellInfo is CellInfoLte && cellInfo.isRegistered -> {
                            val id = cellInfo.cellIdentity
                            val sig = cellInfo.cellSignalStrength
                            sb.append("CellID:${id.ci} PCI:${id.pci} LAC:${id.tac} MNC:${id.mncString} RSRP:${sig.rsrp}dBm")
                        }
                        cellInfo is CellInfoWcdma && cellInfo.isRegistered -> {
                            val id = cellInfo.cellIdentity
                            val sig = cellInfo.cellSignalStrength
                            sb.append("CellID:${id.cid} LAC:${id.lac} MNC:${id.mncString} RSSI:${sig.dbm}dBm")
                        }
                        !cellInfo.isRegistered -> neighborCount++
                    }
                }

                if (sb.isEmpty()) sb.append("셀정보없음")
                sb.append(" 인접:${neighborCount}개")
                sb.toString()

            } catch (e: Exception) {
                "셀정보오류"
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

        fun logIpNow(context: Context) {
            try {
                val ip = URL("https://api.ipify.org").readText()
                val now = System.currentTimeMillis()

                if (ip == lastLogIp && now - lastLogTime < 10_000) return

                lastLogTime = now
                lastLogIp = ip

                val date = Date(now)
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
                val networkType = getNetworkType(context)
                val cellInfo = getCellInfo(context)
                if (cellInfo.contains("인접:0개")) {
                    triggerAlert(context)
                }
                val logData = "$timeStr[$networkType]$ip$cellInfo"
                val hash = generateHash(logData)

                val dir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ), "ip_logs"
                )
                dir.mkdirs()

                val file = File(dir, "$dateStr.txt")

                // 새 파일이면 헤더 추가
                if (!file.exists()) {
                    file.writeText(
                        """
================================================================
IP 로그 파일 - $dateStr
================================================================
[항목 설명]
시간        : 기록 시각 (HH:mm:ss)
통신망      : WiFi / 5G / LTE / 3G / 2G
IP          : 공인 외부 IP 주소
CellID      : 기지국 고유 ID (갑자기 바뀌면 의심)
PCI         : 물리적 셀 ID (0~503, 불법중계기는 비정상값)
LAC/TAC     : 위치 지역 코드 (같은 지역에서 변하면 의심)
MNC         : 통신사 코드 (SKT:11 KT:08 LGU+:06, 변하면 의심)
RSRP        : 신호 세기 (dBm, -80이상:양호 -100이하:불량)
              불법중계기는 비정상적으로 강한 신호(-40~-50) 송출
인접        : 주변 기지국 수 (갑자기 줄면 의심 - 신호 재밍)
HASH        : 위변조 검증값
================================================================
[불법 중계기 의심 패턴]
- 위치 변화 없는데 CellID/LAC 갑자기 변경
- RSRP 신호가 갑자기 매우 강해짐 (-45dBm 이하)
- 인접 기지국 수가 갑자기 1~2개로 줄어듦
- MNC 값이 변경됨
- LTE에서 2G/3G로 강제 다운그레이드
================================================================

                        """.trimIndent()
                    )
                }

                // 파일 잠금 후 기록
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = FileChannel.open(
                        file.toPath(),
                        StandardOpenOption.APPEND,
                        StandardOpenOption.CREATE
                    )
                    val lock = channel.lock()
                    try {
                        channel.write(
                            ByteBuffer.wrap(
                                "$timeStr [$networkType] - $ip | $cellInfo | $hash\n".toByteArray()
                            )
                        )
                    } finally {
                        lock.release()
                        channel.close()
                    }
                } else {
                    file.appendText("$timeStr [$networkType] - $ip | $cellInfo | $hash\n")
                }

            } catch (e: Exception) {
                // 무시
            }
        }

        fun logEvent(context: Context, event: String) {
            try {
                val now = Date()
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)

                val dir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ), "ip_logs"
                )
                dir.mkdirs()
                File(dir, "$dateStr.txt").appendText("$timeStr [이벤트] - $event\n")
            } catch (e: Exception) {
                // 무시
            }
        }
    }
}
