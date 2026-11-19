package com.uacspoofer.mobile.vpn

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.settings.NetworkGuardSettings
import java.io.FileDescriptor

/**
 * Packet splice between Android's TUN and HEV tun2socks.
 * The packet loop reads only a cached flag; it never touches preferences.
 */
object TunPacketFilter {
    @Volatile private var quicEnabled = false
    private val lock = Any()
    private var realTun: ParcelFileDescriptor? = null
    private var filterPeer: ParcelFileDescriptor? = null
    private var upThread: Thread? = null
    private var downThread: Thread? = null
    @Volatile private var running = false

    fun applyFlags(settings: NetworkGuardSettings) {
        quicEnabled = settings.quicBlock
    }

    fun attach(tun: ParcelFileDescriptor, mtu: Int): ParcelFileDescriptor {
        synchronized(lock) {
            stopLocked()
            val pair = createPacketPair()
            val enginePfd = pair.first
            val filterPfd = pair.second
            try {
                setBlocking(tun.fileDescriptor, true)
                setBlocking(filterPfd.fileDescriptor, true)
                bumpSocketBuffers(filterPfd.fileDescriptor)
                bumpSocketBuffers(enginePfd.fileDescriptor)
                realTun = tun
                filterPeer = filterPfd
                running = true
                val bufferSize = (mtu + 64).coerceIn(2048, 65535)
                upThread = startLoop("atom-tun-filter-up") {
                    forwardDeviceToEngine(tun.fileDescriptor, filterPfd.fileDescriptor, bufferSize)
                }
                downThread = startLoop("atom-tun-filter-down") {
                    splice(filterPfd.fileDescriptor, tun.fileDescriptor, bufferSize)
                }
                AppLogRepository.info(LogSource.TUN, "QUIC packet filter attached quic=$quicEnabled mtu=$mtu")
                return enginePfd
            } catch (error: Throwable) {
                running = false
                runCatching { filterPfd.close() }
                runCatching { enginePfd.close() }
                throw error
            }
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        running = false
        runCatching { filterPeer?.close() }
        runCatching { realTun?.close() }
        filterPeer = null
        realTun = null
        val up = upThread
        val down = downThread
        upThread = null
        downThread = null
        up?.join(750L)
        down?.join(750L)
        if (up?.isAlive == true) up.interrupt()
        if (down?.isAlive == true) down.interrupt()
    }

    private fun startLoop(name: String, body: () -> Unit): Thread {
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                body()
            } catch (_: InterruptedException) {
            } catch (error: Throwable) {
                if (running) AppLogRepository.warning(LogSource.TUN, "Packet filter $name stopped", error)
            }
        }, name)
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun forwardDeviceToEngine(tunFd: FileDescriptor, engineFd: FileDescriptor, bufferSize: Int) {
        val packet = ByteArray(bufferSize)
        val reply = ByteArray(bufferSize + 128)
        while (running) {
            val n = readPacket(tunFd, packet) ?: break
            if (n <= 0) break
            if (TunPacketPolicy.inspect(packet, n, quicEnabled) == TunPacketAction.REJECT) {
                val replyLength = TunPacketPolicy.buildPortUnreachable(packet, n, reply)
                if (replyLength > 0) writePacket(tunFd, reply, replyLength)
                continue
            }
            writePacket(engineFd, packet, n)
        }
    }

    private fun splice(from: FileDescriptor, to: FileDescriptor, bufferSize: Int) {
        val packet = ByteArray(bufferSize)
        while (running) {
            val n = readPacket(from, packet) ?: break
            if (n <= 0) break
            writePacket(to, packet, n)
        }
    }

    private fun readPacket(fd: FileDescriptor, buffer: ByteArray): Int? {
        while (running) {
            try {
                val n = Os.read(fd, buffer, 0, buffer.size)
                return if (n <= 0) null else n
            } catch (error: ErrnoException) {
                when (error.errno) {
                    OsConstants.EINTR -> continue
                    OsConstants.EAGAIN -> { waitFor(fd, OsConstants.POLLIN.toShort()); continue }
                    OsConstants.EPIPE, OsConstants.EBADF, OsConstants.ECONNRESET -> return null
                    else -> { if (running) throw error else return null }
                }
            }
        }
        return null
    }

    private fun writePacket(fd: FileDescriptor, buffer: ByteArray, length: Int) {
        var offset = 0
        while (running && offset < length) {
            try {
                val n = Os.write(fd, buffer, offset, length - offset)
                if (n <= 0) return
                offset += n
            } catch (error: ErrnoException) {
                when (error.errno) {
                    OsConstants.EINTR -> continue
                    OsConstants.EAGAIN -> { waitFor(fd, OsConstants.POLLOUT.toShort()); continue }
                    OsConstants.EPIPE, OsConstants.EBADF, OsConstants.ECONNRESET -> return
                    else -> { if (running) throw error else return }
                }
            }
        }
    }

    private fun waitFor(fd: FileDescriptor, event: Short) {
        val poll = android.system.StructPollfd()
        poll.fd = fd
        poll.events = event
        runCatching { Os.poll(arrayOf(poll), 250) }
    }

    private fun createPacketPair(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> {
        val left = FileDescriptor()
        val right = FileDescriptor()
        try {
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, left, right)
        } catch (_: ErrnoException) {
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, left, right)
        }
        return try {
            ParcelFileDescriptor.dup(left) to ParcelFileDescriptor.dup(right)
        } finally {
            runCatching { Os.close(left) }
            runCatching { Os.close(right) }
        }
    }

    private fun setBlocking(fd: FileDescriptor, blocking: Boolean) {
        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
        val next = if (blocking) flags and OsConstants.O_NONBLOCK.inv() else flags or OsConstants.O_NONBLOCK
        if (next != flags) Os.fcntlInt(fd, OsConstants.F_SETFL, next)
    }

    private fun bumpSocketBuffers(fd: FileDescriptor) {
        runCatching { Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 256 * 1024) }
        runCatching { Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, 256 * 1024) }
    }
}
