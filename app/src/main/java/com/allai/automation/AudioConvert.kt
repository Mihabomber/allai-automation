package com.allai.automation

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioConvert {
    private const val MAX_PCM = 9_300_000L

    fun prepare(src: File, dst: File): File {
        val ext = src.extension.lowercase()
        if ((ext == "wav" || ext == "mp3" || ext == "mpeg" || ext == "mpga") && src.length() <= MAX_PCM) return src
        BotLog.add("Аудио: конвертирую ${src.extension.ifEmpty { "?" }} → WAV…")
        val out = decodeToWav(src, dst)
        BotLog.add("Аудио: WAV готов ${out.length() / 1024} KB")
        return out
    }

    private fun decodeToWav(src: File, dst: File): File {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                format = f
                break
            }
        }
        val fmt = format ?: run { ex.release(); throw RuntimeException("аудиодорожка не найдена") }
        ex.selectTrack(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(fmt, null, null, 0)
        dec.start()
        val tmp = File(dst.parentFile ?: File("."), "pcm_tmp")
        var written = 0L
        var eos = false
        val info = MediaCodec.BufferInfo()
        try {
            FileOutputStream(tmp).use { out ->
                var guard = 0
                while (!eos && written < MAX_PCM && guard < 100000) {
                    guard++
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = dec.getInputBuffer(inIdx)!!
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                    var outIdx = dec.dequeueOutputBuffer(info, 10_000)
                    while (outIdx >= 0) {
                        val ob = dec.getOutputBuffer(outIdx)!!
                        val n = info.size
                        if (n > 0) {
                            val chunk = ByteArray(n)
                            ob.position(info.offset)
                            ob.get(chunk)
                            val mono = toMono(chunk, channels)
                            val take = minOf(mono.size.toLong(), MAX_PCM - written).toInt()
                            out.write(mono, 0, take)
                            written += take
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        outIdx = dec.dequeueOutputBuffer(info, 0)
                    }
                }
            }
        } finally {
            try { dec.stop() } catch (_: Exception) {}
            try { dec.release() } catch (_: Exception) {}
            ex.release()
        }
        if (written == 0L) throw RuntimeException("декодер не дал PCM")
        writeWav(dst, tmp, sampleRate)
        tmp.delete()
        return dst
    }

    private fun toMono(chunk: ByteArray, channels: Int): ByteArray {
        if (channels == 1) return chunk
        val frameBytes = 2 * channels
        val frames = chunk.size / frameBytes
        val res = ByteArray(frames * 2)
        for (i in 0 until frames) {
            var sum = 0
            for (ch in 0 until channels) {
                val o = i * frameBytes + ch * 2
                val lo = chunk[o].toInt() and 0xFF
                val hi = chunk[o + 1].toInt() and 0xFF
                sum += (lo or (hi shl 8)).toShort().toInt()
            }
            val v = (sum / channels).toShort()
            res[i * 2] = (v.toInt() and 0xFF).toByte()
            res[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        return res
    }

    private fun writeWav(dst: File, pcm: File, sampleRate: Int) {
        val dataLen = pcm.length()
        val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put("RIFF".toByteArray())
        hdr.putInt((36 + dataLen).toInt())
        hdr.put("WAVE".toByteArray())
        hdr.put("fmt ".toByteArray())
        hdr.putInt(16)
        hdr.putShort(1)
        hdr.putShort(1)
        hdr.putInt(sampleRate)
        hdr.putInt(sampleRate * 2)
        hdr.putShort(2)
        hdr.putShort(16)
        hdr.put("data".toByteArray())
        hdr.putInt(dataLen.toInt())
        FileOutputStream(dst).use { o ->
            o.write(hdr.array())
            pcm.inputStream().use { it.copyTo(o, 65536) }
        }
    }
}
