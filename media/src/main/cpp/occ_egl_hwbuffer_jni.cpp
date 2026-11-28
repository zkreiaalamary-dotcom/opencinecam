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
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

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

JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeBindTexture2D(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong imageHandle) {
    if (imageHandle == 0) return -1;
    const EglProcs& p = loadProcs();
    if (p.imageTargetTexture2D == nullptr) return -2;

    while (glGetError() != GL_NO_ERROR) {}
    p.imageTargetTexture2D(GL_TEXTURE_2D, reinterpret_cast<void*>(imageHandle));
    return static_cast<jint>(glGetError());
}

JNIEXPORT jboolean JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeInspectSurfaceWindow(
    JNIEnv* env, jclass /*clazz*/, jobject surface, jintArray out3) {
    if (surface == nullptr || out3 == nullptr || env->GetArrayLength(out3) < 3) return JNI_FALSE;
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (win == nullptr) return JNI_FALSE;
    jint vals[3] = {
        static_cast<jint>(ANativeWindow_getWidth(win)),
        static_cast<jint>(ANativeWindow_getHeight(win)),
        static_cast<jint>(ANativeWindow_getFormat(win)),
    };
    ANativeWindow_release(win);
    env->SetIntArrayRegion(out3, 0, 3, vals);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeProbeAnwFormatLockAndPost(
    JNIEnv* env, jclass /*clazz*/, jobject surface, jint width, jint height,
    jint format, jint dataSpace, jint patternMode, jintArray outStats6) {
    if (surface == nullptr || outStats6 == nullptr || env->GetArrayLength(outStats6) < 6) return -1;
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (win == nullptr) return -2;
    int32_t rcGeom = (width > 0 && height > 0 && format != 0)
        ? ANativeWindow_setBuffersGeometry(win, width, height, format)
        : 0;
    int32_t rcDs = 0;
    if (rcGeom == 0 && dataSpace != 0) {
        FnSetBuffersDataSpace fn = resolveSetBuffersDataSpace();
        rcDs = (fn != nullptr) ? fn(win, dataSpace) : -3;
    }
    ANativeWindow_Buffer buf;
    memset(&buf, 0, sizeof(buf));
    int32_t rcLock = ANativeWindow_lock(win, &buf, nullptr);
    jint stats[6] = {
        static_cast<jint>(rcGeom),
        static_cast<jint>(rcDs),
        static_cast<jint>(rcLock),
        static_cast<jint>(buf.width),
        static_cast<jint>(buf.stride),
        static_cast<jint>(buf.format),
    };
    env->SetIntArrayRegion(outStats6, 0, 6, stats);
    if (rcLock == 0 && buf.bits != nullptr && buf.width > 0 && buf.height > 0 && buf.stride >= buf.width) {
        if (patternMode == 1 && (buf.format == 0x36 || buf.format == 0x324 || buf.format == 0x2b)) {
            if (buf.format == 0x2b) {
                uint32_t* dst = reinterpret_cast<uint32_t*>(buf.bits);
                for (int32_t y = 0; y < buf.height; ++y) {
                    uint32_t* row = dst + static_cast<size_t>(y) * static_cast<size_t>(buf.stride);
                    for (int32_t x = 0; x < buf.width; ++x) {
                        uint32_t v10 = static_cast<uint32_t>((static_cast<uint64_t>(x) * 1023U) /
                            static_cast<uint32_t>(buf.width > 1 ? buf.width - 1 : 1));
                        row[x] = (3U << 30) | (v10 << 20) | (v10 << 10) | v10;
                    }
                }
            } else {
                uint16_t* dst = reinterpret_cast<uint16_t*>(buf.bits);
                for (int32_t y = 0; y < buf.height; ++y) {
                    uint16_t* row = dst + static_cast<size_t>(y) * static_cast<size_t>(buf.stride);
                    for (int32_t x = 0; x < buf.width; ++x) {
                        uint16_t v10 = static_cast<uint16_t>((static_cast<uint64_t>(x) * 1023U) /
                            static_cast<uint32_t>(buf.width > 1 ? buf.width - 1 : 1));
                        row[x] = static_cast<uint16_t>(v10 << 6);
                    }
                }
            }
        } else if (buf.format == WINDOW_FORMAT_RGBA_8888 || buf.format == WINDOW_FORMAT_RGBX_8888) {
            uint32_t* dst = reinterpret_cast<uint32_t*>(buf.bits);
            for (int32_t y = 0; y < buf.height; ++y) {
                uint32_t* row = dst + static_cast<size_t>(y) * static_cast<size_t>(buf.stride);
                for (int32_t x = 0; x < buf.width; ++x) {
                    uint32_t v8 = static_cast<uint32_t>((static_cast<uint64_t>(x) * 255U) /
                        static_cast<uint32_t>(buf.width > 1 ? buf.width - 1 : 1));
                    row[x] = 0xFF000000U | (v8 << 16) | (v8 << 8) | v8;
                }
            }
        }
        int32_t rcPost = ANativeWindow_unlockAndPost(win);
        ANativeWindow_release(win);
        return static_cast<jint>(rcPost);
    }
    if (rcLock == 0) {
        ANativeWindow_unlockAndPost(win);
    }
    ANativeWindow_release(win);
    return static_cast<jint>(rcLock);
}

JNIEXPORT jint JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeInspectAhbLockPlanes(
    JNIEnv* env, jclass /*clazz*/, jobject hardwareBuffer, jlongArray outMeta6,
    jintArray outPlanes12, jbyteArray sampleOut) {
    if (hardwareBuffer == nullptr || outMeta6 == nullptr || env->GetArrayLength(outMeta6) < 6) return -1;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (ahb == nullptr) return -2;
    AHardwareBuffer_Desc desc;
    memset(&desc, 0, sizeof(desc));
    AHardwareBuffer_describe(ahb, &desc);

    AHardwareBuffer_Planes planes;
    memset(&planes, 0, sizeof(planes));
    int rc = AHardwareBuffer_lockPlanes(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &planes);
    int usedFallbackLock = 0;
    void* singleAddr = nullptr;
    if (rc != 0) {
        rc = AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &singleAddr);
        usedFallbackLock = 1;
    }

    jlong meta[6] = {
        static_cast<jlong>(desc.width),
        static_cast<jlong>(desc.height),
        static_cast<jlong>(desc.format),
        static_cast<jlong>(desc.stride),
        static_cast<jlong>(desc.usage),
        static_cast<jlong>(rc),
    };
    env->SetLongArrayRegion(outMeta6, 0, 6, meta);

    if (outPlanes12 != nullptr && env->GetArrayLength(outPlanes12) >= 12) {
        jint pInfo[12];
        memset(pInfo, 0, sizeof(pInfo));
        if (rc == 0 && !usedFallbackLock) {
            pInfo[0] = static_cast<jint>(planes.planeCount);
            for (uint32_t i = 0; i < planes.planeCount && i < 3; ++i) {
                pInfo[1 + i * 2] = static_cast<jint>(planes.planes[i].pixelStride);
                pInfo[2 + i * 2] = static_cast<jint>(planes.planes[i].rowStride);
            }
            if (planes.planeCount > 1 && planes.planes[0].data != nullptr && planes.planes[1].data != nullptr) {
                ptrdiff_t diff = reinterpret_cast<const uint8_t*>(planes.planes[1].data) -
                    reinterpret_cast<const uint8_t*>(planes.planes[0].data);
                pInfo[7] = static_cast<jint>(diff);
            }
            if (planes.planeCount > 2 && planes.planes[0].data != nullptr && planes.planes[2].data != nullptr) {
                ptrdiff_t diff2 = reinterpret_cast<const uint8_t*>(planes.planes[2].data) -
                    reinterpret_cast<const uint8_t*>(planes.planes[0].data);
                pInfo[8] = static_cast<jint>(diff2);
            }
            if (sampleOut != nullptr && planes.planeCount > 0 && planes.planes[0].data != nullptr) {
                jsize sLen = env->GetArrayLength(sampleOut);
                if (sLen > 0) {
                    env->SetByteArrayRegion(sampleOut, 0, sLen,
                        reinterpret_cast<const jbyte*>(planes.planes[0].data));
                }
            }
        } else if (rc == 0 && singleAddr != nullptr) {
            pInfo[0] = -1;  // fallback AHardwareBuffer_lock succeeded
            if (sampleOut != nullptr) {
                jsize sLen = env->GetArrayLength(sampleOut);
                if (sLen > 0) {
                    env->SetByteArrayRegion(sampleOut, 0, sLen, reinterpret_cast<const jbyte*>(singleAddr));
                }
            }
        }
        env->SetIntArrayRegion(outPlanes12, 0, 12, pInfo);
    }

    if (rc == 0) {
        AHardwareBuffer_unlock(ahb, nullptr);
    }
    return static_cast<jint>(rc);
}

JNIEXPORT jstring JNICALL
Java_com_librestatic_opencinecam_media_video_EglHardwareBufferBridge_nativeProbeVulkanAhb(
    JNIEnv* env, jclass /*clazz*/, jobject ahbInObj, jobject ahbOutObj) {
    char jsonBuf[4096];
    void* vkLib = dlopen("libvulkan.so", RTLD_NOW);
    if (vkLib == nullptr) {
        snprintf(jsonBuf, sizeof(jsonBuf), "{\"ok\":false,\"error\":\"dlopen libvulkan.so failed\"}");
        return env->NewStringUTF(jsonBuf);
    }
    auto pfnGetInstProc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(vkLib, "vkGetInstanceProcAddr"));
    if (pfnGetInstProc == nullptr) {
        snprintf(jsonBuf, sizeof(jsonBuf), "{\"ok\":false,\"error\":\"vkGetInstanceProcAddr missing\"}");
        dlclose(vkLib);
        return env->NewStringUTF(jsonBuf);
    }
    auto pfnEnumVer = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        pfnGetInstProc(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    uint32_t instVer = VK_API_VERSION_1_0;
    if (pfnEnumVer != nullptr) pfnEnumVer(&instVer);

    auto pfnCreateInst = reinterpret_cast<PFN_vkCreateInstance>(
        pfnGetInstProc(VK_NULL_HANDLE, "vkCreateInstance"));
    if (pfnCreateInst == nullptr) {
        snprintf(jsonBuf, sizeof(jsonBuf), "{\"ok\":false,\"error\":\"vkCreateInstance missing\"}");
        dlclose(vkLib);
        return env->NewStringUTF(jsonBuf);
    }

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "OpenCineCamProbe";
    appInfo.apiVersion = (instVer >= VK_API_VERSION_1_1) ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;
    VkInstanceCreateInfo instCi{};
    instCi.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instCi.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult vr = pfnCreateInst(&instCi, nullptr, &instance);
    if (vr != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        snprintf(jsonBuf, sizeof(jsonBuf), "{\"ok\":false,\"error\":\"vkCreateInstance %d\"}", vr);
        dlclose(vkLib);
        return env->NewStringUTF(jsonBuf);
    }

    auto pfnDestroyInst = reinterpret_cast<PFN_vkDestroyInstance>(pfnGetInstProc(instance, "vkDestroyInstance"));
    auto pfnEnumPhys = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(pfnGetInstProc(instance, "vkEnumeratePhysicalDevices"));
    auto pfnGetProps = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(pfnGetInstProc(instance, "vkGetPhysicalDeviceProperties"));
    auto pfnEnumDevExts = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(pfnGetInstProc(instance, "vkEnumerateDeviceExtensionProperties"));
    auto pfnGetQProps = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(pfnGetInstProc(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    auto pfnCreateDev = reinterpret_cast<PFN_vkCreateDevice>(pfnGetInstProc(instance, "vkCreateDevice"));
    auto pfnGetDevProc = reinterpret_cast<PFN_vkGetDeviceProcAddr>(pfnGetInstProc(instance, "vkGetDeviceProcAddr"));

    uint32_t physCount = 1;
    VkPhysicalDevice phys = VK_NULL_HANDLE;
    if (pfnEnumPhys == nullptr || pfnEnumPhys(instance, &physCount, &phys) < 0 || physCount == 0 || phys == VK_NULL_HANDLE) {
        snprintf(jsonBuf, sizeof(jsonBuf), "{\"ok\":false,\"error\":\"no VkPhysicalDevice\"}");
        if (pfnDestroyInst) pfnDestroyInst(instance, nullptr);
        dlclose(vkLib);
        return env->NewStringUTF(jsonBuf);
    }

    VkPhysicalDeviceProperties props{};
    if (pfnGetProps) pfnGetProps(phys, &props);

    bool hasExtMemAhb = false;
    bool hasYcbcrConv = false;
    bool hasForeignQueue = false;
    uint32_t extCount = 0;
    if (pfnEnumDevExts && pfnEnumDevExts(phys, nullptr, &extCount, nullptr) == VK_SUCCESS && extCount > 0) {
        uint32_t cap = extCount > 256 ? 256 : extCount;
        VkExtensionProperties exts[256];
        if (pfnEnumDevExts(phys, nullptr, &cap, exts) >= 0) {
            for (uint32_t i = 0; i < cap; ++i) {
                if (strcmp(exts[i].extensionName, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME) == 0) hasExtMemAhb = true;
                if (strcmp(exts[i].extensionName, VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME) == 0) hasYcbcrConv = true;
                if (strcmp(exts[i].extensionName, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME) == 0) hasForeignQueue = true;
            }
        }
    }

    uint32_t qFamily = 0;
    uint32_t qCount = 0;
    if (pfnGetQProps) {
        pfnGetQProps(phys, &qCount, nullptr);
        uint32_t cap = qCount > 16 ? 16 : qCount;
        VkQueueFamilyProperties qProps[16];
        if (cap > 0) {
            pfnGetQProps(phys, &cap, qProps);
            for (uint32_t i = 0; i < cap; ++i) {
                if ((qProps[i].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
                    qFamily = i;
                    break;
                }
            }
        }
    }

    const char* reqExts[3];
    uint32_t reqExtCount = 0;
    if (hasExtMemAhb) reqExts[reqExtCount++] = VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME;
    if (hasYcbcrConv) reqExts[reqExtCount++] = VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME;
    if (hasForeignQueue) reqExts[reqExtCount++] = VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME;

    float qPrio = 1.0f;
    VkDeviceQueueCreateInfo qCi{};
    qCi.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qCi.queueFamilyIndex = qFamily;
    qCi.queueCount = 1;
    qCi.pQueuePriorities = &qPrio;

    VkDeviceCreateInfo devCi{};
    devCi.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    devCi.queueCreateInfoCount = 1;
    devCi.pQueueCreateInfos = &qCi;
    devCi.enabledExtensionCount = reqExtCount;
    devCi.ppEnabledExtensionNames = reqExts;

    VkDevice device = VK_NULL_HANDLE;
    VkResult devRc = pfnCreateDev ? pfnCreateDev(phys, &devCi, nullptr, &device) : VK_ERROR_INITIALIZATION_FAILED;

    auto formatAhbJson = [&](const char* label, jobject ahbJavaObj, char* out, size_t outCap) {
        if (ahbJavaObj == nullptr || device == VK_NULL_HANDLE || !hasExtMemAhb) {
            snprintf(out, outCap, "\"%s\":null", label);
            return;
        }
        AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, ahbJavaObj);
        if (ahb == nullptr) {
            snprintf(out, outCap, "\"%s\":{\"ok\":false}", label);
            return;
        }
        auto pfnGetAhbProps = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            pfnGetDevProc(device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
        if (pfnGetAhbProps == nullptr) {
            snprintf(out, outCap, "\"%s\":{\"ok\":false,\"err\":\"fn_missing\"}", label);
            return;
        }
        VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{};
        fmtProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
        VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
        ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
        ahbProps.pNext = &fmtProps;
        VkResult ar = pfnGetAhbProps(device, ahb, &ahbProps);
        snprintf(out, outCap,
            "\"%s\":{\"rc\":%d,\"allocSize\":%llu,\"memBits\":\"0x%x\",\"vkFormat\":%d,\"extFormat\":%llu,\"featuresHex\":\"0x%x\",\"sampled\":%s,\"storage\":%s,\"colorAtt\":%s,\"xferDst\":%s}",
            label,
            static_cast<int>(ar),
            static_cast<unsigned long long>(ahbProps.allocationSize),
            ahbProps.memoryTypeBits,
            static_cast<int>(fmtProps.format),
            static_cast<unsigned long long>(fmtProps.externalFormat),
            static_cast<unsigned int>(fmtProps.formatFeatures),
            (fmtProps.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) ? "true" : "false",
            (fmtProps.formatFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) ? "true" : "false",
            (fmtProps.formatFeatures & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) ? "true" : "false",
            (fmtProps.formatFeatures & VK_FORMAT_FEATURE_TRANSFER_DST_BIT) ? "true" : "false");
    };

    char inJson[512];
    char outJson[512];
    formatAhbJson("ahbIn", ahbInObj, inJson, sizeof(inJson));
    formatAhbJson("ahbOut", ahbOutObj, outJson, sizeof(outJson));

    snprintf(jsonBuf, sizeof(jsonBuf),
        "{\"ok\":true,\"instApi\":\"%u.%u.%u\",\"devApi\":\"%u.%u.%u\",\"device\":\"%s\",\"extMemAhb\":%s,\"ycbcrConv\":%s,\"foreignQ\":%s,\"devCreateRc\":%d,%s,%s}",
        VK_VERSION_MAJOR(instVer), VK_VERSION_MINOR(instVer), VK_VERSION_PATCH(instVer),
        VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion),
        props.deviceName,
        hasExtMemAhb ? "true" : "false",
        hasYcbcrConv ? "true" : "false",
        hasForeignQueue ? "true" : "false",
        static_cast<int>(devRc),
        inJson,
        outJson);

    if (device != VK_NULL_HANDLE) {
        auto pfnDestroyDev = reinterpret_cast<PFN_vkDestroyDevice>(pfnGetDevProc(device, "vkDestroyDevice"));
        if (pfnDestroyDev) pfnDestroyDev(device, nullptr);
    }
    if (pfnDestroyInst) pfnDestroyInst(instance, nullptr);
    dlclose(vkLib);
    return env->NewStringUTF(jsonBuf);
}

}  // extern "C"
