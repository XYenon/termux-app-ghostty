#include "ghostty_engine.h"
#include "ghostty_renderer.h"

#include <android/keycodes.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <png.h>

#include <algorithm>
#include <cctype>
#include <climits>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#define LOG_TAG "TermuxGhostty"

namespace {

std::once_flag g_sys_init;

class ScopedEngineLock {
  public:
    explicit ScopedEngineLock(TermuxGhosttyEngine *engine) : engine_(engine) {
        termux_ghostty_engine_lock(engine_);
    }

    ~ScopedEngineLock() {
        if (locked_) termux_ghostty_engine_unlock(engine_);
    }

    ScopedEngineLock(const ScopedEngineLock &) = delete;
    ScopedEngineLock &operator=(const ScopedEngineLock &) = delete;

    void unlock() {
        if (!locked_) return;
        termux_ghostty_engine_unlock(engine_);
        locked_ = false;
    }

  private:
    TermuxGhosttyEngine *engine_;
    bool locked_ = true;
};

JNIEnv *get_env(TermuxGhosttyEngine *engine, bool *attached) {
    *attached = false;
    JNIEnv *env = nullptr;
    if (engine->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) ==
        JNI_OK) {
        return env;
    }
    if (engine->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    *attached = true;
    return env;
}

void release_env(TermuxGhosttyEngine *engine, bool attached) {
    if (attached) engine->vm->DetachCurrentThread();
}

void clear_java_exception(JNIEnv *env, const char *operation) {
    if (!env->ExceptionCheck()) return;
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                        "Java exception during %s", operation);
    env->ExceptionDescribe();
    env->ExceptionClear();
}

void write_pty(GhosttyTerminal, void *userdata, const uint8_t *data,
               size_t length) {
    auto *engine = static_cast<TermuxGhosttyEngine *>(userdata);
    if (engine->pending_pty_writes) {
        // Reentrant call while the engine mutex is held: defer the write so we
        // do not call back into Java (which may block on a full PTY queue)
        // while holding the lock and stalling the render thread.
        try {
            engine->pending_pty_writes->insert(
                engine->pending_pty_writes->end(), data, data + length);
        } catch (const std::bad_alloc &) {
            engine->callback_allocation_failed = true;
        }
        return;
    }
    bool attached;
    JNIEnv *env = get_env(engine, &attached);
    if (!env) return;
    constexpr size_t kChunkSize = 1024 * 1024;
    size_t offset = 0;
    while (offset < length && !env->ExceptionCheck()) {
        const jsize chunk = static_cast<jsize>(
            std::min(kChunkSize, length - offset));
        jbyteArray bytes = env->NewByteArray(chunk);
        if (!bytes) break;
        env->SetByteArrayRegion(
            bytes, 0, chunk,
            reinterpret_cast<const jbyte *>(data + offset));
        if (!env->ExceptionCheck()) {
            env->CallVoidMethod(
                engine->output, engine->write_method, bytes, 0, chunk);
        }
        env->DeleteLocalRef(bytes);
        offset += static_cast<size_t>(chunk);
    }
    clear_java_exception(env, "PTY response");
    release_env(engine, attached);
}

bool terminal_size(GhosttyTerminal terminal, void *,
                   GhosttySizeReportSize *out_size) {
    uint16_t columns = 0;
    uint16_t rows = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    if (!out_size ||
        ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLS,
                            &columns) != GHOSTTY_SUCCESS ||
        ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_ROWS,
                            &rows) != GHOSTTY_SUCCESS ||
        ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_WIDTH_PX,
                            &width) != GHOSTTY_SUCCESS ||
        ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_HEIGHT_PX,
                            &height) != GHOSTTY_SUCCESS ||
        columns == 0 || rows == 0) {
        return false;
    }

    *out_size = {
        rows,
        columns,
        width / columns,
        height / rows,
    };
    return true;
}

bool color_scheme(GhosttyTerminal terminal, void *,
                  GhosttyColorScheme *out_scheme) {
    GhosttyColorRgb background{};
    if (!out_scheme ||
        ghostty_terminal_get(terminal,
                            GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND,
                            &background) != GHOSTTY_SUCCESS) {
        return false;
    }

    *out_scheme = ghostty_color_perceived_luminance(&background) > 0.5
        ? GHOSTTY_COLOR_SCHEME_LIGHT
        : GHOSTTY_COLOR_SCHEME_DARK;
    return true;
}

void bell(GhosttyTerminal, void *userdata) {
    auto *engine = static_cast<TermuxGhosttyEngine *>(userdata);
    bool attached;
    JNIEnv *env = get_env(engine, &attached);
    if (!env) return;
    env->CallVoidMethod(engine->output, engine->bell_method);
    clear_java_exception(env, "bell callback");
    release_env(engine, attached);
}

std::string terminal_string(GhosttyTerminal terminal, GhosttyTerminalData data) {
    GhosttyString value{};
    if (ghostty_terminal_get(terminal, data, &value) != GHOSTTY_SUCCESS ||
        !value.ptr || value.len == 0) {
        return {};
    }
    return {reinterpret_cast<const char *>(value.ptr), value.len};
}

jstring new_java_string_from_utf8(JNIEnv *env, const std::string &value) {
    std::vector<jchar> utf16;
    utf16.reserve(value.size());
    const auto *bytes =
        reinterpret_cast<const uint8_t *>(value.data());
    size_t index = 0;
    while (index < value.size()) {
        const uint8_t lead = bytes[index];
        uint32_t codepoint;
        size_t continuation;
        uint32_t minimum;
        if (lead < 0x80) {
            codepoint = lead;
            continuation = 0;
            minimum = 0;
        } else if ((lead & 0xe0) == 0xc0) {
            codepoint = lead & 0x1f;
            continuation = 1;
            minimum = 0x80;
        } else if ((lead & 0xf0) == 0xe0) {
            codepoint = lead & 0x0f;
            continuation = 2;
            minimum = 0x800;
        } else if ((lead & 0xf8) == 0xf0) {
            codepoint = lead & 0x07;
            continuation = 3;
            minimum = 0x10000;
        } else {
            utf16.push_back(0xfffd);
            ++index;
            continue;
        }

        if (continuation > value.size() - index - 1) {
            utf16.push_back(0xfffd);
            ++index;
            continue;
        }
        bool valid = true;
        for (size_t i = 1; i <= continuation; ++i) {
            const uint8_t next = bytes[index + i];
            if ((next & 0xc0) != 0x80) {
                valid = false;
                break;
            }
            codepoint = (codepoint << 6) | (next & 0x3f);
        }
        if (!valid || codepoint < minimum || codepoint > 0x10ffff ||
            (codepoint >= 0xd800 && codepoint <= 0xdfff)) {
            utf16.push_back(0xfffd);
            ++index;
            continue;
        }

        index += continuation + 1;
        if (codepoint <= 0xffff) {
            utf16.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            utf16.push_back(static_cast<jchar>(
                0xd800 + (codepoint >> 10)));
            utf16.push_back(static_cast<jchar>(
                0xdc00 + (codepoint & 0x3ff)));
        }
    }
    if (utf16.size() > static_cast<size_t>(INT_MAX)) return nullptr;
    return env->NewString(
        utf16.empty() ? nullptr : utf16.data(),
        static_cast<jsize>(utf16.size()));
}

void title_changed(GhosttyTerminal terminal, void *userdata) {
    auto *engine = static_cast<TermuxGhosttyEngine *>(userdata);
    try {
        std::string new_title =
            terminal_string(terminal, GHOSTTY_TERMINAL_DATA_TITLE);
        char *copy = strdup(new_title.c_str());
        if (!copy) {
            engine->callback_allocation_failed = true;
            return;
        }
        free(engine->title);
        engine->title = copy;
    } catch (const std::bad_alloc &) {
        engine->callback_allocation_failed = true;
    }
}

void pwd_changed(GhosttyTerminal terminal, void *userdata) {
    auto *engine = static_cast<TermuxGhosttyEngine *>(userdata);
    try {
        std::string new_pwd =
            terminal_string(terminal, GHOSTTY_TERMINAL_DATA_PWD);
        char *copy = strdup(new_pwd.c_str());
        if (!copy) {
            engine->callback_allocation_failed = true;
            return;
        }
        free(engine->pwd);
        engine->pwd = copy;
    } catch (const std::bad_alloc &) {
        engine->callback_allocation_failed = true;
    }
}

void desktop_notification(
    GhosttyTerminal, void *userdata,
    const GhosttyTerminalDesktopNotification *notification) {
    auto *engine = static_cast<TermuxGhosttyEngine *>(userdata);
    if (!notification ||
        (notification->title.len > 0 && !notification->title.ptr) ||
        (notification->body.len > 0 && !notification->body.ptr)) {
        return;
    }
    try {
        if (notification->title.len == 0) {
            engine->pending_notification_title.clear();
        } else {
            engine->pending_notification_title.assign(
                reinterpret_cast<const char *>(notification->title.ptr),
                notification->title.len);
        }
        if (notification->body.len == 0) {
            engine->pending_notification_body.clear();
        } else {
            engine->pending_notification_body.assign(
                reinterpret_cast<const char *>(notification->body.ptr),
                notification->body.len);
        }
        // A single PTY read can contain several requests. Keep the latest one;
        // Android applies user-visible rate limiting after the native lock is
        // released.
        engine->pending_desktop_notification = true;
    } catch (const std::bad_alloc &) {
        engine->callback_allocation_failed = true;
        engine->pending_desktop_notification = false;
    }
}

void progress_report(GhosttyTerminal, void *userdata,
                     const GhosttyTerminalProgressReport *report) {
    auto *engine = static_cast<TermuxGhosttyEngine *>(userdata);
    if (!report) return;
    engine->pending_progress_report = true;
    engine->pending_progress_state = static_cast<int>(report->state);
    engine->pending_progress_value = static_cast<int>(report->progress);
}

void notify_title_changed(TermuxGhosttyEngine *engine,
                          const std::string &old_title,
                          const std::string &new_title) {
    bool attached;
    JNIEnv *env = get_env(engine, &attached);
    if (!env) return;
    jstring old_value = old_title.empty() ? nullptr :
        new_java_string_from_utf8(env, old_title);
    jstring new_value = new_title.empty() ? nullptr :
        new_java_string_from_utf8(env, new_title);
    if (!env->ExceptionCheck()) {
        env->CallVoidMethod(engine->output, engine->title_method, old_value,
                            new_value);
    }
    if (old_value) env->DeleteLocalRef(old_value);
    if (new_value) env->DeleteLocalRef(new_value);
    clear_java_exception(env, "title callback");
    release_env(engine, attached);
}

void notify_string_changed(TermuxGhosttyEngine *engine, jmethodID method,
                           const std::string &value, const char *operation) {
    bool attached;
    JNIEnv *env = get_env(engine, &attached);
    if (!env) return;
    jstring java_value = value.empty() ? nullptr :
        new_java_string_from_utf8(env, value);
    if (!env->ExceptionCheck()) {
        env->CallVoidMethod(engine->output, method, java_value);
    }
    if (java_value) env->DeleteLocalRef(java_value);
    clear_java_exception(env, operation);
    release_env(engine, attached);
}

void notify_mouse_shape_changed(TermuxGhosttyEngine *engine, int shape) {
    bool attached;
    JNIEnv *env = get_env(engine, &attached);
    if (!env) return;
    env->CallVoidMethod(engine->output, engine->mouse_shape_method,
                        static_cast<jint>(shape));
    clear_java_exception(env, "mouse shape callback");
    release_env(engine, attached);
}

void notify_desktop_notification(TermuxGhosttyEngine *engine,
                                 const std::string &title,
                                 const std::string &body) {
    bool attached;
    JNIEnv *env = get_env(engine, &attached);
    if (!env) return;
    jstring java_title = title.empty() ? nullptr :
        new_java_string_from_utf8(env, title);
    jstring java_body = body.empty() ? nullptr :
        new_java_string_from_utf8(env, body);
    if (!env->ExceptionCheck()) {
        env->CallVoidMethod(engine->output,
                            engine->desktop_notification_method,
                            java_title, java_body);
    }
    if (java_title) env->DeleteLocalRef(java_title);
    if (java_body) env->DeleteLocalRef(java_body);
    clear_java_exception(env, "desktop notification callback");
    release_env(engine, attached);
}

void notify_progress_report(TermuxGhosttyEngine *engine, int state,
                            int progress) {
    bool attached;
    JNIEnv *env = get_env(engine, &attached);
    if (!env) return;
    env->CallVoidMethod(engine->output, engine->progress_report_method,
                        static_cast<jint>(state),
                        static_cast<jint>(progress));
    clear_java_exception(env, "progress report callback");
    release_env(engine, attached);
}

bool is_plain_text_mime(const GhosttyString &mime) {
    char normalized[25];
    size_t length = 0;
    for (size_t i = 0; i < mime.len; ++i) {
        unsigned char ch = mime.ptr[i];
        if (std::isspace(ch)) continue;
        if (length + 1 >= sizeof(normalized)) return false;
        normalized[length++] = static_cast<char>(std::tolower(ch));
    }
    normalized[length] = '\0';
    return strcmp(normalized, "text/plain") == 0 ||
        strcmp(normalized, "text/plain;charset=utf-8") == 0;
}

GhosttyClipboardWriteResult clipboard_write(
    GhosttyTerminal, void *userdata, const GhosttyClipboardWrite *write) {
    auto *engine = static_cast<TermuxGhosttyEngine *>(userdata);
    if (!write) return GHOSTTY_CLIPBOARD_WRITE_RESULT_INVALID_DATA;
    if (write->contents_len > 0 && !write->contents) {
        return GHOSTTY_CLIPBOARD_WRITE_RESULT_INVALID_DATA;
    }

    const GhosttyClipboardContent *selected = nullptr;
    for (size_t i = 0; i < write->contents_len; ++i) {
        const auto &content = write->contents[i];
        if ((content.mime.len > 0 && !content.mime.ptr) ||
            (content.data.len > 0 && !content.data.ptr)) {
            return GHOSTTY_CLIPBOARD_WRITE_RESULT_INVALID_DATA;
        }
        if (content.mime.len == 0) continue;
        if (is_plain_text_mime(content.mime)) {
            selected = &content;
            break;
        }
    }
    if (write->contents_len > 0 && !selected) {
        return GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
    }
    if (selected && (selected->mime.len > INT_MAX ||
                     selected->data.len > INT_MAX)) {
        return GHOSTTY_CLIPBOARD_WRITE_RESULT_INVALID_DATA;
    }

    bool attached;
    JNIEnv *env = get_env(engine, &attached);
    if (!env) return GHOSTTY_CLIPBOARD_WRITE_RESULT_IO_ERROR;

    jstring mime = nullptr;
    jbyteArray data = nullptr;
    if (selected) {
        mime = env->NewStringUTF("text/plain");
        data = env->NewByteArray(static_cast<jsize>(selected->data.len));
        if (data && selected->data.len > 0) {
            env->SetByteArrayRegion(
                data, 0, static_cast<jsize>(selected->data.len),
                reinterpret_cast<const jbyte *>(selected->data.ptr));
        }
    }
    if (selected && (!mime || !data || env->ExceptionCheck())) {
        clear_java_exception(env, "clipboard allocation");
        if (mime) env->DeleteLocalRef(mime);
        if (data) env->DeleteLocalRef(data);
        release_env(engine, attached);
        return GHOSTTY_CLIPBOARD_WRITE_RESULT_IO_ERROR;
    }

    jint result = env->CallIntMethod(
        engine->output, engine->clipboard_write_method,
        static_cast<jint>(write->location), mime, data,
        static_cast<jboolean>(write->contents_len == 0));
    bool failed = env->ExceptionCheck();
    clear_java_exception(env, "clipboard callback");
    if (mime) env->DeleteLocalRef(mime);
    if (data) env->DeleteLocalRef(data);
    release_env(engine, attached);
    if (failed || result < GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS ||
        result > GHOSTTY_CLIPBOARD_WRITE_RESULT_IO_ERROR) {
        return GHOSTTY_CLIPBOARD_WRITE_RESULT_IO_ERROR;
    }
    return static_cast<GhosttyClipboardWriteResult>(result);
}

bool clipboard_read(GhosttyTerminal, void *userdata,
                    GhosttyClipboardLocation location,
                    GhosttyBuffer *out_data) {
    auto *engine = static_cast<TermuxGhosttyEngine *>(userdata);
    if (!out_data) return false;

    bool attached;
    JNIEnv *env = get_env(engine, &attached);
    if (!env) return false;
    auto data = static_cast<jbyteArray>(env->CallObjectMethod(
        engine->output, engine->clipboard_read_method,
        static_cast<jint>(location)));
    if (env->ExceptionCheck() || !data) {
        clear_java_exception(env, "clipboard read callback");
        if (data) env->DeleteLocalRef(data);
        release_env(engine, attached);
        return false;
    }

    const jsize length = env->GetArrayLength(data);
    try {
        engine->clipboard_read_buffer.resize(static_cast<size_t>(length));
    } catch (const std::bad_alloc &) {
        env->DeleteLocalRef(data);
        release_env(engine, attached);
        return false;
    }
    if (length > 0) {
        env->GetByteArrayRegion(
            data, 0, length,
            reinterpret_cast<jbyte *>(engine->clipboard_read_buffer.data()));
    }
    bool failed = env->ExceptionCheck();
    clear_java_exception(env, "clipboard read copy");
    env->DeleteLocalRef(data);
    release_env(engine, attached);
    if (failed) return false;

    out_data->ptr = engine->clipboard_read_buffer.empty()
        ? nullptr
        : engine->clipboard_read_buffer.data();
    out_data->cap = engine->clipboard_read_buffer.size();
    out_data->len = engine->clipboard_read_buffer.size();
    return true;
}

void colors_changed(TermuxGhosttyEngine *engine) {
    bool attached;
    JNIEnv *env = get_env(engine, &attached);
    if (!env) return;
    env->CallVoidMethod(engine->output, engine->colors_method);
    clear_java_exception(env, "colors callback");
    release_env(engine, attached);
}

bool read_background(GhosttyTerminal terminal, GhosttyColorRgb *color) {
    return ghostty_terminal_get(
        terminal, GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND, color) ==
        GHOSTTY_SUCCESS;
}

bool colors_equal(GhosttyColorRgb left, GhosttyColorRgb right) {
    return left.r == right.r && left.g == right.g && left.b == right.b;
}

bool decode_png(void *, const GhosttyAllocator *allocator, const uint8_t *data,
                size_t length, GhosttySysImage *out) {
    png_image image{};
    image.version = PNG_IMAGE_VERSION;
    if (!png_image_begin_read_from_memory(&image, data, length)) return false;
    image.format = PNG_FORMAT_RGBA;
    size_t size = PNG_IMAGE_SIZE(image);
    auto *pixels = static_cast<uint8_t *>(ghostty_alloc(allocator, size));
    if (!pixels) {
        png_image_free(&image);
        return false;
    }
    if (!png_image_finish_read(&image, nullptr, pixels, 0, nullptr)) {
        ghostty_free(allocator, pixels, size);
        png_image_free(&image);
        return false;
    }
    out->width = image.width;
    out->height = image.height;
    out->data = pixels;
    out->data_len = size;
    png_image_free(&image);
    return true;
}

void init_ghostty_sys() {
    ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG,
                    reinterpret_cast<const void *>(decode_png));
    ghostty_sys_set(GHOSTTY_SYS_OPT_LOG,
                    reinterpret_cast<const void *>(ghostty_sys_log_stderr));
}

void throw_illegal_state(JNIEnv *env, const char *message) {
    jclass clazz = env->FindClass("java/lang/IllegalStateException");
    if (clazz) env->ThrowNew(clazz, message);
}

void throw_illegal_argument(JNIEnv *env, const char *message) {
    jclass clazz = env->FindClass("java/lang/IllegalArgumentException");
    if (clazz) env->ThrowNew(clazz, message);
}

GhosttyKey map_android_key(jint key) {
    if (key >= AKEYCODE_A && key <= AKEYCODE_Z) {
        return static_cast<GhosttyKey>(
            GHOSTTY_KEY_A + (key - AKEYCODE_A));
    }
    if (key >= AKEYCODE_0 && key <= AKEYCODE_9) {
        return static_cast<GhosttyKey>(
            GHOSTTY_KEY_DIGIT_0 + (key - AKEYCODE_0));
    }
    if (key >= AKEYCODE_F1 && key <= AKEYCODE_F12) {
        return static_cast<GhosttyKey>(
            GHOSTTY_KEY_F1 + (key - AKEYCODE_F1));
    }
    if (key >= AKEYCODE_NUMPAD_0 && key <= AKEYCODE_NUMPAD_9) {
        return static_cast<GhosttyKey>(
            GHOSTTY_KEY_NUMPAD_0 + (key - AKEYCODE_NUMPAD_0));
    }
    switch (key) {
        case AKEYCODE_DEL: return GHOSTTY_KEY_BACKSPACE;
        case AKEYCODE_FORWARD_DEL: return GHOSTTY_KEY_DELETE;
        case AKEYCODE_ENTER: return GHOSTTY_KEY_ENTER;
        case AKEYCODE_NUMPAD_ENTER: return GHOSTTY_KEY_NUMPAD_ENTER;
        case AKEYCODE_TAB: return GHOSTTY_KEY_TAB;
        case AKEYCODE_SPACE: return GHOSTTY_KEY_SPACE;
        case AKEYCODE_ESCAPE:
        case AKEYCODE_BACK: return GHOSTTY_KEY_ESCAPE;
        case AKEYCODE_DPAD_UP: return GHOSTTY_KEY_ARROW_UP;
        case AKEYCODE_DPAD_DOWN: return GHOSTTY_KEY_ARROW_DOWN;
        case AKEYCODE_DPAD_LEFT: return GHOSTTY_KEY_ARROW_LEFT;
        case AKEYCODE_DPAD_RIGHT: return GHOSTTY_KEY_ARROW_RIGHT;
        case AKEYCODE_MOVE_HOME: return GHOSTTY_KEY_HOME;
        case AKEYCODE_MOVE_END: return GHOSTTY_KEY_END;
        case AKEYCODE_INSERT: return GHOSTTY_KEY_INSERT;
        case AKEYCODE_PAGE_UP: return GHOSTTY_KEY_PAGE_UP;
        case AKEYCODE_PAGE_DOWN: return GHOSTTY_KEY_PAGE_DOWN;
        case AKEYCODE_NUM_LOCK: return GHOSTTY_KEY_NUM_LOCK;
        case AKEYCODE_NUMPAD_ADD: return GHOSTTY_KEY_NUMPAD_ADD;
        case AKEYCODE_NUMPAD_SUBTRACT: return GHOSTTY_KEY_NUMPAD_SUBTRACT;
        case AKEYCODE_NUMPAD_MULTIPLY: return GHOSTTY_KEY_NUMPAD_MULTIPLY;
        case AKEYCODE_NUMPAD_DIVIDE: return GHOSTTY_KEY_NUMPAD_DIVIDE;
        case AKEYCODE_NUMPAD_DOT: return GHOSTTY_KEY_NUMPAD_DECIMAL;
        case AKEYCODE_NUMPAD_COMMA: return GHOSTTY_KEY_NUMPAD_COMMA;
        case AKEYCODE_NUMPAD_EQUALS: return GHOSTTY_KEY_NUMPAD_EQUAL;
        case AKEYCODE_MINUS: return GHOSTTY_KEY_MINUS;
        case AKEYCODE_EQUALS: return GHOSTTY_KEY_EQUAL;
        case AKEYCODE_LEFT_BRACKET: return GHOSTTY_KEY_BRACKET_LEFT;
        case AKEYCODE_RIGHT_BRACKET: return GHOSTTY_KEY_BRACKET_RIGHT;
        case AKEYCODE_BACKSLASH: return GHOSTTY_KEY_BACKSLASH;
        case AKEYCODE_SEMICOLON: return GHOSTTY_KEY_SEMICOLON;
        case AKEYCODE_APOSTROPHE: return GHOSTTY_KEY_QUOTE;
        case AKEYCODE_COMMA: return GHOSTTY_KEY_COMMA;
        case AKEYCODE_PERIOD: return GHOSTTY_KEY_PERIOD;
        case AKEYCODE_SLASH: return GHOSTTY_KEY_SLASH;
        case AKEYCODE_GRAVE: return GHOSTTY_KEY_BACKQUOTE;
        case AKEYCODE_SHIFT_LEFT: return GHOSTTY_KEY_SHIFT_LEFT;
        case AKEYCODE_SHIFT_RIGHT: return GHOSTTY_KEY_SHIFT_RIGHT;
        case AKEYCODE_CTRL_LEFT: return GHOSTTY_KEY_CONTROL_LEFT;
        case AKEYCODE_CTRL_RIGHT: return GHOSTTY_KEY_CONTROL_RIGHT;
        case AKEYCODE_ALT_LEFT: return GHOSTTY_KEY_ALT_LEFT;
        case AKEYCODE_ALT_RIGHT: return GHOSTTY_KEY_ALT_RIGHT;
        case AKEYCODE_META_LEFT: return GHOSTTY_KEY_META_LEFT;
        case AKEYCODE_META_RIGHT: return GHOSTTY_KEY_META_RIGHT;
        case AKEYCODE_CAPS_LOCK: return GHOSTTY_KEY_CAPS_LOCK;
        default: return GHOSTTY_KEY_UNIDENTIFIED;
    }
}

jbyteArray vector_to_byte_array(JNIEnv *env, const uint8_t *data,
                                size_t length) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(length));
    if (result && length > 0) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(length),
                                reinterpret_cast<const jbyte *>(data));
    }
    return result;
}

jbyteArray format_transcript(JNIEnv *env, TermuxGhosttyEngine *engine,
                             bool unwrap, bool trim) {
    GhosttyFormatter formatter = nullptr;
    GhosttyFormatterTerminalOptions options{};
    options.size = sizeof(options);
    options.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
    options.unwrap = unwrap;
    options.trim = trim;
    if (ghostty_formatter_terminal_new(nullptr, &formatter, engine->terminal,
                                       options) != GHOSTTY_SUCCESS) {
        return nullptr;
    }
    uint8_t *data = nullptr;
    size_t length = 0;
    GhosttyResult result =
        ghostty_formatter_format_alloc(formatter, nullptr, &data, &length);
    ghostty_formatter_free(formatter);
    if (result != GHOSTTY_SUCCESS) return nullptr;
    jbyteArray bytes = vector_to_byte_array(env, data, length);
    ghostty_free(nullptr, data, length);
    return bytes;
}

bool relative_viewport_ref(TermuxGhosttyEngine *engine, int column, int row,
                           const GhosttyTerminalScrollbar &scrollbar,
                           GhosttyGridRef *out) {
    if (scrollbar.total == 0) return false;
    int64_t screen_row =
        static_cast<int64_t>(scrollbar.offset) + static_cast<int64_t>(row);
    screen_row = std::max<int64_t>(0, std::min<int64_t>(
        screen_row, static_cast<int64_t>(scrollbar.total - 1)));
    GhosttyPoint point{};
    point.tag = GHOSTTY_POINT_TAG_SCREEN;
    point.value.coordinate.x = static_cast<uint16_t>(std::max(0, column));
    point.value.coordinate.y = static_cast<uint32_t>(screen_row);
    *out = {};
    out->size = sizeof(*out);
    return ghostty_terminal_grid_ref(engine->terminal, point, out) ==
        GHOSTTY_SUCCESS;
}

bool viewport_ref(TermuxGhosttyEngine *engine, int column, int row,
                  GhosttyGridRef *out) {
    GhosttyPoint point{};
    point.tag = GHOSTTY_POINT_TAG_VIEWPORT;
    point.value.coordinate.x = static_cast<uint16_t>(std::max(0, column));
    point.value.coordinate.y = static_cast<uint32_t>(std::max(0, row));
    *out = {};
    out->size = sizeof(*out);
    return ghostty_terminal_grid_ref(engine->terminal, point, out) ==
        GHOSTTY_SUCCESS;
}

jbyteArray format_selection(JNIEnv *env, TermuxGhosttyEngine *engine,
                            const GhosttySelection &selection, bool unwrap,
                            bool trim) {
    GhosttyTerminalSelectionFormatOptions options{};
    options.size = sizeof(options);
    options.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
    options.unwrap = unwrap;
    options.trim = trim;
    options.selection = &selection;
    uint8_t *data = nullptr;
    size_t length = 0;
    GhosttyResult result = ghostty_terminal_selection_format_alloc(
        engine->terminal, nullptr, options, &data, &length);
    if (result != GHOSTTY_SUCCESS) return nullptr;
    jbyteArray bytes = vector_to_byte_array(env, data, length);
    ghostty_free(nullptr, data, length);
    return bytes;
}

void destroy_engine(JNIEnv *env, TermuxGhosttyEngine *engine) {
    if (!engine) return;
    termux_renderer_destroy(engine->renderer);
    ghostty_render_state_row_cells_free(engine->row_cells);
    ghostty_render_state_row_iterator_free(engine->row_iterator);
    ghostty_render_state_free(engine->render_state);
    ghostty_mouse_event_free(engine->mouse_event);
    ghostty_mouse_encoder_free(engine->mouse_encoder);
    ghostty_key_event_free(engine->key_event);
    ghostty_key_encoder_free(engine->key_encoder);
    ghostty_terminal_free(engine->terminal);
    if (engine->output) env->DeleteGlobalRef(engine->output);
    free(engine->title);
    free(engine->pwd);
    pthread_mutex_destroy(&engine->mutex);
    delete engine;
}

}  // namespace

TermuxGhosttyEngine *termux_ghostty_engine_from_handle(jlong handle) {
    return reinterpret_cast<TermuxGhosttyEngine *>(
        static_cast<uintptr_t>(handle));
}

void termux_ghostty_engine_lock(TermuxGhosttyEngine *engine) {
    pthread_mutex_lock(&engine->mutex);
}

void termux_ghostty_engine_unlock(TermuxGhosttyEngine *engine) {
    pthread_mutex_unlock(&engine->mutex);
}

void termux_ghostty_engine_write(TermuxGhosttyEngine *engine,
                                 const uint8_t *data, size_t length) {
    write_pty(engine->terminal, engine, data, length);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeCreate(
    JNIEnv *env, jclass, jobject output, jint columns, jint rows,
    jint cell_width, jint cell_height, jint transcript_rows) {
    std::call_once(g_sys_init, init_ghostty_sys);

    auto *engine = new (std::nothrow) TermuxGhosttyEngine{};
    if (!engine) {
        throw_illegal_state(env, "Out of memory creating libghostty terminal");
        return 0;
    }
    env->GetJavaVM(&engine->vm);
    engine->output = env->NewGlobalRef(output);
    pthread_mutex_init(&engine->mutex, nullptr);

    jclass output_class = env->GetObjectClass(output);
    engine->write_method = env->GetMethodID(output_class, "write", "([BII)V");
    engine->title_method = env->GetMethodID(
        output_class, "titleChanged",
        "(Ljava/lang/String;Ljava/lang/String;)V");
    engine->pwd_method = env->GetMethodID(
        output_class, "workingDirectoryChanged", "(Ljava/lang/String;)V");
    engine->mouse_shape_method = env->GetMethodID(
        output_class, "onMouseShapeChanged", "(I)V");
    engine->desktop_notification_method = env->GetMethodID(
        output_class, "onDesktopNotification",
        "(Ljava/lang/String;Ljava/lang/String;)V");
    engine->progress_report_method = env->GetMethodID(
        output_class, "onProgressReport", "(II)V");
    engine->clipboard_write_method = env->GetMethodID(
        output_class, "onOscClipboard", "(ILjava/lang/String;[BZ)I");
    engine->clipboard_read_method = env->GetMethodID(
        output_class, "onOscClipboardRead", "(I)[B");
    engine->bell_method = env->GetMethodID(output_class, "onBell", "()V");
    engine->colors_method =
        env->GetMethodID(output_class, "onColorsChanged", "()V");
    env->DeleteLocalRef(output_class);
    if (env->ExceptionCheck()) {
        clear_java_exception(env, "resolving TerminalOutput methods");
        env->DeleteGlobalRef(engine->output);
        pthread_mutex_destroy(&engine->mutex);
        delete engine;
        throw_illegal_state(env, "Invalid TerminalOutput JNI interface");
        return 0;
    }

    GhosttyTerminalOptions options{};
    options.cols = static_cast<uint16_t>(columns);
    options.rows = static_cast<uint16_t>(rows);
    options.max_scrollback =
        static_cast<size_t>(std::max(0, transcript_rows));
    if (ghostty_terminal_new(nullptr, &engine->terminal, options) !=
            GHOSTTY_SUCCESS ||
        ghostty_key_encoder_new(nullptr, &engine->key_encoder) !=
            GHOSTTY_SUCCESS ||
        ghostty_key_event_new(nullptr, &engine->key_event) != GHOSTTY_SUCCESS ||
        ghostty_mouse_encoder_new(nullptr, &engine->mouse_encoder) !=
            GHOSTTY_SUCCESS ||
        ghostty_mouse_event_new(nullptr, &engine->mouse_event) !=
            GHOSTTY_SUCCESS ||
        ghostty_render_state_new(nullptr, &engine->render_state) !=
            GHOSTTY_SUCCESS ||
        ghostty_render_state_row_iterator_new(nullptr, &engine->row_iterator) !=
            GHOSTTY_SUCCESS ||
        ghostty_render_state_row_cells_new(nullptr, &engine->row_cells) !=
            GHOSTTY_SUCCESS) {
        destroy_engine(env, engine);
        throw_illegal_state(env, "Could not initialize libghostty state");
        return 0;
    }

    ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_USERDATA,
                         engine);
    ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY,
                         reinterpret_cast<const void *>(write_pty));
    ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_SIZE,
                         reinterpret_cast<const void *>(terminal_size));
    ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_COLOR_SCHEME,
                         reinterpret_cast<const void *>(color_scheme));
    ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_BELL,
                         reinterpret_cast<const void *>(bell));
    ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED,
                         reinterpret_cast<const void *>(title_changed));
    ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_PWD_CHANGED,
                         reinterpret_cast<const void *>(pwd_changed));
    ghostty_terminal_set(
        engine->terminal, GHOSTTY_TERMINAL_OPT_DESKTOP_NOTIFICATION,
        reinterpret_cast<const void *>(desktop_notification));
    ghostty_terminal_set(engine->terminal,
                         GHOSTTY_TERMINAL_OPT_PROGRESS_REPORT,
                         reinterpret_cast<const void *>(progress_report));
    ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE,
                         reinterpret_cast<const void *>(clipboard_write));
    ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_CLIPBOARD_READ,
                         reinterpret_cast<const void *>(clipboard_read));
    ghostty_terminal_resize(engine->terminal, static_cast<uint16_t>(columns),
                            static_cast<uint16_t>(rows),
                            static_cast<uint32_t>(cell_width),
                            static_cast<uint32_t>(cell_height));
    engine->title = strdup("");
    engine->pwd = strdup("");
    if (!engine->title || !engine->pwd) {
        destroy_engine(env, engine);
        throw_illegal_state(env, "Out of memory creating libghostty terminal");
        return 0;
    }
    engine->mouse_shape = GHOSTTY_TERMINAL_MOUSE_SHAPE_TEXT;
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeMeasureFont(
    JNIEnv *env, jclass, jint text_size, jstring font_path) {
    const char *path = font_path
        ? env->GetStringUTFChars(font_path, nullptr)
        : nullptr;
    uint32_t width = 0;
    uint32_t height = 0;
    std::string error;
    bool success = termux_renderer_measure_font(
        static_cast<uint32_t>(text_size), path, &width, &height, &error);
    if (path) env->ReleaseStringUTFChars(font_path, path);
    if (!success) {
        throw_illegal_state(env, error.c_str());
        return nullptr;
    }
    jint values[] = {
        static_cast<jint>(width),
        static_cast<jint>(height),
    };
    jintArray result = env->NewIntArray(2);
    if (result) env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeDestroy(
    JNIEnv *env, jclass, jlong handle) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    destroy_engine(env, engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeFeed(
    JNIEnv *env, jclass, jlong handle, jbyteArray data, jint offset,
    jint count) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    if (!engine || offset < 0 || count < 0 ||
        offset + count > env->GetArrayLength(data)) {
        throw_illegal_argument(env, "Invalid VT input range");
        return;
    }
    try {
        std::vector<uint8_t> buffer(static_cast<size_t>(count));
        env->GetByteArrayRegion(data, offset, count,
                                reinterpret_cast<jbyte *>(buffer.data()));
        if (env->ExceptionCheck()) return;
        GhosttyColorRgb before{};
        GhosttyColorRgb after{};
        std::string old_title;
        std::string new_title;
        std::string old_pwd;
        std::string new_pwd;
        int old_mouse_shape;
        int new_mouse_shape;
        bool has_notification;
        std::string notification_title;
        std::string notification_body;
        bool has_progress;
        int progress_state;
        int progress_value;
        std::vector<uint8_t> pty_replies;
        bool callback_allocation_failed;
        bool had_before;
        bool has_after;
        {
            ScopedEngineLock lock(engine);
            old_title = engine->title ? engine->title : "";
            old_pwd = engine->pwd ? engine->pwd : "";
            old_mouse_shape = engine->mouse_shape;
            engine->pending_desktop_notification = false;
            engine->pending_progress_report = false;
            engine->callback_allocation_failed = false;
            had_before = read_background(engine->terminal, &before);
            engine->pending_pty_writes = &pty_replies;
            ghostty_terminal_vt_write(
                engine->terminal, buffer.data(), buffer.size());
            engine->pending_pty_writes = nullptr;
            has_after = read_background(engine->terminal, &after);
            new_title = engine->title ? engine->title : "";
            new_pwd = engine->pwd ? engine->pwd : "";
            GhosttyTerminalMouseShape mouse_shape =
                GHOSTTY_TERMINAL_MOUSE_SHAPE_TEXT;
            ghostty_terminal_get(engine->terminal,
                                 GHOSTTY_TERMINAL_DATA_MOUSE_SHAPE,
                                 &mouse_shape);
            new_mouse_shape = static_cast<int>(mouse_shape);
            engine->mouse_shape = new_mouse_shape;
            has_notification = engine->pending_desktop_notification;
            notification_title = engine->pending_notification_title;
            notification_body = engine->pending_notification_body;
            has_progress = engine->pending_progress_report;
            progress_state = engine->pending_progress_state;
            progress_value = engine->pending_progress_value;
            callback_allocation_failed = engine->callback_allocation_failed;
        }
        if (callback_allocation_failed) {
            throw std::bad_alloc();
        }
        if (!pty_replies.empty()) {
            termux_ghostty_engine_write(engine, pty_replies.data(),
                                        pty_replies.size());
        }
        if (old_title != new_title) {
            notify_title_changed(engine, old_title, new_title);
        }
        if (old_pwd != new_pwd) {
            notify_string_changed(engine, engine->pwd_method, new_pwd,
                                  "working directory callback");
        }
        if (old_mouse_shape != new_mouse_shape) {
            notify_mouse_shape_changed(engine, new_mouse_shape);
        }
        if (has_notification) {
            notify_desktop_notification(engine, notification_title,
                                        notification_body);
        }
        if (has_progress) {
            notify_progress_report(engine, progress_state, progress_value);
        }
        if (had_before != has_after ||
            (had_before && !colors_equal(before, after))) {
            colors_changed(engine);
        }
    } catch (const std::bad_alloc &) {
        {
            ScopedEngineLock lock(engine);
            engine->pending_pty_writes = nullptr;
        }
        throw_illegal_state(env, "Out of memory handling terminal effects");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeResize(
    JNIEnv *, jclass, jlong handle, jint columns, jint rows, jint cell_width,
    jint cell_height) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_resize(engine->terminal, static_cast<uint16_t>(columns),
                            static_cast<uint16_t>(rows),
                            static_cast<uint32_t>(cell_width),
                            static_cast<uint32_t>(cell_height));
    termux_ghostty_engine_unlock(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeReset(
    JNIEnv *env, jclass, jlong handle) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    try {
        GhosttyColorRgb before{};
        GhosttyColorRgb after{};
        std::string old_title;
        std::string new_title;
        std::string old_pwd;
        int old_mouse_shape;
        bool had_before;
        bool has_after;
        {
            ScopedEngineLock lock(engine);
            old_title = engine->title ? engine->title : "";
            old_pwd = engine->pwd ? engine->pwd : "";
            had_before = read_background(engine->terminal, &before);
            ghostty_terminal_reset(engine->terminal);
            has_after = read_background(engine->terminal, &after);
            new_title = terminal_string(
                engine->terminal, GHOSTTY_TERMINAL_DATA_TITLE);
            char *title = strdup(new_title.c_str());
            char *pwd = strdup("");
            if (!title || !pwd) {
                free(title);
                free(pwd);
                throw std::bad_alloc();
            }
            free(engine->title);
            engine->title = title;
            free(engine->pwd);
            engine->pwd = pwd;
            old_mouse_shape = engine->mouse_shape;
            engine->mouse_shape = GHOSTTY_TERMINAL_MOUSE_SHAPE_TEXT;
        }
        if (old_title != new_title) {
            notify_title_changed(engine, old_title, new_title);
        }
        if (!old_pwd.empty()) {
            notify_string_changed(engine, engine->pwd_method, "",
                                  "working directory reset callback");
        }
        if (old_mouse_shape != GHOSTTY_TERMINAL_MOUSE_SHAPE_TEXT) {
            notify_mouse_shape_changed(
                engine, GHOSTTY_TERMINAL_MOUSE_SHAPE_TEXT);
        }
        notify_progress_report(
            engine, GHOSTTY_TERMINAL_PROGRESS_STATE_REMOVE, -1);
        if (had_before != has_after ||
            (had_before && !colors_equal(before, after))) {
            colors_changed(engine);
        }
    } catch (const std::bad_alloc &) {
        throw_illegal_state(env, "Out of memory resetting libghostty terminal");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeGetInt(
    JNIEnv *, jclass, jlong handle, jint data) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    uint64_t value = 0;
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_get(engine->terminal,
                         static_cast<GhosttyTerminalData>(data), &value);
    termux_ghostty_engine_unlock(engine);
    return static_cast<jint>(value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeGetBoolean(
    JNIEnv *, jclass, jlong handle, jint data) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    bool value = false;
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_get(engine->terminal,
                         static_cast<GhosttyTerminalData>(data), &value);
    termux_ghostty_engine_unlock(engine);
    return value;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeGetBackgroundColor(
    JNIEnv *, jclass, jlong handle) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    GhosttyColorRgb color{};
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_get(engine->terminal,
                         GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND, &color);
    termux_ghostty_engine_unlock(engine);
    return static_cast<jint>(0xff000000u |
        (static_cast<uint32_t>(color.r) << 16u) |
        (static_cast<uint32_t>(color.g) << 8u) | color.b);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeGetString(
    JNIEnv *env, jclass, jlong handle, jint data) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    std::string value;
    termux_ghostty_engine_lock(engine);
    value = terminal_string(engine->terminal,
                            static_cast<GhosttyTerminalData>(data));
    termux_ghostty_engine_unlock(engine);
    return value.empty() ? nullptr : new_java_string_from_utf8(env, value);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeGetViewportOffset(
    JNIEnv *, jclass, jlong handle) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    GhosttyTerminalScrollbar scrollbar{};
    termux_ghostty_engine_lock(engine);
    GhosttyResult result = ghostty_terminal_get(
        engine->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &scrollbar);
    termux_ghostty_engine_unlock(engine);
    if (result != GHOSTTY_SUCCESS) return 0;
    return static_cast<jint>(std::min<uint64_t>(
        scrollbar.offset, static_cast<uint64_t>(INT_MAX)));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeFormatTranscript(
    JNIEnv *env, jclass, jlong handle, jboolean unwrap, jboolean trim) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    termux_ghostty_engine_lock(engine);
    jbyteArray result = format_transcript(env, engine, unwrap, trim);
    termux_ghostty_engine_unlock(engine);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeFormatViewport(
    JNIEnv *env, jclass, jlong handle) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    GhosttyTerminalScrollbar scrollbar{};
    GhosttySelection selection{};
    selection.size = sizeof(selection);
    uint16_t columns = 0;
    termux_ghostty_engine_lock(engine);
    bool ok = ghostty_terminal_get(
                  engine->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR,
                  &scrollbar) == GHOSTTY_SUCCESS &&
        ghostty_terminal_get(engine->terminal, GHOSTTY_TERMINAL_DATA_COLS,
                             &columns) == GHOSTTY_SUCCESS &&
        columns > 0 && scrollbar.len > 0 &&
        relative_viewport_ref(engine, 0, 0, scrollbar, &selection.start) &&
        relative_viewport_ref(
            engine, columns - 1, static_cast<int>(scrollbar.len - 1),
            scrollbar, &selection.end);
    jbyteArray result =
        ok ? format_selection(env, engine, selection, false, false) : nullptr;
    termux_ghostty_engine_unlock(engine);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeSetColorScheme(
    JNIEnv *env, jclass, jlong handle, jintArray colors) {
    if (!colors || env->GetArrayLength(colors) < 259) {
        throw_illegal_argument(env, "Terminal color scheme must have 259 colors");
        return;
    }
    jint *values = env->GetIntArrayElements(colors, nullptr);
    GhosttyColorRgb palette[256];
    for (size_t i = 0; i < 256; ++i) {
        palette[i] = {
            static_cast<uint8_t>((values[i] >> 16) & 0xff),
            static_cast<uint8_t>((values[i] >> 8) & 0xff),
            static_cast<uint8_t>(values[i] & 0xff),
        };
    }
    GhosttyColorRgb foreground{
        static_cast<uint8_t>((values[256] >> 16) & 0xff),
        static_cast<uint8_t>((values[256] >> 8) & 0xff),
        static_cast<uint8_t>(values[256] & 0xff),
    };
    GhosttyColorRgb background{
        static_cast<uint8_t>((values[257] >> 16) & 0xff),
        static_cast<uint8_t>((values[257] >> 8) & 0xff),
        static_cast<uint8_t>(values[257] & 0xff),
    };
    GhosttyColorRgb cursor{
        static_cast<uint8_t>((values[258] >> 16) & 0xff),
        static_cast<uint8_t>((values[258] >> 8) & 0xff),
        static_cast<uint8_t>(values[258] & 0xff),
    };
    env->ReleaseIntArrayElements(colors, values, JNI_ABORT);

    auto *engine = termux_ghostty_engine_from_handle(handle);
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_set(engine->terminal,
                         GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, palette);
    ghostty_terminal_set(engine->terminal,
                         GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &foreground);
    ghostty_terminal_set(engine->terminal,
                         GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &background);
    ghostty_terminal_set(engine->terminal,
                         GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &cursor);
    termux_ghostty_engine_unlock(engine);
    colors_changed(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeSetDefaultCursor(
    JNIEnv *, jclass, jlong handle, jint style, jboolean blink) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    auto cursor_style = static_cast<GhosttyTerminalCursorStyle>(style);
    bool should_blink = blink;
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_set(engine->terminal,
                         GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE,
                         &cursor_style);
    ghostty_terminal_set(engine->terminal,
                         GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK,
                         &should_blink);
    termux_ghostty_engine_unlock(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeSetGlyphProtocolEnabled(
    JNIEnv *, jclass, jlong handle, jboolean enabled) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    bool value = enabled;
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_set(engine->terminal,
                         GHOSTTY_TERMINAL_OPT_GLYPH_PROTOCOL, &value);
    termux_ghostty_engine_unlock(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeSetKittyGraphicsOptions(
    JNIEnv *env, jclass, jlong handle, jlong storage_limit,
    jstring temp_directory) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    uint64_t limit = static_cast<uint64_t>(std::max<jlong>(0, storage_limit));
    const char *path = temp_directory ?
        env->GetStringUTFChars(temp_directory, nullptr) : nullptr;
    GhosttyString directory{
        reinterpret_cast<const uint8_t *>(path),
        path ? strlen(path) : 0,
    };
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_set(engine->terminal,
                         GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT,
                         &limit);
    ghostty_terminal_set(engine->terminal,
                         GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_TEMP_FILE,
                         path ? &directory : nullptr);
    termux_ghostty_engine_unlock(engine);
    if (path) env->ReleaseStringUTFChars(temp_directory, path);
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativePaste(
    JNIEnv *env, jclass, jlong handle, jstring text) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    const char *utf8 = env->GetStringUTFChars(text, nullptr);
    size_t length = strlen(utf8);
    std::vector<char> input(utf8, utf8 + length);
    env->ReleaseStringUTFChars(text, utf8);
    bool bracketed = false;
    std::vector<char> output(length + 16);
    size_t written = 0;
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_mode_get(engine->terminal, GHOSTTY_MODE_BRACKETED_PASTE,
                              &bracketed);
    GhosttyResult result = ghostty_paste_encode(
        input.data(), input.size(), bracketed, output.data(), output.size(),
        &written);
    if (result == GHOSTTY_OUT_OF_SPACE) {
        output.resize(written);
        result = ghostty_paste_encode(input.data(), input.size(), bracketed,
                                      output.data(), output.size(), &written);
    }
    termux_ghostty_engine_unlock(engine);
    if (result == GHOSTTY_SUCCESS) {
        termux_ghostty_engine_write(
            engine, reinterpret_cast<const uint8_t *>(output.data()), written);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeSendKey(
    JNIEnv *env, jclass, jlong handle, jint key, jint action, jint modifiers,
    jstring text, jint unshifted_codepoint) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    const char *utf8 = text ? env->GetStringUTFChars(text, nullptr) : nullptr;
    size_t utf8_len = utf8 ? strlen(utf8) : 0;
    char output[128];
    size_t written = 0;
    termux_ghostty_engine_lock(engine);
    ghostty_key_encoder_setopt_from_terminal(engine->key_encoder,
                                             engine->terminal);
    ghostty_key_event_set_key(engine->key_event, map_android_key(key));
    ghostty_key_event_set_action(
        engine->key_event, static_cast<GhosttyKeyAction>(action));
    ghostty_key_event_set_mods(engine->key_event,
                               static_cast<GhosttyMods>(modifiers));
    ghostty_key_event_set_utf8(engine->key_event, utf8, utf8_len);
    ghostty_key_event_set_unshifted_codepoint(
        engine->key_event, static_cast<uint32_t>(unshifted_codepoint));
    GhosttyResult result = ghostty_key_encoder_encode(
        engine->key_encoder, engine->key_event, output, sizeof(output),
        &written);
    termux_ghostty_engine_unlock(engine);
    if (utf8) env->ReleaseStringUTFChars(text, utf8);
    if (result == GHOSTTY_SUCCESS && written > 0) {
        termux_ghostty_engine_write(
            engine, reinterpret_cast<const uint8_t *>(output), written);
        return true;
    }
    return false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeSendMouse(
    JNIEnv *, jclass, jlong handle, jint action, jint button, jint modifiers,
    jfloat x, jfloat y, jint screen_width, jint screen_height, jint cell_width,
    jint cell_height) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    char output[128];
    size_t written = 0;
    GhosttyMouseEncoderSize size{};
    size.size = sizeof(size);
    size.screen_width = static_cast<uint32_t>(screen_width);
    size.screen_height = static_cast<uint32_t>(screen_height);
    size.cell_width = static_cast<uint32_t>(cell_width);
    size.cell_height = static_cast<uint32_t>(cell_height);

    termux_ghostty_engine_lock(engine);
    ghostty_mouse_encoder_setopt_from_terminal(engine->mouse_encoder,
                                               engine->terminal);
    ghostty_mouse_encoder_setopt(engine->mouse_encoder,
                                 GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);
    ghostty_mouse_event_set_action(
        engine->mouse_event, static_cast<GhosttyMouseAction>(action));
    if (button == 0) {
        ghostty_mouse_event_clear_button(engine->mouse_event);
    } else {
        ghostty_mouse_event_set_button(
            engine->mouse_event, static_cast<GhosttyMouseButton>(button));
    }
    ghostty_mouse_event_set_mods(engine->mouse_event,
                                 static_cast<GhosttyMods>(modifiers));
    ghostty_mouse_event_set_position(engine->mouse_event, {x, y});
    GhosttyResult result = ghostty_mouse_encoder_encode(
        engine->mouse_encoder, engine->mouse_event, output, sizeof(output),
        &written);
    termux_ghostty_engine_unlock(engine);
    if (result == GHOSTTY_SUCCESS && written > 0) {
        termux_ghostty_engine_write(
            engine, reinterpret_cast<const uint8_t *>(output), written);
        return true;
    }
    return false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeSendFocus(
    JNIEnv *, jclass, jlong handle, jboolean focused) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    bool enabled = false;
    char output[8];
    size_t written = 0;
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_mode_get(engine->terminal, GHOSTTY_MODE_FOCUS_EVENT,
                              &enabled);
    termux_ghostty_engine_unlock(engine);
    if (enabled &&
        ghostty_focus_encode(
            focused ? GHOSTTY_FOCUS_GAINED : GHOSTTY_FOCUS_LOST, output,
            sizeof(output), &written) == GHOSTTY_SUCCESS) {
        termux_ghostty_engine_write(
            engine, reinterpret_cast<const uint8_t *>(output), written);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeScrollViewport(
    JNIEnv *, jclass, jlong handle, jint rows) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    GhosttyTerminalScrollViewport scroll{};
    scroll.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
    scroll.value.delta = rows;
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_scroll_viewport(engine->terminal, scroll);
    termux_ghostty_engine_unlock(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeScrollToBottom(
    JNIEnv *, jclass, jlong handle) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    GhosttyTerminalScrollViewport scroll{};
    scroll.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM;
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_scroll_viewport(engine->terminal, scroll);
    termux_ghostty_engine_unlock(engine);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeSelectWordOrOutput(
    JNIEnv *env, jclass, jlong handle, jint column, jint viewport_row) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    GhosttyGridRef ref;
    GhosttySelection selection{};
    selection.size = sizeof(selection);
    GhosttySelection ordered{};
    ordered.size = sizeof(ordered);
    GhosttyPointCoordinate start{};
    GhosttyPointCoordinate end{};
    GhosttyTerminalScrollbar scrollbar{};
    termux_ghostty_engine_lock(engine);
    bool ok = viewport_ref(engine, column, viewport_row, &ref);
    if (ok) {
        GhosttyResult result =
            ghostty_terminal_select_output(engine->terminal, ref, &selection);
        if (result != GHOSTTY_SUCCESS) {
            GhosttyTerminalSelectWordOptions options{};
            options.size = sizeof(options);
            options.ref = ref;
            result = ghostty_terminal_select_word(engine->terminal, &options,
                                                  &selection);
        }
        ok = result == GHOSTTY_SUCCESS &&
            ghostty_terminal_selection_ordered(
                engine->terminal, &selection, GHOSTTY_SELECTION_ORDER_FORWARD,
                &ordered) == GHOSTTY_SUCCESS &&
            ghostty_terminal_point_from_grid_ref(
                engine->terminal, &ordered.start, GHOSTTY_POINT_TAG_SCREEN,
                &start) == GHOSTTY_SUCCESS &&
            ghostty_terminal_point_from_grid_ref(
                engine->terminal, &ordered.end, GHOSTTY_POINT_TAG_SCREEN,
                &end) == GHOSTTY_SUCCESS &&
            ghostty_terminal_get(
                engine->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR,
                &scrollbar) == GHOSTTY_SUCCESS &&
            ghostty_terminal_set(engine->terminal,
                                 GHOSTTY_TERMINAL_OPT_SELECTION,
                                 &ordered) == GHOSTTY_SUCCESS;
    }
    termux_ghostty_engine_unlock(engine);
    if (!ok) return nullptr;

    jint values[] = {
        static_cast<jint>(start.x),
        static_cast<jint>(static_cast<int64_t>(start.y) -
                          static_cast<int64_t>(scrollbar.offset)),
        static_cast<jint>(end.x),
        static_cast<jint>(static_cast<int64_t>(end.y) -
                          static_cast<int64_t>(scrollbar.offset)),
    };
    jintArray result = env->NewIntArray(4);
    if (result) env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeGetWordAt(
    JNIEnv *env, jclass, jlong handle, jint column, jint viewport_row) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    GhosttyGridRef ref{};
    GhosttySelection selection{};
    selection.size = sizeof(selection);
    const uint32_t boundary = ' ';
    GhosttyTerminalSelectWordOptions options{};
    options.size = sizeof(options);
    options.boundary_codepoints = &boundary;
    options.boundary_codepoints_len = 1;
    termux_ghostty_engine_lock(engine);
    bool ok = viewport_ref(engine, column, viewport_row, &ref);
    options.ref = ref;
    ok = ok && ghostty_terminal_select_word(
                   engine->terminal, &options, &selection) == GHOSTTY_SUCCESS;
    jbyteArray result =
        ok ? format_selection(env, engine, selection, true, true) : nullptr;
    termux_ghostty_engine_unlock(engine);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeSetSelection(
    JNIEnv *, jclass, jlong handle, jint start_column, jint start_row,
    jint end_column, jint end_row) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    GhosttySelection selection{};
    selection.size = sizeof(selection);
    GhosttyTerminalScrollbar scrollbar{};
    termux_ghostty_engine_lock(engine);
    bool ok = ghostty_terminal_get(
                  engine->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR,
                  &scrollbar) == GHOSTTY_SUCCESS &&
        relative_viewport_ref(
            engine, start_column, start_row, scrollbar, &selection.start) &&
        relative_viewport_ref(
            engine, end_column, end_row, scrollbar, &selection.end) &&
        ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_SELECTION,
                             &selection) == GHOSTTY_SUCCESS;
    termux_ghostty_engine_unlock(engine);
    return ok;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeClearSelection(
    JNIEnv *, jclass, jlong handle) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    termux_ghostty_engine_lock(engine);
    ghostty_terminal_set(engine->terminal, GHOSTTY_TERMINAL_OPT_SELECTION,
                         nullptr);
    termux_ghostty_engine_unlock(engine);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeGetSelectedText(
    JNIEnv *env, jclass, jlong handle) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    GhosttyTerminalSelectionFormatOptions options{};
    options.size = sizeof(options);
    options.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
    options.unwrap = true;
    options.trim = true;
    uint8_t *data = nullptr;
    size_t length = 0;
    termux_ghostty_engine_lock(engine);
    GhosttyResult result = ghostty_terminal_selection_format_alloc(
        engine->terminal, nullptr, options, &data, &length);
    termux_ghostty_engine_unlock(engine);
    if (result != GHOSTTY_SUCCESS) return nullptr;
    jbyteArray bytes = vector_to_byte_array(env, data, length);
    ghostty_free(nullptr, data, length);
    return bytes;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeGetHyperlink(
    JNIEnv *env, jclass, jlong handle, jint column, jint viewport_row) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    GhosttyGridRef ref;
    size_t length = 0;
    std::vector<uint8_t> uri;
    termux_ghostty_engine_lock(engine);
    bool ok = viewport_ref(engine, column, viewport_row, &ref);
    if (ok) {
        GhosttyResult result =
            ghostty_grid_ref_hyperlink_uri(&ref, nullptr, 0, &length);
        if (result == GHOSTTY_OUT_OF_SPACE && length > 0) {
            uri.resize(length);
            ok = ghostty_grid_ref_hyperlink_uri(
                &ref, uri.data(), uri.size(), &length) == GHOSTTY_SUCCESS;
        } else {
            ok = false;
        }
    }
    termux_ghostty_engine_unlock(engine);
    return ok ? vector_to_byte_array(env, uri.data(), length) : nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeAttachSurface(
    JNIEnv *env, jclass, jlong handle, jobject surface, jint width,
    jint height, jint text_size, jstring font_path) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        throw_illegal_state(env, "Could not acquire Android native window");
        return;
    }
    const char *path = font_path
        ? env->GetStringUTFChars(font_path, nullptr)
        : nullptr;
    std::string error;
    termux_renderer_destroy(engine->renderer);
    engine->renderer = termux_renderer_create(
        engine, window, static_cast<uint32_t>(width),
        static_cast<uint32_t>(height), static_cast<uint32_t>(text_size),
        path, &error);
    ANativeWindow_release(window);
    if (path) env->ReleaseStringUTFChars(font_path, path);
    if (!engine->renderer) throw_illegal_state(env, error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeResizeSurface(
    JNIEnv *env, jclass, jlong handle, jint width, jint height,
    jint text_size, jstring font_path) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    const char *path = font_path
        ? env->GetStringUTFChars(font_path, nullptr)
        : nullptr;
    std::string error;
    bool success = engine->renderer &&
        termux_renderer_resize(
            engine->renderer, static_cast<uint32_t>(width),
            static_cast<uint32_t>(height), static_cast<uint32_t>(text_size),
            path, &error);
    if (path) env->ReleaseStringUTFChars(font_path, path);
    if (!success) throw_illegal_state(env, error.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeRender(
    JNIEnv *env, jclass, jlong handle, jboolean cursor_visible) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    if (!engine->renderer) return JNI_TRUE;
    std::string error;
    TermuxRendererDrawResult result = termux_renderer_draw(
        engine->renderer, cursor_visible, &error);
    if (result == TermuxRendererDrawResult::failure) {
        throw_illegal_state(env, error.c_str());
        return JNI_FALSE;
    }
    return result == TermuxRendererDrawResult::success
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminal_nativeDetachSurface(
    JNIEnv *, jclass, jlong handle) {
    auto *engine = termux_ghostty_engine_from_handle(handle);
    termux_renderer_destroy(engine->renderer);
    engine->renderer = nullptr;
}
