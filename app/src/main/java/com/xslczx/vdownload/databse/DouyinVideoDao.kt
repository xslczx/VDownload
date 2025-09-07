package com.xslczx.vdownload.databse

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DouyinVideoDao {
    @Insert
    suspend fun insert(video: DouyinVideo)

    @Query("SELECT * FROM douyin_video ORDER BY timestamp DESC")
    suspend fun getAll(): List<DouyinVideo>

    @Query("DELETE FROM douyin_video WHERE id = :id")
    suspend fun delete(id: Int)

    //获取最近一条
    @Query("SELECT * FROM douyin_video ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLast(): DouyinVideo?

    //根据属性url查询最近一条记录
    @Query("SELECT * FROM douyin_video WHERE url = :url ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastByUrl(url: String): DouyinVideo?
}
