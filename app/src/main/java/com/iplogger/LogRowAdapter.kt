package com.iplogger

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 필터링된 로그 줄을 row 단위로 보여주는 어댑터. */
class LogRowAdapter(private var rows: List<String>) :
    RecyclerView.Adapter<LogRowAdapter.RowViewHolder>() {

    class RowViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    fun update(newRows: List<String>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_row, parent, false) as TextView
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.textView.text = rows[position]
    }

    override fun getItemCount() = rows.size
}
