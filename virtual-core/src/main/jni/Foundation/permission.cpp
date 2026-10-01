#include <jni.h>
#include <android/log.h>
#include <string>
#include "permission.h"

#define TAG_NAME	"NativePermission"
#define log_err(fmt,args...) __android_log_print(ANDROID_LOG_ERROR, TAG_NAME, (const char*) fmt, ##args)

jclass binderClass = nullptr;
jmethodID getCallingPidMethod = nullptr;
jmethodID getCallingUidMethod = nullptr;

jmethodID checkPermissionMethod = nullptr;
jobject permissionManager = nullptr;

jint PERMISSION_GRANTED;
jint PERMISSION_DENIED;

void initializeCachedReferences(JNIEnv* env) {
  binderClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("android/os/Binder")));
  getCallingPidMethod = env->GetStaticMethodID(binderClass, "getCallingPid", "()I");
  getCallingUidMethod = env->GetStaticMethodID(binderClass, "getCallingUid", "()I");

  const jclass permissionManagerClass = static_cast<jclass>(
      env->NewGlobalRef(env->FindClass("com/lody/virtual/server/permission/VPermissionManager")));
  jmethodID getInstanceMethod = env->GetStaticMethodID(permissionManagerClass,
      "get", "()Lcom/lody/virtual/server/permission/VPermissionManager;");
  permissionManager = env->NewGlobalRef(
      env->CallStaticObjectMethod(permissionManagerClass, getInstanceMethod));
  checkPermissionMethod = env->GetMethodID(permissionManagerClass, "checkPermission",
      "(Ljava/lang/String;I)I");

  const jclass packageManagerClass = static_cast<jclass>(
      env->NewGlobalRef(env->FindClass("android/content/pm/PackageManager")));
  const jfieldID permissionGrantedFieldID = env->GetStaticFieldID(packageManagerClass,
      "PERMISSION_GRANTED", "I");
  const jfieldID permissionDeniedFieldID = env->GetStaticFieldID(packageManagerClass,
      "PERMISSION_DENIED", "I");

  PERMISSION_GRANTED = env->GetStaticIntField(packageManagerClass, permissionGrantedFieldID);
  PERMISSION_DENIED = env->GetStaticIntField(packageManagerClass, permissionDeniedFieldID);
}

bool checkCallingPermission(JNIEnv* env, const char* permission) {
  jint uid = env->CallStaticIntMethod(binderClass, getCallingUidMethod);
  jstring jPermission = env->NewStringUTF(permission);

  jint result = env->CallIntMethod(permissionManager, checkPermissionMethod, jPermission, uid);
  env->DeleteLocalRef(jPermission);

  return result == PERMISSION_GRANTED;
}

bool permissionGranted(JNIEnv* env, const char* permission) {
  return checkCallingPermission(env, permission);
}

int enforcePermission(JNIEnv* env, const char* permission) {
  if (permissionGranted(env, permission)) {
    return android::OK;
  }
  jint pid = env->CallStaticIntMethod(binderClass, getCallingPidMethod);
  jint uid = env->CallStaticIntMethod(binderClass, getCallingUidMethod);
  // Similar to the message in:
  // https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/CameraService.cpp#1856
  log_err("Permission Denial: %s pid=%d, uid=%d", permission, pid, uid);
  return android::PERMISSION_DENIED;
}
