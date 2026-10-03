package com.lody.virtual.server.hook

import com.virtualxposed.hook.server.IVHookService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.vector.ipc.HookScope
import org.matrix.vector.ipc.IManagerService
import org.matrix.vector.service.VectorManagerService
import timber.log.Timber

object VHookManagerService : IVHookService.Stub() {
    private var vectorManager: IManagerService? = null
    fun start() {
        CoroutineScope(Dispatchers.IO).launch {
            vectorManager = VectorManagerService.getService()
        }
    }

    override fun isHookAllowed(
        method: String?,
        modulePackage: String?
    ): Boolean {
        Timber.d("Received request to hook $method from $modulePackage")
        val manager = vectorManager

        if (manager == null) {
            Timber.w("isHookAllowed called before vector manager attach!")
            return false
        }

        // Reject any invalid calls
        if (modulePackage == null || method == null) {
            return false
        }

        val sortedScopes = manager.getHookScopes(modulePackage).sortedWith(
            compareBy<HookScope> { it.priority }.thenBy { it.id }
        )

        Timber.d("Scopes: $sortedScopes")


        val matchingRule = sortedScopes.firstOrNull { scope ->
            scope.active && scope.matchesMethod(method)
        }

        if (matchingRule != null) {
            Timber.d("Found matching rule: $matchingRule")
        }

        return when (matchingRule?.action) {
            null -> {
                Timber.i("No action defined, defaulting to blocking hook: $method")
                false
            }
            HookScope.ACTION_BLOCK -> {
                Timber.i("Hook blocked: $method")
                false
            }
            HookScope.ACTION_ALLOW -> {
                Timber.i("Hook allowed: $method")
                true
            }
            else -> {
                Timber.i("Undefined action (${matchingRule.action}) for method $method. Blocking hook.")
                false
            }
        }
    }

    @JvmStatic
    fun get() = this
}