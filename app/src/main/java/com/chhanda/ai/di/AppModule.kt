package com.chhanda.ai.di

import android.content.Context
import androidx.room.Room
import com.chhanda.ai.data.inference.AndroidMultimodalIngestor
import com.chhanda.ai.data.inference.LiteRTLMEngine
import com.chhanda.ai.data.repository.AppDatabase
import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.DeviceDao
import com.chhanda.ai.data.repository.LocalVectorStore
import com.chhanda.ai.data.repository.VectorChunkDao
import com.chhanda.ai.domain.model.EmbeddingEngine
import com.chhanda.ai.domain.model.Embedding
import com.chhanda.ai.domain.model.LLMEngine
import com.chhanda.ai.domain.model.MultimodalIngestor
import com.chhanda.ai.domain.model.VectorStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides only what cannot be auto-injected: Room DB, DAOs, and the
 * anonymous EmbeddingEngine stub.
 * All concrete classes (LiteRTLMEngine, etc.) use @Inject constructor
 * and are bound to their interfaces in BindingsModule below.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideAppDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "chhanda_db")
            .fallbackToDestructiveMigration().build()

    @Provides @Singleton
    fun provideChatDao(db: AppDatabase): ChatDao = db.chatDao()

    @Provides @Singleton
    fun provideDeviceDao(db: AppDatabase): DeviceDao = db.deviceDao()

    @Provides @Singleton
    fun provideVectorChunkDao(db: AppDatabase): VectorChunkDao = db.vectorChunkDao()

    @Provides @Singleton
    fun provideEmbeddingEngine(impl: LiteRTEmbeddingEngine): EmbeddingEngine = impl

}

/**
 * Binds interface types to their concrete @Singleton implementations.
 * @Binds is zero-overhead — no method body, no object creation.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds @Singleton
    abstract fun bindLLMEngine(impl: LiteRTLMEngine): LLMEngine

    @Binds @Singleton
    abstract fun bindMultimodalIngestor(impl: AndroidMultimodalIngestor): MultimodalIngestor

    @Binds @Singleton
    abstract fun bindVectorStore(impl: LocalVectorStore): VectorStore
}
