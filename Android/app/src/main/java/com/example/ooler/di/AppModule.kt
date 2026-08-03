package com.example.ooler.di

import android.content.Context
import androidx.room.Room
import com.example.ooler.data.KableOolerRepository
import com.example.ooler.data.MockOolerRepository
import com.example.ooler.data.local.AppDatabase
import com.example.ooler.domain.OolerRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ooler_db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideOolerRepository(
        @ApplicationContext context: Context,
        db: AppDatabase,
        scope: CoroutineScope
    ): OolerRepository {
        // Toggle this for Mock vs Live
        val useMock = true
        return if (useMock) {
            MockOolerRepository()
        } else {
            KableOolerRepository(scope)
        }
    }
}
