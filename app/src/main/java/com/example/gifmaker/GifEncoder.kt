package com.example.gifmaker

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A small, self-contained animated GIF (GIF89a) encoder.
 *
 * Implements three well-known, patent-free, public-domain pieces of the GIF
 * format from scratch:
 *  1. Median-cut color quantization (Heckbert, 1980) to build a 256-color
 *     palette per frame.
 *  2. Variable-code-size LZW compression, as required by the GIF spec.
 *  3. The GIF89a container: header, logical screen descriptor, global color
 *     table, NETSCAPE2.0 looping extension, and per-frame graphic control +
 *     image blocks.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val loop: Boolean = true
) {
    private var wroteHeader = false

    /** Adds one frame. [delayCentiseconds] is the display time in 1/100ths of a second. */
    fun addFrame(bitmap: Bitmap, delayCentiseconds: Int) {
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        val (palette, indices) = MedianCutQuantizer.quantize(pixels, maxColors = 256)

        if (!wroteHeader) {
            writeHeader(palette)
            wroteHeader = true
        }

        writeGraphicControlExtension(delayCentiseconds)
        writeImageDescriptorAndData(indices, palette)

        if (scaled !== bitmap) scaled.recycle()
    }

    /** Call once after all frames have been added. */
    fun finish() {
        if (!wroteHeader) {
            // No frames were ever added; nothing meaningful to write.
            return
        }
        out.write(0x3B) // GIF trailer
        out.flush()
    }

    private fun writeHeader(palette: IntArray) {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))

        // Logical screen descriptor
        writeShortLE(width)
        writeShortLE(height)
        val colorTableSizeBits = colorTableSizeBits(palette.size)
        // Global color table flag = 1, color resolution = 7, sort flag = 0
        val packed = 0x80 or (0x07 shl 4) or colorTableSizeBits
        out.write(packed)
        out.write(0) // background color index
        out.write(0) // pixel aspect ratio

        writeColorTable(palette, 1 shl (colorTableSizeBits + 1))

        if (loop) {
            // NETSCAPE2.0 application extension: loop forever
            out.write(0x21) // extension introducer
            out.write(0xFF) // application extension label
            out.write(11)
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(3)
            out.write(1)
            writeShortLE(0) // 0 = loop forever
            out.write(0)
        }
    }

    private fun writeGraphicControlExtension(delayCentiseconds: Int) {
        out.write(0x21) // extension introducer
        out.write(0xF9) // graphic control label
        out.write(4)    // block size
        out.write(0x04) // disposal method = restore to background, no transparency
        writeShortLE(delayCentiseconds)
        out.write(0)    // transparent color index (unused)
        out.write(0)    // block terminator
    }

    private fun writeImageDescriptorAndData(indices: ByteArray, palette: IntArray) {
        out.write(0x2C) // image separator
        writeShortLE(0) // left
        writeShortLE(0) // top
        writeShortLE(width)
        writeShortLE(height)
        out.write(0x00) // no local color table, not interlaced

        val minCodeSize = colorTableSizeBits(palette.size) + 2
        out.write(minCodeSize.coerceIn(2, 8))
        LzwEncoder.encode(indices, minCodeSize.coerceIn(2, 8), out)
    }

    private fun writeColorTable(palette: IntArray, tableSize: Int) {
        for (i in 0 until tableSize) {
            val color = if (i < palette.size) palette[i] else 0
            out.write((color shr 16) and 0xFF) // R
            out.write((color shr 8) and 0xFF)  // G
            out.write(color and 0xFF)          // B
        }
    }

    private fun writeShortLE(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    /** Returns bits-1 such that 2^(bits) >= colorCount, per the GIF spec's packed field. */
    private fun colorTableSizeBits(colorCount: Int): Int {
        var bits = 1
        while ((1 shl bits) < colorCount) bits++
        return bits - 1
    }
}

/**
 * Median-cut color quantizer: recursively splits the set of pixel colors
 * along their widest channel until [maxColors] buckets remain, then uses
 * each bucket's average color as a palette entry.
 */
private object MedianCutQuantizer {

    fun quantize(pixels: IntArray, maxColors: Int): Pair<IntArray, ByteArray> {
        data class Box(val colors: MutableList<Int>)

        val initialBox = Box(pixels.toMutableList())
        val boxes = ArrayDeque<Box>()
        boxes.add(initialBox)

        while (boxes.size < maxColors) {
            val boxToSplit = boxes.maxByOrNull { rangeOf(it.colors) } ?: break
            if (boxToSplit.colors.size <= 1) break
            boxes.remove(boxToSplit)

            val channel = widestChannel(boxToSplit.colors)
            val sorted = boxToSplit.colors.sortedBy { channelValue(it, channel) }
            val mid = sorted.size / 2
            boxes.add(Box(sorted.subList(0, mid).toMutableList()))
            boxes.add(Box(sorted.subList(mid, sorted.size).toMutableList()))
        }

        val palette = IntArray(boxes.size)
        val boxAverages = boxes.map { averageColor(it.colors) }
        for (i in boxes.indices) palette[i] = boxAverages[i]

        // Build a lookup from color -> nearest palette index for fast re-mapping.
        val indices = ByteArray(pixels.size)
        val cache = HashMap<Int, Int>()
        for (i in pixels.indices) {
            val color = pixels[i]
            val idx = cache.getOrPut(color) { nearestPaletteIndex(color, palette) }
            indices[i] = idx.toByte()
        }

        return palette to indices
    }

    private fun rangeOf(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0
        var minR = 255; var maxR = 0
        var minG = 255; var maxG = 0
        var minB = 255; var maxB = 0
        for (c in colors) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r < minR) minR = r; if (r > maxR) maxR = r
            if (g < minG) minG = g; if (g > maxG) maxG = g
            if (b < minB) minB = b; if (b > maxB) maxB = b
        }
        return maxOf(maxR - minR, maxG - minG, maxB - minB)
    }

    private fun widestChannel(colors: List<Int>): Int {
        var minR = 255; var maxR = 0
        var minG = 255; var maxG = 0
        var minB = 255; var maxB = 0
        for (c in colors) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r < minR) minR = r; if (r > maxR) maxR = r
            if (g < minG) minG = g; if (g > maxG) maxG = g
            if (b < minB) minB = b; if (b > maxB) maxB = b
        }
        val rangeR = maxR - minR
        val rangeG = maxG - minG
        val rangeB = maxB - minB
        return when (maxOf(rangeR, rangeG, rangeB)) {
            rangeR -> 0
            rangeG -> 1
            else -> 2
        }
    }

    private fun channelValue(color: Int, channel: Int): Int = when (channel) {
        0 -> (color shr 16) and 0xFF
        1 -> (color shr 8) and 0xFF
        else -> color and 0xFF
    }

    private fun averageColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        for (c in colors) {
            sumR += (c shr 16) and 0xFF
            sumG += (c shr 8) and 0xFF
            sumB += c and 0xFF
        }
        val n = colors.size
        val r = (sumR / n).toInt()
        val g = (sumG / n).toInt()
        val b = (sumB / n).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    private fun nearestPaletteIndex(color: Int, palette: IntArray): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        var bestIndex = 0
        var bestDist = Int.MAX_VALUE
        for (i in palette.indices) {
            val pr = (palette[i] shr 16) and 0xFF
            val pg = (palette[i] shr 8) and 0xFF
            val pb = palette[i] and 0xFF
            val dr = r - pr; val dg = g - pg; val db = b - pb
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
            }
        }
        return bestIndex
    }
}

/**
 * Variable-code-size LZW encoder as specified by the GIF format (based on
 * the classic 1980s TIFF/GIF variant; the underlying LZW algorithm's patents
 * expired in 2003-2004 and it is now a standard, freely implementable
 * technique).
 *
 * Output is written as GIF-style sub-blocks (each up to 255 bytes, prefixed
 * by its own length byte, terminated by a zero-length block).
 */
private object LzwEncoder {

    fun encode(indices: ByteArray, minCodeSize: Int, out: OutputStream) {
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var nextCode = endCode + 1
        var codeSize = minCodeSize + 1

        val dictionary = HashMap<String, Int>()
        fun resetDictionary() {
            dictionary.clear()
            for (i in 0 until clearCode) dictionary[i.toString()] = i
            nextCode = endCode + 1
            codeSize = minCodeSize + 1
        }
        resetDictionary()

        val subBlocks = ByteArrayOutputStream()
        var bitBuffer = 0
        var bitCount = 0

        fun emit(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                subBlocks.write(bitBuffer and 0xFF)
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        emit(clearCode)

        var current = if (indices.isNotEmpty()) (indices[0].toInt() and 0xFF).toString() else ""
        for (i in 1 until indices.size) {
            val pixel = indices[i].toInt() and 0xFF
            val extended = "$current,$pixel"
            if (dictionary.containsKey(extended)) {
                current = extended
            } else {
                emit(dictionary.getValue(current))
                if (nextCode < 4096) {
                    dictionary[extended] = nextCode
                    nextCode++
                    if (nextCode == (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                    }
                } else {
                    // Dictionary full: reset per GIF convention.
                    emit(clearCode)
                    resetDictionary()
                }
                current = pixel.toString()
            }
        }
        if (indices.isNotEmpty()) {
            emit(dictionary.getValue(current))
        }
        emit(endCode)

        // Flush remaining bits.
        if (bitCount > 0) {
            subBlocks.write(bitBuffer and 0xFF)
        }

        val data = subBlocks.toByteArray()
        var offset = 0
        while (offset < data.size) {
            val chunkSize = minOf(255, data.size - offset)
            out.write(chunkSize)
            out.write(data, offset, chunkSize)
            offset += chunkSize
        }
        out.write(0) // block terminator
    }
}
