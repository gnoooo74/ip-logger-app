package com.iplogger

import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LogFragment : Fragment() {
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var scrollView: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val autoRefresh = object : Runnable {
        override fun run() {
            loadLog()
            handler.postDelayed(this, 30_000) // 30초마다 자동 새로고침
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_log, container, false)
        tvLog = view.findViewById(R.id.tvLog)
        tvStatus = view.findViewById(R.id.tvStatus)
        scrollView = view.findViewById(R.id.scrollView)
        view.findViewById<Button>(R.id.btnRefresh).setOnClickListener { loadLog() }
        return view
    }

    override fun onResume() {
        super.onResume()
        handler.post(autoRefresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoRefresh)
    }

    private fun loadLog() {
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ip_logs/$dateStr.txt"
            )
            if (file.exists()) {
                val content = file.readText()
                tvLog.text = content
                tvStatus.text = "● 로깅 중..."
                tvStatus.setTextColor(android.graphics.Color.parseColor("#4ec94e"))
                // 맨 아래로 스크롤
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            } else {
                tvLog.text = "아직 로그가 없어요. 잠시 후 새로고침 해주세요."
            }
        } catch (e: Exception) {
            tvLog.text = "로그 읽기 오류: ${e.message}"
        }
    }
}
