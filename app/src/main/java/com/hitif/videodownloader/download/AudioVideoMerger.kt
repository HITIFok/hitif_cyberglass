package com.hitif.videodownloader.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Merges a video-only stream with an audio-only stream into a single MP4 file
 * using Android's native MediaMuxer API.
 *
 * Inspired by VidMate's approach to DASH adaptive stream merging:
 *  - YouTube DASH provides separate video and audio tracks
 *  - This merger combines them into a single playable file
 *
 * Supported codecs:
 *  - Video: H.264 (AVC), H.265 (HEVC/HVC1)
 *  - Audio: AAC, Opus, MP3
 *
 * Output: MP4 container (most compatible format)
 */
object AudioVideoMerger {

    private const val TAG = "AudioVideoMerger"
    private const val BUFFER_SIZE = 256 * 1024  // 256 KB
    private const val MAX_MUXER_WAIT_MS = 10_000L

    data class MergeResult(
        val outputFile: File,
        val durationMs: Long,
        val videoCodec: String?,
        val audioCodec: String?
    )

    /**
     * Merge a video file and an audio file into a single output file.
     *
     * @param context   Android context (for temp files)
     * @param videoFile Video-only input file (e.g. DASH video track)
     * @param audioFile Audio-only input file (e.g. DASH audio track)
     * @param outputFile Destination file path
     * @return MergeResult with output file reference and metadata
     */
    fun merge(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): MergeResult {
        outputFile.parentFile?.mkdirs()

        if (!videoFile.exists()) throw IllegalArgumentException("Video file not found: ${videoFile.absolutePath}")
        if (!audioFile.exists()) throw IllegalArgumentException("Audio file not found: ${audioFile.absolutePath}")

        Log.d(TAG, "Starting merge: video=${videoFile.name} (${videoFile.length() / 1024}KB) + " +
                "audio=${audioFile.name} (${audioFile.length() / 1024}KB)")

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoDurationUs = 0L
        var audioDurationUs = 0L
        var videoCodec: String? = null
        var audioCodec: String? = null
        var sawVideoEOS = false
        var sawAudioEOS = false

        val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
        val bufferInfo = MediaCodec.BufferInfo()

        // Extract video track
        try {
            val videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoFile.absolutePath)

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrackIndex = muxer.addTrack(format)
                    videoExtractor.selectTrack(i)
                    videoCodec = extractCodecName(mime, format)
                    Log.d(TAG, "Video track: $mime codec=$videoTrackIndex")
                    break
                }
            }

            if (videoTrackIndex >= 0) {
                muxer.start()
                var frameCount = 0
                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = 0
                    val sampleSize = videoExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        sawVideoEOS = true
                        break
                    }
                    bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                    bufferInfo.flags = videoExtractor.sampleFlags
                    bufferInfo.size = sampleSize
                    videoDurationUs = bufferInfo.presentationTimeUs
                    muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                    videoExtractor.advance()
                    frameCount++
                }
                Log.d(TAG, "Video extraction done: $frameCount frames")
            }
            videoExtractor.release()
        } catch (e: Exception) {
            Log.e(TAG, "Video extraction error: ${e.message}", e)
            muxer.release()
            throw IllegalStateException("Video extraction failed: ${e.message}", e)
        }

        // Extract audio track
        try {
            val audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(audioFile.absolutePath)

            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = muxer.addTrack(format)
                    audioExtractor.selectTrack(i)
                    audioCodec = extractCodecName(mime, format)
                    Log.d(TAG, "Audio track: $mime codec=$audioTrackIndex")
                    break
                }
            }

            if (audioTrackIndex >= 0) {
                var frameCount = 0
                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = 0
                    val sampleSize = audioExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        sawAudioEOS = true
                        break
                    }
                    bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                    bufferInfo.flags = audioExtractor.sampleFlags
                    bufferInfo.size = sampleSize
                    audioDurationUs = bufferInfo.presentationTimeUs
                    muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                    audioExtractor.advance()
                    frameCount++
                }
                Log.d(TAG, "Audio extraction done: $frameCount frames")
            }
            audioExtractor.release()
        } catch (e: Exception) {
            Log.e(TAG, "Audio extraction error: ${e.message}", e)
            muxer.release()
            throw IllegalStateException("Audio extraction failed: ${e.message}", e)
        }

        // Finalize
        try {
            muxer.stop()
            muxer.release()
        } catch (e: Exception) {
            Log.e(TAG, "Muxer finalize error: ${e.message}", e)
        }

        val totalDurationMs = maxOf(videoDurationUs, audioDurationUs) / 1000
        Log.d(TAG, "Merge complete: ${outputFile.name} (${outputFile.length() / 1024}KB, ${totalDurationMs}ms)")

        // Cleanup temp input files
        try {
            if (videoFile.exists() && videoFile.name.endsWith(".tmp")) videoFile.delete()
            if (audioFile.exists() && audioFile.name.endsWith(".tmp")) audioFile.delete()
        } catch (_: Exception) {}

        return MergeResult(
            outputFile = outputFile,
            durationMs = totalDurationMs,
            videoCodec = videoCodec,
            audioCodec = audioCodec
        )
    }

    /**
     * Extract a human-readable codec name from the MIME type and format.
     */
    private fun extractCodecName(mime: String, format: MediaFormat): String {
        // On API 29+, we can query the codec profile
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                format.getInteger("codec-profile", -1)
                return when {
                    mime.contains("avc") || mime.contains("h264") -> "H.264"
                    mime.contains("hevc") || mime.contains("h265") || mime.contains("hvc1") -> "H.265"
                    mime.contains("vp9") -> "VP9"
                    mime.contains("vp8") -> "VP8"
                    mime.contains("av1") -> "AV1"
                    mime.contains("aac") -> "AAC"
                    mime.contains("opus") -> "Opus"
                    mime.contains("mp3") || mime.contains("mpeg") -> "MP3"
                    mime.contains("flac") -> "FLAC"
                    mime.contains("vorbis") -> "Vorbis"
                    else -> mime.substringAfter('/').uppercase()
                }
            } catch (_: Exception) {}
        }
        return when {
            mime.contains("avc") || mime.contains("h264") -> "H.264"
            mime.contains("hevc") || mime.contains("h265") -> "H.265"
            mime.contains("aac") -> "AAC"
            mime.contains("opus") -> "Opus"
            else -> mime.substringAfter('/').uppercase()
        }
    }

    /**
     * Check if a MediaItem URL is a YouTube video-only (DASH adaptive) format.
     * Video-only itags don't contain audio and must be merged after download.
     */
    fun isYoutubeVideoOnly(url: String): Boolean {
        val itagMatch = Regex("[?&]itag=(\\d+)").find(url)
        val itag = itagMatch?.groupValues?.get(1) ?: return false
        return itag in YOUTUBE_VIDEO_ONLY_ITAGS
    }

    /**
     * Check if a MediaItem URL is a YouTube audio-only format.
     */
    fun isYoutubeAudioOnly(url: String): Boolean {
        val itagMatch = Regex("[?&]itag=(\\d+)").find(url)
        val itag = itagMatch?.groupValues?.get(1) ?: return false
        return itag in YOUTUBE_AUDIO_ONLY_ITAGS
    }

    private val YOUTUBE_VIDEO_ONLY_ITAGS = setOf(
        "133", "134", "135", "136", "137", "138", "160",
        "167", "168", "169", "170", "218", "219",
        "242", "243", "244", "247", "248", "256",
        "258", "264", "266", "271", "272", "278",
        "298", "299", "302", "303", "308", "313",
        "315", "330", "331", "332", "333", "334",
        "335", "336", "337"
    )

    private val YOUTUBE_AUDIO_ONLY_ITAGS = setOf(
        "139", "140", "141", "171", "172", "249", "250", "251"
    )
}
