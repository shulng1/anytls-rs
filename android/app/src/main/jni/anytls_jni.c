#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "anytls.h"

static JavaVM *g_vm;
static jobject g_callback;
static jmethodID g_on_listening;
static jmethodID g_on_log;
static jmethodID g_on_traffic;
static jmethodID g_on_stopped;

static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if (g_vm == NULL) {
        return NULL;
    }
    jint status = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
            return NULL;
        }
    }
    return env;
}

static void log_callback(AnyTlsLogLevel level, const char *message, void *ctx) {
    (void) ctx;
    JNIEnv *env = get_env();
    if (env == NULL || g_callback == NULL) {
        return;
    }
    jstring text = (*env)->NewStringUTF(env, message != NULL ? message : "");
    if (text == NULL) {
        (*env)->ExceptionClear(env);
        return;
    }
    (*env)->CallVoidMethod(env, g_callback, g_on_log, (jint) level, text);
    (*env)->DeleteLocalRef(env, text);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

static void traffic_callback(const AnyTlsTrafficStatus *status, void *ctx) {
    (void) ctx;
    JNIEnv *env = get_env();
    if (env == NULL || g_callback == NULL || status == NULL) {
        return;
    }
    (*env)->CallVoidMethod(env, g_callback, g_on_traffic, (jlong) status->tx, (jlong) status->rx);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

static void listening_callback(int32_t port, void *ctx) {
    (void) ctx;
    JNIEnv *env = get_env();
    if (env == NULL || g_callback == NULL) {
        return;
    }
    (*env)->CallVoidMethod(env, g_callback, g_on_listening, (jint) port);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass cls = (*env)->FindClass(env, "com/anytls/app/AnyTlsNative$Callback");
    if (cls == NULL) {
        return JNI_ERR;
    }

    g_on_listening = (*env)->GetMethodID(env, cls, "onListening", "(I)V");
    g_on_log = (*env)->GetMethodID(env, cls, "onLog", "(ILjava/lang/String;)V");
    g_on_traffic = (*env)->GetMethodID(env, cls, "onTraffic", "(JJ)V");
    g_on_stopped = (*env)->GetMethodID(env, cls, "onStopped", "(I)V");
    (*env)->DeleteLocalRef(env, cls);

    if (g_on_listening == NULL || g_on_log == NULL || g_on_traffic == NULL || g_on_stopped == NULL) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_anytls_app_AnyTlsNative_run(JNIEnv *env, jclass clazz, jstring command, jobject callback) {
    (void) clazz;
    if (command == NULL) {
        return -2;
    }

    const char *chars = (*env)->GetStringUTFChars(env, command, NULL);
    if (chars == NULL) {
        return -2;
    }
    char *command_line = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, command, chars);
    if (command_line == NULL) {
        return -2;
    }

    if (g_callback != NULL) {
        (*env)->DeleteGlobalRef(env, g_callback);
        g_callback = NULL;
    }
    if (callback != NULL) {
        g_callback = (*env)->NewGlobalRef(env, callback);
    }

    anytls_set_log_callback(false, log_callback, NULL);
    anytls_set_traffic_status_callback(1, traffic_callback, NULL);

    jint result = anytls_client_run(command_line, listening_callback, NULL);

    anytls_set_traffic_status_callback(0, NULL, NULL);
    anytls_set_log_callback(false, NULL, NULL);

    if (g_callback != NULL) {
        JNIEnv *calling_env = get_env();
        if (calling_env != NULL) {
            (*calling_env)->CallVoidMethod(calling_env, g_callback, g_on_stopped, result);
            if ((*calling_env)->ExceptionCheck(calling_env)) {
                (*calling_env)->ExceptionClear(calling_env);
            }
        }
        (*env)->DeleteGlobalRef(env, g_callback);
        g_callback = NULL;
    }

    free(command_line);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_anytls_app_AnyTlsNative_stop(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return anytls_client_stop();
}

JNIEXPORT jstring JNICALL
Java_com_anytls_app_AnyTlsNative_generateUrl(JNIEnv *env, jclass clazz, jstring command) {
    (void) clazz;
    if (command == NULL) {
        return NULL;
    }

    const char *chars = (*env)->GetStringUTFChars(env, command, NULL);
    if (chars == NULL) {
        return NULL;
    }

    jstring result = NULL;
    size_t required = anytls_generate_url(chars, NULL, 0);
    if (required > 0) {
        char *buffer = (char *) malloc(required);
        if (buffer != NULL) {
            if (anytls_generate_url(chars, buffer, required) > 0) {
                result = (*env)->NewStringUTF(env, buffer);
            }
            free(buffer);
        }
    }

    (*env)->ReleaseStringUTFChars(env, command, chars);
    return result;
}
