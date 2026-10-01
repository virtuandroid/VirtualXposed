#include <jni.h>
#include <sys/types.h>

void init_log_classes(JNIEnv *env);
void start_log_server();
void safe_log_exec(const char *msg);

void log_kill(pid_t pid);
void log_socket(int fd, pid_t pid);
void log_file_access(const char *filename, bool forbid);