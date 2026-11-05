package dev.namnv.rexc.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.namnv.rexc.core.dispatcher.AppCoroutineDispatchers
import dev.namnv.rexc.core.dispatcher.DefaultAppCoroutineDispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface AppModule {
  @Binds
  @Singleton
  fun appCoroutineDispatchers(impl: DefaultAppCoroutineDispatchers): AppCoroutineDispatchers
}
