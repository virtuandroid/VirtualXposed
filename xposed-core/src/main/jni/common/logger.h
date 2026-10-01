//
// Created by b on 2026-08-15.
//

#define LOG_INFO(message, ...) ((void)__android_log_print(ANDROID_LOG_INFO, "LSPLANT-Bridge", "[%s %s] " message, __FILE_NAME__, __func__, ##__VA_ARGS__))

