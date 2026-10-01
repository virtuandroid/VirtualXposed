//
// VirtualApp Native Project
//
#ifndef PERMISSION_H
#define PERMISSION_H

#include <errno.h>

namespace android {

/** Error codes
 * From https://android.googlesource.com/platform/system/core/+/refs/heads/main/libutils/binder/include/utils/Errors.h
 */
enum {
  OK                  = 0,
  PERMISSION_DENIED   = -EPERM,
};

}  // namespace android

void initializeCachedReferences(JNIEnv* env);

bool permissionGranted(JNIEnv* env, const char* permission);

/**
 * Return error code, based on permission granted
 * Log error if not granted
 */
int enforcePermission(JNIEnv* env, const char* permission);

#endif // PERMISSION_H
