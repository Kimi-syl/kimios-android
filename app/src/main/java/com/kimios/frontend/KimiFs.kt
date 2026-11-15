package com.kimios.frontend

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Direct reader/writer for the KimiFS disk image. Only used while the VM is
 * stopped; the guest rebuilds its allocation bitmap from the inode table at
 * mount, so no on-disk bitmap needs to be maintained here.
 */
class KimiFs(private val image: File) : Closeable {
    companion object {
        const val SECTOR = 512L
        const val INODE_COUNT = 64
        const val INODE_SIZE = 128
        const val INODE_LBA = 1L
        const val DATA_LBA = 17L
        const val MAX_NAME = 31
        private val MAGIC = "KIMIFS1\u0000".toByteArray(Charsets.US_ASCII)
    }

    data class Entry(
        val index: Int,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val first: Long,
        val blocks: Long,
        val parent: Long
    )

    private val raf = RandomAccessFile(image, "rw")
    val totalSectors: Long
    private val inodes = ArrayList<Entry>(INODE_COUNT)

    init {
        val superblock = ByteArray(SECTOR.toInt())
        raf.seek(0)
        raf.readFully(superblock)
        for (i in MAGIC.indices) {
            require(superblock[i] == MAGIC[i]) { "not a KimiFS disk (boot KimiOS once to format it)" }
        }
        totalSectors = readU64(superblock, 16)
        val table = ByteArray(INODE_COUNT * INODE_SIZE)
        raf.seek(INODE_LBA * SECTOR)
        raf.readFully(table)
        for (i in 0 until INODE_COUNT) {
            val off = i * INODE_SIZE
            if (table[off + 32].toInt() == 0) continue
            val nameBytes = table.copyOfRange(off, off + MAX_NAME)
            val end = nameBytes.indexOf(0.toByte()).let { if (it < 0) MAX_NAME else it }
            inodes.add(
                Entry(
                    index = i,
                    name = String(nameBytes, 0, end, Charsets.US_ASCII),
                    isDir = table[off + 33].toInt() == 2,
                    size = readU64(table, off + 36),
                    first = readU64(table, off + 44),
                    blocks = readU64(table, off + 52),
                    parent = readU64(table, off + 60)
                )
            )
        }
    }

    fun list(parent: Long = 0): List<Entry> = inodes.filter { it.parent == parent }

    fun usedBlocks(): Long = inodes.sumOf { it.blocks }

    fun freeBytes(): Long = (totalSectors - DATA_LBA - usedBlocks()) * SECTOR

    fun insert(name: String, input: InputStream, expectedSize: Long, parent: Long = 0): Entry {
        val clean = sanitize(name)
        val existing = inodes.firstOrNull { it.parent == parent && it.name == clean && !it.isDir }
        val index = existing?.index ?: (1 until INODE_COUNT).firstOrNull { idx ->
            inodes.none { it.index == idx }
        } ?: error("inode table full (64 files max)")

        val others = inodes.filter { it.index != index && it.blocks > 0 }
            .sortedBy { it.first }
        val needed = ((expectedSize + SECTOR - 1) / SECTOR).coerceAtLeast(0)
        val run = if (needed > 0) findRun(others, needed) else DATA_LBA

        raf.seek(run * SECTOR)
        val buffer = ByteArray(1 shl 16)
        var written = 0L
        val capacity = needed * SECTOR
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            if (written + n > capacity) {
                input.close()
                error("file is larger than its reported size")
            }
            raf.write(buffer, 0, n)
            written += n
        }
        input.close()

        val blocks = (written + SECTOR - 1) / SECTOR
        val inode = ByteArray(INODE_SIZE)
        val nameBytes = clean.toByteArray(Charsets.US_ASCII)
        nameBytes.copyInto(inode)
        inode[32] = 1
        inode[33] = 1
        writeU64(inode, 36, written)
        writeU64(inode, 44, if (blocks > 0) run else 0)
        writeU64(inode, 52, blocks)
        writeU64(inode, 60, parent)
        raf.seek(INODE_LBA * SECTOR + index * INODE_SIZE)
        raf.write(inode)

        val superblock = ByteArray(SECTOR.toInt())
        raf.seek(0)
        raf.readFully(superblock)
        val nextFree = readU64(superblock, 48)
        if (run + blocks > nextFree) {
            writeU64(superblock, 48, run + blocks)
            raf.seek(0)
            raf.write(superblock)
        }
        raf.fd.sync()

        val entry = Entry(index, clean, false, written, run, blocks, parent)
        inodes.removeAll { it.index == index }
        inodes.add(entry)
        return entry
    }

    fun delete(name: String, parent: Long = 0): Boolean {
        val entry = inodes.firstOrNull { it.parent == parent && it.name == name } ?: return false
        val zero = ByteArray(INODE_SIZE)
        raf.seek(INODE_LBA * SECTOR + entry.index * INODE_SIZE)
        raf.write(zero)
        raf.fd.sync()
        inodes.removeAll { it.index == entry.index }
        return true
    }

    private fun findRun(others: List<Entry>, needed: Long): Long {
        var candidate = DATA_LBA
        for (entry in others) {
            if (candidate + needed <= entry.first) return candidate
            if (entry.first + entry.blocks > candidate) {
                candidate = entry.first + entry.blocks
            }
        }
        if (candidate + needed > totalSectors) error("disk full")
        return candidate
    }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').ifEmpty { "file.bin" }
        val builder = StringBuilder()
        for (c in base) {
            if (c.code in 33..126 && c != '/') builder.append(c) else builder.append('_')
            if (builder.length == MAX_NAME) break
        }
        return builder.toString().ifEmpty { "file.bin" }
    }

    override fun close() {
        raf.close()
    }
}

private fun readU64(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 7 downTo 0) {
        value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
    }
    return value
}

private fun writeU64(bytes: ByteArray, offset: Int, value: Long) {
    for (i in 0..7) {
        bytes[offset + i] = ((value ushr (8 * i)) and 0xff).toByte()
    }
}
