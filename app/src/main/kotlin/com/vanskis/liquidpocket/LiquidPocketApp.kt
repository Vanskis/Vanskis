package com.vanskis.liquidpocket

import android.app.Application
import com.vanskis.liquidpocket.chat.CachingMediaLoader
import com.vanskis.liquidpocket.chat.MediaLoader
import com.vanskis.liquidpocket.core.ModelCatalog
import com.vanskis.liquidpocket.core.ModelInstaller
import com.vanskis.liquidpocket.data.AppDatabase
import com.vanskis.liquidpocket.data.ChatRepository
import com.vanskis.liquidpocket.download.ModelDownloads
import com.vanskis.liquidpocket.engine.Engine
import com.vanskis.liquidpocket.engine.Engines
import com.vanskis.liquidpocket.media.AndroidMediaLoader
import com.vanskis.liquidpocket.media.AttachmentFiles
import com.vanskis.liquidpocket.settings.SettingsRepository
import java.io.File

/** The model the app downloads and chats with in M1. */
val ACTIVE_MODEL = ModelCatalog.LFM3_OMNI_2B

/** Holds the process-wide objects the screens and the download worker share. */
class LiquidPocketApp : Application() {
    val installer: ModelInstaller by lazy { ModelInstaller(File(filesDir, "models")) }
    val downloads: ModelDownloads by lazy { ModelDownloads(this, installer) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val engine: Engine by lazy { Engines.llama(this) }
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val chats: ChatRepository by lazy { ChatRepository(database.chatDao()) }
    val attachments: AttachmentFiles by lazy { AttachmentFiles(this) }
    /** Decoded photos and clips for the engine, cached because every reply resends the whole history. */
    val media: MediaLoader by lazy { CachingMediaLoader(AndroidMediaLoader()) }
}
