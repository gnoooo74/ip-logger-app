package com.iplogger

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * 저장된 날짜별 로그 파일에서 ip / cellid / pci / lac(=TAC 포함) 조건을
 * AND 로 조합해 검색하는 화면. 기존 로깅(IpLogWorker)이 쓰는 것과
 * 동일한 Downloads/ip_logs 폴더를 읽기 전용으로만 사용한다.
 */
class FilterFragment : Fragment() {

    private lateinit var spinnerDate: Spinner
    private lateinit var etIp: EditText
    private lateinit var etCellId: EditText
    private lateinit var etPci: EditText
    private lateinit var etLac: EditText
    private lateinit var tvCount: TextView
    private lateinit var recyclerView: RecyclerView
    private val adapter = LogRowAdapter(emptyList())

    private val logDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ip_logs"
        )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_filter, container, false)

        spinnerDate = view.findViewById(R.id.spinnerDate)
        etIp = view.findViewById(R.id.etIp)
        etCellId = view.findViewById(R.id.etCellId)
        etPci = view.findViewById(R.id.etPci)
        etLac = view.findViewById(R.id.etLac)
        tvCount = view.findViewById(R.id.tvCount)
        recyclerView = view.findViewById(R.id.recyclerViewResults)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        recyclerView.adapter = adapter

        loadDateList()

        view.findViewById<Button>(R.id.btnSearch).setOnClickListener { runFilter() }
        view.findViewById<Button>(R.id.btnClear).setOnClickListener {
            etIp.setText("")
            etCellId.setText("")
            etPci.setText("")
            etLac.setText("")
            runFilter()
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        loadDateList()
    }

    /** ip_logs 폴더 안의 yyyy-MM-dd.txt 파일들을 최신순으로 스피너에 채운다. */
    private fun loadDateList() {
        val prevSelection = spinnerDate.selectedItem as? String

        val files = logDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        if (files.isEmpty()) {
            spinnerDate.adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, listOf("(저장된 로그 없음)")
            )
            tvCount.text = "저장된 로그 파일이 없어요."
            adapter.update(emptyList())
            return
        }

        val names = files.map { it.name.removeSuffix(".txt") }
        spinnerDate.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, names
        )

        val restoreIndex = names.indexOf(prevSelection)
        spinnerDate.setSelection(if (restoreIndex >= 0) restoreIndex else 0)
    }

    private fun runFilter() {
        val dateName = spinnerDate.selectedItem as? String
        if (dateName.isNullOrBlank() || dateName == "(저장된 로그 없음)") {
            tvCount.text = "선택 가능한 로그 파일이 없어요."
            adapter.update(emptyList())
            return
        }

        val file = File(logDir, "$dateName.txt")
        if (!file.exists()) {
            tvCount.text = "파일을 찾을 수 없어요: $dateName.txt"
            adapter.update(emptyList())
            return
        }

        val ipQuery = etIp.text.toString().trim()
        val cellIdQuery = etCellId.text.toString().trim()
        val pciQuery = etPci.text.toString().trim()
        val lacQuery = etLac.text.toString().trim()
        val hasFilter = listOf(ipQuery, cellIdQuery, pciQuery, lacQuery).any { it.isNotEmpty() }

        val matched = mutableListOf<String>()

        try {
            file.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEachLine

                // 안내 헤더 등 로그/이벤트 줄이 아닌 것은 제외 ("HH:mm:ss"로 시작하는지 확인)
                val isLogLine = line.length >= 8 &&
                    line[2] == ':' && line[5] == ':' &&
                    line.substring(0, 8).all { it.isDigit() || it == ':' }
                if (!isLogLine) return@forEachLine

                if (line.contains("[이벤트]")) {
                    // 이벤트 줄엔 ip/cell 정보가 없으므로, 필터가 걸려 있으면 제외
                    if (!hasFilter) matched.add(line)
                    return@forEachLine
                }

                val parts = line.split(" | ")
                if (parts.size != 3) return@forEachLine

                if (!hasFilter) {
                    matched.add(line)
                    return@forEachLine
                }

                val (logPart, cellPart, _) = parts
                val ip = logPart.trim().split(" ").getOrNull(3) ?: ""
                val cellTokens = cellPart.split(" ")

                fun tokenValue(prefix: String): String? =
                    cellTokens.firstOrNull { it.startsWith(prefix) }?.substringAfter(":")

                val cellId = tokenValue("CellID:")
                val pci = tokenValue("PCI:")
                // 5G(NR)는 TAC 라벨, LTE/3G는 LAC 라벨을 씀 -> LAC 필터는 TAC도 함께 매칭
                val lac = tokenValue("LAC:") ?: tokenValue("TAC:")

                fun match(query: String, value: String?): Boolean =
                    query.isEmpty() || (value != null && value.contains(query, ignoreCase = true))

                if (match(ipQuery, ip) &&
                    match(cellIdQuery, cellId) &&
                    match(pciQuery, pci) &&
                    match(lacQuery, lac)
                ) {
                    matched.add(line)
                }
            }
        } catch (e: Exception) {
            tvCount.text = "로그 읽기 오류: ${e.message}"
            adapter.update(emptyList())
            return
        }

        adapter.update(matched)
        tvCount.text = if (matched.isEmpty()) {
            "일치하는 로그가 없어요 ($dateName.txt)"
        } else {
            "${matched.size}건 검색됨 ($dateName.txt)"
        }
    }
}
