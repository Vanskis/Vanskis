package com.vanskis.liquidpocket.settings

import com.vanskis.liquidpocket.core.Heretic
import com.vanskis.liquidpocket.core.ModelCatalog
import com.vanskis.liquidpocket.engine.GenerationParams
import com.vanskis.liquidpocket.engine.Sampling
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTuningTest {
    private val tuned = ModelTuning(Sampling(0.8f, 20, 0.1f, 1.1f, 42), maxReply = 512, instructions = "Be brief.")

    @Test fun `tunings round-trip and defaults are not stored`() {
        val all = mapOf("a" to tuned, "b" to ModelTuning())
        assertEquals(mapOf("a" to tuned), ModelTuning.decode(ModelTuning.encode(all)))
        assertEquals("{}", ModelTuning.encode(mapOf("b" to ModelTuning())))
        assertEquals(emptyMap<String, ModelTuning>(), ModelTuning.decode("{"))
        assertEquals(mapOf("a" to ModelTuning(maxReply = 256)), ModelTuning.decode("""{"a":{"maxReply":256}}"""))
    }

    @Test fun `stored values are kept in range`() {
        val wild = ModelTuning.decode("""{"a":{"topP":3,"topK":-5,"minP":-1,"repeatPenalty":0.2,"seed":-9,"maxReply":-1}}""")["a"]!!
        assertEquals(ModelTuning(Sampling(1f, 0, 0f, 1f, Sampling.RANDOM_SEED), maxReply = 0), wild)
    }

    @Test fun `a heretic build uses its base model's tuning unless it has its own`() {
        val base = ModelCatalog.LFM3_1_7B
        val heretic = Heretic.spec(base, "someone/LFM3-heretic-GGUF")
        assertEquals(tuned, ModelTuning.of(heretic.id, mapOf(base.id to tuned)))
        assertEquals(ModelTuning(maxReply = 256), ModelTuning.of(heretic.id, mapOf(base.id to tuned, heretic.id to ModelTuning(maxReply = 256))))
        assertEquals(ModelTuning(), ModelTuning.of(null, mapOf(base.id to tuned)))
    }

    @Test fun `no reply limit runs until the context is full`() {
        assertEquals(GenerationParams.UNTIL_CONTEXT_FULL, ModelTuning().maxNewTokens)
        assertEquals(512, tuned.maxNewTokens)
    }
}
