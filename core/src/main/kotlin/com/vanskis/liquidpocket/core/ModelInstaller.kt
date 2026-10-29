package com.vanskis.liquidpocket.core

import java.io.File
import java.io.IOException

/** Download progress for the UI and notification. */
sealed interface InstallProgress {
    data object Resolving : InstallProgress
    data class Downloading(val bytes: Long, val total: Long) : InstallProgress
    data class Verifying(val bytes: Long, val total: Long) : InstallProgress
}

class ChecksumMismatchException(path: String) : IOException("Checksum mismatch for $path; the download was deleted")

/**
 * Owns the files of each model in [dir]:
 * - `<id>.resolved`: the chosen repo files (path, size, sha256 per line triple: model, then the
 *   mmproj if the spec has one), so a resume targets the same files
 * - `<name>.part` + `<name>.part.target`: the partial download and the sha256 it is for
 * - `<name>`: the verified file, renamed from `.part` only after the checksum matched
 */
class ModelInstaller(
    private val dir: File,
    private val http: HttpFetcher = HttpFetcher(),
    private val baseUrl: String = HuggingFace.BASE_URL,
) {
    /** The resolved model file; null if unresolved or the record predates the mmproj. */
    fun resolved(spec: ModelSpec): RepoFile? = resolvedPair(spec)?.first

    private fun resolvedPair(spec: ModelSpec): Pair<RepoFile, RepoFile?>? {
        val f = File(dir, "${spec.id}.resolved")
        if (!f.isFile) return null
        val lines = f.readLines()
        val model = parse(lines, 0) ?: return null
        if (spec.mmprojQuants == null) return model to null
        return model to (parse(lines, 3) ?: return null)
    }

    private fun parse(lines: List<String>, at: Int): RepoFile? {
        if (lines.size < at + 3) return null
        return RepoFile(lines[at], lines[at + 1].toLongOrNull() ?: return null, lines[at + 2].ifBlank { null })
    }

    fun installedFile(spec: ModelSpec): File? {
        val (model, proj) = resolvedPair(spec) ?: return null
        if (proj != null && complete(proj) == null) return null
        return complete(model)
    }

    fun installedMmproj(spec: ModelSpec): File? {
        val proj = resolvedPair(spec)?.second ?: return null
        return complete(proj)
    }

    fun partialBytes(spec: ModelSpec): Long =
        resolvedPair(spec)?.let { (m, p) -> partFile(m).length() + (p?.let { partFile(it).length() } ?: 0L) } ?: 0L

    /** Looks the files up in the repo listing (cached after the first success). */
    fun resolve(spec: ModelSpec): RepoFile {
        resolvedPair(spec)?.let { return it.first }
        val files = HuggingFace.parseTree(http.fetchText(HuggingFace.treeUrl(spec.repo, baseUrl)))
        val pick = HuggingFace.selectGguf(files, spec.quant)
            ?: throw IOException("No ${spec.quant} GGUF found in ${spec.repo}")
        val proj = spec.mmprojQuants?.let {
            HuggingFace.selectMmproj(files, it) ?: throw IOException("No mmproj GGUF found in ${spec.repo}")
        }
        dir.mkdirs()
        File(dir, "${spec.id}.resolved").writeText(
            listOfNotNull(pick, proj).joinToString("") { "${it.path}\n${it.size}\n${it.sha256 ?: ""}\n" },
        )
        return pick
    }

    fun install(
        spec: ModelSpec,
        isCancelled: () -> Boolean = { false },
        onProgress: (InstallProgress) -> Unit = {},
    ): File {
        installedFile(spec)?.let { return it }
        onProgress(InstallProgress.Resolving)
        resolve(spec)
        val (model, proj) = resolvedPair(spec)!!
        val total = model.size + (proj?.size ?: 0L)
        val dest = fetch(spec, model, 0L, total, isCancelled, onProgress)
        proj?.let { fetch(spec, it, model.size, total, isCancelled, onProgress) }
        return dest
    }

    /** Downloads and verifies one file; [done] is the bytes of earlier files, for combined progress. */
    private fun fetch(
        spec: ModelSpec,
        file: RepoFile,
        done: Long,
        total: Long,
        isCancelled: () -> Boolean,
        onProgress: (InstallProgress) -> Unit,
    ): File {
        complete(file)?.let { onProgress(InstallProgress.Downloading(done + file.size, total)); return it }
        val part = partFile(file)
        val target = File(dir, part.name + ".target")
        val plan = ResumePlanner.plan(
            partialBytes = part.length(),
            expectedSize = file.size,
            partialTarget = target.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null },
            target = file.sha256,
        )
        if (plan == StartPlan.Fresh) {
            part.delete()
            target.writeText(file.sha256 ?: "")
        }
        onProgress(InstallProgress.Downloading(done + part.length(), total))
        http.download(
            url = HuggingFace.resolveUrl(spec.repo, file.path, baseUrl),
            partFile = part,
            expectedSize = file.size,
            plan = plan,
            isCancelled = isCancelled,
        ) { onProgress(InstallProgress.Downloading(done + it, total)) }

        file.sha256?.let { sha ->
            val ok = Sha256.matches(part, sha) { onProgress(InstallProgress.Verifying(done + it, total)) }
            if (!ok) {
                part.delete(); target.delete()
                File(dir, "${spec.id}.resolved").delete()
                throw ChecksumMismatchException(file.path)
            }
        }
        val dest = localFile(file)
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Could not move ${part.name} into place")
        target.delete()
        return dest
    }

    fun delete(spec: ModelSpec) {
        val lines = File(dir, "${spec.id}.resolved").takeIf { it.isFile }?.readLines().orEmpty()
        // Lenient: also clears files of a record written before the mmproj was tracked.
        listOfNotNull(parse(lines, 0), parse(lines, 3)).let { files ->
            for (r in files) {
                localFile(r).delete()
                partFile(r).delete()
                File(dir, partFile(r).name + ".target").delete()
            }
        }
        File(dir, "${spec.id}.resolved").delete()
    }

    private fun complete(r: RepoFile): File? = localFile(r).takeIf { it.isFile && it.length() == r.size }
    private fun localFile(r: RepoFile) = File(dir, r.path.substringAfterLast('/'))
    private fun partFile(r: RepoFile) = File(dir, r.path.substringAfterLast('/') + ".part")
}
