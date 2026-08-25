#include "ghostty_renderer.h"

#include "ghostty_engine.h"

#include <android/font.h>
#include <android/font_matcher.h>
#include <android/log.h>
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_OUTLINE_H
#include <hb-ft.h>
#include <hb.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <climits>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <map>
#include <memory>
#include <string>
#include <tuple>
#include <utility>
#include <vector>

#define LOG_TAG "TermuxVulkan"

constexpr uint32_t KITTY_UNICODE_PLACEHOLDER = 0x10eeee;
constexpr uint64_t FRAME_WAIT_TIMEOUT_NS = 16'000'000;
constexpr size_t TEXT_RUN_CACHE_MAX_ENTRIES = 4096;
constexpr size_t TEXT_RUN_CACHE_MAX_KEY_BYTES = 64;

struct Face {
    FT_Face ft = nullptr;
    hb_font_t *hb = nullptr;

    ~Face() {
        if (hb) hb_font_destroy(hb);
        if (ft) FT_Done_Face(ft);
    }
};

struct TextRunKey {
    std::string text;
    bool bold = false;
    bool italic = false;

    bool operator<(const TextRunKey &other) const {
        return std::tie(text, bold, italic) <
            std::tie(other.text, other.bold, other.italic);
    }
};

struct CachedTextGlyph {
    int x = 0;
    int y = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    bool colored = false;
    std::vector<uint8_t> pixels;
};

struct CachedTextRun {
    std::vector<CachedTextGlyph> glyphs;
};

struct FontSystem {
    FT_Library library = nullptr;
    AFontMatcher *matcher = nullptr;
    std::map<std::string, std::unique_ptr<Face>> faces;
    uint32_t text_size = 16;
    std::string custom_path;
    uint32_t cell_width = 8;
    uint32_t cell_height = 18;
    int ascender = 14;
    std::map<TextRunKey, CachedTextRun> text_runs;

    ~FontSystem() {
        faces.clear();
        if (matcher) AFontMatcher_destroy(matcher);
        if (library) FT_Done_FreeType(library);
    }
};

struct CachedGlyph {
    bool registered = false;
    uint32_t width = 0;
    uint32_t height = 0;
    std::vector<uint8_t> alpha;
};

struct RenderCell {
    uint16_t column = 0;
    uint16_t row = 0;
    uint32_t codepoint = 0;
    GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
    GhosttyStyle style{};
    GhosttyColorRgb foreground{};
    std::string text;
};

struct CursorFrameState {
    bool drawn = false;
    uint16_t x = 0;
    uint16_t y = 0;
    bool wide_tail = false;
    GhosttyRenderStateCursorVisualStyle style =
        GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK;
};

constexpr bool cursor_states_equal(const CursorFrameState &left,
                                   const CursorFrameState &right) {
    if (left.drawn != right.drawn) return false;
    return !left.drawn ||
        (left.x == right.x && left.y == right.y &&
         left.wide_tail == right.wide_tail &&
         left.style == right.style);
}

constexpr bool cursor_row_needs_redraw(const CursorFrameState &previous,
                                       const CursorFrameState &current,
                                       uint16_t row) {
    return (previous.drawn && previous.y == row) ||
        (current.drawn && current.y == row);
}

constexpr uint16_t cursor_start_column(const CursorFrameState &cursor) {
    return cursor.wide_tail && cursor.x > 0 ? cursor.x - 1 : cursor.x;
}

constexpr uint16_t cursor_width_cells(const CursorFrameState &cursor,
                                      GhosttyCellWide wide) {
    return cursor.wide_tail || wide == GHOSTTY_CELL_WIDE_WIDE ? 2 : 1;
}

static_assert(cursor_row_needs_redraw(
    {true, 1, 2, false, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK},
    {true, 8, 2, false, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK}, 2));
static_assert(cursor_row_needs_redraw(
    {true, 1, 2, false, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK},
    {true, 1, 3, false, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK}, 2));
static_assert(cursor_row_needs_redraw(
    {true, 1, 2, false, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK},
    {true, 1, 3, false, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK}, 3));
static_assert(!cursor_row_needs_redraw(
    {true, 1, 2, false, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK},
    {true, 1, 3, false, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK}, 4));
static_assert(cursor_row_needs_redraw(
    {true, 1, 2, false, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK},
    {}, 2));
static_assert(cursor_start_column(
    {true, 3, 2, false, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK}) == 3);
static_assert(cursor_start_column(
    {true, 3, 2, true, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK}) == 2);
static_assert(cursor_width_cells({}, GHOSTTY_CELL_WIDE_NARROW) == 1);
static_assert(cursor_width_cells({}, GHOSTTY_CELL_WIDE_WIDE) == 2);
static_assert(cursor_width_cells(
    {true, 3, 2, true, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK},
    GHOSTTY_CELL_WIDE_SPACER_TAIL) == 2);
static_assert(!cursor_states_equal(
    {true, 3, 2, false, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK},
    {true, 3, 2, true, GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK}));

struct TermuxVulkanRenderer {
    TermuxGhosttyEngine *engine = nullptr;
    ANativeWindow *window = nullptr;

    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queue_family = 0;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkExtent2D extent{};
    std::vector<VkImage> images;
    std::vector<bool> image_initialized;
    VkCommandPool command_pool = VK_NULL_HANDLE;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkSemaphore acquired = VK_NULL_HANDLE;
    VkSemaphore rendered = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory staging_memory = VK_NULL_HANDLE;
    size_t staging_size = 0;

    FontSystem fonts;
    uint64_t glyph_generation = 0;
    uint64_t kitty_generation = 0;
    std::map<uint32_t, CachedGlyph> glyphs;
    std::vector<RenderCell> render_cells;
    std::vector<uint8_t> frame;
    bool frame_initialized = false;
    bool frame_pending_upload = false;
    bool frame_cursor_initialized = false;
    CursorFrameState frame_cursor;
    uint16_t last_cols = 0;
    uint16_t last_rows = 0;
    uint16_t pending_cols = 0;
    uint16_t pending_rows = 0;
    uint32_t requested_width = 0;
    uint32_t requested_height = 0;
};

namespace {

std::string vk_error(const char *operation, VkResult result) {
    return std::string(operation) + " failed with VkResult " +
        std::to_string(static_cast<int>(result));
}

uint32_t find_memory_type(VkPhysicalDevice physical, uint32_t mask,
                          VkMemoryPropertyFlags flags) {
    VkPhysicalDeviceMemoryProperties properties{};
    vkGetPhysicalDeviceMemoryProperties(physical, &properties);
    for (uint32_t i = 0; i < properties.memoryTypeCount; ++i) {
        if ((mask & (1u << i)) != 0 &&
            (properties.memoryTypes[i].propertyFlags & flags) == flags) {
            return i;
        }
    }
    return UINT32_MAX;
}

bool initialize_fonts(FontSystem *fonts, uint32_t text_size,
                      const char *font_path, std::string *error) {
    fonts->faces.clear();
    fonts->text_runs.clear();
    if (fonts->matcher) {
        AFontMatcher_destroy(fonts->matcher);
        fonts->matcher = nullptr;
    }
    if (!fonts->library && FT_Init_FreeType(&fonts->library) != 0) {
        *error = "FT_Init_FreeType failed";
        return false;
    }
    fonts->matcher = AFontMatcher_create();
    if (!fonts->matcher) {
        *error = "AFontMatcher_create failed";
        return false;
    }
    fonts->text_size = std::max<uint32_t>(8, text_size);
    fonts->custom_path = font_path ? font_path : "";

    std::string key = fonts->custom_path.empty() ?
        std::string("/system/fonts/RobotoMono-Regular.ttf#0") :
        fonts->custom_path + "#0";
    auto face = std::make_unique<Face>();
    if (FT_New_Face(fonts->library,
                    key.substr(0, key.rfind('#')).c_str(), 0,
                    &face->ft) != 0) {
        uint16_t sample = 'M';
        uint32_t run = 0;
        AFont *font = AFontMatcher_match(fonts->matcher, "monospace", &sample,
                                         1, &run);
        if (!font) {
            *error = "No Android monospace font is available";
            return false;
        }
        std::string path = AFont_getFontFilePath(font);
        size_t index = AFont_getCollectionIndex(font);
        key = path + "#" + std::to_string(index);
        int result = FT_New_Face(fonts->library, path.c_str(),
                                 static_cast<FT_Long>(index), &face->ft);
        AFont_close(font);
        if (result != 0) {
            *error = "FreeType could not open the Android monospace font";
            return false;
        }
    }
    FT_Set_Pixel_Sizes(face->ft, 0, fonts->text_size);
    face->hb = hb_ft_font_create_referenced(face->ft);
    if (!face->hb) {
        *error = "HarfBuzz could not create the primary font";
        return false;
    }

    FT_UInt glyph_index = FT_Get_Char_Index(face->ft, 'M');
    if (FT_Load_Glyph(face->ft, glyph_index, FT_LOAD_DEFAULT) != 0) {
        *error = "FreeType could not measure the primary font";
        return false;
    }
    fonts->cell_width = std::max<uint32_t>(
        1, static_cast<uint32_t>((face->ft->glyph->advance.x + 63) / 64));
    fonts->cell_height = std::max<uint32_t>(
        fonts->text_size,
        static_cast<uint32_t>((face->ft->size->metrics.height + 63) / 64));
    fonts->ascender =
        static_cast<int>((face->ft->size->metrics.ascender + 63) / 64);
    fonts->faces.emplace(key, std::move(face));
    return true;
}

std::vector<uint16_t> utf8_to_utf16(const uint8_t *bytes, size_t length) {
    std::vector<uint16_t> result;
    for (size_t i = 0; i < length;) {
        uint32_t cp = 0xfffd;
        uint8_t first = bytes[i++];
        if (first < 0x80) {
            cp = first;
        } else if ((first & 0xe0) == 0xc0 && i < length) {
            cp = ((first & 0x1f) << 6) | (bytes[i++] & 0x3f);
        } else if ((first & 0xf0) == 0xe0 && i + 1 < length) {
            uint8_t second = bytes[i++];
            uint8_t third = bytes[i++];
            cp = ((first & 0x0f) << 12) |
                ((second & 0x3f) << 6) | (third & 0x3f);
        } else if ((first & 0xf8) == 0xf0 && i + 2 < length) {
            uint8_t second = bytes[i++];
            uint8_t third = bytes[i++];
            uint8_t fourth = bytes[i++];
            cp = ((first & 0x07) << 18) |
                ((second & 0x3f) << 12) |
                ((third & 0x3f) << 6) | (fourth & 0x3f);
        }
        if (cp <= 0xffff) {
            result.push_back(static_cast<uint16_t>(cp));
        } else {
            cp -= 0x10000;
            result.push_back(static_cast<uint16_t>(0xd800 + (cp >> 10)));
            result.push_back(static_cast<uint16_t>(0xdc00 + (cp & 0x3ff)));
        }
    }
    return result;
}

Face *face_for_text(FontSystem *fonts, const uint8_t *bytes, size_t length,
                    bool bold, bool italic) {
    if (!fonts->custom_path.empty()) return fonts->faces.begin()->second.get();

    std::vector<uint16_t> utf16 = utf8_to_utf16(bytes, length);
    if (utf16.empty()) return fonts->faces.begin()->second.get();
    AFontMatcher_setStyle(fonts->matcher,
                          bold ? AFONT_WEIGHT_BOLD : AFONT_WEIGHT_NORMAL,
                          italic);
    uint32_t run = 0;
    AFont *font = AFontMatcher_match(fonts->matcher, "monospace", utf16.data(),
                                     utf16.size(), &run);
    if (!font) return fonts->faces.begin()->second.get();
    std::string path = AFont_getFontFilePath(font);
    size_t index = AFont_getCollectionIndex(font);
    std::string key = path + "#" + std::to_string(index) +
        (bold ? "b" : "n") + (italic ? "i" : "n");
    auto existing = fonts->faces.find(key);
    if (existing != fonts->faces.end()) {
        AFont_close(font);
        return existing->second.get();
    }

    auto face = std::make_unique<Face>();
    if (FT_New_Face(fonts->library, path.c_str(),
                    static_cast<FT_Long>(index), &face->ft) != 0) {
        AFont_close(font);
        return fonts->faces.begin()->second.get();
    }
    AFont_close(font);
    FT_Set_Pixel_Sizes(face->ft, 0, fonts->text_size);
    face->hb = hb_ft_font_create_referenced(face->ft);
    if (!face->hb) return fonts->faces.begin()->second.get();
    Face *result = face.get();
    fonts->faces.emplace(key, std::move(face));
    return result;
}

void put_pixel(std::vector<uint8_t> *frame, uint32_t width, uint32_t height,
               int x, int y, GhosttyColorRgb color, uint8_t alpha = 255) {
    if (x < 0 || y < 0 || x >= static_cast<int>(width) ||
        y >= static_cast<int>(height)) return;
    size_t index = (static_cast<size_t>(y) * width + x) * 4;
    uint32_t inv = 255 - alpha;
    (*frame)[index] = static_cast<uint8_t>(
        (color.r * alpha + (*frame)[index] * inv) / 255);
    (*frame)[index + 1] = static_cast<uint8_t>(
        (color.g * alpha + (*frame)[index + 1] * inv) / 255);
    (*frame)[index + 2] = static_cast<uint8_t>(
        (color.b * alpha + (*frame)[index + 2] * inv) / 255);
    (*frame)[index + 3] = 255;
}

void fill_rect(std::vector<uint8_t> *frame, uint32_t width, uint32_t height,
               int left, int top, int right, int bottom,
               GhosttyColorRgb color) {
    left = std::max(0, left);
    top = std::max(0, top);
    right = std::min(static_cast<int>(width), right);
    bottom = std::min(static_cast<int>(height), bottom);
    if (left >= right || top >= bottom) return;

    uint8_t *first_row = frame->data() +
        (static_cast<size_t>(top) * width + left) * 4;
    uint8_t *pixel = first_row;
    for (int x = left; x < right; ++x, pixel += 4) {
        pixel[0] = color.r;
        pixel[1] = color.g;
        pixel[2] = color.b;
        pixel[3] = 255;
    }
    size_t row_bytes = static_cast<size_t>(right - left) * 4;
    for (int y = top + 1; y < bottom; ++y) {
        uint8_t *row = frame->data() +
            (static_cast<size_t>(y) * width + left) * 4;
        memcpy(row, first_row, row_bytes);
    }
}

CachedTextRun load_text_run(FontSystem *fonts, const uint8_t *bytes,
                            size_t length, const GhosttyStyle &style) {
    CachedTextRun result;
    Face *face = face_for_text(fonts, bytes, length, style.bold, style.italic);
    hb_buffer_t *buffer = hb_buffer_create();
    hb_buffer_add_utf8(buffer, reinterpret_cast<const char *>(bytes), length,
                       0, length);
    hb_buffer_guess_segment_properties(buffer);
    hb_shape(face->hb, buffer, nullptr, 0);
    unsigned int count = 0;
    hb_glyph_info_t *infos = hb_buffer_get_glyph_infos(buffer, &count);
    hb_glyph_position_t *positions =
        hb_buffer_get_glyph_positions(buffer, &count);
    int pen_x = 0;
    int pen_y = fonts->ascender;
    for (unsigned int i = 0; i < count; ++i) {
        if (FT_Load_Glyph(face->ft, infos[i].codepoint,
                          FT_LOAD_DEFAULT | FT_LOAD_COLOR) != 0) continue;
        if (FT_Render_Glyph(face->ft->glyph, FT_RENDER_MODE_NORMAL) != 0)
            continue;
        CachedTextGlyph glyph;
        glyph.x = pen_x + positions[i].x_offset / 64 +
            face->ft->glyph->bitmap_left;
        glyph.y = pen_y - positions[i].y_offset / 64 -
            face->ft->glyph->bitmap_top;
        const FT_Bitmap &bitmap = face->ft->glyph->bitmap;
        glyph.width = bitmap.width;
        glyph.height = bitmap.rows;
        glyph.colored = bitmap.pixel_mode == FT_PIXEL_MODE_BGRA;
        size_t bytes_per_pixel = glyph.colored ? 4 : 1;
        glyph.pixels.resize(
            static_cast<size_t>(glyph.width) * glyph.height *
            bytes_per_pixel);
        for (uint32_t row = 0; row < glyph.height; ++row) {
            const uint8_t *source =
                bitmap.buffer + row * std::abs(bitmap.pitch);
            uint8_t *destination = glyph.pixels.data() +
                static_cast<size_t>(row) * glyph.width * bytes_per_pixel;
            memcpy(destination, source, glyph.width * bytes_per_pixel);
        }
        result.glyphs.emplace_back(std::move(glyph));
        pen_x += positions[i].x_advance / 64;
        pen_y -= positions[i].y_advance / 64;
    }
    hb_buffer_destroy(buffer);
    return result;
}

void draw_text_run(const CachedTextRun &run, std::vector<uint8_t> *frame,
                   uint32_t width, uint32_t height, int cell_x, int cell_y,
                   GhosttyColorRgb color) {
    for (const auto &glyph : run.glyphs) {
        int x = cell_x + glyph.x;
        int y = cell_y + glyph.y;
        for (uint32_t row = 0; row < glyph.height; ++row) {
            for (uint32_t column = 0; column < glyph.width; ++column) {
                size_t index =
                    (static_cast<size_t>(row) * glyph.width + column) *
                    (glyph.colored ? 4 : 1);
                if (glyph.colored) {
                    GhosttyColorRgb pixel{glyph.pixels[index + 2],
                                          glyph.pixels[index + 1],
                                          glyph.pixels[index]};
                    put_pixel(frame, width, height, x + column, y + row,
                              pixel, glyph.pixels[index + 3]);
                } else {
                    put_pixel(frame, width, height, x + column, y + row,
                              color, glyph.pixels[index]);
                }
            }
        }
    }
}

void draw_text(FontSystem *fonts, std::vector<uint8_t> *frame,
               uint32_t width, uint32_t height, const uint8_t *bytes,
               size_t length, int cell_x, int cell_y,
               GhosttyColorRgb color, const GhosttyStyle &style) {
    if (length > TEXT_RUN_CACHE_MAX_KEY_BYTES) {
        CachedTextRun run = load_text_run(fonts, bytes, length, style);
        draw_text_run(run, frame, width, height,
                      cell_x * static_cast<int>(fonts->cell_width), cell_y,
                      color);
        return;
    }

    TextRunKey key{
        std::string(reinterpret_cast<const char *>(bytes), length),
        style.bold,
        style.italic,
    };
    auto found = fonts->text_runs.find(key);
    if (found == fonts->text_runs.end()) {
        if (fonts->text_runs.size() >= TEXT_RUN_CACHE_MAX_ENTRIES)
            fonts->text_runs.clear();
        found = fonts->text_runs.emplace(
            std::move(key), load_text_run(fonts, bytes, length, style)).first;
    }
    draw_text_run(found->second, frame, width, height,
                  cell_x * static_cast<int>(fonts->cell_width), cell_y,
                  color);
}

struct GlyphSize {
    double width;
    double height;
    double x;
    double y;
};

double glyph_scale_factor(GhosttyGlyphConstraintSize size,
                          double group_width, double group_height,
                          double face_width, double face_height,
                          uint32_t constraint_width,
                          double pad_left, double pad_right,
                          double pad_bottom, double pad_top) {
    double target_width =
        (constraint_width - (pad_left + pad_right)) * face_width;
    double target_height = (1.0 - (pad_bottom + pad_top)) * face_height;
    double width_factor = target_width / group_width;
    double height_factor = target_height / group_height;
    switch (size) {
        case GHOSTTY_GLYPH_CONSTRAINT_SIZE_FIT:
            return std::min({1.0, width_factor, height_factor});
        case GHOSTTY_GLYPH_CONSTRAINT_SIZE_COVER:
            return std::min(width_factor, height_factor);
        case GHOSTTY_GLYPH_CONSTRAINT_SIZE_FIT_COVER_ONE: {
            double factor = std::min(width_factor, height_factor);
            if (constraint_width > 1 && factor > 1.0) {
                double single_width =
                    (1.0 - (pad_left + pad_right)) * face_width;
                factor = std::max(
                    1.0, std::min(single_width / group_width,
                                  target_height / group_height));
            }
            return factor;
        }
        default:
            return 1.0;
    }
}

double align_glyph_axis(GhosttyGlyphConstraintAlign align, double current,
                        double start, double end, bool center_first,
                        double first_end) {
    double center = (start + end) / 2.0;
    switch (align) {
        case GHOSTTY_GLYPH_CONSTRAINT_ALIGN_START:
            return start;
        case GHOSTTY_GLYPH_CONSTRAINT_ALIGN_END:
            return std::max(start, end);
        case GHOSTTY_GLYPH_CONSTRAINT_ALIGN_CENTER:
            return std::max(start, center);
        case GHOSTTY_GLYPH_CONSTRAINT_ALIGN_CENTER_ONE:
            return center_first
                ? std::max(start, (start + first_end) / 2.0)
                : center;
        default:
            return end < start ? center :
                std::max(start, std::min(current, end));
    }
}

GlyphSize constrain_glyph(const GhosttyGlyphInfo &info, GlyphSize glyph,
                          double group_width, double group_height,
                          double face_width, double face_height,
                          uint32_t constraint_width) {
    bool constrained =
        info.constraint_size != GHOSTTY_GLYPH_CONSTRAINT_SIZE_NONE ||
        info.align_horizontal != GHOSTTY_GLYPH_CONSTRAINT_ALIGN_NONE ||
        info.align_vertical != GHOSTTY_GLYPH_CONSTRAINT_ALIGN_NONE;
    if (!constrained) return glyph;

    double relative_x = glyph.x / group_width;
    double relative_y = glyph.y / group_height;
    GlyphSize group{
        group_width,
        group_height,
        glyph.x - group_width * relative_x,
        glyph.y - group_height * relative_y,
    };

    uint32_t available_width = std::min(2u, constraint_width);
    double pad_top = info.pad_top;
    double pad_right = info.pad_right;
    double pad_bottom = info.pad_bottom;
    double pad_left = info.pad_left;
    if (info.constraint_size == GHOSTTY_GLYPH_CONSTRAINT_SIZE_STRETCH) {
        pad_top = std::max(0.0, pad_top);
        pad_right = std::max(0.0, pad_right);
        pad_bottom = std::max(0.0, pad_bottom);
        pad_left = std::max(0.0, pad_left);
        if (face_width > 0.9 * face_height) available_width = 1;
    }

    double width_factor;
    double height_factor;
    if (info.constraint_size == GHOSTTY_GLYPH_CONSTRAINT_SIZE_STRETCH) {
        width_factor =
            (available_width - (pad_left + pad_right)) *
            face_width / group.width;
        height_factor =
            (1.0 - (pad_bottom + pad_top)) *
            face_height / group.height;
    } else {
        double factor = glyph_scale_factor(
            info.constraint_size, group.width, group.height,
            face_width, face_height, available_width, pad_left, pad_right,
            pad_bottom, pad_top);
        width_factor = factor;
        height_factor = factor;
    }

    double center_x = group.x + group.width / 2.0;
    double center_y = group.y + group.height / 2.0;
    group.width *= width_factor;
    group.height *= height_factor;
    group.x = center_x - group.width / 2.0;
    group.y = center_y - group.height / 2.0;

    double start_x = pad_left * face_width;
    double end_x =
        face_width + (available_width - 1) * face_width -
        group.width - pad_right * face_width;
    double first_end_x = face_width - group.width - pad_right * face_width;
    group.x = align_glyph_axis(
        info.align_horizontal, group.x, start_x, end_x, true,
        first_end_x);

    double start_y = pad_bottom * face_height;
    double end_y = face_height - group.height - pad_top * face_height;
    group.y = align_glyph_axis(
        info.align_vertical, group.y, start_y, end_y, false,
        end_y);

    return {
        width_factor * glyph.width,
        height_factor * glyph.height,
        group.x + group.width * relative_x,
        group.y + group.height * relative_y,
    };
}

struct GlyphRasterTarget {
    uint32_t width;
    uint32_t height;
    std::vector<uint8_t> *alpha;
};

void glyph_span(int y, int count, const FT_Span *spans, void *user) {
    auto *target = static_cast<GlyphRasterTarget *>(user);
    int row = static_cast<int>(target->height) - y - 1;
    if (row < 0 || row >= static_cast<int>(target->height)) return;
    for (int i = 0; i < count; ++i) {
        int start = std::max(0, static_cast<int>(spans[i].x));
        int end = std::min(
            static_cast<int>(target->width),
            static_cast<int>(spans[i].x) + spans[i].len);
        for (int x = start; x < end; ++x) {
            size_t index = static_cast<size_t>(row) * target->width + x;
            (*target->alpha)[index] =
                std::max((*target->alpha)[index], spans[i].coverage);
        }
    }
}

CachedGlyph load_glyph(TermuxVulkanRenderer *renderer, uint32_t codepoint) {
    CachedGlyph result;
    GhosttyGlyphInfo info{};
    info.size = sizeof(info);
    if (ghostty_glyph_info(renderer->engine->terminal, codepoint, &info) !=
        GHOSTTY_SUCCESS) return result;
    result.registered = true;
    result.width = renderer->fonts.cell_width *
        std::clamp<uint32_t>(info.cell_width, 1, 2);
    result.height = renderer->fonts.cell_height;
    result.alpha.assign(
        static_cast<size_t>(result.width) * result.height, 0);
    if (info.units_per_em == 0 || info.advance_width == 0 ||
        info.line_height == 0 || info.contour_count == 0 ||
        info.point_count == 0 || info.contour_count > SHRT_MAX ||
        info.point_count > SHRT_MAX) return result;

    std::vector<uint16_t> contour_indices(info.contour_count);
    std::vector<GhosttyGlyphPoint> source_points(info.point_count);
    size_t contour_count = 0;
    size_t point_count = 0;
    if (ghostty_glyph_contours(
            renderer->engine->terminal, codepoint, contour_indices.data(),
            contour_indices.size(), &contour_count) != GHOSTTY_SUCCESS ||
        ghostty_glyph_points(
            renderer->engine->terminal, codepoint, source_points.data(),
            source_points.size(), &point_count) != GHOSTTY_SUCCESS ||
        contour_count != contour_indices.size() ||
        point_count != source_points.size()) return result;

    uint16_t previous = 0;
    for (size_t i = 0; i < contour_indices.size(); ++i) {
        if (contour_indices[i] >= source_points.size() ||
            (i > 0 && contour_indices[i] <= previous)) return result;
        previous = contour_indices[i];
    }
    if (contour_indices.back() + 1 != source_points.size()) return result;

    int32_t min_x = source_points[0].x;
    int32_t max_x = source_points[0].x;
    int32_t min_y = source_points[0].y;
    int32_t max_y = source_points[0].y;
    for (const auto &point : source_points) {
        min_x = std::min(min_x, point.x);
        max_x = std::max(max_x, point.x);
        min_y = std::min(min_y, point.y);
        max_y = std::max(max_y, point.y);
    }
    if (min_x == max_x || min_y == max_y) return result;

    double scale = static_cast<double>(renderer->fonts.cell_height) /
        info.units_per_em;
    GlyphSize placement{
        (max_x - static_cast<double>(min_x)) * scale,
        (max_y - static_cast<double>(min_y)) * scale,
        min_x * scale,
        min_y * scale,
    };
    placement = constrain_glyph(
        info, placement, info.advance_width * scale,
        info.line_height * scale, renderer->fonts.cell_width,
        renderer->fonts.cell_height,
        std::clamp<uint32_t>(info.cell_width, 1, 2));
    double scale_x = placement.width / (max_x - static_cast<double>(min_x));
    double scale_y = placement.height / (max_y - static_cast<double>(min_y));

    std::vector<FT_Vector> points(source_points.size());
    std::vector<char> tags(source_points.size());
    std::vector<short> contours(contour_indices.size());
    for (size_t i = 0; i < source_points.size(); ++i) {
        double x = placement.x + (source_points[i].x - min_x) * scale_x;
        double y = placement.y + (source_points[i].y - min_y) * scale_y;
        points[i].x = static_cast<FT_Pos>(std::llround(x * 64.0));
        points[i].y = static_cast<FT_Pos>(std::llround(y * 64.0));
        tags[i] = source_points[i].on_curve
            ? FT_CURVE_TAG_ON : FT_CURVE_TAG_CONIC;
    }
    for (size_t i = 0; i < contour_indices.size(); ++i)
        contours[i] = static_cast<short>(contour_indices[i]);

    FT_Outline outline{};
    outline.n_contours = static_cast<short>(contours.size());
    outline.n_points = static_cast<short>(points.size());
    outline.points = points.data();
    outline.tags = tags.data();
    outline.contours = contours.data();
    GlyphRasterTarget target{result.width, result.height, &result.alpha};
    FT_Raster_Params params{};
    params.flags =
        FT_RASTER_FLAG_AA | FT_RASTER_FLAG_DIRECT | FT_RASTER_FLAG_CLIP;
    params.gray_spans = glyph_span;
    params.user = &target;
    params.clip_box = {
        0, 0, static_cast<FT_Pos>(result.width),
        static_cast<FT_Pos>(result.height),
    };
    if (FT_Outline_Render(renderer->fonts.library, &outline, &params) != 0)
        std::fill(result.alpha.begin(), result.alpha.end(), 0);
    return result;
}

const CachedGlyph *glyph_for_codepoint(TermuxVulkanRenderer *renderer,
                                       uint32_t codepoint) {
    auto found = renderer->glyphs.find(codepoint);
    if (found == renderer->glyphs.end())
        found = renderer->glyphs.emplace(
            codepoint, load_glyph(renderer, codepoint)).first;
    return found->second.registered ? &found->second : nullptr;
}

void draw_glyph(std::vector<uint8_t> *frame, uint32_t frame_width,
                uint32_t frame_height, const CachedGlyph &glyph,
                int x, int y, GhosttyColorRgb color) {
    for (uint32_t row = 0; row < glyph.height; ++row) {
        for (uint32_t column = 0; column < glyph.width; ++column) {
            uint8_t alpha =
                glyph.alpha[static_cast<size_t>(row) * glyph.width + column];
            if (alpha != 0)
                put_pixel(frame, frame_width, frame_height, x + column,
                          y + row, color, alpha);
        }
    }
}

void draw_cell_content(TermuxVulkanRenderer *renderer, const RenderCell &cell,
                       bool glyphs_available, GhosttyColorRgb color) {
    int left = cell.column * renderer->fonts.cell_width;
    int top = cell.row * renderer->fonts.cell_height;
    int right = (cell.column + 1) * renderer->fonts.cell_width;
    int bottom = (cell.row + 1) * renderer->fonts.cell_height;
    if (!cell.style.invisible && !cell.text.empty() &&
        cell.codepoint != KITTY_UNICODE_PLACEHOLDER) {
        const CachedGlyph *glyph = glyphs_available
            ? glyph_for_codepoint(renderer, cell.codepoint)
            : nullptr;
        if (glyph) {
            draw_glyph(&renderer->frame, renderer->extent.width,
                       renderer->extent.height, *glyph, left, top, color);
        } else {
            draw_text(&renderer->fonts, &renderer->frame,
                      renderer->extent.width, renderer->extent.height,
                      reinterpret_cast<const uint8_t *>(cell.text.data()),
                      cell.text.size(), cell.column, top, color, cell.style);
        }
    }
    if (cell.style.underline != 0) {
        fill_rect(&renderer->frame, renderer->extent.width,
                  renderer->extent.height, left, bottom - 2, right,
                  bottom - 1, color);
    }
    if (cell.style.strikethrough) {
        int y = top + renderer->fonts.cell_height / 2;
        fill_rect(&renderer->frame, renderer->extent.width,
                  renderer->extent.height, left, y, right, y + 1, color);
    }
    if (cell.style.overline) {
        fill_rect(&renderer->frame, renderer->extent.width,
                  renderer->extent.height, left, top, right, top + 1, color);
    }
}

const RenderCell *find_render_cell(const TermuxVulkanRenderer *renderer,
                                   uint16_t row, uint16_t column) {
    for (const auto &cell : renderer->render_cells) {
        if (cell.row == row && cell.column == column) return &cell;
    }
    return nullptr;
}

GhosttyColorRgb dim_color(GhosttyColorRgb color) {
    return {
        static_cast<uint8_t>(color.r * 2 / 3),
        static_cast<uint8_t>(color.g * 2 / 3),
        static_cast<uint8_t>(color.b * 2 / 3),
    };
}

void draw_kitty_image(TermuxVulkanRenderer *renderer,
                      GhosttyKittyGraphics graphics, uint32_t image_id,
                      int64_t destination_x, int64_t destination_y,
                      uint32_t pixel_width, uint32_t pixel_height,
                      uint32_t source_x, uint32_t source_y,
                      uint32_t source_width, uint32_t source_height) {
    GhosttyKittyGraphicsImage image =
        ghostty_kitty_graphics_image(graphics, image_id);
    if (!image || pixel_width == 0 || pixel_height == 0 ||
        source_width == 0 || source_height == 0) return;

    const uint8_t *pixels = nullptr;
    size_t length = 0;
    uint32_t image_width = 0;
    uint32_t image_height = 0;
    GhosttyKittyImageFormat format = GHOSTTY_KITTY_IMAGE_FORMAT_RGBA;
    ghostty_kitty_graphics_image_get(
        image, GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR, &pixels);
    ghostty_kitty_graphics_image_get(
        image, GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN, &length);
    ghostty_kitty_graphics_image_get(
        image, GHOSTTY_KITTY_IMAGE_DATA_WIDTH, &image_width);
    ghostty_kitty_graphics_image_get(
        image, GHOSTTY_KITTY_IMAGE_DATA_HEIGHT, &image_height);
    ghostty_kitty_graphics_image_get(
        image, GHOSTTY_KITTY_IMAGE_DATA_FORMAT, &format);
    if (!pixels || length == 0) return;

    uint32_t bytes_per_pixel =
        format == GHOSTTY_KITTY_IMAGE_FORMAT_RGB ? 3 :
        format == GHOSTTY_KITTY_IMAGE_FORMAT_RGBA ? 4 :
        format == GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA ? 2 : 1;
    const int64_t draw_left = std::max<int64_t>(0, destination_x);
    const int64_t draw_top = std::max<int64_t>(0, destination_y);
    const int64_t draw_right = std::min<int64_t>(
        renderer->extent.width, destination_x + pixel_width);
    const int64_t draw_bottom = std::min<int64_t>(
        renderer->extent.height, destination_y + pixel_height);
    if (draw_left >= draw_right || draw_top >= draw_bottom) return;

    for (int64_t destination_row = draw_top;
         destination_row < draw_bottom; ++destination_row) {
        const uint64_t y = static_cast<uint64_t>(
            destination_row - destination_y);
        const uint64_t sy = static_cast<uint64_t>(source_y) +
            y * source_height / pixel_height;
        if (sy >= image_height) continue;
        for (int64_t destination_column = draw_left;
             destination_column < draw_right; ++destination_column) {
            const uint64_t x = static_cast<uint64_t>(
                destination_column - destination_x);
            const uint64_t sx = static_cast<uint64_t>(source_x) +
                x * source_width / pixel_width;
            if (sx >= image_width) continue;
            const uint64_t pixel_index = sy * image_width + sx;
            if (length < bytes_per_pixel ||
                pixel_index > (length - bytes_per_pixel) / bytes_per_pixel)
                continue;
            const size_t offset = static_cast<size_t>(pixel_index) *
                bytes_per_pixel;
            if (offset + bytes_per_pixel > length) continue;
            GhosttyColorRgb color{};
            uint8_t alpha = 255;
            switch (format) {
                case GHOSTTY_KITTY_IMAGE_FORMAT_RGB:
                    color = {pixels[offset], pixels[offset + 1],
                             pixels[offset + 2]};
                    break;
                case GHOSTTY_KITTY_IMAGE_FORMAT_RGBA:
                    color = {pixels[offset], pixels[offset + 1],
                             pixels[offset + 2]};
                    alpha = pixels[offset + 3];
                    break;
                case GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA:
                    color = {pixels[offset], pixels[offset], pixels[offset]};
                    alpha = pixels[offset + 1];
                    break;
                case GHOSTTY_KITTY_IMAGE_FORMAT_GRAY:
                    color = {pixels[offset], pixels[offset], pixels[offset]};
                    break;
                default:
                    continue;
            }
            put_pixel(&renderer->frame, renderer->extent.width,
                      renderer->extent.height,
                      static_cast<int>(destination_column),
                      static_cast<int>(destination_row), color, alpha);
        }
    }
}

constexpr int64_t kitty_destination_coordinate(int32_t cell,
                                               uint32_t cell_size,
                                               uint32_t pixel_offset) {
    return static_cast<int64_t>(cell) * cell_size + pixel_offset;
}

static_assert(kitty_destination_coordinate(INT32_MIN, UINT32_MAX, 0) < 0);
static_assert(kitty_destination_coordinate(
    INT32_MAX, UINT32_MAX, UINT32_MAX) > INT32_MAX);

struct KittyDrawPlacement {
    uint32_t image_id;
    int64_t destination_x;
    int64_t destination_y;
    uint32_t pixel_width;
    uint32_t pixel_height;
    uint32_t source_x;
    uint32_t source_y;
    uint32_t source_width;
    uint32_t source_height;
    int32_t z;
};

bool kitty_placement_in_layer(int32_t z, GhosttyKittyPlacementLayer layer) {
    constexpr int32_t kBackgroundLimit = INT32_MIN / 2;
    switch (layer) {
        case GHOSTTY_KITTY_PLACEMENT_LAYER_BELOW_BG:
            return z < kBackgroundLimit;
        case GHOSTTY_KITTY_PLACEMENT_LAYER_BELOW_TEXT:
            return z >= kBackgroundLimit && z < 0;
        case GHOSTTY_KITTY_PLACEMENT_LAYER_ABOVE_TEXT:
            return z >= 0;
        case GHOSTTY_KITTY_PLACEMENT_LAYER_ALL:
            return true;
        default:
            return false;
    }
}

void draw_kitty_layer(TermuxVulkanRenderer *renderer,
                      GhosttyKittyPlacementLayer layer) {
    GhosttyKittyGraphics graphics = nullptr;
    if (ghostty_terminal_get(renderer->engine->terminal,
                             GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,
                             &graphics) != GHOSTTY_SUCCESS || !graphics) return;
    GhosttyKittyGraphicsPlacementIterator iterator = nullptr;
    if (ghostty_kitty_graphics_placement_iterator_new(nullptr, &iterator) !=
        GHOSTTY_SUCCESS) return;
    std::vector<KittyDrawPlacement> placements;
    ghostty_kitty_graphics_placement_iterator_set(
        iterator, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_ITERATOR_OPTION_LAYER,
        &layer);
    if (ghostty_kitty_graphics_get(
            graphics, GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR,
            &iterator) != GHOSTTY_SUCCESS) {
        ghostty_kitty_graphics_placement_iterator_free(iterator);
        return;
    }

    while (ghostty_kitty_graphics_placement_next(iterator)) {
        uint32_t image_id = 0;
        bool is_virtual = false;
        uint32_t offset_x = 0;
        uint32_t offset_y = 0;
        int32_t z = 0;
        ghostty_kitty_graphics_placement_get(
            iterator, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID,
            &image_id);
        ghostty_kitty_graphics_placement_get(
            iterator, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IS_VIRTUAL,
            &is_virtual);
        if (is_virtual) continue;
        GhosttyKittyGraphicsImage image =
            ghostty_kitty_graphics_image(graphics, image_id);
        if (!image) continue;
        ghostty_kitty_graphics_image_get(
            image, GHOSTTY_KITTY_IMAGE_DATA_ID, &image_id);
        ghostty_kitty_graphics_placement_get(
            iterator, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_X_OFFSET,
            &offset_x);
        ghostty_kitty_graphics_placement_get(
            iterator, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Y_OFFSET,
            &offset_y);
        ghostty_kitty_graphics_placement_get(
            iterator, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Z, &z);
        GhosttyKittyGraphicsPlacementRenderInfo info{};
        info.size = sizeof(info);
        if (ghostty_kitty_graphics_placement_render_info(
                iterator, image, renderer->engine->terminal, &info) !=
                GHOSTTY_SUCCESS || !info.viewport_visible ||
            info.pixel_width == 0 || info.pixel_height == 0) continue;

        int64_t destination_x = kitty_destination_coordinate(
            info.viewport_col, renderer->fonts.cell_width, offset_x);
        int64_t destination_y = kitty_destination_coordinate(
            info.viewport_row, renderer->fonts.cell_height, offset_y);
        placements.push_back({
            image_id, destination_x, destination_y, info.pixel_width,
            info.pixel_height, info.source_x, info.source_y,
            info.source_width, info.source_height, z,
        });
    }
    ghostty_kitty_graphics_placement_iterator_free(iterator);

    size_t count = 0;
    GhosttyResult result = ghostty_kitty_graphics_virtual_placements(
        renderer->engine->terminal, nullptr, 0, &count);
    if (count > 0 &&
        (result == GHOSTTY_SUCCESS || result == GHOSTTY_OUT_OF_SPACE)) {
        std::vector<GhosttyKittyGraphicsVirtualPlacementRenderInfo>
            virtuals(count);
        for (auto &placement : virtuals)
            placement.size = sizeof(placement);
        if (ghostty_kitty_graphics_virtual_placements(
                renderer->engine->terminal, virtuals.data(),
                virtuals.size(), &count) == GHOSTTY_SUCCESS) {
            for (const auto &placement : virtuals) {
                if (!kitty_placement_in_layer(placement.z, layer)) continue;
                placements.push_back({
                    placement.image_id,
                    kitty_destination_coordinate(
                        placement.viewport_col, renderer->fonts.cell_width,
                        placement.offset_x),
                    kitty_destination_coordinate(
                        placement.viewport_row, renderer->fonts.cell_height,
                        placement.offset_y),
                    placement.pixel_width,
                    placement.pixel_height,
                    placement.source_x,
                    placement.source_y,
                    placement.source_width,
                    placement.source_height,
                    placement.z,
                });
            }
        }
    }

    std::stable_sort(
        placements.begin(), placements.end(),
        [](const KittyDrawPlacement &left,
           const KittyDrawPlacement &right) {
            return left.z < right.z ||
                (left.z == right.z && left.image_id < right.image_id);
        });
    for (const auto &placement : placements) {
        draw_kitty_image(
            renderer, graphics, placement.image_id, placement.destination_x,
            placement.destination_y, placement.pixel_width,
            placement.pixel_height,
            placement.source_x, placement.source_y, placement.source_width,
            placement.source_height);
    }
}

bool get_kitty_graphics_generation(TermuxVulkanRenderer *renderer,
                                  uint64_t *generation) {
    *generation = 0;
    GhosttyKittyGraphics graphics = nullptr;
    if (ghostty_terminal_get(renderer->engine->terminal,
                             GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS,
                             &graphics) != GHOSTTY_SUCCESS || !graphics) {
        return false;
    }
    return ghostty_kitty_graphics_get(
               graphics, GHOSTTY_KITTY_GRAPHICS_DATA_GENERATION,
               generation) == GHOSTTY_SUCCESS;
}

void clear_render_dirty(TermuxGhosttyEngine *engine) {
    if (ghostty_render_state_get(
            engine->render_state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
            &engine->row_iterator) == GHOSTTY_SUCCESS) {
        bool clean = false;
        while (ghostty_render_state_row_iterator_next(engine->row_iterator)) {
            ghostty_render_state_row_set(
                engine->row_iterator, GHOSTTY_RENDER_STATE_ROW_OPTION_DIRTY,
                &clean);
        }
    }
    GhosttyRenderStateDirty clean = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
    ghostty_render_state_set(engine->render_state,
                            GHOSTTY_RENDER_STATE_OPTION_DIRTY, &clean);
}

bool compose_frame(TermuxVulkanRenderer *renderer, bool cursor_visible,
                   bool *frame_changed, std::string *error) {
    TermuxGhosttyEngine *engine = renderer->engine;
    if (ghostty_render_state_update(engine->render_state, engine->terminal) !=
        GHOSTTY_SUCCESS) {
        *error = "ghostty_render_state_update failed";
        return false;
    }
    uint16_t cols = 0, rows = 0;
    GhosttyRenderStateColors colors{};
    colors.size = sizeof(colors);
    ghostty_render_state_get(engine->render_state,
                             GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
    ghostty_render_state_get(engine->render_state,
                             GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
    GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FULL;
    ghostty_render_state_get(engine->render_state,
                             GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
    uint64_t glyph_generation = 0;
    bool glyphs_available =
        ghostty_glyph_generation(engine->terminal, &glyph_generation) ==
        GHOSTTY_SUCCESS;
    bool glyphs_changed = glyphs_available &&
        glyph_generation != renderer->glyph_generation;
    if (glyphs_changed) {
        renderer->glyphs.clear();
        renderer->glyph_generation = glyph_generation;
    }
    size_t frame_size = static_cast<size_t>(renderer->extent.width) *
        renderer->extent.height * 4;
    bool dimensions_changed = renderer->frame.size() != frame_size ||
        cols != renderer->last_cols || rows != renderer->last_rows;
    uint64_t kitty_generation = 0;
    bool kitty_generation_available = get_kitty_graphics_generation(
        renderer, &kitty_generation);
    bool kitty_changed = kitty_generation_available &&
        kitty_generation != renderer->kitty_generation;

    bool terminal_cursor_visible = false;
    bool cursor_has_position = false;
    CursorFrameState current_cursor;
    // Ghostty tracks cursor state independently from row dirtiness. Since the
    // framebuffer includes cursor pixels, compare it explicitly so moving the
    // cursor also restores the row containing its previous position.
    ghostty_render_state_get(engine->render_state,
                             GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE,
                             &terminal_cursor_visible);
    ghostty_render_state_get(
        engine->render_state,
        GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE,
        &cursor_has_position);
    current_cursor.drawn =
        cursor_visible && terminal_cursor_visible && cursor_has_position;
    if (current_cursor.drawn) {
        ghostty_render_state_get(
            engine->render_state,
            GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X, &current_cursor.x);
        ghostty_render_state_get(
            engine->render_state,
            GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y, &current_cursor.y);
        ghostty_render_state_get(
            engine->render_state,
            GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_WIDE_TAIL,
            &current_cursor.wide_tail);
        ghostty_render_state_get(
            engine->render_state,
            GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE,
            &current_cursor.style);
    }
    bool cursor_changed = !renderer->frame_cursor_initialized ||
        !cursor_states_equal(renderer->frame_cursor, current_cursor);
    bool changed = renderer->frame_pending_upload ||
        !renderer->frame_initialized || dimensions_changed ||
        cursor_changed || glyphs_changed || kitty_changed ||
        dirty != GHOSTTY_RENDER_STATE_DIRTY_FALSE;
    renderer->pending_cols = cols;
    renderer->pending_rows = rows;
    if (!changed) {
        *frame_changed = false;
        return true;
    }

    bool kitty_graphics = kitty_generation_available && kitty_generation != 0;
    bool full_redraw = !renderer->frame_initialized || dimensions_changed ||
        glyphs_changed || kitty_changed || kitty_graphics ||
        dirty == GHOSTTY_RENDER_STATE_DIRTY_FULL;
    colors.size = sizeof(colors);
    ghostty_render_state_get(engine->render_state,
                             GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);

    renderer->frame.resize(frame_size);
    if (full_redraw) {
        fill_rect(&renderer->frame, renderer->extent.width,
                  renderer->extent.height, 0, 0, renderer->extent.width,
                  renderer->extent.height, colors.background);
        if (kitty_graphics)
            draw_kitty_layer(renderer,
                             GHOSTTY_KITTY_PLACEMENT_LAYER_BELOW_BG);
        fill_rect(&renderer->frame, renderer->extent.width,
                  renderer->extent.height, 0, 0,
                  cols * renderer->fonts.cell_width,
                  rows * renderer->fonts.cell_height, colors.background);
    }

    if (ghostty_render_state_get(
            engine->render_state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
            &engine->row_iterator) != GHOSTTY_SUCCESS) {
        *error = "Could not obtain Ghostty row iterator";
        return false;
    }

    renderer->render_cells.clear();
    renderer->render_cells.reserve(static_cast<size_t>(cols) * rows);
    uint16_t row_index = 0;
    while (ghostty_render_state_row_iterator_next(engine->row_iterator) &&
           row_index < rows) {
        bool row_dirty = true;
        ghostty_render_state_row_get(
            engine->row_iterator, GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY,
            &row_dirty);
        bool cursor_row = !full_redraw &&
            ((cursor_changed && cursor_row_needs_redraw(
                renderer->frame_cursor, current_cursor, row_index)) ||
             (current_cursor.drawn &&
              current_cursor.style ==
                  GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK &&
              current_cursor.y == row_index));
        if (!full_redraw && !row_dirty && !cursor_row) {
            ++row_index;
            continue;
        }
        if (!full_redraw) {
            fill_rect(&renderer->frame, renderer->extent.width,
                      renderer->extent.height, 0,
                      row_index * renderer->fonts.cell_height,
                      cols * renderer->fonts.cell_width,
                      (row_index + 1) * renderer->fonts.cell_height,
                      colors.background);
        }
        if (ghostty_render_state_row_get(
                engine->row_iterator, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                &engine->row_cells) != GHOSTTY_SUCCESS) break;
        uint16_t column = 0;
        while (ghostty_render_state_row_cells_next(engine->row_cells) &&
               column < cols) {
            GhosttyCell raw = 0;
            GhosttyStyle style{};
            style.size = sizeof(style);
            GhosttyColorRgb foreground = colors.foreground;
            GhosttyColorRgb background = colors.background;
            bool selected = false;
            GhosttyBuffer text{};
            uint8_t stack_text[64];
            text.ptr = stack_text;
            text.cap = sizeof(stack_text);
            ghostty_render_state_row_cells_get(
                engine->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW,
                &raw);
            ghostty_render_state_row_cells_get(
                engine->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
                &style);
            if (ghostty_render_state_row_cells_get(
                    engine->row_cells,
                    GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
                    &foreground) != GHOSTTY_SUCCESS) {
                foreground = colors.foreground;
            }
            if (ghostty_render_state_row_cells_get(
                    engine->row_cells,
                    GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
                    &background) != GHOSTTY_SUCCESS) {
                background = colors.background;
            }
            ghostty_render_state_row_cells_get(
                engine->row_cells,
                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED, &selected);
            if (style.inverse || selected) std::swap(foreground, background);
            if (style.faint) foreground = dim_color(foreground);
            if (background.r != colors.background.r ||
                background.g != colors.background.g ||
                background.b != colors.background.b) {
                fill_rect(&renderer->frame, renderer->extent.width,
                          renderer->extent.height,
                          column * renderer->fonts.cell_width,
                          row_index * renderer->fonts.cell_height,
                          (column + 1) * renderer->fonts.cell_width,
                          (row_index + 1) * renderer->fonts.cell_height,
                          background);
            }

            RenderCell cell{};
            cell.column = column;
            cell.row = row_index;
            cell.style = style;
            cell.foreground = foreground;
            ghostty_cell_get(raw, GHOSTTY_CELL_DATA_CODEPOINT,
                             &cell.codepoint);
            ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &cell.wide);
            if (!style.invisible) {
                GhosttyResult text_result =
                    ghostty_render_state_row_cells_get(
                        engine->row_cells,
                        GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8,
                        &text);
                std::vector<uint8_t> dynamic_text;
                if (text_result == GHOSTTY_OUT_OF_SPACE && text.len > 0) {
                    dynamic_text.resize(text.len);
                    text.ptr = dynamic_text.data();
                    text.cap = dynamic_text.size();
                    text_result = ghostty_render_state_row_cells_get(
                        engine->row_cells,
                        GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8,
                        &text);
                }
                if (text_result == GHOSTTY_SUCCESS && text.len > 0)
                    cell.text.assign(
                        reinterpret_cast<const char *>(text.ptr), text.len);
            }
            renderer->render_cells.emplace_back(std::move(cell));
            ++column;
        }
        ++row_index;
    }

    if (kitty_graphics)
        draw_kitty_layer(renderer,
                         GHOSTTY_KITTY_PLACEMENT_LAYER_BELOW_TEXT);

    for (const auto &cell : renderer->render_cells) {
        draw_cell_content(renderer, cell, glyphs_available, cell.foreground);
    }

    if (kitty_graphics)
        draw_kitty_layer(renderer,
                         GHOSTTY_KITTY_PLACEMENT_LAYER_ABOVE_TEXT);

    if (current_cursor.drawn) {
        GhosttyColorRgb cursor_color =
            colors.cursor_has_value ? colors.cursor : colors.foreground;
        uint16_t cursor_column = cursor_start_column(current_cursor);
        const RenderCell *cursor_cell = find_render_cell(
            renderer, current_cursor.y, cursor_column);
        uint16_t cursor_columns = cursor_width_cells(
            current_cursor, cursor_cell
                ? cursor_cell->wide
                : GHOSTTY_CELL_WIDE_NARROW);
        int left = cursor_column * renderer->fonts.cell_width;
        int top = current_cursor.y * renderer->fonts.cell_height;
        int right = left + renderer->fonts.cell_width *
            cursor_columns;
        int bottom = top + renderer->fonts.cell_height;
        bool hollow = false;
        bool solid_block = false;
        switch (current_cursor.style) {
            case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BAR:
                right = left + std::max(1u, renderer->fonts.cell_width / 5);
                break;
            case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_UNDERLINE:
                top = bottom - std::max(1u, renderer->fonts.cell_height / 6);
                break;
            case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK_HOLLOW:
                fill_rect(&renderer->frame, renderer->extent.width,
                          renderer->extent.height, left, top, right, top + 1,
                          cursor_color);
                fill_rect(&renderer->frame, renderer->extent.width,
                          renderer->extent.height, left, bottom - 1, right,
                          bottom, cursor_color);
                fill_rect(&renderer->frame, renderer->extent.width,
                          renderer->extent.height, left, top, left + 1, bottom,
                          cursor_color);
                fill_rect(&renderer->frame, renderer->extent.width,
                          renderer->extent.height, right - 1, top, right,
                          bottom, cursor_color);
                hollow = true;
                break;
            case GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK:
                solid_block = true;
                break;
            default:
                break;
        }
        if (!hollow) {
            fill_rect(&renderer->frame, renderer->extent.width,
                      renderer->extent.height, left, top, right, bottom,
                      cursor_color);
        }
        if (solid_block && cursor_cell) {
            draw_cell_content(renderer, *cursor_cell, glyphs_available,
                              colors.background);
        }
    }
    renderer->frame_cursor = current_cursor;
    renderer->frame_cursor_initialized = true;
    if (kitty_generation_available)
        renderer->kitty_generation = kitty_generation;
    *frame_changed = true;
    return true;
}

bool create_staging(TermuxVulkanRenderer *renderer, size_t size,
                    std::string *error) {
    if (renderer->staging_size >= size) return true;
    if (renderer->staging) vkDestroyBuffer(renderer->device,
                                           renderer->staging, nullptr);
    if (renderer->staging_memory) vkFreeMemory(renderer->device,
                                               renderer->staging_memory,
                                               nullptr);
    renderer->staging = VK_NULL_HANDLE;
    renderer->staging_memory = VK_NULL_HANDLE;
    renderer->staging_size = 0;

    VkBufferCreateInfo buffer_info{};
    buffer_info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    buffer_info.size = size;
    buffer_info.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    buffer_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VkResult result =
        vkCreateBuffer(renderer->device, &buffer_info, nullptr,
                       &renderer->staging);
    if (result != VK_SUCCESS) {
        *error = vk_error("vkCreateBuffer", result);
        return false;
    }
    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(renderer->device, renderer->staging,
                                  &requirements);
    uint32_t type = find_memory_type(
        renderer->physical, requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (type == UINT32_MAX) {
        *error = "No host-visible Vulkan memory type";
        return false;
    }
    VkMemoryAllocateInfo allocation{};
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = type;
    result = vkAllocateMemory(renderer->device, &allocation, nullptr,
                              &renderer->staging_memory);
    if (result != VK_SUCCESS) {
        *error = vk_error("vkAllocateMemory", result);
        return false;
    }
    vkBindBufferMemory(renderer->device, renderer->staging,
                       renderer->staging_memory, 0);
    renderer->staging_size = size;
    return true;
}

bool create_swapchain(TermuxVulkanRenderer *renderer, std::string *error) {
    VkSurfaceCapabilitiesKHR capabilities{};
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
        renderer->physical, renderer->surface, &capabilities);
    uint32_t format_count = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(renderer->physical, renderer->surface,
                                         &format_count, nullptr);
    if (format_count == 0) {
        *error = "Vulkan surface has no supported formats";
        return false;
    }
    std::vector<VkSurfaceFormatKHR> formats(format_count);
    vkGetPhysicalDeviceSurfaceFormatsKHR(renderer->physical, renderer->surface,
                                         &format_count, formats.data());
    VkSurfaceFormatKHR selected = formats[0];
    for (const auto &candidate : formats) {
        if (candidate.format == VK_FORMAT_R8G8B8A8_UNORM ||
            candidate.format == VK_FORMAT_B8G8R8A8_UNORM) {
            selected = candidate;
            break;
        }
    }
    renderer->format = selected.format;
    renderer->extent = capabilities.currentExtent;
    if (renderer->extent.width == UINT32_MAX) {
        renderer->extent.width = std::clamp(
            renderer->requested_width, capabilities.minImageExtent.width,
            capabilities.maxImageExtent.width);
        renderer->extent.height = std::clamp(
            renderer->requested_height, capabilities.minImageExtent.height,
            capabilities.maxImageExtent.height);
    }
    if ((capabilities.supportedUsageFlags &
         VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
        *error = "Vulkan swapchain does not support transfer destination images";
        return false;
    }
    uint32_t image_count = std::max(2u, capabilities.minImageCount);
    if (capabilities.maxImageCount > 0)
        image_count = std::min(image_count, capabilities.maxImageCount);
    VkSwapchainCreateInfoKHR info{};
    info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    info.surface = renderer->surface;
    info.minImageCount = image_count;
    info.imageFormat = selected.format;
    info.imageColorSpace = selected.colorSpace;
    info.imageExtent = renderer->extent;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.preTransform =
        (capabilities.supportedTransforms &
         VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
        ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
        : capabilities.currentTransform;
    info.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    if ((capabilities.supportedCompositeAlpha &
         info.compositeAlpha) == 0) {
        info.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    }
    info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    info.clipped = VK_TRUE;
    info.oldSwapchain = renderer->swapchain;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkResult result = vkCreateSwapchainKHR(renderer->device, &info, nullptr,
                                           &swapchain);
    if (result != VK_SUCCESS) {
        *error = vk_error("vkCreateSwapchainKHR", result);
        return false;
    }
    if (renderer->swapchain)
        vkDestroySwapchainKHR(renderer->device, renderer->swapchain, nullptr);
    renderer->swapchain = swapchain;
    uint32_t count = 0;
    vkGetSwapchainImagesKHR(renderer->device, renderer->swapchain, &count,
                            nullptr);
    renderer->images.resize(count);
    vkGetSwapchainImagesKHR(renderer->device, renderer->swapchain, &count,
                            renderer->images.data());
    renderer->image_initialized.assign(count, false);
    renderer->frame_initialized = false;
    return create_staging(
        renderer,
        static_cast<size_t>(renderer->extent.width) *
            renderer->extent.height * 4,
        error);
}

bool initialize_vulkan(TermuxVulkanRenderer *renderer, std::string *error) {
    const char *extensions[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
    };
    VkApplicationInfo application{};
    application.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application.pApplicationName = "Termux";
    application.applicationVersion = VK_MAKE_VERSION(0, 118, 0);
    application.pEngineName = "libghostty-vt";
    application.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    application.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo instance_info{};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &application;
    instance_info.enabledExtensionCount = 2;
    instance_info.ppEnabledExtensionNames = extensions;
    VkResult result = vkCreateInstance(&instance_info, nullptr,
                                       &renderer->instance);
    if (result != VK_SUCCESS) {
        *error = vk_error("vkCreateInstance", result);
        return false;
    }
    VkAndroidSurfaceCreateInfoKHR surface_info{};
    surface_info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surface_info.window = renderer->window;
    result = vkCreateAndroidSurfaceKHR(renderer->instance, &surface_info,
                                       nullptr, &renderer->surface);
    if (result != VK_SUCCESS) {
        *error = vk_error("vkCreateAndroidSurfaceKHR", result);
        return false;
    }

    uint32_t physical_count = 0;
    vkEnumeratePhysicalDevices(renderer->instance, &physical_count, nullptr);
    std::vector<VkPhysicalDevice> physicals(physical_count);
    vkEnumeratePhysicalDevices(renderer->instance, &physical_count,
                               physicals.data());
    for (VkPhysicalDevice physical : physicals) {
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(physical, &properties);
        if (properties.apiVersion < VK_API_VERSION_1_1) continue;
        uint32_t queue_count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &queue_count,
                                                 nullptr);
        std::vector<VkQueueFamilyProperties> queues(queue_count);
        vkGetPhysicalDeviceQueueFamilyProperties(physical, &queue_count,
                                                 queues.data());
        for (uint32_t i = 0; i < queue_count; ++i) {
            VkBool32 present = false;
            vkGetPhysicalDeviceSurfaceSupportKHR(physical, i,
                                                 renderer->surface, &present);
            if (present && (queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
                renderer->physical = physical;
                renderer->queue_family = i;
                break;
            }
        }
        if (renderer->physical) break;
    }
    if (!renderer->physical) {
        *error = "No Vulkan 1.1 device can present this Android surface";
        return false;
    }
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info{};
    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queueFamilyIndex = renderer->queue_family;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &priority;
    const char *device_extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo device_info{};
    device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    device_info.enabledExtensionCount = 1;
    device_info.ppEnabledExtensionNames = device_extensions;
    result = vkCreateDevice(renderer->physical, &device_info, nullptr,
                            &renderer->device);
    if (result != VK_SUCCESS) {
        *error = vk_error("vkCreateDevice", result);
        return false;
    }
    vkGetDeviceQueue(renderer->device, renderer->queue_family, 0,
                     &renderer->queue);
    VkCommandPoolCreateInfo pool_info{};
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queueFamilyIndex = renderer->queue_family;
    if (vkCreateCommandPool(renderer->device, &pool_info, nullptr,
                            &renderer->command_pool) != VK_SUCCESS) {
        *error = "vkCreateCommandPool failed";
        return false;
    }
    VkCommandBufferAllocateInfo command_info{};
    command_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    command_info.commandPool = renderer->command_pool;
    command_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    command_info.commandBufferCount = 1;
    vkAllocateCommandBuffers(renderer->device, &command_info,
                             &renderer->command);
    VkSemaphoreCreateInfo semaphore_info{};
    semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    vkCreateSemaphore(renderer->device, &semaphore_info, nullptr,
                      &renderer->acquired);
    vkCreateSemaphore(renderer->device, &semaphore_info, nullptr,
                      &renderer->rendered);
    VkFenceCreateInfo fence_info{};
    fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fence_info.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    vkCreateFence(renderer->device, &fence_info, nullptr, &renderer->fence);
    return create_swapchain(renderer, error);
}

TermuxRendererDrawResult upload_frame(TermuxVulkanRenderer *renderer,
                                      std::string *error) {
    uint32_t image_index = 0;
    VkResult result = vkAcquireNextImageKHR(
        renderer->device, renderer->swapchain, UINT64_MAX,
        renderer->acquired, VK_NULL_HANDLE, &image_index);
    if (result == VK_ERROR_OUT_OF_DATE_KHR) {
        if (!create_swapchain(renderer, error))
            return TermuxRendererDrawResult::failure;
        result = vkAcquireNextImageKHR(
            renderer->device, renderer->swapchain, UINT64_MAX,
            renderer->acquired, VK_NULL_HANDLE, &image_index);
    }
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        *error = vk_error("vkAcquireNextImageKHR", result);
        return TermuxRendererDrawResult::failure;
    }
    void *mapped = nullptr;
    result = vkMapMemory(renderer->device, renderer->staging_memory, 0,
                         renderer->frame.size(), 0, &mapped);
    if (result != VK_SUCCESS) {
        *error = vk_error("vkMapMemory", result);
        return TermuxRendererDrawResult::failure;
    }
    if (renderer->format == VK_FORMAT_B8G8R8A8_UNORM ||
        renderer->format == VK_FORMAT_B8G8R8A8_SRGB) {
        auto *destination = static_cast<uint8_t *>(mapped);
        for (size_t i = 0; i < renderer->frame.size(); i += 4) {
            destination[i] = renderer->frame[i + 2];
            destination[i + 1] = renderer->frame[i + 1];
            destination[i + 2] = renderer->frame[i];
            destination[i + 3] = renderer->frame[i + 3];
        }
    } else {
        memcpy(mapped, renderer->frame.data(), renderer->frame.size());
    }
    vkUnmapMemory(renderer->device, renderer->staging_memory);

    vkResetFences(renderer->device, 1, &renderer->fence);
    vkResetCommandBuffer(renderer->command, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(renderer->command, &begin);
    VkImageMemoryBarrier to_transfer{};
    to_transfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    to_transfer.srcAccessMask = 0;
    to_transfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    to_transfer.oldLayout = renderer->image_initialized[image_index]
        ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
        : VK_IMAGE_LAYOUT_UNDEFINED;
    to_transfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    to_transfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    to_transfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    to_transfer.image = renderer->images[image_index];
    to_transfer.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    to_transfer.subresourceRange.levelCount = 1;
    to_transfer.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(renderer->command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                         nullptr, 1, &to_transfer);
    VkBufferImageCopy copy{};
    copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copy.imageSubresource.layerCount = 1;
    copy.imageExtent = {renderer->extent.width, renderer->extent.height, 1};
    vkCmdCopyBufferToImage(renderer->command, renderer->staging,
                           renderer->images[image_index],
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    VkImageMemoryBarrier to_present{};
    to_present.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    to_present.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    to_present.dstAccessMask = 0;
    to_present.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    to_present.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    to_present.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    to_present.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    to_present.image = renderer->images[image_index];
    to_present.subresourceRange = to_transfer.subresourceRange;
    vkCmdPipelineBarrier(renderer->command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr,
                         0, nullptr, 1, &to_present);
    vkEndCommandBuffer(renderer->command);
    VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &renderer->acquired;
    submit.pWaitDstStageMask = &wait_stage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &renderer->command;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &renderer->rendered;
    result = vkQueueSubmit(renderer->queue, 1, &submit, renderer->fence);
    if (result != VK_SUCCESS) {
        *error = vk_error("vkQueueSubmit", result);
        return TermuxRendererDrawResult::failure;
    }
    VkPresentInfoKHR present{};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &renderer->rendered;
    present.swapchainCount = 1;
    present.pSwapchains = &renderer->swapchain;
    present.pImageIndices = &image_index;
    result = vkQueuePresentKHR(renderer->queue, &present);
    renderer->image_initialized[image_index] = true;
    if (result == VK_ERROR_OUT_OF_DATE_KHR ||
        result == VK_SUBOPTIMAL_KHR)
        return TermuxRendererDrawResult::success;
    if (result != VK_SUCCESS) {
        *error = vk_error("vkQueuePresentKHR", result);
        return TermuxRendererDrawResult::failure;
    }
    return TermuxRendererDrawResult::success;
}

}  // namespace

TermuxVulkanRenderer *termux_renderer_create(
    TermuxGhosttyEngine *engine, ANativeWindow *window, uint32_t width,
    uint32_t height, uint32_t text_size, const char *font_path,
    std::string *error) {
    auto renderer = std::make_unique<TermuxVulkanRenderer>();
    renderer->engine = engine;
    renderer->window = window;
    ANativeWindow_acquire(window);
    renderer->requested_width = width;
    renderer->requested_height = height;
    if (!initialize_fonts(&renderer->fonts, text_size, font_path, error) ||
        !initialize_vulkan(renderer.get(), error)) {
        termux_renderer_destroy(renderer.release());
        return nullptr;
    }
    return renderer.release();
}

bool termux_renderer_resize(TermuxVulkanRenderer *renderer, uint32_t width,
                            uint32_t height, uint32_t text_size,
                            const char *font_path, std::string *error) {
    renderer->requested_width = width;
    renderer->requested_height = height;
    vkDeviceWaitIdle(renderer->device);
    if (text_size != renderer->fonts.text_size ||
        std::string(font_path ? font_path : "") !=
            renderer->fonts.custom_path) {
        if (!initialize_fonts(&renderer->fonts, text_size, font_path, error))
            return false;
        renderer->glyphs.clear();
        renderer->frame_initialized = false;
    }
    return create_swapchain(renderer, error);
}

TermuxRendererDrawResult termux_renderer_draw(
    TermuxVulkanRenderer *renderer, bool cursor_visible,
    std::string *error) {
    VkResult result = vkWaitForFences(
        renderer->device, 1, &renderer->fence, VK_TRUE,
        FRAME_WAIT_TIMEOUT_NS);
    if (result == VK_TIMEOUT) return TermuxRendererDrawResult::deferred;
    if (result != VK_SUCCESS) {
        *error = vk_error("vkWaitForFences", result);
        return TermuxRendererDrawResult::failure;
    }

    termux_ghostty_engine_lock(renderer->engine);
    bool frame_changed = false;
    bool composed = compose_frame(
        renderer, cursor_visible, &frame_changed, error);
    if (composed && frame_changed) {
        // Clear the snapshot while still holding the engine lock. A PTY write
        // after this point sets dirty again and cannot be erased by the frame
        // upload completing on this thread.
        clear_render_dirty(renderer->engine);
        renderer->frame_pending_upload = true;
    }
    termux_ghostty_engine_unlock(renderer->engine);
    if (!composed) return TermuxRendererDrawResult::failure;
    if (!frame_changed) return TermuxRendererDrawResult::success;
    TermuxRendererDrawResult uploaded = upload_frame(renderer, error);
    if (uploaded == TermuxRendererDrawResult::success) {
        renderer->frame_pending_upload = false;
        renderer->frame_initialized = true;
        renderer->last_cols = renderer->pending_cols;
        renderer->last_rows = renderer->pending_rows;
    }
    return uploaded;
}

void termux_renderer_destroy(TermuxVulkanRenderer *renderer) {
    if (!renderer) return;
    if (renderer->device) vkDeviceWaitIdle(renderer->device);
    if (renderer->staging) vkDestroyBuffer(renderer->device,
                                           renderer->staging, nullptr);
    if (renderer->staging_memory) vkFreeMemory(renderer->device,
                                               renderer->staging_memory,
                                               nullptr);
    if (renderer->fence) vkDestroyFence(renderer->device, renderer->fence,
                                        nullptr);
    if (renderer->acquired) vkDestroySemaphore(renderer->device,
                                               renderer->acquired, nullptr);
    if (renderer->rendered) vkDestroySemaphore(renderer->device,
                                               renderer->rendered, nullptr);
    if (renderer->command_pool) vkDestroyCommandPool(
        renderer->device, renderer->command_pool, nullptr);
    if (renderer->swapchain) vkDestroySwapchainKHR(
        renderer->device, renderer->swapchain, nullptr);
    if (renderer->device) vkDestroyDevice(renderer->device, nullptr);
    if (renderer->surface) vkDestroySurfaceKHR(renderer->instance,
                                               renderer->surface, nullptr);
    if (renderer->instance) vkDestroyInstance(renderer->instance, nullptr);
    if (renderer->window) ANativeWindow_release(renderer->window);
    delete renderer;
}

bool termux_renderer_measure_font(uint32_t text_size, const char *font_path,
                                  uint32_t *cell_width,
                                  uint32_t *cell_height,
                                  std::string *error) {
    FontSystem fonts;
    if (!initialize_fonts(&fonts, text_size, font_path, error)) return false;
    *cell_width = fonts.cell_width;
    *cell_height = fonts.cell_height;
    return true;
}
