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
 * - `<id>.resolved`: the chosen repo file (path, size, sha256), so a resume targets the same file
 * - `<name>.part` + `<name>.part.target`: the partial download and the sha256 it is for
 * - `<name>`: the verified model, renamed from `.part` only after the checksum matched
 */
class ModelInstaller(
    private val dir: File,
    private val http: HttpFetcher = HttpFetcher(),
    private val baseUrl: String = HuggingFace.BASE_URL,
) {
    fun resolved(spec: ModelSpec): RepoFile? {
        val f = File(dir, "${spec.id}.resolved")
        if (!f.isFile) return null
        val lines = f.readLines()
        if (lines.size < 3) return null
        return RepoFile(lines[0], lines[1].toLongOrNull() ?: return null, lines[2].ifBlank { null })
    }

    fun installedFile(spec: ModelSpec): File? {
        val r = resolved(spec) ?: return null
        val f = modelFile(r)
        return f.takeIf { it.isFile && it.length() == r.size }
    }

    fun partialBytes(spec: ModelSpec): Long = resolved(spec)?.let { partFile(it).length() } ?: 0L

    /** Looks the file up in the repo listing (cached after the first success). */
    fun resolve(spec: ModelSpec): RepoFile {
        resolved(spec)?.let { return it }
        val files = HuggingFace.parseTree(http.fetchText(HuggingFace.treeUrl(spec.repo, baseUrl)))
        val pick = HuggingFace.selectGguf(files, spec.quant)
            ?: throw IOException("No ${spec.quant} GGUF found in ${spec.repo}")
        dir.mkdirs()
        File(dir, "${spec.id}.resolved").writeText("${pick.path}\n${pick.size}\n${pick.sha256 ?: ""}\n")
        return pick
    }

    fun install(
        spec: ModelSpec,
        isCancelled: () -> Boolean = { false },
        onProgress: (InstallProgress) -> Unit = {},
    ): File {
        installedFile(spec)?.let { return it }
        onProgress(InstallProgress.Resolving)
        val file = resolve(spec)
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
        onProgress(InstallProgress.Downloading(part.length(), file.size))
        http.download(
            url = HuggingFace.resolveUrl(spec.repo, file.path, baseUrl),
            partFile = part,
            expectedSize = file.size,
            plan = plan,
            isCancelled = isCancelled,
        ) { onProgress(InstallProgress.Downloading(it, file.size)) }

        file.sha256?.let { sha ->
            val ok = Sha256.matches(part, sha) { onProgress(InstallProgress.Verifying(it, file.size)) }
            if (!ok) {
                part.delete(); target.delete()
                File(dir, "${spec.id}.resolved").delete()
                throw ChecksumMismatchException(file.path)
            }
        }
        val dest = modelFile(file)
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Could not move ${part.name} into place")
        target.delete()
        return dest
    }

    fun delete(spec: ModelSpec) {
        resolved(spec)?.let { r ->
            modelFile(r).delete()
            partFile(r).delete()
            File(dir, partFile(r).name + ".target").delete()
        }
        File(dir, "${spec.id}.resolved").delete()
    }

    private fun modelFile(r: RepoFile) = File(dir, r.path.substringAfterLast('/'))
    private fun partFile(r: RepoFile) = File(dir, r.path.substringAfterLast('/') + ".part")
}
