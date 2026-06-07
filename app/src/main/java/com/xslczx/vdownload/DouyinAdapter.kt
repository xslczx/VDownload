package com.xslczx.vdownload

import android.annotation.SuppressLint
import android.content.Context
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
    private val videos: MutableList<DouyinVideo>,
    private val onItemClick: ((DouyinAdapter, Int, DouyinVideo, Boolean) -> Unit)? = null,
    private val onImageDownload: ((Media) -> Unit)? = null,
) : RecyclerView.Adapter<DouyinAdapter.ViewHolder>() {

    private companion object {
        const val MEDIA_PREVIEW_SIZE_DP = 100f
        const val MEDIA_PREVIEW_MARGIN_DP = 5f
    }

    fun deleteItem(position: Int) {
        if (position !in videos.indices) {
            return
        }

        videos.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, videos.size)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setNewData(data: List<DouyinVideo>) {
        videos.clear()
        videos.addAll(data)
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
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_douyin, parent, false)
        return ViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val videoRecord = videos[position]
        holder.title.text = videoRecord.title
        holder.container.setOnClickListener {
            dispatchItemClick(holder, videoRecord, isLongClick = false)
        }
        holder.container.setOnLongClickListener {
            dispatchItemClick(holder, videoRecord, isLongClick = true)
            true
        }

        holder.imageContainer.removeAllViews()
        val mediaList = buildMediaList(videoRecord)
        Log.d(">>>", "$position ==> $mediaList")
        mediaList.forEach { media ->
            holder.imageContainer.addView(createMediaPreview(holder, media))
        }
    }

    override fun getItemCount(): Int = videos.size

    private fun dispatchItemClick(holder: ViewHolder, videoRecord: DouyinVideo, isLongClick: Boolean) {
        val adapterPosition = holder.bindingAdapterPosition
        if (adapterPosition == RecyclerView.NO_POSITION) {
            return
        }
        onItemClick?.invoke(this, adapterPosition, videoRecord, isLongClick)
    }

    private fun buildMediaList(videoRecord: DouyinVideo): List<Media> {
        return buildList {
            addAll(parseMediaPaths(videoRecord.savedImagePaths, isVideo = false))
            addAll(parseMediaPaths(videoRecord.savedVideoPath, isVideo = true))
        }
    }

    private fun parseMediaPaths(rawPaths: String?, isVideo: Boolean): List<Media> {
        return rawPaths
            ?.split(",")
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map { Media(it, isVideo) }
            .orEmpty()
    }

    private fun createMediaPreview(holder: ViewHolder, media: Media): View {
        val context = holder.itemView.context
        val previewSize = SizeUtils.dp2px(MEDIA_PREVIEW_SIZE_DP)
        val previewContainer = FrameLayout(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(previewSize, previewSize).apply {
                marginEnd = SizeUtils.dp2px(MEDIA_PREVIEW_MARGIN_DP)
            }
        }
        val previewImage = ImageView(context)
        previewContainer.addView(previewImage)
        if (media.isVideo) {
            previewContainer.addView(
                createVideoIndicator(context),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        Glide.with(previewImage).load(media.path).into(previewImage)
        previewContainer.setOnClickListener {
            openMedia(holder, media)
        }
        previewContainer.setOnLongClickListener {
            onImageDownload?.invoke(media)
            true
        }
        return previewContainer
    }

    private fun createVideoIndicator(context: Context): ImageView {
        return ImageView(context).apply {
            setImageResource(R.drawable.baseline_play_circle_outline_24)
            setColorFilter(Color.WHITE)
        }
    }

    private fun openMedia(holder: ViewHolder, media: Media) {
        val mediaFile = File(media.path)
        if (!mediaFile.exists() || !mediaFile.isFile) {
            ToastUtils.showLong("文件不存在")
            return
        }

        val mediaUri = UriUtils.file2Uri(mediaFile)
        if (mediaUri == null) {
            ToastUtils.showLong("文件不存在")
            return
        }

        val context = holder.itemView.context
        val openMediaIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(mediaUri, if (media.isVideo) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (openMediaIntent.resolveActivity(context.packageManager) == null) {
            ToastUtils.showLong("未找到可打开该文件的应用")
            return
        }
        context.startActivity(openMediaIntent)
    }
}
