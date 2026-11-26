/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.research

import android.content.Context
import android.os.StatFs
import android.util.AtomicFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

internal data class StorageUsageSummary(
    val totalBytes: Long,
    val mediaBytes: Long,
    val metadataBytes: Long,
    val otherBytes: Long,
    val fileCount: Int,
    val freeBytes: Long,
    val limitBytes: Long,
    val reserveBytes: Long,
    val verifiedPrunableBytes: Long,
    val verifiedPrunableFiles: Int,
    val hasVerifiedExport: Boolean,
) {
    val remainingEvidenceBudgetBytes: Long get() = max(0L, limitBytes - totalBytes)
    val usableFreeAboveReserveBytes: Long get() = max(0L, freeBytes - reserveBytes)
    val effectiveAvailableForLabBytes: Long get() = minOf(remainingEvidenceBudgetBytes, usableFreeAboveReserveBytes)
}

internal data class StorageSpaceDecision(
    val allowed: Boolean,
    val reason: String?,
    val usage: StorageUsageSummary,
    val upcomingEstimateBytes: Long,
)

internal data class ExportedEntryReceipt(
    val archivePath: String,
    val sizeBytes: Long,
    val sha256Hex: String,
    val prunableMedia: Boolean,
    val pruned: Boolean = false,
)

internal data class ExportReceipt(
    val exportedEpochMs: Long,
    val zipTotalBytes: Long,
    val zipSha256Hex: String,
    val entryCount: Int,
    val readbackVerified: Boolean,
    val readbackBytes: Long,
    val readbackSha256Hex: String?,
    val entries: List<ExportedEntryReceipt>,
)

internal data class ExportVerificationResult(
    val verified: Boolean,
    val message: String,
    val receipt: ExportReceipt,
)

internal data class PruneResult(
    val executed: Boolean,
    val deletedFiles: Int,
    val freedBytes: Long,
    val message: String,
)

/**
 * Manages Research Lab evidence storage (20 GB default, user-configurable),
 * keeps a separate 5 GiB device free-space safety reserve, avoids overlapping
 * directory double-counting, performs zero-temp-copy streaming ZIP export with
 * SHA-256 readback verification, and prunes verified exported media ONLY on
 * explicit operator action.
 */
internal object LabStorageManager {
    const val DEFAULT_EVIDENCE_LIMIT_BYTES: Long = 20_000_000_000L
    const val MIN_EVIDENCE_LIMIT_BYTES: Long = 2_000_000_000L
    const val MAX_EVIDENCE_LIMIT_BYTES: Long = 100_000_000_000L
    const val DEVICE_FREE_RESERVE_BYTES: Long = 5L * 1024L * 1024L * 1024L // 5 GiB

    val LIMIT_PRESETS_GB: List<Int> = listOf(5, 10, 20, 30, 50)

    private const val PREFS_NAME = "opencinecam_research_prefs"
    private const val KEY_EVIDENCE_LIMIT_BYTES = "evidence_limit_bytes"
    private const val VERIFIED_RECEIPT_FILE = "verified_export_receipt.json"
    private const val LAST_RECEIPT_FILE = "export_receipt_last.json"
    private const val PRUNE_AUDIT_FILE = "prune_history.jsonl"
    private const val STREAM_BUFFER_BYTES = 64 * 1024

    private val PRUNABLE_MEDIA_EXTENSIONS = setOf("mp4", "dng", "raw", "yuv", "h265", "hevc", "bin")
    private val METADATA_EXTENSIONS = setOf("json", "jsonl", "txt", "md", "log", "csv")

    fun loadEvidenceLimitBytes(context: Context): Long {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_EVIDENCE_LIMIT_BYTES, DEFAULT_EVIDENCE_LIMIT_BYTES)
        return clampLimitBytes(raw)
    }

    fun saveEvidenceLimitBytes(context: Context, limitBytes: Long): Long {
        val clamped = clampLimitBytes(limitBytes)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_EVIDENCE_LIMIT_BYTES, clamped)
            .apply()
        return clamped
    }

    fun clampLimitBytes(bytes: Long): Long =
        bytes.coerceIn(MIN_EVIDENCE_LIMIT_BYTES, MAX_EVIDENCE_LIMIT_BYTES)

    fun isPrunableMediaFile(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in PRUNABLE_MEDIA_EXTENSIONS
    }

    fun availableDeviceBytes(root: File): Long = runCatching {
        if (!root.exists()) root.mkdirs()
        StatFs(root.absolutePath).availableBytes
    }.getOrElse { 0L }

    /**
     * Collects deduplicated physical files under [root] and [additionalDirs] without
     * double-counting nested roots or symlink aliases.
     */
    fun collectUniqueFiles(
        root: File,
        additionalDirs: List<File> = emptyList(),
    ): List<Pair<String, File>> {
        val canonicalRoots = buildList {
            add("research" to root)
            additionalDirs.forEachIndexed { index, dir ->
                val label = dir.name.ifBlank { "extra_$index" }
                add(label to dir)
            }
        }
        val visitedCanonicalFiles = HashSet<String>()
        val collected = mutableListOf<Pair<String, File>>()

        for ((prefix, dir) in canonicalRoots) {
            if (!dir.exists()) continue
            val dirCanon = runCatching { dir.canonicalFile }.getOrElse { dir.absoluteFile }
            dir.walkTopDown()
                .onFail { _, _ -> }
                .filter { file ->
                    file.isFile &&
                        !file.name.endsWith(".new") &&
                        !file.name.endsWith(".bak") &&
                        !file.name.endsWith(".tmp") &&
                        file.name != VERIFIED_RECEIPT_FILE &&
                        file.name != LAST_RECEIPT_FILE &&
                        file.name != PRUNE_AUDIT_FILE
                }
                .forEach { file ->
                    val fileCanon = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
                    if (!visitedCanonicalFiles.add(fileCanon)) return@forEach
                    val rel = runCatching {
                        val rootCanon = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
                        val fCanonFile = File(fileCanon)
                        val underPrimary = fCanonFile.toPath().startsWith(rootCanon.toPath())
                        if (underPrimary) {
                            fCanonFile.relativeTo(rootCanon).invariantSeparatorsPath
                        } else {
                            val subRel = fCanonFile.relativeTo(dirCanon).invariantSeparatorsPath
                            "$prefix/$subRel"
                        }
                    }.getOrElse { "$prefix/${file.name}" }
                    collected += rel to file
                }
        }
        return collected.sortedBy { it.first }
    }

    fun measureUsage(
        root: File,
        additionalDirs: List<File> = emptyList(),
        freeBytes: Long = availableDeviceBytes(root),
        limitBytes: Long = DEFAULT_EVIDENCE_LIMIT_BYTES,
    ): StorageUsageSummary {
        var mediaBytes = 0L
        var metadataBytes = 0L
        var otherBytes = 0L
        val files = collectUniqueFiles(root, additionalDirs)
        val fileMap = HashMap<String, File>(files.size)
        for ((rel, file) in files) {
            val len = file.length().coerceAtLeast(0L)
            fileMap[rel] = file
            when (file.extension.lowercase(Locale.ROOT)) {
                in PRUNABLE_MEDIA_EXTENSIONS -> mediaBytes += len
                in METADATA_EXTENSIONS -> metadataBytes += len
                else -> otherBytes += len
            }
        }
        val receipt = readVerifiedReceipt(root)
        var verifiedPrunableBytes = 0L
        var verifiedPrunableFiles = 0
        if (receipt != null && receipt.readbackVerified) {
            for (entry in receipt.entries) {
                if (!entry.prunableMedia || entry.pruned) continue
                val diskFile = fileMap[entry.archivePath] ?: continue
                if (diskFile.isFile && isPrunableMediaFile(diskFile) && diskFile.length() == entry.sizeBytes) {
                    verifiedPrunableBytes += entry.sizeBytes
                    verifiedPrunableFiles += 1
                }
            }
        }
        val totalBytes = mediaBytes + metadataBytes + otherBytes
        return StorageUsageSummary(
            totalBytes = totalBytes,
            mediaBytes = mediaBytes,
            metadataBytes = metadataBytes,
            otherBytes = otherBytes,
            fileCount = files.size,
            freeBytes = freeBytes,
            limitBytes = clampLimitBytes(limitBytes),
            reserveBytes = DEVICE_FREE_RESERVE_BYTES,
            verifiedPrunableBytes = verifiedPrunableBytes,
            verifiedPrunableFiles = verifiedPrunableFiles,
            hasVerifiedExport = receipt?.readbackVerified == true,
        )
    }

    fun estimateAttemptBytes(case: LabCase): Long {
        val durationSec = if (case.longer) 35L else 8L
        val rawBitrateBps = when (case.path) {
            LabPath.RAW_CAMERA, LabPath.RAW_NATIVE -> (case.width.toLong() * case.height * 12L / 8L) * case.fps.coerceAtLeast(1)
            LabPath.DIRECT_HEVC, LabPath.DIRECT_HEVC_PREVIEW, LabPath.DIRECT_HEVC_SURFACETEXTURE,
            LabPath.DIRECT_HEVC_SURFACETEXTURE_360P, LabPath.LOG_REGULAR_4K60, LabPath.GPU_HEVC_4K60 -> 60_000_000L
            LabPath.LOG_REGULAR_4K30, LabPath.GPU_HEVC_4K30, LabPath.LOG, LabPath.HLG, LabPath.HDR10, LabPath.HEVC, LabPath.AVC ->
                if (case.fps >= 60) 60_000_000L else 40_000_000L
            LabPath.CODEC_SYNTHETIC_MATRIX -> 45_000_000L * 4L / durationSec.coerceAtLeast(1)
            LabPath.ISP_TONEMAP_AB, LabPath.ISP_COLOR_CORRECTION_PROBE, LabPath.ISP_CUVA_HDR_PROBE, LabPath.SOURCE_FORMAT_PROBE -> 24_000_000L
            LabPath.BREAKTHROUGH_4K60_INPUT, LabPath.BREAKTHROUGH_CUVA_VIDEO, LabPath.BREAKTHROUGH_SYNTH_4K60_PRIORITY,
            LabPath.BREAKTHROUGH_HIDDEN_RAW, LabPath.BREAKTHROUGH_BB10_CLIPS -> 40_000_000L
            LabPath.CODEC_LEVEL_PAIR -> 8_000_000L
        }
        val estimatedMedia = (rawBitrateBps / 8L) * durationSec
        return ((estimatedMedia * 5L) / 4L).coerceAtLeast(4_000_000L)
    }

    fun checkSpace(
        root: File,
        additionalDirs: List<File> = emptyList(),
        freeBytes: Long = availableDeviceBytes(root),
        limitBytes: Long = DEFAULT_EVIDENCE_LIMIT_BYTES,
        upcomingEstimateBytes: Long = 0L,
    ): StorageSpaceDecision {
        val usage = measureUsage(root, additionalDirs, freeBytes, limitBytes)
        val reserve = usage.reserveBytes
        val headroomNeeded = upcomingEstimateBytes.coerceAtLeast(0L)
        if (freeBytes < reserve || (freeBytes - headroomNeeded) < reserve) {
            return StorageSpaceDecision(
                allowed = false,
                reason = "Research needs at least ${formatGiB(reserve)} free device storage kept in reserve " +
                    "(available ${formatBytes(freeBytes)}, estimate ${formatBytes(headroomNeeded)}).",
                usage = usage,
                upcomingEstimateBytes = headroomNeeded,
            )
        }
        if (usage.totalBytes >= usage.limitBytes || (usage.totalBytes + headroomNeeded) > usage.limitBytes) {
            return StorageSpaceDecision(
                allowed = false,
                reason = "Research evidence (${formatBytes(usage.totalBytes)} + est. ${formatBytes(headroomNeeded)}) " +
                    "would exceed the configured ${formatBytes(usage.limitBytes)} limit.",
                usage = usage,
                upcomingEstimateBytes = headroomNeeded,
            )
        }
        return StorageSpaceDecision(
            allowed = true,
            reason = null,
            usage = usage,
            upcomingEstimateBytes = headroomNeeded,
        )
    }

    /**
     * Streams all evidence files directly into the destination ZIP output stream without
     * creating temporary archives in local storage, computes SHA-256 of every archived
     * file and of the output ZIP byte stream, and then performs a streaming readback SHA-256
     * verification via [readbackProvider].
     */
    fun streamingVerifiedExport(
        root: File,
        outputProvider: () -> OutputStream,
        readbackProvider: () -> InputStream,
        additionalDirs: List<File> = emptyList(),
    ): ExportVerificationResult {
        val files = collectUniqueFiles(root, additionalDirs)
        val zipDigest = MessageDigest.getInstance("SHA-256")
        val entryReceipts = mutableListOf<ExportedEntryReceipt>()
        val copyBuffer = ByteArray(STREAM_BUFFER_BYTES)
        var writtenZipBytes = 0L

        outputProvider().use { rawOut ->
            val countingOut = ByteCountingOutputStream(BufferedOutputStream(rawOut, STREAM_BUFFER_BYTES))
            DigestOutputStream(countingOut, zipDigest).use { digestOut ->
                ZipOutputStream(digestOut).use { zip ->
                    for ((relPath, file) in files) {
                        if (!file.isFile) continue
                        val fileDigest = MessageDigest.getInstance("SHA-256")
                        var entryBytes = 0L
                        zip.putNextEntry(ZipEntry(relPath))
                        BufferedInputStream(file.inputStream(), STREAM_BUFFER_BYTES).use { input ->
                            while (true) {
                                val read = input.read(copyBuffer)
                                if (read <= 0) break
                                zip.write(copyBuffer, 0, read)
                                fileDigest.update(copyBuffer, 0, read)
                                entryBytes += read
                            }
                        }
                        zip.closeEntry()
                        entryReceipts += ExportedEntryReceipt(
                            archivePath = relPath,
                            sizeBytes = entryBytes,
                            sha256Hex = fileDigest.digest().toHex(),
                            prunableMedia = isPrunableMediaFile(file),
                        )
                    }
                    // Include an embedded manifest JSON inside the archive itself.
                    val manifestJson = buildManifestJson(entryReceipts).toByteArray(Charsets.UTF_8)
                    zip.putNextEntry(ZipEntry("export-manifest.json"))
                    zip.write(manifestJson)
                    zip.closeEntry()
                }
                digestOut.flush()
            }
            countingOut.flush()
            writtenZipBytes = countingOut.bytesWritten
        }

        val expectedZipSha256 = zipDigest.digest().toHex()
        val readbackDigest = MessageDigest.getInstance("SHA-256")
        var readbackBytes = 0L
        readbackProvider().use { input ->
            val buffered = BufferedInputStream(input, STREAM_BUFFER_BYTES)
            while (true) {
                val read = buffered.read(copyBuffer)
                if (read <= 0) break
                readbackDigest.update(copyBuffer, 0, read)
                readbackBytes += read
            }
        }
        val actualReadbackSha256 = readbackDigest.digest().toHex()
        val verified = readbackBytes == writtenZipBytes &&
            actualReadbackSha256.equals(expectedZipSha256, ignoreCase = true)

        val receipt = ExportReceipt(
            exportedEpochMs = System.currentTimeMillis(),
            zipTotalBytes = writtenZipBytes,
            zipSha256Hex = expectedZipSha256,
            entryCount = entryReceipts.size,
            readbackVerified = verified,
            readbackBytes = readbackBytes,
            readbackSha256Hex = actualReadbackSha256,
            entries = entryReceipts,
        )
        persistReceipt(File(root, LAST_RECEIPT_FILE), receipt)
        if (verified) {
            persistReceipt(File(root, VERIFIED_RECEIPT_FILE), receipt)
        }
        val message = if (verified) {
            "Verified ZIP export (${entryReceipts.size} files, ${formatBytes(writtenZipBytes)}, SHA-256 ${expectedZipSha256.take(12)})."
        } else {
            "ZIP export readback mismatch (wrote $writtenZipBytes bytes/${expectedZipSha256.take(12)}, read $readbackBytes bytes/${actualReadbackSha256.take(12)}). Originals untouched."
        }
        return ExportVerificationResult(verified = verified, message = message, receipt = receipt)
    }

    /**
     * Deletes ONLY heavy media files (MP4/DNG/RAW/YUV/HEVC) listed in the last readback-verified
     * export receipt whose disk size still matches the verified export. Never deletes metadata,
     * JSONL logs, knowledge base, reports, or unverified media.
     */
    fun pruneVerifiedExportedMedia(
        root: File,
        additionalDirs: List<File> = emptyList(),
    ): PruneResult {
        val receipt = readVerifiedReceipt(root)
        if (receipt == null || !receipt.readbackVerified) {
            return PruneResult(
                executed = false,
                deletedFiles = 0,
                freedBytes = 0L,
                message = "Pruning requires a completed SHA-256 readback-verified export first.",
            )
        }
        val filesByRel = collectUniqueFiles(root, additionalDirs).toMap()
        val updatedEntries = mutableListOf<ExportedEntryReceipt>()
        var deletedFiles = 0
        var freedBytes = 0L

        for (entry in receipt.entries) {
            if (!entry.prunableMedia || entry.pruned) {
                updatedEntries += entry
                continue
            }
            val target = filesByRel[entry.archivePath]
            if (target == null || !target.isFile || !isPrunableMediaFile(target)) {
                updatedEntries += entry
                continue
            }
            val currentSize = target.length()
            if (currentSize != entry.sizeBytes) {
                updatedEntries += entry
                continue
            }
            if (target.delete()) {
                deletedFiles += 1
                freedBytes += currentSize
                updatedEntries += entry.copy(pruned = true)
            } else {
                updatedEntries += entry
            }
        }

        if (deletedFiles > 0) {
            persistReceipt(File(root, VERIFIED_RECEIPT_FILE), receipt.copy(entries = updatedEntries))
            appendPruneAudit(root, receipt.zipSha256Hex, deletedFiles, freedBytes)
        }

        val message = if (deletedFiles > 0) {
            "Pruned $deletedFiles verified exported media file(s), freeing ${formatBytes(freedBytes)}. Logs & knowledge preserved."
        } else {
            "No eligible verified exported media files remained to prune."
        }
        return PruneResult(
            executed = deletedFiles > 0,
            deletedFiles = deletedFiles,
            freedBytes = freedBytes,
            message = message,
        )
    }

    fun readVerifiedReceipt(root: File): ExportReceipt? {
        val file = File(root, VERIFIED_RECEIPT_FILE)
        if (!file.isFile) return null
        return runCatching {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val arr = obj.optJSONArray("entries") ?: JSONArray()
            val entries = buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(
                        ExportedEntryReceipt(
                            archivePath = item.getString("archivePath"),
                            sizeBytes = item.getLong("sizeBytes"),
                            sha256Hex = item.getString("sha256Hex"),
                            prunableMedia = item.optBoolean("prunableMedia", false),
                            pruned = item.optBoolean("pruned", false),
                        )
                    )
                }
            }
            ExportReceipt(
                exportedEpochMs = obj.getLong("exportedEpochMs"),
                zipTotalBytes = obj.getLong("zipTotalBytes"),
                zipSha256Hex = obj.getString("zipSha256Hex"),
                entryCount = obj.getInt("entryCount"),
                readbackVerified = obj.optBoolean("readbackVerified", false),
                readbackBytes = obj.optLong("readbackBytes", 0L),
                readbackSha256Hex = obj.optString("readbackSha256Hex").takeIf { it.isNotBlank() },
                entries = entries,
            )
        }.getOrNull()
    }

    private fun persistReceipt(file: File, receipt: ExportReceipt) {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val json = JSONObject().apply {
            put("exportedEpochMs", receipt.exportedEpochMs)
            put("zipTotalBytes", receipt.zipTotalBytes)
            put("zipSha256Hex", receipt.zipSha256Hex)
            put("entryCount", receipt.entryCount)
            put("readbackVerified", receipt.readbackVerified)
            put("readbackBytes", receipt.readbackBytes)
            put("readbackSha256Hex", receipt.readbackSha256Hex)
            put(
                "entries",
                JSONArray(receipt.entries.map { e ->
                    JSONObject().apply {
                        put("archivePath", e.archivePath)
                        put("sizeBytes", e.sizeBytes)
                        put("sha256Hex", e.sha256Hex)
                        put("prunableMedia", e.prunableMedia)
                        put("pruned", e.pruned)
                    }
                })
            )
        }
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (t: Throwable) {
            atomic.failWrite(out)
            throw t
        }
    }

    private fun appendPruneAudit(root: File, exportSha256: String, deletedFiles: Int, freedBytes: Long) {
        runCatching {
            val line = JSONObject().apply {
                put("epochMs", System.currentTimeMillis())
                put("exportSha256", exportSha256)
                put("deletedFiles", deletedFiles)
                put("freedBytes", freedBytes)
            }.toString() + "\n"
            FileOutputStream(File(root, PRUNE_AUDIT_FILE), true).use {
                it.write(line.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        }
    }

    private fun buildManifestJson(entries: List<ExportedEntryReceipt>): String = JSONObject().apply {
        put("generatedEpochMs", System.currentTimeMillis())
        put("entryCount", entries.size)
        put(
            "entries",
            JSONArray(entries.map { e ->
                JSONObject().apply {
                    put("path", e.archivePath)
                    put("sizeBytes", e.sizeBytes)
                    put("sha256", e.sha256Hex)
                    put("prunableMedia", e.prunableMedia)
                }
            })
        )
    }.toString(2)

    fun formatBytes(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        if (gb >= 1.0) return String.format(Locale.US, "%.2f GB", gb)
        val mb = bytes / 1_000_000.0
        if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb)
        val kb = bytes / 1_000.0
        return String.format(Locale.US, "%.0f KB", kb)
    }

    private fun formatGiB(bytes: Long): String {
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f GiB", gib)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.US, it) }

    private class ByteCountingOutputStream(delegate: OutputStream) : FilterOutputStream(delegate) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten += 1L
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len.toLong()
        }
    }
}
