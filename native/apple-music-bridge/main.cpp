#include <Windows.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Control.h>

#include <chrono>
#include <cstdio>

#include "protocol.h"

namespace {

using namespace winrt::Windows::Media::Control;

std::int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}

std::string metadata(const winrt::hstring& value) {
    // Bound conversion allocation too, and do not split a UTF-16 surrogate pair.
    auto length = std::min<std::size_t>(value.size(), dango::max_text_bytes);
    if (length < value.size() && length > 0 &&
        value.c_str()[length - 1] >= 0xd800 && value.c_str()[length - 1] <= 0xdbff) {
        --length;
    }
    return dango::bounded_utf8(winrt::to_string(std::wstring_view(value.c_str(), length)));
}

dango::Snapshot capture() {
    auto manager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
    const auto sessions = manager.GetSessions();
    dango::AppleSessionSelection selection;
    for (std::uint32_t index = 0; index < sessions.Size(); ++index) {
        const auto session = sessions.GetAt(index);
        const auto identity = session.SourceAppUserModelId();
        // Never consult GetCurrentSession: another player must not become Apple Music.
        if (identity.size() <= 128) {
            selection.observe(winrt::to_string(identity), index);
        }
    }
    if (const auto index = selection.selected_index()) {
        const auto session = sessions.GetAt(*index);
        const auto identity = session.SourceAppUserModelId();
        const auto properties = session.TryGetMediaPropertiesAsync().get();
        const auto title = properties.Title();
        const auto artist = properties.Artist();
        const auto album = properties.AlbumTitle();
        const auto playback = session.GetPlaybackInfo();
        const auto status = playback.PlaybackStatus();
        const auto rate_reference = playback.PlaybackRate();
        const std::optional<double> rate = rate_reference
            ? std::optional<double>(rate_reference.Value()) : std::nullopt;
        const auto timeline = session.GetTimelineProperties();
        const auto start_time = timeline.StartTime();
        const auto end_time = timeline.EndTime();
        const auto position_time = timeline.Position();
        const auto updated_time = timeline.LastUpdatedTime();
        const auto properties_after = session.TryGetMediaPropertiesAsync().get();
        const auto playback_after = session.GetPlaybackInfo();
        const auto timeline_after = session.GetTimelineProperties();
        const auto rate_after_reference = playback_after.PlaybackRate();
        const std::optional<double> rate_after = rate_after_reference
            ? std::optional<double>(rate_after_reference.Value()) : std::nullopt;
        // SMTC has no atomic snapshot API. Reject a sample crossing any observed
        // metadata, playback, seek, or timeline change instead of mixing tracks.
        if (identity != session.SourceAppUserModelId() ||
            title != properties_after.Title() ||
            artist != properties_after.Artist() ||
            album != properties_after.AlbumTitle() ||
            status != playback_after.PlaybackStatus() ||
            rate != rate_after ||
            start_time != timeline_after.StartTime() ||
            end_time != timeline_after.EndTime() ||
            position_time != timeline_after.Position() ||
            updated_time != timeline_after.LastUpdatedTime()) {
            dango::Snapshot unavailable;
            unavailable.captured_at_ms = now_ms();
            return unavailable;
        }
        dango::Snapshot result;
        result.session_id = winrt::to_string(identity);
        result.title = metadata(title);
        result.artist = metadata(artist);
        result.album = metadata(album);
        const auto start = start_time.count();
        const auto end = end_time.count();
        const auto position = position_time.count();
        // Convert first to avoid overflow if a broken provider reports extreme ticks.
        result.duration_ms = std::max<std::int64_t>(0, end / 10000 - start / 10000);
        result.position_ms = position / 10000 - start / 10000;
        result.captured_at_ms = now_ms();
        constexpr std::int64_t windows_epoch_ms = 11644473600000LL;
        result.updated_at_ms = updated_time.time_since_epoch().count() / 10000
            - windows_epoch_ms;
        switch (status) {
        case GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing:
            result.state = "playing";
            result.playback_rate = 1;
            break;
        case GlobalSystemMediaTransportControlsSessionPlaybackStatus::Paused:
            result.state = "paused";
            break;
        default:
            result.state = "stopped";
            break;
        }
        if (result.state == "playing") {
            if (rate) {
                result.playback_rate = *rate;
            }
        }
        result.available = true;
        return result;
    }
    dango::Snapshot result;
    result.captured_at_ms = now_ms();
    return result;
}

}

int main() {
    // An unsupported OS, disappearing session, denied access, or malformed provider
    // all yield unavailable. The JVM owns the hard process deadline and termination.
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
    try {
        winrt::init_apartment(winrt::apartment_type::multi_threaded);
        dango::Snapshot snapshot;
        try {
            snapshot = capture();
        } catch (...) {
            snapshot.captured_at_ms = now_ms();
        }
        const auto json = dango::serialize(snapshot);
        if (json.size() <= dango::max_snapshot_bytes) {
            std::fwrite(json.data(), 1, json.size(), stdout);
            return 0;
        }
    } catch (...) {
    }
    // Literal fallback remains usable even if allocation or apartment setup failed.
    std::fputs("{\"available\":false,\"sessionId\":\"\",\"title\":\"\",\"artists\":[],"
               "\"album\":\"\",\"durationMs\":0,\"positionMs\":0,\"updatedAtUnixMs\":0,"
               "\"capturedAtUnixMs\":0,\"playbackRate\":0,\"state\":\"stopped\"}\n", stdout);
    return 0;
}
