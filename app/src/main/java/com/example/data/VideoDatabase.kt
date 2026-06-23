package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "video_downloads")
data class VideoDownload(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUrl: String,
    val extractedVideoUrl: String,
    val filePath: String?,
    val platform: String, // "Instagram", "TikTok", "X", "YouTube", "Other"
    val timestamp: Long = System.currentTimeMillis(),
    val status: String, // "QUEUED", "DOWNLOADING", "COMPLETED", "FAILED"
    val fileSize: String = "Unknown size"
)

@Dao
interface VideoDownloadDao {
    @Query("SELECT * FROM video_downloads ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<VideoDownload>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: VideoDownload): Long

    @Update
    suspend fun updateDownload(download: VideoDownload)

    @Query("DELETE FROM video_downloads WHERE id = :id")
    suspend fun deleteDownloadById(id: Long)

    @Query("DELETE FROM video_downloads")
    suspend fun clearAll()
}

@Database(entities = [VideoDownload::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract val videoDownloadDao: VideoDownloadDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "video_downloader_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class VideoDownloadRepository(private val dao: VideoDownloadDao) {
    val allDownloads: Flow<List<VideoDownload>> = dao.getAllDownloads()

    suspend fun insert(download: VideoDownload): Long {
        return dao.insertDownload(download)
    }

    suspend fun update(download: VideoDownload) {
        dao.updateDownload(download)
    }

    suspend fun deleteById(id: Long) {
        dao.deleteDownloadById(id)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }
}
