package media.sovereign.sovemu.firetv.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import media.sovereign.sovemu.core.ClientInfo

/** Which app this is: the only thing that knows which surface this build is. */
@Module
@InstallIn(SingletonComponent::class)
object ClientInfoModule {
    @Provides
    @Singleton
    fun provideClientInfo(): ClientInfo = ClientInfo(tv = true)
}
