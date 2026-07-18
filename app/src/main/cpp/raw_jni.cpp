#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <sys/stat.h>

#include "libraw/libraw.h"

#define LOG_TAG "NativeRaw"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// Reads from a plain POSIX file descriptor via pread(), so LibRaw only pulls the exact
// byte ranges it needs (TIFF header, IFD chain, embedded thumbnail bytes) instead of
// requiring the whole file to be present as a local path. Mirrors LibRaw_buffer_datastream's
// semantics (see libraw_datastream.cpp) but backed by pread() instead of a memory buffer.
class FdDatastream : public LibRaw_abstract_datastream {
public:
    explicit FdDatastream(int fd) : fd_(fd), pos_(0), size_(-1) {
        struct stat st{};
        if (fstat(fd_, &st) == 0) {
            size_ = st.st_size;
        }
    }

    int valid() override { return fd_ >= 0 && size_ >= 0; }

    int read(void *ptr, size_t sz, size_t nmemb) override {
        size_t want = sz * nmemb;
        if (pos_ >= size_ || want == 0) {
            return 0;
        }
        size_t avail = static_cast<size_t>(size_ - pos_);
        size_t toRead = want > avail ? avail : want;
        ssize_t got = pread(fd_, ptr, toRead, pos_);
        if (got <= 0) {
            return 0;
        }
        pos_ += got;
        return static_cast<int>((static_cast<size_t>(got) + sz - 1) / (sz > 0 ? sz : 1));
    }

    int seek(INT64 o, int whence) override {
        int64_t base;
        switch (whence) {
            case SEEK_SET: base = 0; break;
            case SEEK_CUR: base = pos_; break;
            case SEEK_END: base = size_; break;
            default: return 0;
        }
        int64_t target = base + o;
        if (target < 0) target = 0;
        if (target > size_) target = size_;
        pos_ = target;
        return 0;
    }

    INT64 tell() override { return pos_; }

    INT64 size() override { return size_; }

    int get_char() override {
        if (pos_ >= size_) return -1;
        unsigned char c;
        ssize_t got = pread(fd_, &c, 1, pos_);
        if (got != 1) return -1;
        pos_ += 1;
        return c;
    }

    char *gets(char *s, int sz) override {
        if (sz < 1 || pos_ >= size_) return nullptr;
        int i = 0;
        while (i < sz - 1 && pos_ < size_) {
            unsigned char c;
            ssize_t got = pread(fd_, &c, 1, pos_);
            if (got != 1) break;
            pos_ += 1;
            s[i++] = static_cast<char>(c);
            if (c == '\n') break;
        }
        s[i] = 0;
        return s;
    }

    int scanf_one(const char *fmt, void *val) override {
        if (pos_ > size_ - 24) return 0;
        char buffer[25];
        ssize_t got = pread(fd_, buffer, 24, pos_);
        if (got <= 0) return 0;
        buffer[got] = 0;
        int res = sscanf(buffer, fmt, val);
        if (res > 0) {
            int xcnt = 0;
            unsigned char c = 0;
            while (pos_ < size_ - 1) {
                pos_++;
                xcnt++;
                if (pread(fd_, &c, 1, pos_) != 1) break;
                if (c == 0 || c == ' ' || c == '\t' || c == '\n' || xcnt > 24) break;
            }
        }
        return res;
    }

    int eof() override { return pos_ >= size_; }

private:
    int fd_;
    int64_t pos_;
    int64_t size_;
};

jobject createBitmapFromRgb888(JNIEnv *env, const uint8_t *src, int width, int height) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb8888 = env->GetStaticObjectField(configClass, argb8888Field);
    jmethodID createBitmapMethod = env->GetStaticMethodID(bitmapClass, "createBitmap",
                                                           "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, width, height, argb8888);
    if (bitmap == nullptr) {
        LOGE("Bitmap.createBitmap returned null (OOM?)");
        return nullptr;
    }

    void *pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("AndroidBitmap_lockPixels failed");
        return nullptr;
    }

    auto *dst = static_cast<uint32_t *>(pixels);
    int pixelCount = width * height;
    for (int i = 0; i < pixelCount; i++) {
        uint8_t r = src[i * 3 + 0];
        uint8_t g = src[i * 3 + 1];
        uint8_t b = src[i * 3 + 2];
        dst[i] = (0xFFu << 24) | (static_cast<uint32_t>(b) << 16) |
                 (static_cast<uint32_t>(g) << 8) | r;
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

} // namespace

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

    LOGI("decoded %dx%d", img->width, img->height);
    jobject bitmap = createBitmapFromRgb888(env, img->data, img->width, img->height);
    LibRaw::dcraw_clear_mem(img);
    return bitmap;
}

// Extracts the embedded thumbnail from a raw file given an already-open, seekable file
// descriptor (from ContentResolver.openFileDescriptor on a SAF content:// Uri). LibRaw only
// reads the byte ranges it actually needs via FdDatastream's pread() calls, so this avoids
// copying the whole (often tens-of-MB) raw file just to show a small browse-grid thumbnail.
// Returns either a byte[] (compressed JPEG thumb - decode with BitmapFactory) or a Bitmap
// (uncompressed thumb), depending on how the camera stored it, or null if unavailable.
extern "C" JNIEXPORT jobject JNICALL
Java_com_rawviewergo_NativeRaw_decodeThumbFd(JNIEnv *env, jclass, jint fd) {
    FdDatastream stream(fd);
    if (!stream.valid()) {
        LOGE("FdDatastream invalid for fd %d", fd);
        return nullptr;
    }

    LibRaw processor;
    int ret = processor.open_datastream(&stream);
    if (ret != LIBRAW_SUCCESS) {
        LOGE("open_datastream failed: %s", libraw_strerror(ret));
        return nullptr;
    }

    ret = processor.unpack_thumb();
    if (ret != LIBRAW_SUCCESS) {
        LOGE("unpack_thumb failed: %s", libraw_strerror(ret));
        return nullptr;
    }

    int thumbErr = 0;
    libraw_processed_image_t *thumb = processor.dcraw_make_mem_thumb(&thumbErr);
    if (!thumb || thumbErr != LIBRAW_SUCCESS) {
        LOGE("dcraw_make_mem_thumb failed: %s", libraw_strerror(thumbErr));
        if (thumb) LibRaw::dcraw_clear_mem(thumb);
        return nullptr;
    }

    jobject result = nullptr;
    if (thumb->type == LIBRAW_IMAGE_JPEG) {
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(thumb->data_size));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(thumb->data_size),
                                 reinterpret_cast<const jbyte *>(thumb->data));
        result = arr;
    } else if (thumb->type == LIBRAW_IMAGE_BITMAP && thumb->colors == 3 && thumb->bits == 8) {
        result = createBitmapFromRgb888(env, thumb->data, thumb->width, thumb->height);
    } else {
        LOGE("unexpected thumb format type=%d colors=%d bits=%d", thumb->type, thumb->colors, thumb->bits);
    }

    LibRaw::dcraw_clear_mem(thumb);
    return result;
}
