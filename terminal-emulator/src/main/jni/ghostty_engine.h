#ifndef TERMUX_GHOSTTY_ENGINE_H
#define TERMUX_GHOSTTY_ENGINE_H

#include <jni.h>
#include <pthread.h>
#include <stdint.h>

#include <string>
#include <vector>

#include <ghostty/vt.h>

struct TermuxGhosttyEngine {
    JavaVM *vm;
    jobject output;
    jmethodID write_method;
    jmethodID title_method;
    jmethodID pwd_method;
    jmethodID mouse_shape_method;
    jmethodID desktop_notification_method;
    jmethodID progress_report_method;
    jmethodID clipboard_write_method;
    jmethodID clipboard_permission_method;
    jmethodID clipboard_mimes_method;
    jmethodID clipboard_read_method;
    jmethodID clipboard_read_complete_method;
    jmethodID bell_method;
    jmethodID colors_method;
    pthread_mutex_t mutex;
    GhosttyTerminal terminal;
    GhosttyKeyEncoder key_encoder;
    GhosttyKeyEvent key_event;
    GhosttyMouseEncoder mouse_encoder;
    GhosttyMouseEvent mouse_event;
    GhosttyRenderState render_state;
    GhosttyRenderStateRowIterator row_iterator;
    GhosttyRenderStateRowCells row_cells;
    GhosttySearch search;

    char *title;
    char *pwd;
    int mouse_shape;
    bool pending_desktop_notification;
    std::string pending_notification_title;
    std::string pending_notification_body;
    bool callback_allocation_failed;
    bool pending_pty_writes_overflow;
    size_t clipboard_read_bytes_remaining;
    size_t clipboard_read_requests_remaining;
    bool pending_progress_report;
    int pending_progress_state;
    int pending_progress_value;
    struct TermuxVulkanRenderer *renderer;

    // When non-null, PTY responses produced reentrantly from a
    // ghostty_terminal_* call are appended here instead of being written to
    // Java, so the caller can flush them after releasing the engine mutex.
    std::vector<uint8_t> *pending_pty_writes;
};

TermuxGhosttyEngine *termux_ghostty_engine_from_handle(jlong handle);
void termux_ghostty_engine_lock(TermuxGhosttyEngine *engine);
void termux_ghostty_engine_unlock(TermuxGhosttyEngine *engine);
void termux_ghostty_engine_write(TermuxGhosttyEngine *engine,
                                 const uint8_t *data, size_t length);

#endif
