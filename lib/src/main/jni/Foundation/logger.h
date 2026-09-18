#include <jni.h>

void init_log_classes(JNIEnv *env);
void start_log_server();
void safe_log_exec(const char *msg);