package de.robv.android.xposed

import android.annotation.SuppressLint
import com.virtualxposed.hook.VHookClient
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexClassLoader
import timber.log.Timber
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Member

object HookVerifier {
    fun getModuleCaller(): String? {
        // We need to figure out the identity of the module which calls registerHook.
        // This is done by correlating the current stacktrace with all loaded modules.
        val callingPackages = getStacktraceModuleCallers()
        if (callingPackages == null) {
            Timber.w("No hook caller found, rejecting hook!")
            return null
        }

        val loadedModules = VHookClient.getLoadedModules()
        val loadedPackages = loadedModules.associate { packageInfo ->
            packageInfo.applicationInfo?.sourceDir to packageInfo.packageName
        }

        val matchingCallers = callingPackages.filter {
            loadedPackages.keys.contains(it)
        }

        // If multiple different modules are in the stack trace it could indicate one module
        // trying to affect another module, we therefore reject it.
        // There is no reason for this to happen under normal circumstances.
        if (matchingCallers.size != 1) {
            Timber.e("Unable determine the real module hook caller! Rejecting hook!")
            return null
        }

        val callingPackage = matchingCallers.first()
        val callingModule = loadedPackages[callingPackage]
        return callingModule
    }

    fun isHookAllowed(hook: Member, callingModule: String): Boolean {
        return VHookClient.isHookAllowed(hook, callingModule)
    }

    /**
     * Gets the base APK of all callers in the current stack trace
     */
    fun getStacktraceModuleCallers(): Set<String>? {
        val backtrace = getBacktrace(Throwable()) ?: return null
        val callingFiles = backtrace.flatMap {
            val classLoader = it.classLoader as? DexClassLoader
            getDexClassLoaderPaths(classLoader)
        }.toSet()

        return callingFiles
    }

    @SuppressLint("DiscouragedPrivateApi")
    /**
     * We use an internal field of the backtrace to get Class instances of
     * the whole stack trace. This makes it possible to get the classloaders of
     * all classes in the stack trace. We can thereby get an unambigious source
     * of our caller.
     */
    private fun getBacktrace(throwable: Throwable): List<Class<*>>? {
        return try {
            val field: Field = Throwable::class.java.getDeclaredField("backtrace")
            field.isAccessible = true

            val backtrace = field.get(throwable) as? Array<*>
            backtrace?.filterIsInstance<Class<*>>()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Extracts a list of string file paths (DEX/APK/JAR files) contained inside the given [DexClassLoader].
     */
    fun getDexClassLoaderPaths(classLoader: DexClassLoader?): List<String> {
        if (classLoader == null) return emptyList()

        val paths = mutableListOf<String>()

        try {
            // Get the 'pathList' field from BaseDexClassLoader
            val baseClass = BaseDexClassLoader::class.java
            val pathListField = baseClass.getDeclaredField("pathList").apply {
                isAccessible = true
            }
            val pathList = pathListField.get(classLoader) ?: return emptyList()

            // Get the 'dexElements' array from DexPathList
            val dexElementsField = pathList.javaClass.getDeclaredField("dexElements").apply {
                isAccessible = true
            }
            val dexElements = dexElementsField.get(pathList) as? Array<*> ?: return emptyList()

            // Extract the file or path from each Element
            for (element in dexElements) {
                element ?: continue
                val elementClass = element.javaClass

                // Attempt 'path' or 'file' field based on Android OS version
                val path = try {
                    val fileField =
                        elementClass.getDeclaredField("path").apply { isAccessible = true }
                    (fileField.get(element) as? File)?.absolutePath ?: fileField.get(element)
                        ?.toString()
                } catch (_: NoSuchFieldException) {
                    try {
                        val fileField =
                            elementClass.getDeclaredField("file").apply { isAccessible = true }
                        (fileField.get(element) as? File)?.absolutePath
                    } catch (_: NoSuchFieldException) {
                        null
                    }
                }

                if (!path.isNullOrEmpty()) {
                    paths.add(path)
                } else {
                    // Fallback -> Parse the path from element.toString() e.g. "zip file "/path/to/base.apk""
                    val elementString = element.toString()
                    val extractedPath = elementString.substringAfter("\"").substringBefore("\"")
                    if (extractedPath.isNotEmpty() && extractedPath != elementString) {
                        paths.add(extractedPath)
                    }
                }
            }
        } catch (e: Throwable) {
            Timber.e(e)
        }

        return paths
    }
}