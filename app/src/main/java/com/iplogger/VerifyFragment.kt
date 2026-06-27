package com.iplogger

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.security.MessageDigest

class VerifyFragment : Fragment() {
    private lateinit var tvResult: TextView
    private lateinit var tvFilePath: TextView
    private val SECRET_KEY = "kg7226kg050309"

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { verifyFile(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_verify, container, false)
        tvResult = view.findViewById(R.id.tvResult)
        tvFilePath = view.findViewById(R.id.tvFilePath)
        view.findViewById<Button>(R.id.btnSelectFile).setOnClickListener {
            filePicker.launch("text/plain")
        }
        return view
    }

    private fun generateHash(data: String): String {
        val raw = "$data$SECRET_KEY"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(raw.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun verifyFile(uri: Uri) {
        try {
            tvFilePath.text = uri.lastPathSegment ?: "선택된 파일"
            val lines = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readLines() ?: return

            val sb = SpannableStringBuilder()
            var ok = 0; var fail = 0; var skip = 0
            val suspicious = mutableListOf<String>()
            var prevCellId = ""; var prevLac = ""; var prevNeighbor = -1

            for (line in lines) {
                if (line.isBlank()) continue
                if (!line.contains(" - ") && !line.contains("[이벤트]") && !line.contains(" | ")) continue
                
                // 이벤트 줄
                if (line.contains("[이벤트]")) {
                    appendColored(sb, "📌 $line\n", Color.parseColor("#ffcc00"))
                    skip++
                    continue
                }

                val parts = line.split(" | ")
                if (parts.size != 3) {
                    appendColored(sb, "⚠️ 형식오류: $line\n", Color.YELLOW)
                    skip++
                    continue
                }

                val (logPart, cellPart, storedHash) = parts
                val tokens = logPart.trim().split(" ")

                try {
                    val time = tokens[0]
                    val network = tokens[1].trim('[', ']')
                    val ip = tokens[3]
                    val logData = "$time[$network]$ip$cellPart"
                    val calcHash = generateHash(logData)

                    if (calcHash == storedHash) {
                        appendColored(sb, "✅ $logPart\n", Color.parseColor("#4ec94e"))
                        ok++
                    } else {
                        appendColored(sb, "❌ 변조됨: $logPart\n", Color.parseColor("#f44747"))
                        fail++
                    }

                    // 의심 패턴 분석
                    for (token in cellPart.split(" ")) {
                        when {
                            token.startsWith("CellID:") -> {
                                val cid = token.substringAfter(":")
                                if (prevCellId.isNotEmpty() && prevCellId != cid)
                                    suspicious.add("[$time] CellID 변경: $prevCellId → $cid")
                                prevCellId = cid
                            }
                            token.startsWith("LAC:") || token.startsWith("TAC:") -> {
                                val lac = token.substringAfter(":")
                                if (prevLac.isNotEmpty() && prevLac != lac)
                                    suspicious.add("[$time] LAC/TAC 변경: $prevLac → $lac")
                                prevLac = lac
                            }
                            token.startsWith("RSRP:") -> {
                                val rsrp = token.replace("RSRP:", "").replace("dBm", "").toIntOrNull()
                                if (rsrp != null && rsrp > -50)
                                    suspicious.add("[$time] 비정상 강한 신호: ${rsrp}dBm")
                            }
                            token.startsWith("인접:") -> {
                                val n = token.replace("인접:", "").replace("개", "").toIntOrNull() ?: -1
                                if (prevNeighbor > 3 && n <= 1)
                                    suspicious.add("[$time] 인접 기지국 급감: ${prevNeighbor}개 → ${n}개")
                                prevNeighbor = n
                            }
                        }
                    }
                } catch (e: Exception) {
                    appendColored(sb, "⚠️ 파싱오류: $line\n", Color.YELLOW)
                    skip++
                }
            }

            // 요약
            appendColored(sb, "\n${"=".repeat(40)}\n", Color.parseColor("#569cd6"))
            appendColored(sb, "정상: $ok  |  변조: $fail  |  기타: $skip\n", Color.parseColor("#569cd6"))
            appendColored(sb, "${"=".repeat(40)}\n", Color.parseColor("#569cd6"))

            // 의심 패턴
            if (suspicious.isNotEmpty()) {
                appendColored(sb, "\n🚨 불법 중계기 의심 패턴:\n", Color.parseColor("#f44747"))
                suspicious.forEach { appendColored(sb, "  $it\n", Color.parseColor("#f44747")) }
            } else {
                appendColored(sb, "\n✅ 불법 중계기 의심 패턴 없음\n", Color.parseColor("#4ec94e"))
            }

            tvResult.text = sb

        } catch (e: Exception) {
            tvResult.text = "오류: ${e.message}"
        }
    }

    private fun appendColored(sb: SpannableStringBuilder, text: String, color: Int) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, 0)
    }
}
