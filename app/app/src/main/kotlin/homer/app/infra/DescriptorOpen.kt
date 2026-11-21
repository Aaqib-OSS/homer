package homer.app.infra

import org.slf4j.Logger
import java.io.IOException
import java.io.InputStream
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Reads from [position] into [dst], as `FileChannel.read(dst, position)` does; returns -1 at the end. */
internal fun SeekableByteChannel.readAt(
    dst: ByteBuffer,
    position: Long,
): Int {
    position(position)
    return read(dst)
}

/** A `struct stat` field: its byte offset and its width (2, 4 or 8 bytes, unsigned). */
internal data class StatField(
    val offset: Long,
    val width: Int,
)

/** An `fstat` entry point: `symbol(fd, buf)`, or `symbol(version, fd, buf)` (glibc `__fxstat`) with a [version]. */
internal data class FstatCall(
    val symbol: String,
    val version: Int? = null,
)

/** The per-OS/arch facts a descriptor-checked open needs: `open(2)` flags and the `struct stat` layout. */
internal data class StatPlatform(
    val openFlags: Int,
    val mode: StatField,
    val nlink: StatField,
    val size: StatField,
    val fstatCalls: List<FstatCall>,
) {
    companion object {
        /** The platform for [osName]/[arch] (`os.name`/`os.arch`), or null where none is defined. */
        fun of(
            osName: String,
            arch: String,
        ): StatPlatform? {
            val os =
                when {
                    osName.startsWith("Linux") -> "linux"
                    osName.startsWith("Mac OS X") -> "darwin"
                    else -> return null
                }
            return PLATFORMS["$os/${ARCHES[arch]}"]
        }

        private const val LINUX_O_RDONLY_NONBLOCK_NOCTTY_CLOEXEC = 0x800 or 0x100 or 0x80000
        private const val LINUX_X64_O_NOFOLLOW = 0x20000
        private const val LINUX_ARM64_O_NOFOLLOW = 0x8000
        private const val DARWIN_O_RDONLY_NONBLOCK_NOFOLLOW_NOCTTY_CLOEXEC = 0x4 or 0x100 or 0x20000 or 0x1000000

        // glibc before 2.33 exports no `fstat`, only `__fxstat(_STAT_VER, fd, buf)`; _STAT_VER is 1 on x86_64, 0 on arm64.
        private const val LINUX_X64_STAT_VER = 1
        private const val LINUX_ARM64_STAT_VER = 0

        private val LINUX_X64 =
            StatPlatform(
                openFlags = LINUX_O_RDONLY_NONBLOCK_NOCTTY_CLOEXEC or LINUX_X64_O_NOFOLLOW,
                mode = StatField(offset = 24, width = 4),
                nlink = StatField(offset = 16, width = 8),
                size = StatField(offset = 48, width = 8),
                fstatCalls = listOf(FstatCall("fstat"), FstatCall("__fxstat", version = LINUX_X64_STAT_VER)),
            )
        private val LINUX_ARM64 =
            StatPlatform(
                openFlags = LINUX_O_RDONLY_NONBLOCK_NOCTTY_CLOEXEC or LINUX_ARM64_O_NOFOLLOW,
                mode = StatField(offset = 16, width = 4),
                nlink = StatField(offset = 20, width = 4),
                size = StatField(offset = 48, width = 8),
                fstatCalls = listOf(FstatCall("fstat"), FstatCall("__fxstat", version = LINUX_ARM64_STAT_VER)),
            )

        // On x86_64 the bare `fstat` symbol is the older 32-bit-inode variant, whose layout differs.
        private val DARWIN =
            StatPlatform(
                openFlags = DARWIN_O_RDONLY_NONBLOCK_NOFOLLOW_NOCTTY_CLOEXEC,
                mode = StatField(offset = 4, width = 2),
                nlink = StatField(offset = 6, width = 2),
                size = StatField(offset = 96, width = 8),
                fstatCalls = listOf(FstatCall("fstat\$INODE64"), FstatCall("fstat")),
            )

        private val ARCHES = mapOf("amd64" to "x64", "x86_64" to "x64", "aarch64" to "arm64", "arm64" to "arm64")
        private val PLATFORMS =
            mapOf("linux/x64" to LINUX_X64, "linux/arm64" to LINUX_ARM64, "darwin/x64" to DARWIN, "darwin/arm64" to DARWIN)
    }
}

/**
 * Opens a file without blocking and checks the opened descriptor, never the path, for a single-link regular
 * file (CWE-59/CWE-367): a FIFO, device, directory, symlink or hard link swapped in at any time is refused.
 */
object DescriptorOpen {
    private val native: Result<NativeDescriptors> by lazy {
        runCatching {
            val platform =
                StatPlatform.of(System.getProperty("os.name"), System.getProperty("os.arch"))
                    ?: error("no descriptor-checked open is defined for ${System.getProperty("os.name")}")
            NativeDescriptors(platform)
        }
    }

    /**
     * Logs whether descriptor-checked file reads work in this JVM: once at startup, at ERROR naming the refused reads and
     * the cause when they do not (ADR-0047, amendment 2026-11-21).
     */
    fun logStatus(logger: Logger) = logStatus(logger, native)

    internal fun logStatus(
        logger: Logger,
        native: Result<NativeDescriptors>,
    ) {
        native
            .onSuccess { logger.info("Descriptor-checked file reads ready (fstat entry point: {})", it.fstatSymbol) }
            .onFailure {
                logger.error(
                    "Descriptor-checked file open unavailable ({}): artifact downloads, the log tail, the LLM-event stream, " +
                        "LangGraph run identity reads and continuation results are refused",
                    it.message,
                    it,
                )
            }
    }

    /** A stream over [path]'s own descriptor; the caller closes it. */
    internal fun openRegular(path: Path): InputStream = Channels.newInputStream(openRegularChannel(path))

    /** A read-only channel over [path]'s own descriptor whose `size()` follows the file's growth; the caller closes it. */
    internal fun openRegularChannel(path: Path): SeekableByteChannel = openRegularChannel(path, native)

    /** Fails closed: without [native] the read is refused, never retried as a blocking by-name open. */
    internal fun openRegularChannel(
        path: Path,
        native: Result<NativeDescriptors>,
    ): SeekableByteChannel =
        native
            .getOrElse { throw IOException("Refusing to read $path: no descriptor-checked open on this platform", it) }
            .openRegular(path)
}

/** FFM downcalls to `open`, `fstat`, `pread` and `close`; construction fails where any of them is missing. */
internal class NativeDescriptors(
    private val platform: StatPlatform,
    private val lookup: (String) -> MemorySegment? = {
        Linker
            .nativeLinker()
            .defaultLookup()
            .find(it)
            .orElse(null)
    },
) {
    private val linker = Linker.nativeLinker()
    private val errnoState = Linker.Option.captureStateLayout()
    private val errnoField = errnoState.varHandle(MemoryLayout.PathElement.groupElement("errno"))
    private val captureErrno = Linker.Option.captureCallState("errno")

    private val openCall =
        downcall(
            "open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            captureErrno,
            Linker.Option.firstVariadicArg(2),
        )
    private val fstat: FstatCall =
        platform.fstatCalls.firstOrNull { lookup(it.symbol) != null }
            ?: error("none of ${platform.fstatCalls.map { it.symbol }} is available on this platform")

    /** The `fstat` entry point in use. */
    val fstatSymbol: String get() = fstat.symbol

    private val fstatCall =
        if (fstat.version == null) {
            downcall(fstat.symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
        } else {
            downcall(
                fstat.symbol,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            )
        }
    private val preadCall =
        downcall(
            "pread",
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
            ),
            captureErrno,
        )
    private val closeCall = downcall("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))

    /** Opens [path] `O_NOFOLLOW | O_NONBLOCK`, then refuses it unless its descriptor is a single-link regular file. */
    fun openRegular(path: Path): SeekableByteChannel {
        val arena = Arena.ofShared()
        var channel: DescriptorChannel? = null
        var checked = false
        try {
            channel = DescriptorChannel(openFd(path, arena), arena)
            channel.requireSingleLinkRegular(path)
            checked = true
            return channel
        } finally {
            if (!checked) {
                if (channel != null) channel.close() else arena.close()
            }
        }
    }

    private fun openFd(
        path: Path,
        arena: Arena,
    ): Int {
        val bytes = path.toString().toByteArray(PATH_CHARSET)
        // Zero-filled by the arena, so the byte after the copy is the C string's terminator.
        val cPath = arena.allocate(bytes.size + 1L)
        MemorySegment.copy(bytes, 0, cPath, ValueLayout.JAVA_BYTE, 0, bytes.size)
        val state = arena.allocate(errnoState)
        val fd = openCall.invokeWithArguments(state, cPath, platform.openFlags, 0) as Int
        if (fd >= 0) return fd
        throw when (val errno = errno(state)) {
            ENOENT -> NoSuchFileException(path.toString())
            EACCES -> AccessDeniedException(path.toString())
            else -> IOException("Refusing to read $path: open(2) failed with errno $errno (a symlink fails it)")
        }
    }

    private fun errno(state: MemorySegment): Int = errnoField.get(state, 0L) as Int

    private fun MemorySegment.unsigned(field: StatField): Long =
        when (field.width) {
            Short.SIZE_BYTES -> java.lang.Short.toUnsignedLong(get(ValueLayout.JAVA_SHORT, field.offset))
            Int.SIZE_BYTES -> Integer.toUnsignedLong(get(ValueLayout.JAVA_INT, field.offset))
            else -> get(ValueLayout.JAVA_LONG, field.offset)
        }

    private fun downcall(
        symbol: String,
        descriptor: FunctionDescriptor,
        vararg options: Linker.Option,
    ): MethodHandle {
        val address = lookup(symbol) ?: error("$symbol is not available on this platform")
        return linker.downcallHandle(address, descriptor, *options)
    }

    /** Owns [fd] and [arena]: closing the channel closes both. Reads are positional and never reopen by path. */
    private inner class DescriptorChannel(
        private val fd: Int,
        private val arena: Arena,
    ) : SeekableByteChannel {
        private val closed = AtomicBoolean(false)
        private val buffer = arena.allocate(READ_CHUNK_BYTES.toLong())
        private val state = arena.allocate(errnoState)
        private val stat = arena.allocate(STAT_BUFFER_BYTES, Long.SIZE_BYTES.toLong())
        private var position = 0L

        fun requireSingleLinkRegular(path: Path) {
            refreshStat(path)
            val regular = stat.unsigned(platform.mode) and S_IFMT == S_IFREG
            if (!regular || stat.unsigned(platform.nlink) != 1L) {
                throw IOException("Refusing to read a non-regular or hard-linked file: $path")
            }
        }

        override fun read(dst: ByteBuffer): Int {
            ensureOpen()
            if (!dst.hasRemaining()) return 0
            val n = preadChunk(minOf(dst.remaining(), READ_CHUNK_BYTES))
            if (n == 0) return -1
            dst.put(buffer.asSlice(0, n.toLong()).asByteBuffer())
            position += n
            return n
        }

        private fun preadChunk(len: Int): Int {
            while (true) {
                val n = preadCall.invokeWithArguments(state, fd, buffer, len.toLong(), position) as Long
                if (n >= 0) return n.toInt()
                val errno = errno(state)
                if (errno != EINTR) throw IOException("pread(2) failed with errno $errno")
            }
        }

        override fun size(): Long {
            ensureOpen()
            refreshStat(null)
            return stat.unsigned(platform.size)
        }

        private fun refreshStat(path: Path?) {
            val rc = fstat.version?.let { fstatCall.invokeWithArguments(it, fd, stat) } ?: fstatCall.invokeWithArguments(fd, stat)
            if (rc as Int != 0) throw IOException("fstat(2) failed${path?.let { " on $it" } ?: ""}")
        }

        override fun position(): Long {
            ensureOpen()
            return position
        }

        override fun position(newPosition: Long): SeekableByteChannel {
            require(newPosition >= 0) { "negative position: $newPosition" }
            ensureOpen()
            position = newPosition
            return this
        }

        override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

        override fun isOpen(): Boolean = !closed.get()

        private fun ensureOpen() {
            if (closed.get()) throw ClosedChannelException()
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                // Arena first: a racing read then fails on the closed buffer instead of reading a reused fd number.
                arena.close()
                closeCall.invokeWithArguments(fd)
            }
        }
    }

    private companion object {
        const val ENOENT = 2
        const val EINTR = 4
        const val EACCES = 13
        const val S_IFMT = 0xF000L
        const val S_IFREG = 0x8000L
        const val STAT_BUFFER_BYTES = 256L
        const val READ_CHUNK_BYTES = 64 * 1024

        // The charset the JDK itself encodes file names with, so the native open names the same file.
        val PATH_CHARSET: Charset =
            System.getProperty("sun.jnu.encoding")?.let { runCatching { Charset.forName(it) }.getOrNull() }
                ?: Charsets.UTF_8
    }
}
