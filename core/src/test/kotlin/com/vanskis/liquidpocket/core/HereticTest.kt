package com.vanskis.liquidpocket.core

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class HereticTest {
    private val base = ModelCatalog.LFM3_1_7B

    @Test
    fun `a heretic build keeps the base model's settings under its own id`() {
        val spec = Heretic.spec(base, " someone/LFM3-1.7B-heretic-GGUF ")
        assertEquals("lfm3-1.7b-heretic-someone-lfm3-1-7b-heretic-gguf", spec.id)
        assertEquals("someone/LFM3-1.7B-heretic-GGUF", spec.repo)
        assertEquals("LFM3-1.7B Heretic", spec.displayName)
        assertEquals(base.quant, spec.quant)
        assertFalse(base.quant in spec.fallbackQuants)
        assertEquals(ModelCatalog.LFM3_OMNI_2B.mmprojQuants, Heretic.spec(ModelCatalog.LFM3_OMNI_2B, "a/b").mmprojQuants)
    }

    @Test
    fun `standard is used until heretic is on with a valid repo`() {
        assertSame(base, Heretic.chosen(base, use = false, repo = "a/b"))
        assertSame(base, Heretic.chosen(base, use = true, repo = ""))
        assertSame(base, Heretic.chosen(base, use = true, repo = "not a repo"))
        assertEquals("a/b", Heretic.chosen(base, use = true, repo = "a/b").repo)
        assertTrue(Heretic.isRepo("user.name/model_v2-GGUF"))
        assertFalse(Heretic.isRepo("https://huggingface.co/a/b"))
    }

    @Test
    fun `a download job finds its heretic spec again from the id and repo`() {
        val spec = Heretic.spec(ModelCatalog.LFM3_OMNI_2B, "a/b")
        assertEquals(spec, Heretic.byId(spec.id, "a/b"))
        assertNull(Heretic.byId(spec.id, "a/c"))
        assertNull(Heretic.byId(spec.id, null))
    }

    @Test
    fun `the suggested repo is a valid repo for the 1_7B and none for Omni`() {
        assertTrue(Heretic.isRepo(Heretic.suggested(base)!!))
        assertNull(Heretic.suggested(ModelCatalog.LFM3_OMNI_2B))
    }
}
