#include "gomob_berxel_host_sdk.h"

#include <cassert>
#include <iostream>
#include <string>

namespace {

void stop_before_start_is_noop() {
    gomob::berxel::host::P100R3DualSession session;
    assert(session.state() == gomob::berxel::host::P100R3SessionState::kIdle);

    session.stop();
    session.join();

    const gomob::berxel::host::P100R3DualSessionStats stats = session.stats();
    assert(stats.state == gomob::berxel::host::P100R3SessionState::kIdle);
    assert(stats.stop_reason == gomob::berxel::host::P100R3SessionStopReason::kNone);
}

void start_requires_at_least_one_stream() {
    gomob::berxel::host::P100R3DualSessionConfig config;
    config.enable_color = false;
    config.enable_depth = false;
    gomob::berxel::host::P100R3DualSession session(config);

    std::string log_line;
    gomob::berxel::host::P100R3DualSessionCallbacks callbacks;
    callbacks.log = [&](const std::string& msg) {
        log_line = msg;
    };
    const bool ok = session.start(callbacks);
    assert(!ok);

    const gomob::berxel::host::P100R3DualSessionStats stats = session.stats();
    assert(stats.state == gomob::berxel::host::P100R3SessionState::kFailed);
    assert(stats.stop_reason == gomob::berxel::host::P100R3SessionStopReason::kSetupFailed);
    assert(stats.error_message.find("至少需要启用一路流") != std::string::npos);
    assert(log_line == stats.error_message);
}

}  // namespace

int main() {
    stop_before_start_is_noop();
    start_requires_at_least_one_stream();
    std::cout << "berxel_session_state_test PASS\n";
    return 0;
}
