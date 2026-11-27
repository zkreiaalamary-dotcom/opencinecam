// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <GLES3/gl3.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>

#ifndef EGL_NATIVE_BUFFER_ANDROID
#define EGL_NATIVE_BUFFER_ANDROID 0x3140
#endif

#ifndef EGL_IMAGE_PRESERVED_KHR
#define EGL_IMAGE_PRESERVED_KHR 0x30D2
#endif

#ifndef GL_TEXTURE_EXTERNAL_OES
#define GL_TEXTURE_EXTERNAL_OES 0x8D65
#endif

namespace {

using FnGetNativeClientBufferANDROID = EGLClientBuffer(EGLAPIENTRY*)(const AHardwareBuffer* buffer);
using FnCreateImageKHR = void*(EGLAPIENTRY*)(EGLDisplay dpy, EGLContext ctx, EGLenum target, EGLClientBuffer buffer, const EGLint* attrib_list);
using FnSetBuffersDataSpace = int32_t (*)(ANativeWindow* window, int32_t dataSpace);

FnSetBuffersDataSpace resolveSetBuffersDataSpace() {
    static FnSetBuffersDataSpace cached = nullptr;
    static bool attempted = false;
    if (!attempted) {
        attempted = true;
        void* lib = dlopen("libandroid.so", RTLD_LAZY | RTLD_NOLOAD);
        if (lib == nullptr) {
            lib = dlopen("libandroid.so", RTLD_NOW);
        }
        if (lib != nullptr) {
            cached = reinterpret_cast<FnSetBuffersDataSpace>(
                dlsym(lib, "ANativeWindow_setBuffersDataSpace"));
        }
    }
    return cached;
}
using FnDestroyImageKHR = EGLBoolean(EGLAPIENTRY*)(EGLDisplay dpy, void* image);
using FnGlEglImageTargetTexture2DOES = void(GL_APIENTRY*)(GLenum target, void* image);
using FnGlEglImageTargetRenderbufferStorageOES = void(GL_APIENTRY*)(GLenum target, void* image);

struct EglProcs {
    FnGetNativeClientBufferANDROID getNativeClientBuffer;
    FnCreateImageKHR createImage;
    FnDestroyImageKHR destroyImage;
    FnGlEglImageTargetTexture2DOES imageTargetTexture2D;
    FnGlEglImageTargetRenderbufferStorageOES imageTargetRenderbufferStorage;
};

const EglProcs& loadProcs() {
    static const EglProcs procs{
        reinterpret_cast<FnGetNativeClientBufferANDROID>(eglGetProcAddress("eglGetNativeClientBufferANDROID")),
        reinterpret_cast<FnCreateImageKHR>(eglGetProcAddress("eglCreateImageKHR")),
        reinterpret_cast<FnDestroyImageKHR>(eglGetProcAddress("eglDestroyImageKHR")),
        reinterpret_cast<FnGlEglImageTargetTexture2DOES>(eglGetProcAddress("glEGLImageTargetTexture2DOES")),
        reinterpret_cast<FnGlEglImageTargetRenderbufferStorageOES>(eglGetProcAddress("glEGLImageTargetRenderbufferStorageOES")),
    };
    return procs;
}

EGLDisplay currentOrDefaultDisplay() {
    EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy == EGL_NO_DISPLAY) {
        dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    }
    return dpy;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeProbeExtensions(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    const EglProcs& p = loadProcs();
    jint mask = 0;
    if (p.getNativeClientBuffer != nullptr) mask |= 1;
    if (p.createImage != nullptr) mask |= 2;
    if (p.destroyImage != nullptr) mask |= 4;
    if (p.imageTargetTexture2D != nullptr) mask |= 8;
    if (p.imageTargetRenderbufferStorage != nullptr) mask |= 16;
    return mask;
}

JNIEXPORT jlong JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeCreateEglImage(
    JNIEnv* env, jclass /*clazz*/, jobject hardwareBuffer) {
    if (hardwareBuffer == nullptr) return 0;
    const EglProcs& p = loadProcs();
    if (p.getNativeClientBuffer == nullptr || p.createImage == nullptr) return 0;

    EGLDisplay dpy = currentOrDefaultDisplay();
    if (dpy == EGL_NO_DISPLAY) return 0;

    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (ahb == nullptr) return 0;

    EGLClientBuffer clientBuf = p.getNativeClientBuffer(ahb);
    if (clientBuf == nullptr) return 0;

    const EGLint attrs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE,
    };
    void* img = p.createImage(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuf, attrs);
    return reinterpret_cast<jlong>(img);
}

JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeBindExternalTexture(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong imageHandle) {
    if (imageHandle == 0) return -1;
    const EglProcs& p = loadProcs();
    if (p.imageTargetTexture2D == nullptr) return -2;

    while (glGetError() != GL_NO_ERROR) {}
    p.imageTargetTexture2D(GL_TEXTURE_EXTERNAL_OES, reinterpret_cast<void*>(imageHandle));
    return static_cast<jint>(glGetError());
}

JNIEXPORT jboolean JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeDestroyEglImage(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong imageHandle) {
    if (imageHandle == 0) return JNI_FALSE;
    const EglProcs& p = loadProcs();
    if (p.destroyImage == nullptr) return JNI_FALSE;

    EGLDisplay dpy = currentOrDefaultDisplay();
    if (dpy == EGL_NO_DISPLAY) return JNI_FALSE;

    return p.destroyImage(dpy, reinterpret_cast<void*>(imageHandle)) == EGL_TRUE ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeLastEglError(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    return static_cast<jint>(eglGetError());
}

JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeBindRenderbuffer(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong imageHandle) {
    if (imageHandle == 0) return -1;
    const EglProcs& p = loadProcs();
    if (p.imageTargetRenderbufferStorage == nullptr) return -2;

    while (glGetError() != GL_NO_ERROR) {}
    p.imageTargetRenderbufferStorage(GL_RENDERBUFFER, reinterpret_cast<void*>(imageHandle));
    return static_cast<jint>(glGetError());
}

JNIEXPORT jboolean JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeIsAhbSupported(
    JNIEnv* /*env*/, jclass /*clazz*/, jint width, jint height, jint format, jlong usage) {
    if (width <= 0 || height <= 0) return JNI_FALSE;
    AHardwareBuffer_Desc desc;
    memset(&desc, 0, sizeof(desc));
    desc.width = static_cast<uint32_t>(width);
    desc.height = static_cast<uint32_t>(height);
    desc.layers = 1;
    desc.format = static_cast<uint32_t>(format);
    desc.usage = static_cast<uint64_t>(usage);
    return AHardwareBuffer_isSupported(&desc) != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeAllocateAhb(
    JNIEnv* env, jclass /*clazz*/, jint width, jint height, jint format, jlong usage) {
    if (width <= 0 || height <= 0) return nullptr;
    AHardwareBuffer_Desc desc;
    memset(&desc, 0, sizeof(desc));
    desc.width = static_cast<uint32_t>(width);
    desc.height = static_cast<uint32_t>(height);
    desc.layers = 1;
    desc.format = static_cast<uint32_t>(format);
    desc.usage = static_cast<uint64_t>(usage);
    AHardwareBuffer* ahb = nullptr;
    if (AHardwareBuffer_allocate(&desc, &ahb) != 0 || ahb == nullptr) return nullptr;
    jobject javaHb = AHardwareBuffer_toHardwareBuffer(env, ahb);
    AHardwareBuffer_release(ahb);
    return javaHb;
}

JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeSetSurfaceGeometryAndDataSpace(
    JNIEnv* env, jclass /*clazz*/, jobject surface, jint width, jint height, jint format, jint dataSpace) {
    if (surface == nullptr) return -1;
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (win == nullptr) return -2;
    int32_t rc = 0;
    if (width > 0 && height > 0 && format != 0) {
        rc = ANativeWindow_setBuffersGeometry(win, width, height, format);
    }
    if (rc == 0 && dataSpace != 0) {
        FnSetBuffersDataSpace fn = resolveSetBuffersDataSpace();
        rc = (fn != nullptr) ? fn(win, dataSpace) : -3;
    }
    ANativeWindow_release(win);
    return static_cast<jint>(rc);
}

JNIEXPORT jboolean JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeReadAhbFormatAndUsage(
    JNIEnv* env, jclass /*clazz*/, jobject hardwareBuffer, jlongArray out2) {
    if (hardwareBuffer == nullptr || out2 == nullptr || env->GetArrayLength(out2) < 2) return JNI_FALSE;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (ahb == nullptr) return JNI_FALSE;
    AHardwareBuffer_Desc desc;
    memset(&desc, 0, sizeof(desc));
    AHardwareBuffer_describe(ahb, &desc);
    jlong vals[2] = { static_cast<jlong>(desc.format), static_cast<jlong>(desc.usage) };
    env->SetLongArrayRegion(out2, 0, 2, vals);
    return JNI_TRUE;
}

}  // extern "C"
