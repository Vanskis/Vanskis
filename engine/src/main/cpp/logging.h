// Adapted from llama.cpp examples/llama.android/lib/src/main/cpp/logging.h
// (MIT, Copyright (c) 2023-2026 The ggml authors; original by Han Yin, Arm).
#pragma once

#include <android/log.h>
#include "ggml.h"

#ifndef LOG_TAG
#define LOG_TAG "lpengine"
#endif

#define LOGi(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static inline int lp_android_log_prio(enum ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        case GGML_LOG_LEVEL_WARN:  return ANDROID_LOG_WARN;
        case GGML_LOG_LEVEL_INFO:  return ANDROID_LOG_INFO;
        default:                   return ANDROID_LOG_DEBUG;
    }
}

// llama.cpp's debug output is very chatty: forward INFO and above only.
static inline void lp_android_log_callback(enum ggml_log_level level, const char * text, void * /*user*/) {
    if (level == GGML_LOG_LEVEL_DEBUG || level == GGML_LOG_LEVEL_CONT) return;
    __android_log_write(lp_android_log_prio(level), LOG_TAG, text);
}
