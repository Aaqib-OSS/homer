package homer.app.runners.ssh

import homer.app.runners.TarStreams
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import java.nio.file.attribute.PosixFilePermissions

/** Whether [path] exists on the peer; any failure other than no-such-file/path propagates. */
internal fun remoteExists(
    sftp: SftpClient,
    path: String,
): Boolean =
    try {
        sftp.stat(path)
        true
    } catch (e: SftpException) {
        if (e.status == SftpConstants.SSH_FX_NO_SUCH_FILE || e.status == SftpConstants.SSH_FX_NO_SUCH_PATH) {
            false
        } else {
            throw e
        }
    }

/** `mkdir -p` over SFTP: a segment that already exists as a directory is fine, any other failure surfaces. */
internal fun SftpClient.mkdirsIdempotent(path: String) {
    val parts = path.trimEnd('/').split("/").filter { it.isNotEmpty() }
    var acc = if (path.startsWith("/")) "" else "."
    for (p in parts) {
        acc = "$acc/$p"
        try {
            mkdir(acc)
        } catch (e: SftpException) {
            // Benign "already exists" if the segment is now a directory; otherwise
            // this was a real failure (permission/quota/etc) and must surface.
            val isDir = runCatching { stat(acc).isDirectory }.getOrDefault(false)
            if (!isDir) throw e
        }
    }
}

/**
 * Makes [dir] an owner-only (0700) directory, creating it when missing, so no other user can open a file in it.
 * A path that exists as anything but a directory, a symlink included, is refused.
 */
internal fun SftpClient.ensurePrivateDir(dir: String) {
    val attrs = lstatOrNull(dir)
    if (attrs != null && !attrs.isDirectory) throw SecurityException("remote path '$dir' is not a directory")
    if (attrs == null) mkdirsIdempotent(dir)
    if (attrs == null || attrs.permissions and GROUP_OTHER_BITS != 0) setStat(dir, SftpClient.Attributes().perms(OWNER_ONLY_DIR))
}

/**
 * Creates [path] new, never over an existing entry, and makes it owner-only (0600) before writing [content]; a
 * server that leaves the file readable by others fails the write and the file is removed.
 */
internal fun SftpClient.createPrivateFile(
    path: String,
    content: ByteArray,
) {
    try {
        open(path, SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive).use { handle ->
            setStat(handle, SftpClient.Attributes().perms(OWNER_ONLY_FILE))
            if (stat(handle).permissions and GROUP_OTHER_BITS != 0) throw SecurityException("remote file '$path' did not become owner-only")
            writeAll(handle, content)
        }
    } catch (e: SecurityException) {
        runCatching { remove(path) }
        throw e
    }
}

private fun SftpClient.writeAll(
    handle: SftpClient.Handle,
    content: ByteArray,
) {
    var offset = 0
    while (offset < content.size) {
        val length = minOf(WRITE_CHUNK_BYTES, content.size - offset)
        write(handle, offset.toLong(), content, offset, length)
        offset += length
    }
}

/** `lstat`, or null when [path] does not exist; any other failure propagates. */
private fun SftpClient.lstatOrNull(path: String): SftpClient.Attributes? =
    try {
        lstat(path)
    } catch (e: SftpException) {
        if (e.status != SftpConstants.SSH_FX_NO_SUCH_FILE && e.status != SftpConstants.SSH_FX_NO_SUCH_PATH) throw e
        null
    }

private val OWNER_ONLY_FILE = TarStreams.posixToMode(PosixFilePermissions.fromString("rw-------"))
private val OWNER_ONLY_DIR = TarStreams.posixToMode(PosixFilePermissions.fromString("rwx------"))
private val GROUP_OTHER_BITS = TarStreams.posixToMode(PosixFilePermissions.fromString("---rwxrwx"))

/** One SSH_FXP_WRITE each; below every server's packet limit. */
private const val WRITE_CHUNK_BYTES = 32 * 1024
