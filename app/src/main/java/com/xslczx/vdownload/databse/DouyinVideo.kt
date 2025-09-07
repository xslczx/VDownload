package com.xslczx.vdownload.databse

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "douyin_video")
data class DouyinVideo(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val title: String?,
    val savedImagePaths: String?, // 下载后的本地路径（多个图片也用逗号）
    val savedVideoPath: String?, // 视频保存路径
    val timestamp: Long = System.currentTimeMillis()
)
