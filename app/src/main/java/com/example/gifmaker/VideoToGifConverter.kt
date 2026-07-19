package com.example.gifmaker

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

data class GifRequest(
    val sourceUri: Uri,
    val outputFile: File,
    val startMs: Long,
    val endMs: Long,
    val fps: Int,
    val targetWidth: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** If set, the converter will try to keep the final file at or under this size. */
    val targetMaxBytes: Long? = null,
    /** If set, a JPEG snapshot of the first frame is saved here for use as a Studio thumbnail. */
    val thumbnailFile: File? = null
)

/** Thrown internally when the user cancels an in-progress conversion; always caught in [VideoToGifConverter.convert]. */
class ConversionCancelledException : Exception("Dibatalkan oleh pengguna")

sealed class GifResult {
    data class Success(
        val outputFile: File,
        val frameCount: Int,
        val finalWidth: Int,
        val finalFps: Int,
        val sizeBytes: Long,
        val wasDownscaledForSizeBudget: Boolean
    ) : GifResult()
    data class Failure(val message: String) : GifResult()
}

/**
 * Converts a trimmed section of a video into an animated GIF using
 * [MediaMetadataRetriever] for frame extraction and [GifEncoder] for
 * writing the result.
 *
 * When [GifRequest.targetMaxBytes] is set, the converter first encodes at
 * the requested width/FPS, then — only if the result is over budget —
 * mathematically estimates how much smaller the resolution needs to be
 * (GIF size scales roughly with pixel-count × frame-count for a given
 * clip) and re-encodes at that lower size. This keeps quality/resolution
 * as high as the size budget allows, rather than always dropping to a
 * fixed low preset.
 */
object VideoToGifConverter {

    /** Hard cap so a long trim range at a high FPS can't run forever on-device. */
    const val MAX_FRAMES = 240

    /** At most this many encode passes when fitting to a size budget. */
    private const val MAX_SIZE_FIT_ATTEMPTS = 3
    private const val MIN_WIDTH = 120
    private const val MIN_FPS = 5

    /** How many real frames to sample+encode when estimating output size. */
    private const val ESTIMATE_SAMPLE_FRAMES = 5

    /** Refuse to start (or continue) an encode if the output directory has less free space than this. */
    private const val MIN_FREE_BYTES = 20L * 1024 * 1024

    fun convert(
        context: Context,
        request: GifRequest,
        isCancelled: () -> Boolean = { false },
        onProgress: (current: Int, total: Int) -> Unit
    ): GifResult {
        val storageDir = request.outputFile.absoluteFile.parentFile
        val freeSpace = storageDir?.usableSpace ?: Long.MAX_VALUE
        if (freeSpace < MIN_FREE_BYTES) {
            return GifResult.Failure("Penyimpanan perangkat hampir penuh. Kosongkan sedikit ruang lalu coba lagi.")
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, request.sourceUri)

            var width = request.targetWidth
            var fps = request.fps
            var wasDownscaled = false
            var lastFrameCount = 0
            var lastSize = 0L

            for (attempt in 1..MAX_SIZE_FIT_ATTEMPTS) {
                val (frameCount, height) = encodeOnce(retriever, request, width, fps, isCancelled, onProgress)
                lastFrameCount = frameCount
                lastSize = request.outputFile.length()

                val budget = request.targetMaxBytes
                if (budget == null || lastSize <= budget || attempt == MAX_SIZE_FIT_ATTEMPTS) {
                    return GifResult.Success(
                        outputFile = request.outputFile,
                        frameCount = frameCount,
                        finalWidth = width,
                        finalFps = fps,
                        sizeBytes = lastSize,
                        wasDownscaledForSizeBudget = wasDownscaled
                    )
                }

                // Over budget: scale width/fps down using the ratio of target to
                // actual size (pixel-count × frame-count is roughly proportional
                // to output size for a given clip's visual complexity).
                wasDownscaled = true
                val ratio = (budget.toDouble() / lastSize.toDouble()).coerceIn(0.15, 0.9)
                val scale = sqrt(ratio)
                width = (width * scale).toInt().coerceAtLeast(MIN_WIDTH)
                if (width == MIN_WIDTH) {
                    // Resolution is already at the floor; trim FPS instead on the next pass.
                    fps = (fps * scale).toInt().coerceAtLeast(MIN_FPS)
                }
            }

            // Unreachable, but keeps the compiler happy.
            GifResult.Success(request.outputFile, lastFrameCount, width, fps, lastSize, wasDownscaled)
        } catch (e: ConversionCancelledException) {
            runCatching { request.outputFile.delete() }
            GifResult.Failure("Dibatalkan oleh pengguna.")
        } catch (e: OutOfMemoryError) {
            runCatching { request.outputFile.delete() }
            GifResult.Failure("Kehabisan memori saat memproses video. Coba turunkan lebar output, FPS, atau potongan durasi, lalu coba lagi.")
        } catch (e: Exception) {
            runCatching { request.outputFile.delete() }
            GifResult.Failure(e.message ?: "Terjadi kesalahan tak dikenal saat membuat GIF.")
        } finally {
            retriever.release()
        }
    }

    /**
     * Estimates the final GIF's file size *before* running the full
     * conversion, by actually extracting and encoding a handful of real
     * sample frames from this specific video at the chosen width/height —
     * then separating the GIF's one-time header cost from the per-frame
     * cost, and projecting the per-frame cost across the full frame count.
     *
     * This is far more accurate than a fixed formula because GIF size
     * depends heavily on how visually complex/colorful the actual footage
     * is, which a generic estimate can't know in advance.
     *
     * Returns null if the video can't be read (e.g. permission revoked).
     */
    fun estimateSizeBytes(context: Context, request: GifRequest): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, request.sourceUri)

            val (frameCount, height) = computeFrameCountAndHeight(request, request.targetWidth, request.fps)
            val sampleCount = minOf(ESTIMATE_SAMPLE_FRAMES, frameCount).coerceAtLeast(1)

            if (sampleCount < 2) {
                // Too short a clip to calibrate marginal cost; encode the one
                // frame we have and scale it by a conservative factor.
                val single = encodeSampleAndMeasure(retriever, request, request.targetWidth, height, 1, frameCount)
                return single
            }

            val durationMs = (request.endMs - request.startMs).coerceAtLeast(1)
            val checkpoints = mutableListOf<Long>()
            val counting = CountingOutputStream()
            val encoder = GifEncoder(counting, request.targetWidth, height, loop = true)

            for (i in 0 until sampleCount) {
                // Spread samples evenly across the whole trim range so the
                // estimate reflects the clip's overall complexity, not just
                // its first moment.
                val fraction = i.toDouble() / (sampleCount - 1)
                val timeMs = request.startMs + (fraction * durationMs).toLong()
                val frame = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val scaled = if (frame.width != request.targetWidth || frame.height != height) {
                    Bitmap.createScaledBitmap(frame, request.targetWidth, height, true)
                } else frame
                encoder.addFrame(scaled, 5)
                checkpoints.add(counting.count)
                if (scaled !== frame) { frame.recycle(); scaled.recycle() } else frame.recycle()
            }
            encoder.finish()

            if (checkpoints.size < 2) return checkpoints.firstOrNull()

            val marginalPerFrame = (checkpoints.last() - checkpoints.first()).toDouble() / (checkpoints.size - 1)
            val headerCost = checkpoints.first() - marginalPerFrame

            (headerCost + marginalPerFrame * frameCount).toLong().coerceAtLeast(0)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun encodeSampleAndMeasure(
        retriever: MediaMetadataRetriever,
        request: GifRequest,
        width: Int,
        height: Int,
        sampleFrameIndex: Int,
        totalFrameCount: Int
    ): Long {
        val counting = CountingOutputStream()
        val encoder = GifEncoder(counting, width, height, loop = true)
        val frame = retriever.getFrameAtTime(request.startMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        if (frame != null) {
            val scaled = if (frame.width != width || frame.height != height) {
                Bitmap.createScaledBitmap(frame, width, height, true)
            } else frame
            encoder.addFrame(scaled, 5)
            if (scaled !== frame) { frame.recycle(); scaled.recycle() } else frame.recycle()
        }
        encoder.finish()
        // Single-frame clip: header + 1 frame is essentially the real output.
        return counting.count
    }

    /** Shared frame-count/effective-height math used by both the real encode and the estimator. */
    private fun computeFrameCountAndHeight(request: GifRequest, width: Int, fps: Int): Pair<Int, Int> {
        val durationMs = (request.endMs - request.startMs).coerceAtLeast(1)
        var frameCount = ((durationMs / 1000.0) * fps).toInt().coerceAtLeast(1)
        if (frameCount > MAX_FRAMES) frameCount = MAX_FRAMES

        val height = if (request.sourceWidth > 0 && request.sourceHeight > 0) {
            (width.toDouble() * request.sourceHeight / request.sourceWidth).toInt()
                .let { if (it % 2 != 0) it + 1 else it }
                .coerceAtLeast(2)
        } else {
            width
        }
        return frameCount to height
    }

    /** Encodes one full pass at the given width/fps, writing over [GifRequest.outputFile]. Returns (frameCount, height). */
    private fun encodeOnce(
        retriever: MediaMetadataRetriever,
        request: GifRequest,
        width: Int,
        fps: Int,
        isCancelled: () -> Boolean,
        onProgress: (current: Int, total: Int) -> Unit
    ): Pair<Int, Int> {
        val durationMs = (request.endMs - request.startMs).coerceAtLeast(1)
        val (frameCountRaw, height) = computeFrameCountAndHeight(request, width, fps)
        var frameCount = frameCountRaw
        var effectiveFps = fps
        if (frameCount >= MAX_FRAMES) {
            effectiveFps = (frameCount * 1000.0 / durationMs).toInt().coerceAtLeast(1)
        }

        val delayCentiseconds = (100.0 / effectiveFps).toInt().coerceAtLeast(2)
        val intervalMs = durationMs.toDouble() / frameCount
        // Cap how often we push progress to the UI so a 240-frame render doesn't
        // trigger 240 separate recompositions; ~40 updates is plenty smooth.
        val progressEvery = (frameCount / 40).coerceAtLeast(1)

        FileOutputStream(request.outputFile).use { fos ->
            val encoder = GifEncoder(fos, width, height, loop = true)
            for (i in 0 until frameCount) {
                if (isCancelled()) throw ConversionCancelledException()

                val timeMs = request.startMs + (i * intervalMs).toLong()
                val frame = retriever.getFrameAtTime(
                    timeMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                val scaled = if (frame.width != width || frame.height != height) {
                    Bitmap.createScaledBitmap(frame, width, height, true)
                } else {
                    frame
                }
                encoder.addFrame(scaled, delayCentiseconds)
                if (i == 0 && request.thumbnailFile != null) {
                    runCatching {
                        java.io.FileOutputStream(request.thumbnailFile).use { thumbOut ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 85, thumbOut)
                        }
                    }
                }
                if (scaled !== frame) {
                    frame.recycle()
                    scaled.recycle()
                } else {
                    frame.recycle()
                }
                if ((i + 1) % progressEvery == 0 || i == frameCount - 1) {
                    onProgress(i + 1, frameCount)
                }
            }
            encoder.finish()
        }

        return frameCount to height
    }
}

/** Minimal OutputStream wrapper that tracks total bytes written, for size estimation. */
private class CountingOutputStream : java.io.OutputStream() {
    var count: Long = 0
        private set

    override fun write(b: Int) {
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        count += len
    }
}


