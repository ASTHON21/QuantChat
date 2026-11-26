package com.quantchat.engine.core

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ActiveMmapLeaseInfo(
    val leaseId: String,
    val canonicalPath: String,
    val fileName: String,
    val modelId: String,
    val backendId: String,
    val useMmap: Boolean,
    val sharedFileLockHeld: Boolean,
    val acquiredAtMs: Long
)

data class MmapLifecycleSnapshot(
    val activeLeases: List<ActiveMmapLeaseInfo> = emptyList(),
    val totalActiveMappings: Int = 0,
    val lockedWritePaths: Set<String> = emptySet(),
    val lastReleasedPath: String? = null,
    val lastReleaseAtMs: Long? = null,
    val lastBlockedDeletePath: String? = null,
    val lastBlockedDeleteReason: String? = null
)

sealed interface SafeDeleteCheck {
    data object Allowed : SafeDeleteCheck

    data class BlockedActiveMapping(
        val canonicalPath: String,
        val activeLeaseCount: Int,
        val backendLabels: List<String>
    ) : SafeDeleteCheck

    data class BlockedWriteLock(
        val canonicalPath: String
    ) : SafeDeleteCheck
}

class MmapLeaseHandle internal constructor(
    val leaseId: String,
    val canonicalPath: String,
    val fileName: String,
    val modelId: String,
    val backendId: String,
    val useMmap: Boolean,
    val sharedFileLockHeld: Boolean,
    val acquiredAtMs: Long
) {
    @Volatile
    internal var ownedPfd: ParcelFileDescriptor? = null

    @Volatile
    internal var fdDetachedToNative: Boolean = false

    internal val released = AtomicBoolean(false)

    val isActive: Boolean
        get() = !released.get()
}

class ExclusiveWriteLockHandle internal constructor(
    val canonicalPath: String,
    internal val lockFile: File,
    internal val raf: RandomAccessFile?,
    internal val channel: FileChannel?,
    internal val fileLock: FileLock?
) {
    internal val released = AtomicBoolean(false)

    val isHeld: Boolean
        get() = !released.get()
}

object MmapLifecycleRegistry {

    private class SharedReadGroup(
        val raf: RandomAccessFile?,
        val channel: FileChannel?,
        val fileLock: FileLock?,
        val leases: MutableMap<String, ActiveMmapLeaseInfo> = LinkedHashMap()
    )

    private val lock = Any()
    private val readGroups = LinkedHashMap<String, SharedReadGroup>()
    private val writeLocks = LinkedHashMap<String, ExclusiveWriteLockHandle>()

    @Volatile
    private var lastReleasedPath: String? = null

    @Volatile
    private var lastReleaseAtMs: Long? = null

    @Volatile
    private var lastBlockedDeletePath: String? = null

    @Volatile
    private var lastBlockedDeleteReason: String? = null

    private val _snapshot = MutableStateFlow(MmapLifecycleSnapshot())
    val snapshot: StateFlow<MmapLifecycleSnapshot> = _snapshot.asStateFlow()

    fun acquireReadMapping(
        file: File,
        modelId: String,
        backendId: String,
        useMmap: Boolean
    ): MmapLeaseHandle {
        val canonical = canonicalize(file)
        synchronized(lock) {
            if (writeLocks.containsKey(canonical)) {
                throw EngineError.FileLocked(
                    fileName = file.name,
                    reason = "Model file is currently locked by an active write or verification operation."
                )
            }

            val existingGroup = readGroups[canonical]
            val group = if (existingGroup != null) {
                existingGroup
            } else {
                val opened = openSharedReadLock(file)
                readGroups[canonical] = opened
                opened
            }

            val leaseId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val sharedHeld = group.fileLock != null && group.fileLock.isValid
            val info = ActiveMmapLeaseInfo(
                leaseId = leaseId,
                canonicalPath = canonical,
                fileName = file.name,
                modelId = modelId,
                backendId = backendId,
                useMmap = useMmap,
                sharedFileLockHeld = sharedHeld,
                acquiredAtMs = now
            )
            group.leases[leaseId] = info
            emitSnapshotLocked()

            return MmapLeaseHandle(
                leaseId = leaseId,
                canonicalPath = canonical,
                fileName = file.name,
                modelId = modelId,
                backendId = backendId,
                useMmap = useMmap,
                sharedFileLockHeld = sharedHeld,
                acquiredAtMs = now
            )
        }
    }

    fun attachPfd(handle: MmapLeaseHandle, pfd: ParcelFileDescriptor?) {
        if (!handle.released.get()) {
            handle.ownedPfd = pfd
            handle.fdDetachedToNative = false
        }
    }

    fun markPfdDetachedToNative(handle: MmapLeaseHandle) {
        handle.fdDetachedToNative = true
        handle.ownedPfd = null
    }

    fun releaseMapping(handle: MmapLeaseHandle?) {
        if (handle == null) return
        if (!handle.released.compareAndSet(false, true)) return

        if (!handle.fdDetachedToNative) {
            handle.ownedPfd?.let { pfd ->
                runCatching { pfd.close() }
            }
        }
        handle.ownedPfd = null

        synchronized(lock) {
            val group = readGroups[handle.canonicalPath]
            if (group != null) {
                group.leases.remove(handle.leaseId)
                if (group.leases.isEmpty()) {
                    readGroups.remove(handle.canonicalPath)
                    runCatching {
                        if (group.fileLock?.isValid == true) {
                            group.fileLock.release()
                        }
                    }
                    runCatching { group.channel?.close() }
                    runCatching { group.raf?.close() }
                }
            }
            lastReleasedPath = handle.canonicalPath
            lastReleaseAtMs = System.currentTimeMillis()
            emitSnapshotLocked()
        }
    }

    fun acquireExclusiveWriteLock(file: File): ExclusiveWriteLockHandle? {
        val canonical = canonicalize(file)
        synchronized(lock) {
            val activeRead = readGroups[canonical]
            if (activeRead != null && activeRead.leases.isNotEmpty()) {
                return null
            }
            if (writeLocks.containsKey(canonical)) {
                return null
            }

            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val lockFile = File(parent ?: File("."), "${file.name}.lck")
            var raf: RandomAccessFile? = null
            var channel: FileChannel? = null
            var osLock: FileLock? = null

            try {
                raf = RandomAccessFile(lockFile, "rw")
                channel = raf.channel
                osLock = try {
                    channel.tryLock(0L, Long.MAX_VALUE, false)
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (osLock == null) {
                    runCatching { channel.close() }
                    runCatching { raf.close() }
                    return null
                }
            } catch (_: Exception) {
                runCatching { osLock?.release() }
                runCatching { channel?.close() }
                runCatching { raf?.close() }
                return null
            }

            val handle = ExclusiveWriteLockHandle(
                canonicalPath = canonical,
                lockFile = lockFile,
                raf = raf,
                channel = channel,
                fileLock = osLock
            )
            writeLocks[canonical] = handle
            emitSnapshotLocked()
            return handle
        }
    }

    fun releaseExclusiveWriteLock(handle: ExclusiveWriteLockHandle?) {
        if (handle == null) return
        if (!handle.released.compareAndSet(false, true)) return

        synchronized(lock) {
            if (writeLocks[handle.canonicalPath] === handle) {
                writeLocks.remove(handle.canonicalPath)
            }
            runCatching {
                if (handle.fileLock?.isValid == true) {
                    handle.fileLock.release()
                }
            }
            runCatching { handle.channel?.close() }
            runCatching { handle.raf?.close() }
            runCatching {
                if (handle.lockFile.exists()) {
                    handle.lockFile.delete()
                }
            }
            emitSnapshotLocked()
        }
    }

    fun activeMappingCount(file: File): Int {
        val canonical = canonicalize(file)
        synchronized(lock) {
            return readGroups[canonical]?.leases?.size ?: 0
        }
    }

    fun hasActiveMappings(file: File): Boolean = activeMappingCount(file) > 0

    fun isWriteLocked(file: File): Boolean {
        val canonical = canonicalize(file)
        synchronized(lock) {
            return writeLocks.containsKey(canonical)
        }
    }

    fun canDeleteSafely(file: File): SafeDeleteCheck {
        val canonical = canonicalize(file)
        synchronized(lock) {
            val activeLeases = readGroups[canonical]?.leases?.values?.toList().orEmpty()
            if (activeLeases.isNotEmpty()) {
                return SafeDeleteCheck.BlockedActiveMapping(
                    canonicalPath = canonical,
                    activeLeaseCount = activeLeases.size,
                    backendLabels = activeLeases.map { it.backendId }.distinct()
                )
            }
            if (writeLocks.containsKey(canonical)) {
                return SafeDeleteCheck.BlockedWriteLock(canonicalPath = canonical)
            }
            return SafeDeleteCheck.Allowed
        }
    }

    fun recordBlockedDeleteAttempt(file: File, reason: String) {
        val canonical = canonicalize(file)
        synchronized(lock) {
            lastBlockedDeletePath = canonical
            lastBlockedDeleteReason = reason
            emitSnapshotLocked()
        }
    }

    fun resetForTests() {
        synchronized(lock) {
            readGroups.values.forEach { group ->
                runCatching {
                    if (group.fileLock?.isValid == true) group.fileLock.release()
                }
                runCatching { group.channel?.close() }
                runCatching { group.raf?.close() }
            }
            readGroups.clear()

            writeLocks.values.forEach { wl ->
                wl.released.set(true)
                runCatching {
                    if (wl.fileLock?.isValid == true) wl.fileLock.release()
                }
                runCatching { wl.channel?.close() }
                runCatching { wl.raf?.close() }
                runCatching { if (wl.lockFile.exists()) wl.lockFile.delete() }
            }
            writeLocks.clear()
            lastReleasedPath = null
            lastReleaseAtMs = null
            lastBlockedDeletePath = null
            lastBlockedDeleteReason = null
            emitSnapshotLocked()
        }
    }

    private fun openSharedReadLock(file: File): SharedReadGroup {
        if (!file.exists() || !file.isFile) {
            return SharedReadGroup(raf = null, channel = null, fileLock = null)
        }
        var raf: RandomAccessFile? = null
        var channel: FileChannel? = null
        return try {
            raf = RandomAccessFile(file, "r")
            channel = raf.channel
            val osLock = try {
                channel.tryLock(0L, Long.MAX_VALUE, true)
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (osLock == null) {
                runCatching { channel.close() }
                runCatching { raf.close() }
                throw EngineError.FileLocked(
                    fileName = file.name,
                    reason = "Exclusive file lock held by another process."
                )
            }
            SharedReadGroup(raf = raf, channel = channel, fileLock = osLock)
        } catch (e: EngineError) {
            runCatching { channel?.close() }
            runCatching { raf?.close() }
            throw e
        } catch (_: Exception) {
            runCatching { channel?.close() }
            runCatching { raf?.close() }
            SharedReadGroup(raf = null, channel = null, fileLock = null)
        }
    }

    private fun canonicalize(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private fun emitSnapshotLocked() {
        val leases = readGroups.values.flatMap { it.leases.values }
        _snapshot.value = MmapLifecycleSnapshot(
            activeLeases = leases,
            totalActiveMappings = leases.size,
            lockedWritePaths = writeLocks.keys.toSet(),
            lastReleasedPath = lastReleasedPath,
            lastReleaseAtMs = lastReleaseAtMs,
            lastBlockedDeletePath = lastBlockedDeletePath,
            lastBlockedDeleteReason = lastBlockedDeleteReason
        )
    }
}
