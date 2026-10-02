package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownloadById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADING'")
    suspend fun getActiveDownloads(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(download: DownloadEntity)

    @Update
    suspend fun update(download: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, errorReason = :errorReason WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, errorReason: String? = null)

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total, speedBytesPerSec = :speed WHERE id = :id")
    suspend fun updateProgress(id: String, downloaded: Long, total: Long, speed: Long)

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun getBookmarkByUrl(url: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("SELECT DISTINCT category FROM bookmarks")
    fun getCategories(): Flow<List<String>>
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitTime DESC LIMIT 200")
    fun getRecentHistory(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE url = :url LIMIT 1")
    suspend fun getHistoryByUrl(url: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: HistoryEntity): Long

    @Query("UPDATE history SET visitCount = visitCount + 1, visitTime = :timestamp WHERE url = :url")
    suspend fun incrementVisit(url: String, timestamp: Long)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteHistoryById(id: Long)

    @Query("DELETE FROM history")
    suspend fun clearHistory()
}

@Dao
interface OfflinePageDao {
    @Query("SELECT * FROM offline_pages ORDER BY savedAt DESC")
    fun getAllOfflinePages(): Flow<List<OfflinePageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOfflinePage(page: OfflinePageEntity)

    @Query("DELETE FROM offline_pages WHERE id = :id")
    suspend fun deleteOfflinePageById(id: String)
}

@Dao
interface BrowsingStatDao {
    @Query("SELECT * FROM browsing_stats WHERE dateStr = :dateStr LIMIT 1")
    suspend fun getStatForDate(dateStr: String): BrowsingStatEntity?

    @Query("SELECT * FROM browsing_stats ORDER BY dateStr DESC LIMIT 7")
    fun getRecentStats(): Flow<List<BrowsingStatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(stat: BrowsingStatEntity)
}
