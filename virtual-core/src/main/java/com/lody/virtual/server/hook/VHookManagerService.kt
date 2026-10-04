package com.lody.virtual.server.hook

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
import android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.os.IBinder
import com.lody.virtual.client.VectorClient.VECTOR_PACKAGE
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.stub.GrantHookScopesActivity
import com.lody.virtual.os.VBinder
import com.lody.virtual.server.am.VActivityManagerService
import com.virtualxposed.hook.server.IVHookService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.matrix.vector.ipc.HookScope
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.service.VectorManagerService
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

object VHookManagerService : IVHookService.Stub() {
    private var vectorManager: IManagerService? = VectorManagerService.getManagerServer()

    const val DEFAULT_ACTION = DENIED

    override fun isHookAllowed(
        method: String?, modulePackage: String?
    ): Int {
        Timber.d("Received request to hook $method from $modulePackage")
        val manager = vectorManager

        if (manager == null) {
            Timber.w("isHookAllowed called before vector manager attach!")
            return DENIED
        }

        // Reject any invalid calls
        if (modulePackage == null || method == null) {
            return DENIED
        }

        val sortedScopes = manager.getHookScopes(modulePackage)
            .sortedWith(compareBy<HookScope> { it.priority }.thenBy { it.id })

        val matchingRule = sortedScopes.firstOrNull { scope ->
            scope.active && scope.matchesMethod(method)
        }

        if (matchingRule != null) {
            Timber.d("Found matching rule: $matchingRule")
        }

        return when (matchingRule?.action) {
            null -> {
                val callingPackage = VBinder.getCallingPackageName()
                val result = CompletableDeferred<Int?>()
                val context = VirtualCore.get().context

                val callback: (Int?) -> Unit = {
                    result.complete(it ?: DEFAULT_ACTION)
                    val launchIntent = VirtualCore.get().getLaunchIntent(callingPackage, 0)
                    VActivityManager.get().startActivity(launchIntent, 0)
                }

                val intent = GrantHookScopesActivity.createStartIntent(
                    method,
                    modulePackage,
                    callingPackage,
                    callback
                ).apply {
                    addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_REORDER_TO_FRONT)
                }

                context.startActivity(intent)

                try {
                    runBlocking {
                        withTimeout(30.seconds) {
                            result.await()
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    Timber.i("Response within 30 seconds, defaulting to blocking hook: $method")
                    return DEFAULT_ACTION
                }

                @OptIn(ExperimentalCoroutinesApi::class)
                result.getCompleted() ?: DEFAULT_ACTION
            }

            HookScope.ACTION_BLOCK -> {
                Timber.i("Hook blocked: $method")
                DENIED
            }

            HookScope.ACTION_ALLOW -> {
                Timber.i("Hook allowed: $method")
                ALLOWED
            }

            else -> {
                Timber.i("Undefined action (${matchingRule.action}) for method $method. Blocking hook.")
                DENIED
            }
        }
    }

    override fun getVectorManager(): IBinder? {
        // Return the binder only if the caller is the vector app or the system app (:app project)
        // The vector manager can execute privileged actions and should therefore not be accessible normal guest apps.
        return if (
            VBinder.getCallingPackageName() == VECTOR_PACKAGE ||
            VBinder.getCallingUid() == VActivityManager.get().systemPid
        ) {
            VectorManagerService.getManagerServer()?.asBinder()
        } else {
            null
        }
    }

    @JvmStatic
    fun get() = this
}