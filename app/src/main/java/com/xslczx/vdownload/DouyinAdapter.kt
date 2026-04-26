package com.xslczx.vdownload

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.SizeUtils
import com.blankj.utilcode.util.ToastUtils
import com.blankj.utilcode.util.UriUtils
import com.bumptech.glide.Glide
import com.xslczx.vdownload.databse.DouyinVideo
import java.io.File

class DouyinAdapter(
    private val list: MutableList<DouyinVideo>,
    private val onItemClick: ((DouyinAdapter, Int, DouyinVideo, Boolean) -> Unit)? = null,
    private val onImageDownload: ((Media) -> Unit)? = null,
) : RecyclerView.Adapter<DouyinAdapter.ViewHolder>() {

    fun deleteItem(position: Int) {
        list.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, list.size)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setNewData(data: List<DouyinVideo>) {
        list.clear()
        list.addAll(data)
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val tvTips: TextView = view.findViewById(R.id.tv_tips)
        val imageContainer: LinearLayout = view.findViewById(R.id.imageContainer)
        val container: LinearLayout = view.findViewById(R.id.container)
        val scrollView: HorizontalScrollView = view.findViewById(R.id.scrollView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_douyin, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        //holder.tvTips.isVisible = position==0
        holder.title.text = item.title
        holder.container.setOnClickListener {
            onItemClick?.invoke(this, holder.layoutPosition, item, false)
        }
        holder.container.setOnLongClickListener {
            onItemClick?.invoke(this, holder.layoutPosition, item, true)
            return@setOnLongClickListener true
        }
        holder.imageContainer.removeAllViews()
        val h = SizeUtils.dp2px(100f)
        val imagePaths = item.savedImagePaths?.split(",") ?: emptyList()
        val videoPaths = item.savedVideoPath?.split(",") ?: emptyList()
        val list = mutableListOf<Media>()
        imagePaths.forEach { s -> list.add(Media(s,false)) }
        videoPaths.forEach { s -> list.add(Media(s,true)) }
        Log.d(">>>","$position ==>$list")
        list.forEach { media ->
            if (media.path.isEmpty()) return@forEach
            val frameLayout = FrameLayout(holder.itemView.context)
            frameLayout.layoutParams =
                ViewGroup.MarginLayoutParams(h, h).apply { marginEnd = SizeUtils.dp2px(5f) }
            val imageCover = ImageView(holder.itemView.context)

            frameLayout.addView(imageCover)
            if (media.isVideo) {
                val imagePlay = ImageView(holder.itemView.context)
                imagePlay.setImageResource(R.drawable.baseline_play_circle_outline_24)
                imagePlay.setColorFilter(Color.WHITE)
                frameLayout.addView(
                    imagePlay,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )
            }
            Glide.with(imageCover).load(media.path).into(imageCover)
            holder.imageContainer.addView(frameLayout)
            frameLayout.setOnClickListener {
                val file = File(media.path)
                val uri = UriUtils.file2Uri(file)
                if (uri == null) {
                    ToastUtils.showLong("文件不存在")
                    return@setOnClickListener
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, if (media.isVideo)"video/*" else "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                holder.itemView.context.startActivity(intent)
            }
            frameLayout.setOnLongClickListener {
                onImageDownload?.invoke(media)
                return@setOnLongClickListener true
            }
        }
    }

    override fun getItemCount(): Int = list.size
}