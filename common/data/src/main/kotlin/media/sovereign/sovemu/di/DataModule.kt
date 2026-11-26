package media.sovereign.sovemu.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import media.sovereign.sovemu.ClientEvents
import media.sovereign.sovemu.Settings
import media.sovereign.sovemu.SovEmuApi
import media.sovereign.sovemu.Store

/** The scope of work that lives as long as the app's process, not a screen's or an activity's. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * The server, the accounts and the library's state, one of each for the whole process: the
 * session ([SovEmuApi.session]) is the one owner of every token, whatever activity, service or
 * job asks for one.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun provideApi(settings: Settings, @ApplicationContext context: Context): SovEmuApi =
        SovEmuApi(settings, ClientEvents.deviceName(context))

    @Provides
    @Singleton
    fun provideStore(api: SovEmuApi, @ApplicationScope scope: CoroutineScope): Store =
        Store(api, scope)
}
