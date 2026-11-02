package com.pim.app.di

import com.pim.app.nativework.BoundNativeWorkbenchTransport
import com.pim.app.nativework.NativeWorkbenchTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NativeWorkbenchModule {
    @Binds
    @Singleton
    abstract fun bindNativeWorkbenchTransport(
        transport: BoundNativeWorkbenchTransport,
    ): NativeWorkbenchTransport
}
