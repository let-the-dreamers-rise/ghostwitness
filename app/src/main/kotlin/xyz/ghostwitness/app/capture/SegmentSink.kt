package xyz.ghostwitness.app.capture

import android.annotation.TargetApi
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Where MediaMuxer writes one plaintext segment before it is encrypted. On Android 11+ this is an anonymous
 * in-memory file (memfd), so plaintext never touches storage. On Android 10 it is a temp file in the app's
 * private cache that is deleted as soon as the segment is sealed.
 */
internal interface SegmentSink : Closeable {
    val fd: FileDescriptor

    fun readAll(): ByteArray
}

internal object SegmentSinks {
    fun create(cacheDir: File): SegmentSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MemorySink() else TempFileSink(cacheDir)
}

@TargetApi(Build.VERSION_CODES.R)
private class MemorySink : SegmentSink {
    override val fd: FileDescriptor = Os.memfd_create("gw-segment", OsConstants.MFD_CLOEXEC)

    override fun readAll(): ByteArray = readFully(fd)

    override fun close() = Os.close(fd)
}

private class TempFileSink(dir: File) : SegmentSink {
    private val file = File.createTempFile("gw-seg", ".mp4", dir)
    private val handle = RandomAccessFile(file, "rw")

    override val fd: FileDescriptor get() = handle.fd

    override fun readAll(): ByteArray = readFully(handle.fd)

    override fun close() {
        handle.close()
        file.delete()
    }
}

private fun readFully(fd: FileDescriptor): ByteArray {
    val size = Os.fstat(fd).st_size
    if (size > Int.MAX_VALUE) throw IOException("segment too large: $size bytes")
    val out = ByteArray(size.toInt())
    var offset = 0
    while (offset < out.size) {
        val n = Os.pread(fd, out, offset, out.size - offset, offset.toLong())
        if (n <= 0) throw IOException("short read at $offset of ${out.size}")
        offset += n
    }
    return out
}
