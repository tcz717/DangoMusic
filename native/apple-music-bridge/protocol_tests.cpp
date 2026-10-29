#include "protocol.h"

#include <cstdlib>
#include <iostream>
#include <limits>

namespace {
void require(bool condition, const char* message) {
    if (!condition) {
        std::cerr << message << '\n';
        std::exit(1);
    }
}
}

int main() {
    require(dango::is_apple_music(dango::apple_aumid), "Apple Music AUMID rejected");
    require(dango::is_apple_music(dango::apple_family), "Apple Music family rejected");
    require(!dango::is_apple_music("AppleMusic.exe"), "Unverified executable accepted");
    require(!dango::is_apple_music("spotify!AppleInc.AppleMusicWin_nzyj5cx40ttqa!App"),
        "Substring identity accepted");
    require(!dango::is_apple_music("AppleInc.AppleMusicWin_nzyj5cx40ttqa!Other"),
        "Different application accepted");
    dango::AppleSessionSelection selection;
    selection.observe("Spotify.exe", 0);
    require(!selection.selected_index(), "Non-Apple session was selected");
    selection.observe(dango::apple_aumid, 1);
    require(selection.selected_index() == 1u, "Unique Apple session was not selected");
    selection.observe(dango::apple_family, 2);
    require(!selection.selected_index(), "Ambiguous Apple sessions were selected");
    selection.observe(dango::apple_aumid, 3);
    require(!selection.selected_index(), "Third Apple session cleared ambiguity");
    require(dango::json_string(std::string("\"\\\n\0", 4)) == "\"\\\"\\\\\\u000a\\u0000\"",
        "JSON escaping failed");
    const auto unicode = std::string(1023, 'a') + "\xe4\xb8\xad";
    require(dango::bounded_utf8(unicode).size() == 1023, "UTF-8 character was split");
    dango::Snapshot snapshot;
    const auto unavailable = dango::serialize(snapshot);
    require(unavailable.find("\"available\":false") != std::string::npos, "Missing unavailable");
    require(unavailable.find("\"artists\":[]") != std::string::npos, "Unexpected artist");
    require(unavailable.back() == '\n' && unavailable.find('\n') == unavailable.size() - 1,
        "Output must be exactly one line");
    snapshot.available = true;
    snapshot.title = snapshot.artist = snapshot.album = std::string(100000, '\x01');
    snapshot.duration_ms = 100;
    snapshot.position_ms = 200;
    snapshot.updated_at_ms = 500;
    snapshot.captured_at_ms = 300;
    snapshot.playback_rate = std::numeric_limits<double>::quiet_NaN();
    snapshot.state = "injected\"state";
    const auto json = dango::serialize(snapshot);
    require(json.size() <= dango::max_snapshot_bytes, "Output exceeds protocol bound");
    require(json.find("\"positionMs\":100") != std::string::npos, "Position not clamped");
    require(json.find("\"updatedAtUnixMs\":0") != std::string::npos,
        "Future timestamp disguised as a valid anchor");
    require(json.find("\"playbackRate\":0") != std::string::npos, "Invalid JSON rate");
    require(json.find("\"state\":\"stopped\"") != std::string::npos, "Unrecognized state");
    snapshot.state = "playing";
    snapshot.playback_rate = 1.25;
    snapshot.updated_at_ms = -1;
    require(dango::serialize(snapshot).find("\"updatedAtUnixMs\":0") != std::string::npos,
        "Negative timestamp disguised as a valid anchor");
    snapshot.updated_at_ms = 200;
    require(dango::serialize(snapshot).find("\"updatedAtUnixMs\":200") != std::string::npos,
        "Valid timeline anchor changed");
    require(dango::serialize(snapshot).find("\"playbackRate\":1.25") != std::string::npos,
        "Playback rate lost");
    snapshot.duration_ms = -1;
    snapshot.position_ms = -100;
    require(dango::serialize(snapshot).find("\"durationMs\":0,\"positionMs\":0") !=
        std::string::npos, "Negative timeline not clamped");
}
