#include <jni.h>

#include <android/log.h>
#include <android/native_window_jni.h>

#include <memory>
#include <string>

#include "input_queue.hpp"
#include "native_log.hpp"
#include "qmp_client.hpp"
#include "rfb_client.hpp"

#define LOG_TAG "KimiOS-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jclass g_bridge_class = nullptr;
static jmethodID g_first_frame_method = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    // Resolve the class here, on a Java thread with the app classloader.
    // FindClass from an attached native thread only sees the system classloader.
    jclass local = env->FindClass("com/kimios/frontend/NativeBridge");
    if (local == nullptr) {
        return JNI_ERR;
    }
    g_bridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_first_frame_method = env->GetStaticMethodID(g_bridge_class, "onFirstFrameFromNative", "()V");
    if (g_first_frame_method == nullptr) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

void kimios_first_frame() {
    if (g_vm == nullptr || g_bridge_class == nullptr || g_first_frame_method == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallStaticVoidMethod(g_bridge_class, g_first_frame_method);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

namespace {

InputQueue g_queue;
std::unique_ptr<QmpClient> g_qmp;
std::unique_ptr<RfbClient> g_rfb;

std::string to_string(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_kimios_frontend_NativeBridge_nativeInit(JNIEnv* env, jobject, jstring path) {
    const std::string socket_path = to_string(env, path);
    LOGI("nativeInit qmp=%s", socket_path.c_str());
    g_qmp = std::make_unique<QmpClient>(socket_path, g_queue);
    g_qmp->start();
}

JNIEXPORT void JNICALL
Java_com_kimios_frontend_NativeBridge_nativeSurfaceCreated(JNIEnv* env, jobject, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        return;
    }
    if (!g_rfb) {
        g_rfb = std::make_unique<RfbClient>();
    }
    g_rfb->set_window(window);
    g_rfb->start();
    LOGI("surface attached");
}

JNIEXPORT void JNICALL
Java_com_kimios_frontend_NativeBridge_nativeSurfaceDestroyed(JNIEnv*, jobject) {
    if (g_rfb) {
        g_rfb->stop();
        g_rfb->set_window(nullptr);
    }
    LOGI("surface detached");
}

JNIEXPORT void JNICALL
Java_com_kimios_frontend_NativeBridge_nativePointer(JNIEnv*, jobject, jfloat x, jfloat y, jint state) {
    InputEvent event;
    event.kind = InputKind::Pointer;
    event.x = x;
    event.y = y;
    event.pointer_state = state;
    g_queue.push(std::move(event));
}

JNIEXPORT void JNICALL
Java_com_kimios_frontend_NativeBridge_nativeKey(JNIEnv* env, jobject, jstring qcode, jboolean down) {
    InputEvent event;
    event.kind = InputKind::Key;
    event.qcode = to_string(env, qcode);
    event.key_down = down == JNI_TRUE;
    g_queue.push(std::move(event));
}

JNIEXPORT jstring JNICALL
Java_com_kimios_frontend_NativeBridge_nativeGetLog(JNIEnv* env, jobject) {
    const std::string text = nlog::dump();
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_kimios_frontend_NativeBridge_nativeShutdown(JNIEnv*, jobject) {
    if (g_qmp) {
        g_qmp->stop();
        g_qmp.reset();
    }
    if (g_rfb) {
        g_rfb->stop();
        g_rfb->set_window(nullptr);
        g_rfb.reset();
    }
    g_queue.clear();
    LOGI("native shutdown");
}

}
