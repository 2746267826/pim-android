package com.pim.app.di

import android.content.Context
import com.pim.app.notes.NoteArtifactClient
import com.pim.app.notes.NoteArtifactRepository
import com.pim.app.notes.NoteArtifactTarget
import com.pim.app.notes.NoteMaterialStore
import com.pim.core.auth.TokenManager
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.BoundUploadSession
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NoteArtifactModule {
    @Provides
    @Singleton
    fun provideNoteArtifactRepository(
        @ApplicationContext context: Context,
        clients: ApiClientProvider,
        sessions: TokenManager,
        settings: ServerSettingsStore,
    ): NoteArtifactRepository {
        val materials = NoteMaterialStore.at(context.noBackupFilesDir)
        return NoteArtifactRepository.at(
            noBackupDirectory = context.noBackupFilesDir,
            materials = materials,
            targetProvider = {
                runCatching {
                    val origin = PimServerEndpoints.from(settings.getBaseUrl()).trustedOrigin
                    var target: NoteArtifactTarget? = null
                    settings.commitSessionIfCurrentServer(origin) {
                        val session = sessions.snapshot()
                        val ownerId = session.ownerId
                        if (session.tokens == null || session.serverIdentity != origin || ownerId == null) {
                            false
                        } else {
                            target = NoteArtifactTarget(
                                binding = BoundUploadSession(origin, ownerId),
                                ownerName = session.ownerName.orEmpty(),
                            )
                            true
                        }
                    }
                    target
                }.getOrNull()
            },
            factory = { target ->
                NoteArtifactClient(clients.noteUploadApiService(target.binding), target.binding)
            },
        )
    }
}
