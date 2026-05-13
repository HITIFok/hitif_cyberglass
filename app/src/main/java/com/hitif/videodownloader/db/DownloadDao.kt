package com.hitif.videodownloader.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface DownloadDao {

    // ── Insert / Update ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DownloadRecord): Long

    @Update
    suspend fun update(record: DownloadRecord)

    // ── Queries ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM download_history ORDER BY startedAt DESC")
    fun observeAll(): LiveData<List<DownloadRecord>>

    @Query("SELECT * FROM download_history ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<DownloadRecord>

    @Query("SELECT * FROM download_history WHERE downloadManagerId = :dmId LIMIT 1")
    suspend fun getByDownloadManagerId(dmId: Long): DownloadRecord?

    @Query("SELECT * FROM download_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadRecord?

    /** All episodes of the same series, sorted */
    @Query("""
        SELECT * FROM download_history
        WHERE seriesName = :series
        ORDER BY season ASC, episode ASC
    """)
    suspend fun getSeriesEpisodes(series: String): List<DownloadRecord>

    /** Distinct series names that have >= 2 episodes */
    @Query("""
        SELECT seriesName FROM download_history
        WHERE seriesName IS NOT NULL
        GROUP BY seriesName
        HAVING COUNT(*) >= 2
        ORDER BY MAX(startedAt) DESC
    """)
    suspend fun getSeriesNames(): List<String>

    // ── Delete ───────────────────────────────────────────────────────────────

    @Delete
    suspend fun delete(record: DownloadRecord)

    @Query("DELETE FROM download_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download_history")
    suspend fun deleteAll()

    @Query("DELETE FROM download_history WHERE state = 'FAILED'")
    suspend fun deleteFailedRecords()

    // ── State updates ─────────────────────────────────────────────────────────

    @Query("""
        UPDATE download_history
        SET state = :state, completedAt = :ts
        WHERE downloadManagerId = :dmId
    """)
    suspend fun updateState(dmId: Long, state: String, ts: Long? = null)

    @Query("""
        UPDATE download_history
        SET progressBytes = :downloaded, totalBytes = :total, speedBps = :speed
        WHERE downloadManagerId = :dmId
    """)
    suspend fun updateProgress(dmId: Long, downloaded: Long, total: Long, speed: Long = 0)

    /** Update download state by URL */
    @Query("""
        UPDATE download_history
        SET state = :state, completedAt = :ts
        WHERE url = :url
    """)
    suspend fun updateStateByUrl(url: String, state: String, ts: Long? = null)

    /** Update download progress by URL */
    @Query("""
        UPDATE download_history
        SET progressBytes = :downloaded, totalBytes = :total, speedBps = :speed
        WHERE url = :url
    """)
    suspend fun updateProgressByUrl(url: String, downloaded: Long, total: Long, speed: Long = 0)

    /** Update download state and final size by URL (on completion) */
    @Query("""
        UPDATE download_history
        SET state = :state, completedAt = :ts, sizeBytes = :fileSize,
            totalBytes = :fileSize, progressBytes = :fileSize
        WHERE url = :url
    """)
    suspend fun completeDownloadByUrl(url: String, state: String, ts: Long, fileSize: Long)

    /**
     * Set sizeBytes and totalBytes as soon as the HEAD response returns the
     * Content-Length — before any byte is downloaded.
     * Only updates rows where the size is still unknown (<= 0).
     */
    @Query("""
        UPDATE download_history
        SET sizeBytes = :totalBytes, totalBytes = :totalBytes
        WHERE url = :url AND (sizeBytes <= 0 OR sizeBytes IS NULL)
    """)
    suspend fun updateExpectedSizeByUrl(url: String, totalBytes: Long)

    // NOTE: failStaleDownloads() was removed.
    // Stale detection is now done in DownloadProgressService by comparing
    // DB records against TurboDownloadEngine.getActiveUrls() — no time
    // threshold needed. A record is failed if and only if its URL is no
    // longer tracked by the engine.
}
