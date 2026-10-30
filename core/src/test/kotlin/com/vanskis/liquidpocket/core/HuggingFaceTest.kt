package com.vanskis.liquidpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HuggingFaceTest {
    private val tree = """
        [
          {"type":"file","path":".gitattributes","size":1570},
          {"type":"file","path":"mmproj-LFM3-Omni-2B-Q8_0.gguf","size":600000000,
           "lfs":{"oid":"AAAA","size":600000000,"pointerSize":135}},
          {"type":"file","path":"LFM3-Omni-2B-Q4_K_M.gguf","size":135,
           "lfs":{"oid":"ABCDEF0123","size":1300000000,"pointerSize":135}},
          {"type":"file","path":"mmproj-LFM3-Omni-2B-Q4_K_M.gguf","size":300000000,
           "lfs":{"oid":"BBBB","size":300000000,"pointerSize":135}},
          {"type":"file","path":"LFM3-Omni-2B-Q8_0.gguf","size":2200000000,
           "lfs":{"oid":"CCCC","size":2200000000,"pointerSize":135}},
          {"type":"directory","path":"extras","size":0}
        ]
    """.trimIndent()

    @Test fun selectsMainQ4KmAndExcludesMmproj() {
        val pick = HuggingFace.selectGguf(HuggingFace.parseTree(tree), "Q4_K_M")
        assertEquals("LFM3-Omni-2B-Q4_K_M.gguf", pick?.path)
    }

    @Test fun usesLfsSizeAndShaNotPointer() {
        val pick = HuggingFace.selectGguf(HuggingFace.parseTree(tree), "q4_k_m")!!
        assertEquals(1_300_000_000L, pick.size)
        assertEquals("abcdef0123", pick.sha256)
    }

    @Test fun skipsDirectoriesAndKeepsNonLfsFiles() {
        val files = HuggingFace.parseTree(tree)
        assertEquals(5, files.size)
        assertNull(files.first { it.path == ".gitattributes" }.sha256)
    }

    @Test fun mmprojNeverSelectedEvenWhenShorterOrAlone() {
        val main = RepoFile("LFM3-Omni-2B-Q4_K_M.gguf", 1, null)
        val proj = RepoFile("mmproj-Q4_K_M.gguf", 1, null)
        assertEquals(main, HuggingFace.selectGguf(listOf(proj, main), "Q4_K_M"))
        assertNull(HuggingFace.selectGguf(listOf(proj), "Q4_K_M"))
    }

    @Test fun mmprojFollowsPreferenceOrder() {
        val files = HuggingFace.parseTree(tree)
        assertEquals("mmproj-LFM3-Omni-2B-Q8_0.gguf", HuggingFace.selectMmproj(files, listOf("q8_0", "F16"))?.path)
        assertEquals("mmproj-LFM3-Omni-2B-Q4_K_M.gguf", HuggingFace.selectMmproj(files, listOf("F16", "Q4_K_M"))?.path)
        assertEquals("AAAA".lowercase(), HuggingFace.selectMmproj(files, listOf("Q8_0"))?.sha256)
    }

    @Test fun mmprojFallsBackToSmallestAndIgnoresMainModels() {
        val files = HuggingFace.parseTree(tree)
        assertEquals("mmproj-LFM3-Omni-2B-Q4_K_M.gguf", HuggingFace.selectMmproj(files, listOf("F16", "BF16"))?.path)
        assertNull(HuggingFace.selectMmproj(listOf(RepoFile("LFM3-Q8_0.gguf", 1, null)), listOf("Q8_0")))
    }

    @Test fun noMatchReturnsNull() {
        assertNull(HuggingFace.selectGguf(HuggingFace.parseTree(tree), "Q2_K"))
    }

    @Test fun skipsSplitShards() {
        val files = listOf(RepoFile("M-Q4_K_M-00001-of-00002.gguf", 1, null))
        assertNull(HuggingFace.selectGguf(files, "Q4_K_M"))
    }

    @Test fun urls() {
        assertEquals("https://huggingface.co/api/models/LiquidAI/LFM3-Omni-2B-GGUF/tree/main",
            HuggingFace.treeUrl("LiquidAI/LFM3-Omni-2B-GGUF"))
        assertEquals("https://huggingface.co/LiquidAI/X/resolve/main/sub/a%20b.gguf",
            HuggingFace.resolveUrl("LiquidAI/X", "sub/a b.gguf"))
    }

    @Test fun catalogOffersBothAndOnlyOmniHasProjector() {
        assertEquals(listOf(ModelCatalog.LFM3_1_7B, ModelCatalog.LFM3_OMNI_2B), ModelCatalog.offered)
        assertEquals(listOf("Q8_0", "F16", "BF16"), ModelCatalog.LFM3_OMNI_2B.mmprojQuants)
        assertNull(ModelCatalog.LFM3_1_7B.mmprojQuants)
        assertEquals("LiquidAI/LFM3-1.7B-GGUF", ModelCatalog.byId("lfm3-1.7b")?.repo)
        assertNull("the retired 8B is gone", ModelCatalog.byId("lfm2.5-8b-a1b"))
    }
}
