#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <locale>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>

namespace dango {

inline constexpr std::size_t max_text_bytes = 1024;
inline constexpr std::size_t max_snapshot_bytes = 32768;
inline constexpr std::string_view apple_aumid = "AppleInc.AppleMusicWin_nzyj5cx40ttqa!App";
inline constexpr std::string_view apple_family = "AppleInc.AppleMusicWin_nzyj5cx40ttqa";

inline bool is_apple_music(std::string_view identity) {
    // SMTC providers expose either the full AUMID or package-family identity.
    return identity == apple_aumid || identity == apple_family;
}

class AppleSessionSelection {
public:
    void observe(std::string_view identity, std::uint32_t index) {
        if (!is_apple_music(identity)) {
            return;
        }
        if (selected_) {
            ambiguous_ = true;
        } else {
            selected_ = index;
        }
    }

    std::optional<std::uint32_t> selected_index() const {
        return ambiguous_ ? std::nullopt : selected_;
    }

private:
    std::optional<std::uint32_t> selected_;
    bool ambiguous_ = false;
};

inline std::string bounded_utf8(std::string_view text) {
    auto length = std::min(text.size(), max_text_bytes);
    if (length < text.size()) {
        while (length > 0 && (static_cast<unsigned char>(text[length]) & 0xc0) == 0x80) {
            --length;
        }
    }
    return std::string(text.substr(0, length));
}

inline std::string json_string(std::string_view text) {
    std::string result = "\"";
    constexpr char hex[] = "0123456789abcdef";
    for (unsigned char ch : bounded_utf8(text)) {
        if (ch == '"' || ch == '\\') {
            result += '\\';
            result += static_cast<char>(ch);
        } else if (ch < 0x20) {
            result += "\\u00";
            result += hex[ch >> 4];
            result += hex[ch & 0xf];
        } else {
            result += static_cast<char>(ch);
        }
    }
    result += '"';
    return result;
}

struct Snapshot {
    bool available = false;
    std::string session_id;
    std::string title;
    std::string artist;
    std::string album;
    std::int64_t duration_ms = 0;
    std::int64_t position_ms = 0;
    std::int64_t updated_at_ms = 0;
    std::int64_t captured_at_ms = 0;
    double playback_rate = 0;
    std::string state = "stopped";
};

inline std::string serialize(const Snapshot& snapshot) {
    std::ostringstream json;
    json.imbue(std::locale::classic());
    const auto duration = std::max<std::int64_t>(0, snapshot.duration_ms);
    const auto position = std::clamp<std::int64_t>(snapshot.position_ms, 0, duration);
    const auto captured = std::max<std::int64_t>(0, snapshot.captured_at_ms);
    const auto updated = snapshot.updated_at_ms >= 0 && snapshot.updated_at_ms <= captured
        ? snapshot.updated_at_ms : 0;
    const auto rate = std::isfinite(snapshot.playback_rate) && snapshot.playback_rate >= 0
        ? snapshot.playback_rate : 0;
    const auto state = snapshot.state == "playing" || snapshot.state == "paused"
        ? snapshot.state : "stopped";
    json << "{\"available\":" << (snapshot.available ? "true" : "false")
         << ",\"sessionId\":" << json_string(snapshot.session_id)
         << ",\"title\":" << json_string(snapshot.title)
         << ",\"artists\":[";
    if (!snapshot.artist.empty()) {
        json << json_string(snapshot.artist);
    }
    json << "],\"album\":" << json_string(snapshot.album)
         << ",\"durationMs\":" << duration
         << ",\"positionMs\":" << position
         << ",\"updatedAtUnixMs\":" << updated
         << ",\"capturedAtUnixMs\":" << captured
         << ",\"playbackRate\":" << rate
         << ",\"state\":\"" << state << "\"}\n";
    return json.str();
}

}
