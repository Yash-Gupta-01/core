package com.maxrave.media3.service

import android.content.Context
import android.content.ContextWrapper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.lang.reflect.Proxy
import java.util.concurrent.FutureTask

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SimpleMediaServiceTest {
    @Test
    fun destroyingServiceAllowsSameSessionIdWithoutCancellingSharedHandler() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val task = FutureTask {
            for (killServiceOnExit in listOf(false, true)) {
                val sharedScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
                // Only the handler boundary is faked; releasing it has the real scope-cancellation effect.
                val handler =
                    Proxy.newProxyInstance(
                        MediaPlayerHandler::class.java.classLoader,
                        arrayOf(MediaPlayerHandler::class.java),
                    ) { _, method, _ ->
                        when (method.name) {
                            "release" -> sharedScope.cancel().let { null }
                            "shouldReleaseOnTaskRemoved" -> killServiceOnExit
                            "setOnUpdateNotification" -> null
                            "toString" -> "TestMediaPlayerHandler"
                            else -> error("Unexpected handler call: ${method.name}")
                        }
                    } as MediaPlayerHandler
                startKoin { modules(module { single<MediaPlayerHandler> { handler } }) }
                val player = ExoPlayer.Builder(instrumentation.targetContext).build()
                var session: MediaLibrarySession? = null
                var replacement: MediaLibrarySession? = null
                try {
                    val service = SimpleMediaService()
                    // Attach context without onCreate: exercise destruction independently of service binding.
                    ContextWrapper::class.java
                        .getDeclaredMethod("attachBaseContext", Context::class.java)
                        .apply { isAccessible = true }
                        .invoke(service, instrumentation.targetContext)
                    session =
                        MediaLibrarySession
                            .Builder(service, player, object : MediaLibrarySession.Callback {})
                            .setId(SimpleMediaService::class.java.name)
                            .build()
                    SimpleMediaService::class.java
                        .getDeclaredField("mediaSession")
                        .apply { isAccessible = true }
                        .set(service, session)

                    service.onDestroy()

                    assertTrue("Service recreation must retain the shared handler scope", sharedScope.isActive)
                    replacement =
                        MediaLibrarySession
                            .Builder(service, player, object : MediaLibrarySession.Callback {})
                            .setId(SimpleMediaService::class.java.name)
                            .build()
                } finally {
                    replacement?.release()
                    session?.release()
                    player.release()
                    sharedScope.cancel()
                    stopKoin()
                }
            }
        }
        instrumentation.runOnMainSync(task)
        task.get()
    }
}
