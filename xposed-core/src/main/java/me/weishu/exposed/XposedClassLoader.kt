package me.weishu.exposed

/**
 * XposedClassLoader: use to only load Xposed api classes.
 */
internal class XposedClassLoader(private val mHostClassLoader: ClassLoader) : ClassLoader(
    getSystemClassLoader().parent
) {
    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*>? {
        if (name.startsWith("de.robv.android.xposed")
            || name.startsWith("android")
            || name.startsWith("external")
            || name.startsWith("me.weishu.epic.art")
            || name.startsWith("com.taobao.android.dexposed")
        ) {
            return mHostClassLoader.loadClass(name)
        }
        return super.loadClass(name, resolve)
    }
}
