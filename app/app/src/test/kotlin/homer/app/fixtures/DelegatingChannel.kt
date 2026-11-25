package homer.app.fixtures

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** A [SeekableByteChannel] that delegates every call to [delegate], so a test can hook one of them. */
internal open class DelegatingChannel(
    protected val delegate: SeekableByteChannel,
) : SeekableByteChannel {
    override fun read(dst: ByteBuffer): Int = delegate.read(dst)

    override fun write(src: ByteBuffer): Int = delegate.write(src)

    override fun position(): Long = delegate.position()

    override fun position(newPosition: Long): SeekableByteChannel = delegate.position(newPosition)

    override fun size(): Long = delegate.size()

    override fun truncate(size: Long): SeekableByteChannel = delegate.truncate(size)

    override fun isOpen(): Boolean = delegate.isOpen()

    override fun close() = delegate.close()
}
