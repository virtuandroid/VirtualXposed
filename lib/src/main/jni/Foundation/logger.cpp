#include <unistd.h>
#include <cstdlib>
#include <thread>
#include <chrono>
#include <sys/un.h>
#include <sys/socket.h>


#include "IOUniformer.h"
#include "SandboxFs.h"
#include "Path.h"
#include "SymbolFinder.h"
#include "fb/Environment.h"

#include <iostream>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <linux/audit.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>


static jclass g_helperClass = nullptr;
static jmethodID g_logExecMethod = nullptr;
static jmethodID g_logKillMethod = nullptr;
static jmethodID g_logSocketMethod = nullptr;

void log_kill(pid_t pid) {
    facebook::jni::Environment::ensureCurrentThreadIsAttached();
    JNIEnv *env = facebook::jni::Environment::current();

    if (env == nullptr || g_helperClass == nullptr || g_logKillMethod == nullptr) return;

    env->CallStaticVoidMethod(g_helperClass, g_logKillMethod, pid);
}

void log_exec(const char *filename) {
    if (filename == nullptr) return;

    facebook::jni::Environment::ensureCurrentThreadIsAttached();
    JNIEnv *env = facebook::jni::Environment::current();

    if (env == nullptr || g_helperClass == nullptr || g_logExecMethod == nullptr) return;

    jstring jPath = env->NewStringUTF(filename);
    if (jPath != nullptr) {

        env->CallStaticVoidMethod(g_helperClass, g_logExecMethod, jPath);
        env->DeleteLocalRef(jPath);
    }
}


void log_socket(int fd, pid_t pid) {
    facebook::jni::Environment::ensureCurrentThreadIsAttached();
    JNIEnv *env = facebook::jni::Environment::current();

    if (env == nullptr || g_helperClass == nullptr || g_logSocketMethod == nullptr) return;

    env->CallStaticVoidMethod(g_helperClass, g_logSocketMethod, fd, pid);
}


void *log_server_thread_func(void *arg) {
    int server_fd = socket(AF_UNIX, SOCK_DGRAM, 0);
    struct sockaddr_un addr = {0};
    addr.sun_family = AF_UNIX;
    // TODO this creates a vulnerability because other processes can connect to this logger
    strncpy(addr.sun_path + 1, "logger", sizeof(addr.sun_path) - 2);
    bind(server_fd, (struct sockaddr *) &addr, sizeof(addr));

    char buf[256];
    while (true) {
        ssize_t bytes = read(server_fd, buf, sizeof(buf) - 1);
        if (bytes > 0) {
            buf[bytes] = '\0';
            log_exec(buf);
        }
    }
}


void start_log_server() {
    pthread_t thread;
    pthread_create(&thread, nullptr, log_server_thread_func, nullptr);
    pthread_detach(thread);
}

// The logging must be using a socket to bypass an Android bug which prevents logcat logging
// during process creation.
void safe_log_exec(const char *msg) {
    size_t len = strlen(msg);
    if (len == 0) return;

    int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return;

    // Safety check: Ensure the OS didn't assign FD 3 to our socket.
    // If it assigned 3, duplicate it to a higher slot.
    if (fd == 3) {
        int new_fd = fcntl(fd, F_DUPFD_CLOEXEC, 4);
        close(fd);
        fd = new_fd;
        if (fd < 0) return;
    }

    struct sockaddr_un addr = {0};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path + 1, "logger", sizeof(addr.sun_path) - 2);

    sendto(fd, msg, len, MSG_DONTWAIT, (struct sockaddr *) &addr, sizeof(addr));

    close(fd);
}

void init_log_classes(JNIEnv *env) {
    jclass localClass = env->FindClass("com/virtualxposed/lsplantbridge/NativeHelper");
    if (localClass == nullptr) return;

    g_helperClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);

    // Cache the static method ID
    g_logExecMethod = env->GetStaticMethodID(
            g_helperClass,
            "logNativeExec",
            "(Ljava/lang/String;)V"
    );

    g_logKillMethod = env->GetStaticMethodID(
            g_helperClass,
            "logNativeKill",
            "(I)V"
    );

    g_logSocketMethod = env->GetStaticMethodID(
            g_helperClass,
            "logNativeSocket",
            "(II)V"
    );
}