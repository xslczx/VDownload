package com.xslczx.vdownload.databse

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DouyinVideoDao {
    @Insert
    suspend fun insert(video: DouyinVideo)

    // timestamp 毫秒级，同毫秒插入时用自增 id 次级排序保证结果确定
    @Query("SELECT * FROM douyin_video ORDER BY timestamp DESC, id DESC")
    suspend fun getAll(): List<DouyinVideo>

    @Query("DELETE FROM douyin_video WHERE id = :id")
    suspend fun delete(id: Int)

    //获取最近一条
    @Query("SELECT * FROM douyin_video ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLast(): DouyinVideo?

    //根据属性url查询最近一条记录
    @Query("SELECT * FROM douyin_video WHERE url = :url ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLastByUrl(url: String): DouyinVideo?
}
