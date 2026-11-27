package com.pim.app.location.service

import android.app.Service
import android.content.Intent
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** Optional dev-only sessions hosted by the existing foreground service. Empty in production. */
interface LocalCollectionExperiment {
    /** null means this command belongs to normal collection. */
    fun onStartCommand(service: Service, intent: Intent?, startId: Int): Int?
    fun onDestroy(service: Service)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalCollectionExperimentModule {
    @Multibinds abstract fun experiments(): Set<LocalCollectionExperiment>
}
