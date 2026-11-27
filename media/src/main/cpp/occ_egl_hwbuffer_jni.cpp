// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 OpenCineCam contributors

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <jni.h>
#include <stdint.h>

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
using FnDestroyImageKHR = EGLBoolean(EGLAPIENTRY*)(EGLDisplay dpy, void* image);
using FnGlEglImageTargetTexture2DOES = void(GL_APIENTRY*)(GLenum target, void* image);

struct EglProcs {
    FnGetNativeClientBufferANDROID getNativeClientBuffer;
    FnCreateImageKHR createImage;
    FnDestroyImageKHR destroyImage;
    FnGlEglImageTargetTexture2DOES imageTargetTexture2D;
};

const EglProcs& loadProcs() {
    static const EglProcs procs{
        reinterpret_cast<FnGetNativeClientBufferANDROID>(eglGetProcAddress("eglGetNativeClientBufferANDROID")),
        reinterpret_cast<FnCreateImageKHR>(eglGetProcAddress("eglCreateImageKHR")),
        reinterpret_cast<FnDestroyImageKHR>(eglGetProcAddress("eglDestroyImageKHR")),
        reinterpret_cast<FnGlEglImageTargetTexture2DOES>(eglGetProcAddress("glEGLImageTargetTexture2DOES")),
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

}  // extern "C"
