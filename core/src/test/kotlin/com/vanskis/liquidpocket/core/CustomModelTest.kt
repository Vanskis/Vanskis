package com.vanskis.liquidpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CustomModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val hub = CustomModel(
        id = CustomModel.idFor("bartowski/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf"),
        name = "Qwen3-1.7B-Q4_K_M",
        repo = "bartowski/Qwen3-1.7B-GGUF",
        file = "Qwen3-1.7B-Q4_K_M.gguf",
        vision = true, tools = true, thinks = true,
        hereticRepo = "someone/Qwen3-1.7B-heretic-GGUF",
    )

    @Test fun `ids are stable and safe`() {
        assertEquals("custom-bartowski-qwen3-1-7b-gguf-qwen3-1-7b-q4-k-m", hub.id)
        assertEquals("custom-my-model", CustomModel.idFor("", "/sdcard/My Model.gguf"))
        assertEquals("My Model", CustomModel.nameFor("/sdcard/My Model.gguf"))
    }

    @Test fun `its spec picks exactly its file, with a projector only for vision from a repo`() {
        val files = listOf(
            RepoFile("Qwen3-1.7B-Q4_K_M.gguf", 10, null),
            RepoFile("Qwen3-1.7B-Q4_K_S.gguf", 9, null),
            RepoFile("mmproj-Qwen3-1.7B-F16.gguf", 2, null),
        )
        assertEquals("Qwen3-1.7B-Q4_K_M.gguf", HuggingFace.selectGguf(files, hub.spec.quant)?.path)
        assertEquals(listOf("Q8_0", "F16", "BF16"), hub.spec.mmprojQuants)
        assertTrue(hub.spec.thinks)
        assertNull(hub.copy(vision = false).spec.mmprojQuants)
        assertNull("an import has no repo to fetch a projector from", hub.copy(repo = "").spec.mmprojQuants)
        assertEquals("someone/Qwen3-1.7B-heretic-GGUF", hub.heretic?.repo)
        assertNull(hub.copy(hereticRepo = "not a repo").heretic)
    }

    @Test fun `the list round-trips and bad entries are dropped`() {
        val local = CustomModel(CustomModel.idFor("", "a.gguf"), "a", "", "a.gguf")
        assertEquals(listOf(hub, local), CustomModel.decode(CustomModel.encode(listOf(hub, local))))
        assertEquals(emptyList<CustomModel>(), CustomModel.decode("{"))
        assertEquals(emptyList<CustomModel>(), CustomModel.decode("""[{"id":"lfm3-1.7b","file":"x.gguf"},{"id":"custom-x"}]"""))
    }

    @Test fun `traits come from the chat template`() {
        assertEquals(TemplateTraits(thinks = false, tools = false), TemplateTraits.of(null))
        assertEquals(TemplateTraits(thinks = true, tools = true), TemplateTraits.of("{% if tools %}<tool_call>{% endif %}<think>\n"))
        assertEquals(TemplateTraits(thinks = false, tools = false), TemplateTraits.of("<|im_start|>{{ message.content }}"))
    }

    @Test fun `the chat template is read from a gguf header past other keys`() {
        val template = "{% for m in messages %}<think>{{ m.content }}{% endfor %}"
        val gguf = gguf(template)
        assertEquals(template, GgufHeader.chatTemplate(gguf.inputStream()))
        assertNull(GgufHeader.chatTemplate(gguf(null).inputStream()))
        assertThrows { GgufHeader.chatTemplate("not a model at all".byteInputStream()) }
    }

    @Test fun `hugging face lists the template of a gguf repo`() {
        assertEquals("<think>", HuggingFace.parseChatTemplate("""{"id":"x","gguf":{"total":1,"chat_template":"<think>"}}"""))
        assertNull(HuggingFace.parseChatTemplate("""{"id":"x"}"""))
        assertNull(HuggingFace.parseChatTemplate("oops"))
    }

    @Test fun `a spec survives a download job's input`() {
        listOf(hub.spec, hub.heretic!!, ModelCatalog.LFM3_1_7B, ModelCatalog.LFM3_OMNI_2B).forEach { spec ->
            val data = ModelSpecData.encode(spec)
            assertEquals(spec.copy(approxSizeBytes = 0L, offered = false), ModelSpecData.decode(data::get))
        }
        assertNull(ModelSpecData.decode { null })
    }

    @Test fun `an imported file is adopted as installed`() {
        val dir = tmp.newFolder("models")
        val installer = ModelInstaller(dir)
        val local = CustomModel(CustomModel.idFor("", "mine.gguf"), "mine", "", "mine.gguf")
        assertNull(installer.installedFile(local.spec))
        val file = installer.importFile("/storage/Download/mine.gguf").apply { writeBytes(ByteArray(42)) }
        installer.adopt(local.spec, file)
        assertEquals(file, installer.installedFile(local.spec))
        assertFalse(installer.installedFile(local.spec) == null)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        }
        throw AssertionError("expected an IOException")
    }

    /** A GGUF v3 header: a u32, a string array and, if given, the chat template; no tensors. */
    private fun gguf(template: String?): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) = s.toByteArray().let { u64(it.size.toLong()); out.write(it) }
        u32(0x46554747); u32(3); u64(0); u64(if (template == null) 2 else 3)
        str("general.alignment"); u32(4); u32(32)
        str("tokenizer.ggml.tokens"); u32(9); u32(8); u64(3); str("a"); str("<think>"); str("c")
        template?.let { str("tokenizer.chat_template"); u32(8); str(it) }
        return out.toByteArray()
    }
}
