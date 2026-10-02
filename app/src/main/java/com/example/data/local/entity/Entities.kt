package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val fileName: String,
    val localPath: String,
    val subtitleUrl: String? = null,
    val subtitlePath: String? = null,
    val mimeType: String = "video/mp4",
    val quality: String = "720p",
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val status: String = "DOWNLOADING", // DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    val speedBytesPerSec: Long = 0L,
    val destinationType: String = "INTERNAL_VAULT", // INTERNAL_VAULT, PUBLIC_DOWNLOADS, CUSTOM
    val pageUrl: String? = null,
    val errorReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val category: String = "عام",
    val iconUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val visitTime: Long = System.currentTimeMillis(),
    val visitCount: Int = 1
)

@Entity(tableName = "offline_pages")
data class OfflinePageEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val filePath: String,
    val sizeBytes: Long = 0L,
    val savedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "browsing_stats")
data class BrowsingStatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateStr: String,
    val sitesVisited: Int = 0,
    val trackersBlocked: Int = 0,
    val bytesSaved: Long = 0L,
    val timeSpentSeconds: Long = 0L
)
