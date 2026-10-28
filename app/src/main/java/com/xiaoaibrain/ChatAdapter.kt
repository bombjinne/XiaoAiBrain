package com.xiaoaibrain

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 聊天气泡适配器（v1.0.16 重写）。
 *
 * 用 ListAdapter + DiffUtil 取代原来"手动 addView / removeAllViews"的方式：
 *  - 视图由 RecyclerView 池化回收，绝不因数据变动而反复销毁重建 → 不会打断输入框焦点 / IME；
 *  - DiffUtil 只追加新增消息，主线程开销极低；
 *  - 头像左右对齐、气泡配色、系统消息居中都在 onBindViewHolder 里按 role 设置，无运行时增删视图。
 */
data class ChatItem(
    val role: String,
    val text: String,
    val time: Long
)

class ChatAdapter : ListAdapter<ChatItem, ChatAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatItem>() {
            override fun areItemsTheSame(a: ChatItem, b: ChatItem): Boolean =
                a.role == b.role && a.text == b.text
            override fun areContentsTheSame(a: ChatItem, b: ChatItem): Boolean = a == b
        }
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatar: ImageView = itemView.findViewById(R.id.ivAvatar)
        val col: LinearLayout = itemView.findViewById(R.id.col)
        val bubble: TextView = itemView.findViewById(R.id.tvBubble)
        val time: TextView = itemView.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = getItem(pos)
        val isSys = m.role == ChatBus.ROLE_SYS
        val isPeer = m.role == ChatBus.ROLE_PEER

        // 头像
        if (isSys) {
            h.avatar.visibility = View.GONE
        } else {
            h.avatar.visibility = View.VISIBLE
            val ai = !isPeer
            h.avatar.setImageResource(if (ai) R.drawable.ic_ai_robot else R.drawable.ic_person)
            h.avatar.setBackgroundResource(if (ai) R.drawable.bg_avatar_ai else R.drawable.bg_avatar_peer)
        }

        // 气泡配色
        when {
            isSys -> {
                h.bubble.setBackgroundResource(R.drawable.bg_bubble_sys)
                h.bubble.setTextColor(0xFF7A7D8A.toInt())
            }
            isPeer -> {
                h.bubble.setBackgroundResource(R.drawable.bg_bubble_peer)
                h.bubble.setTextColor(0xFF15161C.toInt())
            }
            else -> {
                h.bubble.setBackgroundResource(R.drawable.bg_bubble_ai)
                h.bubble.setTextColor(0xFFFFFFFF.toInt())
            }
        }

        // 头像左右对齐 + 气泡列锚定
        val avatarP = h.avatar.layoutParams as RelativeLayout.LayoutParams
        val colP = h.col.layoutParams as RelativeLayout.LayoutParams
        when {
            isSys -> {
                colP.addRule(RelativeLayout.ALIGN_PARENT_START)
                colP.removeRule(RelativeLayout.START_OF)
                colP.removeRule(RelativeLayout.END_OF)
                h.col.gravity = android.view.Gravity.CENTER
            }
            isPeer -> {
                avatarP.addRule(RelativeLayout.ALIGN_PARENT_START)
                avatarP.removeRule(RelativeLayout.ALIGN_PARENT_END)
                colP.addRule(RelativeLayout.END_OF, R.id.ivAvatar)
                colP.removeRule(RelativeLayout.START_OF)
                h.col.gravity = android.view.Gravity.START
            }
            else -> {
                avatarP.addRule(RelativeLayout.ALIGN_PARENT_END)
                avatarP.removeRule(RelativeLayout.ALIGN_PARENT_START)
                colP.addRule(RelativeLayout.START_OF, R.id.ivAvatar)
                colP.removeRule(RelativeLayout.END_OF)
                h.col.gravity = android.view.Gravity.END
            }
        }
        h.avatar.layoutParams = avatarP
        h.col.layoutParams = colP

        h.bubble.text = m.text
        if (isSys) {
            h.time.visibility = View.GONE
        } else {
            h.time.visibility = View.VISIBLE
            h.time.text = fmt.format(java.util.Date(m.time))
        }
    }
}
