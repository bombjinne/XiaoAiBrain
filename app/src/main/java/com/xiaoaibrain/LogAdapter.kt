package com.xiaoaibrain

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * 诊断日志适配器（v1.0.16 新增）。
 *
 * 取代原来"把几百~上千行拼成一个巨大 String 塞进一个 TextView"的做法：
 *  - RecyclerView 只布局/绘制可见行，主线程不再因超长文本做全量 measure/layout；
 *  - 按内容着色（错误红 / 警告橙 / 普通深灰），可读性更好。
 */
class LogAdapter : ListAdapter<String, LogAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String): Boolean = a === b
            override fun areContentsTheSame(a: String, b: String): Boolean = a == b
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tv: TextView = itemView.findViewById(R.id.tvLine)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val line = getItem(pos)
        h.tv.text = line
        h.tv.setTextColor(
            when {
                line.contains("失败") || line.contains("E/") || line.contains("错误") || line.contains("Exception") ->
                    0xFFE5484D.toInt()
                line.contains("W/") || line.contains("警告") ->
                    0xFFE0962B.toInt()
                else -> 0xFF3D4150.toInt()
            }
        )
    }
}
