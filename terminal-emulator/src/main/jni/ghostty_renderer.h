#ifndef TERMUX_GHOSTTY_RENDERER_H
#define TERMUX_GHOSTTY_RENDERER_H

#include <android/native_window.h>

#include <cstdint>
#include <string>

struct TermuxGhosttyEngine;
struct TermuxVulkanRenderer;

enum class TermuxRendererDrawResult {
    success,
    deferred,
    failure,
};

TermuxVulkanRenderer *termux_renderer_create(
    TermuxGhosttyEngine *engine,
    ANativeWindow *window,
    uint32_t width,
    uint32_t height,
    uint32_t text_size,
    const char *font_path,
    std::string *error);

bool termux_renderer_resize(
    TermuxVulkanRenderer *renderer,
    uint32_t width,
    uint32_t height,
    uint32_t text_size,
    const char *font_path,
    std::string *error);

TermuxRendererDrawResult termux_renderer_draw(
    TermuxVulkanRenderer *renderer,
    bool cursor_visible,
    std::string *error);

void termux_renderer_destroy(TermuxVulkanRenderer *renderer);

bool termux_renderer_measure_font(
    uint32_t text_size,
    const char *font_path,
    uint32_t *cell_width,
    uint32_t *cell_height,
    std::string *error);

#endif
