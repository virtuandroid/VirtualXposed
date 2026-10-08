package me.weishu.exposed

import android.content.pm.ApplicationInfo

/**
 * author: weishu on 18/1/14.
 */
interface ModuleLoadListener {
    fun onLoadingModule(
        moduleClassName: String?,
        applicationInfo: ApplicationInfo?,
        appClassLoader: ClassLoader?
    )

    fun onModuleLoaded(
        moduleClassName: String?,
        applicationInfo: ApplicationInfo?,
        appClassLoader: ClassLoader?
    )
}
