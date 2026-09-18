#include <jni.h>
#include <lsplant.hpp>
#include <android/log.h>
#include <dobby.h>
#include <dlfcn.h>
#include "elf/elf_image.h"
#include "elf/symbol_cache.h"
#include "common/logger.h"
#include <cstdio>
#include <sys/mman.h>

#define _uintval(p)               reinterpret_cast<uintptr_t>(p)
#define _ptr(p)                   reinterpret_cast<void *>(p)
#define _align_up(x, n)           (((x) + ((n) - 1)) & ~((n) - 1))
#define _align_down(x, n)         ((x) & -(n))
#define _page_size                16384
#define _page_align(n)            _align_up(static_cast<uintptr_t>(n), _page_size)
#define _ptr_align(x)             _ptr(_align_down(reinterpret_cast<uintptr_t>(x), _page_size))
#define _make_rwx(p, n)           ::mprotect(_ptr_align(p), \
                                              _page_align(_uintval(p) + n) != _page_align(_uintval(p)) ? _page_align(n) + _page_size : _page_align(n), \
                                              PROT_READ | PROT_WRITE | PROT_EXEC)

#include <jni.h>

static JavaVM *g_vm = nullptr;
static jclass g_helperClass = nullptr;
static jmethodID g_logMethod = nullptr;

JNIEnv *getEnv() {
    JNIEnv *env = nullptr;
    if (g_vm != nullptr) {
        g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    }
    return env;
}

// Needs something better
void logCodeLoad(const char *filename) {
    if (filename == nullptr) return;

    JNIEnv *env = getEnv();
    if (env == nullptr || g_helperClass == nullptr || g_logMethod == nullptr) return;

    jstring jPath = env->NewStringUTF(filename);
    if (jPath != nullptr) {
        env->CallStaticVoidMethod(g_helperClass, g_logMethod, jPath);
        env->DeleteLocalRef(jPath);
    }
}

void init_log_classes(JNIEnv *env) {
    jclass localClass = env->FindClass("com/virtualxposed/lsplantbridge/NativeHelper");
    if (localClass == nullptr) return;

    g_helperClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);

    // Cache the static method ID (valid globally)
    g_logMethod = env->GetStaticMethodID(
            g_helperClass,
            "logNativeCodeLoad",
            "(Ljava/lang/String;)V"
    );
}


typedef void *(*dlopen_func_t)(const char *filename, int flags, const void *extinfo, void *caller_addr);

static dlopen_func_t orig_dlopen = nullptr;

void *fake_dlopen(const char *filename, int flags, const void *extinfo, void *caller_addr) {
    if (filename != nullptr) {
        LOG_INFO("[+] dlopen intercepted: %s (flags: 0x%x)\n", filename, flags);
        logCodeLoad(filename);
    }

    if (orig_dlopen != nullptr) {
        void *result = orig_dlopen(filename, flags, extinfo, caller_addr);
        if (result != nullptr) {
            // LOG_INFO("[+] dlopen succeeded! Handle address: %p\n", result);
        } else {
            LOG_INFO("[-] dlopen failed for %s\n", filename ? filename : "NULL");
        }
        return result;
    }

    return nullptr;
}

void install_dlopen_hook() {
    void *dl = dlopen("libdl.so", RTLD_LAZY);
    void *dlopen_addr = dlsym(dl, "__loader_android_dlopen_ext");
    // void *dlopen_addr = DobbySymbolResolver("libdl.so", "__loader_dlopen");

    if (dlopen_addr != NULL) {
        int ret = DobbyHook(
                dlopen_addr,
                reinterpret_cast<void *>(fake_dlopen),
                reinterpret_cast<void **>(&orig_dlopen)
        );

        if (ret == 0) {
            LOG_INFO("[+] dlopen hook installed. Trampoline: %p\n", orig_dlopen);
        } else {
            LOG_INFO("[-] DobbyHook failed: %d\n", ret);
        }
    } else {
        LOG_INFO("[-] DobbyHook failed: no dlopen_addr\n");
    }
}

void *InlineHooker(void *target, void *hooker) {
    _make_rwx(target, _page_size);
    void *origin_call;
    if (DobbyHook(target, hooker, &origin_call) == 0) {
        return origin_call;
    } else {
        return nullptr;
    }
}

bool InlineUnhooker(void *func) {
    return DobbyDestroy(func) == 0;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    g_vm = vm;

    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    lsplant::InitInfo info{
            .inline_hooker = InlineHooker,
            .inline_unhooker = InlineUnhooker,
            .art_symbol_resolver =
            [](auto symbol) {
                return vector::native::ElfSymbolCache::GetArt()->getSymbAddress(symbol);
            },
            .art_symbol_prefix_resolver =
            [](auto symbol) {
                return vector::native::ElfSymbolCache::GetArt()->getSymbPrefixFirstAddress(symbol);
            },
            .generated_class_name = "VirtualXposed_",
            .generated_source_name = "Dobby",
    };

    if (!lsplant::Init(env, info)) {
        LOG_INFO("Bridge error");
        // Initialization failed
        return JNI_ERR;
    }

    init_log_classes(env);
    LOG_INFO("Bridge initialized");

    return JNI_VERSION_1_6;
}


extern "C"
JNIEXPORT jobject JNICALL
Java_com_virtualxposed_lsplantbridge_MethodHooker_hookTarget(JNIEnv *env, jobject thiz, jobject target, jobject callback) {
    return lsplant::Hook(env, target, thiz, callback);
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_virtualxposed_lsplantbridge_MethodHooker_unhookTarget(JNIEnv *env, jobject thiz, jobject target) {
    return lsplant::UnHook(env, target);
}

// TODO This should be separate and implemented together with the IO Sandbox
extern "C"
JNIEXPORT void JNICALL
Java_com_lody_virtual_client_NativeEngine_hookDlOpen(JNIEnv *env, jclass clazz) {
    install_dlopen_hook();
}