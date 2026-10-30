package com.vanskis.liquidpocket.core

/**
 * A Heretic (uncensored) build of a catalogued model, downloaded from a Hugging Face GGUF repo the
 * user enters. It keeps the base model's settings under its own id, so both can be installed.
 */
object Heretic {
    const val ID_SUFFIX = "-heretic-"

    /** Tried after the base model's quant: community repos rarely ship the same set. */
    val QUANTS = listOf("Q4_K_M", "Q4_0", "Q4_K_S", "Q5_K_M", "Q8_0")

    private val REPO = Regex("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*")

    /** Whether [text] looks like a Hugging Face repo id, owner/name. */
    fun isRepo(text: String) = REPO.matches(text.trim())

    fun spec(base: ModelSpec, repo: String): ModelSpec {
        val clean = repo.trim()
        return base.copy(
            id = base.id + ID_SUFFIX + clean.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'),
            displayName = base.displayName + " Heretic",
            repo = clean,
            fallbackQuants = QUANTS - base.quant,
        )
    }

    /** The Heretic build when [use] is on and [repo] is valid, else [base]. */
    fun chosen(base: ModelSpec, use: Boolean, repo: String): ModelSpec =
        if (use && isRepo(repo)) spec(base, repo) else base

    /** The spec a download job was started for: [id] must be what [spec] gives for [repo]. */
    fun byId(id: String, repo: String?): ModelSpec? {
        if (repo == null || !isRepo(repo)) return null
        return ModelCatalog.all.map { spec(it, repo) }.firstOrNull { it.id == id }
    }
}
