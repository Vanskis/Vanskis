package com.vanskis.liquidpocket

import android.app.Application
import com.vanskis.liquidpocket.access.SharedDocuments
import com.vanskis.liquidpocket.chat.CachingMediaLoader
import com.vanskis.liquidpocket.chat.MediaLoader
import com.vanskis.liquidpocket.chat.SavedChatState
import com.vanskis.liquidpocket.core.ModelCatalog
import com.vanskis.liquidpocket.core.ModelInstaller
import com.vanskis.liquidpocket.data.AppDatabase
import com.vanskis.liquidpocket.data.ChatRepository
import com.vanskis.liquidpocket.docs.DocumentStore
import com.vanskis.liquidpocket.download.ModelDownloads
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.settings.NpuAutoPick
import com.vanskis.liquidpocket.engine.Engines
import com.vanskis.liquidpocket.media.AndroidMediaLoader
import com.vanskis.liquidpocket.media.AttachmentFiles
import com.vanskis.liquidpocket.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/** The main model: text, thinking and tools. The chat opens once it is installed. */
val ACTIVE_MODEL = ModelCatalog.LFM3_1_7B

/** Loaded in place of [ACTIVE_MODEL] for photo and voice turns; downloaded after it. */
val MEDIA_MODEL = ModelCatalog.LFM3_OMNI_2B

/** The id of LFM2.5-8B-A1B, the "smart" model earlier versions downloaded; its files are deleted at startup. */
private const val RETIRED_MODEL_ID = "lfm2.5-8b-a1b"

/** Holds the process-wide objects the screens and the download worker share. */
class LiquidPocketApp : Application() {
    /** Work that must outlive any one activity, such as copying a shared document. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val installer: ModelInstaller by lazy { ModelInstaller(File(filesDir, "models")) }
    val downloads: ModelDownloads by lazy { ModelDownloads(this, installer) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val engine: Engine by lazy { Engines.llama(this) }
    val npuPick: NpuAutoPick by lazy { NpuAutoPick(this, engine, settings) }
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val savedState: SavedChatState by lazy { SavedChatState(File(cacheDir, "kv"), engine) }
    val chats: ChatRepository by lazy { ChatRepository(database.chatDao(), savedState = savedState) }
    val attachments: AttachmentFiles by lazy { AttachmentFiles(this) }
    /** Attached documents' extracted text, by the id a DOCUMENT attachment keeps in its path. */
    val documents: DocumentStore by lazy { DocumentStore(File(filesDir, "docs")) }
    /** Decoded photos and clips for the engine, cached because every reply resends the whole history. */
    val media: MediaLoader by lazy { CachingMediaLoader(AndroidMediaLoader()) }

    override fun onCreate() {
        super.onCreate()
        // Frees the retired model's few GB; once it is gone this is one missing-file check.
        thread(name = "retire-models") { runCatching { installer.deleteById(RETIRED_MODEL_ID) } }
        // Shared copies from a previous process; the in-memory inbox that listed them is empty now.
        thread(name = "clear-shared") { SharedDocuments.clearStored(this) }
        // The NPU took the last process down (a native abort): asking for it again would crash the next reply.
        appScope.launch {
            if (engine.npuCrashedLastRun && settings.settings.first().useNpu) settings.setUseNpu(false)
            npuPick.firstRun()
        }
    }
}
