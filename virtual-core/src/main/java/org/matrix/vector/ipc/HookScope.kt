package org.matrix.vector.ipc

import android.annotation.SuppressLint
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@SuppressLint("UnsafeOptInUsageError")
@Parcelize
@Serializable
data class HookScope(
    val action: Int,
    /**
     * Package/Class scope expression.
     * Expressed using glob patterns for precision:
     * - "com.example.MyClass"  (Exact class match)
     * - "com.example.*"        (Direct classes under com.example, not subpackages)
     * - "com.example.**"       (Recursive match across subpackages)
     */
    val scope: String,
    /**
     * Optional rule identifier
     */
    val name: String?,
    /**
     * Toggle to temporarily disable a rule without deleting it.
     */
    val active: Boolean,
    /**
     * Lower numbers evaluated first
     */
    val priority: Int,
    /**
     * Unique identifier to identify a specific rule
     * Useful for tracking rule changes and create more elaborate rules in the future.
     */
    val id: String = Uuid.random().toString()
) : Parcelable {
    // TODO Create tests for this
    fun matchesMethod(member: String): Boolean {
        return if (scope.endsWith("**")) {
            member.startsWith(scope.removeSuffix("**"))
        } else if (scope.endsWith("*")) {
            member.substringBeforeLast(".") == scope.removeSuffix("*")
        } else {
            scope == member
        }
    }

    companion object {
        const val ACTION_BLOCK = 0
        const val ACTION_ALLOW = 1
    }
}