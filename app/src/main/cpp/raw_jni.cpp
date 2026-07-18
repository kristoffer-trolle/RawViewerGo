#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstdint>

#include "libraw/libraw.h"

#define LOG_TAG "NativeRaw"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jobject JNICALL
Java_com_rawviewergo_NativeRaw_decodeFull(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);

    LibRaw processor;
    int ret = processor.open_file(path);
    env->ReleaseStringUTFChars(jpath, path);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("open_file failed: %s", libraw_strerror(ret));
        return nullptr;
    }

    ret = processor.unpack();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("unpack failed: %s", libraw_strerror(ret));
        return nullptr;
    }

    processor.imgdata.params.use_camera_wb = 1;
    processor.imgdata.params.output_color = 1; // sRGB
    processor.imgdata.params.output_bps = 8;
    processor.imgdata.params.no_auto_bright = 0;

    ret = processor.dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("dcraw_process failed: %s", libraw_strerror(ret));
        return nullptr;
    }

    libraw_processed_image_t *img = processor.dcraw_make_mem_image(&ret);
    if (!img || ret != LIBRAW_SUCCESS) {
        LOGE("dcraw_make_mem_image failed: %s", libraw_strerror(ret));
        return nullptr;
    }

    if (img->type != LIBRAW_IMAGE_BITMAP || img->colors != 3 || img->bits != 8) {
        LOGE("unexpected image format type=%d colors=%d bits=%d", img->type, img->colors, img->bits);
        LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }

    int width = img->width;
    int height = img->height;
    LOGI("decoded %dx%d", width, height);

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb8888 = env->GetStaticObjectField(configClass, argb8888Field);
    jmethodID createBitmapMethod = env->GetStaticMethodID(bitmapClass, "createBitmap",
                                                            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, width, height, argb8888);
    if (bitmap == nullptr) {
        LOGE("Bitmap.createBitmap returned null (OOM?)");
        LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }

    void *pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("AndroidBitmap_lockPixels failed");
        LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }

    auto *dst = static_cast<uint32_t *>(pixels);
    const uint8_t *src = reinterpret_cast<const uint8_t *>(img->data);
    int pixelCount = width * height;
    for (int i = 0; i < pixelCount; i++) {
        uint8_t r = src[i * 3 + 0];
        uint8_t g = src[i * 3 + 1];
        uint8_t b = src[i * 3 + 2];
        dst[i] = (0xFFu << 24) | (static_cast<uint32_t>(b) << 16) |
                  (static_cast<uint32_t>(g) << 8) | r;
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    LibRaw::dcraw_clear_mem(img);

    return bitmap;
}
