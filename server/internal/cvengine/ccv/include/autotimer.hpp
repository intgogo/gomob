#ifndef _PKCODE_AUTOTIMER_H_
#define _PKCODE_AUTOTIMER_H_

#include <string>
#include <chrono>

#define DEBUG_TIMER 1

class AutoTimer {
public:
    std::chrono::steady_clock::time_point start;
    std::string dbg;
    AutoTimer(std::string func, int line) {
#if DEBUG_TIMER
        start = std::chrono::steady_clock::now();
        dbg = func + "[" + std::to_string(line) + "]";
#endif
    }
    ~AutoTimer() {
#if DEBUG_TIMER
        auto end = std::chrono::steady_clock::now();
        std::chrono::duration<double> spent = end - start;
        printf("%s: %0.1f ms\n", dbg.c_str(), spent.count()*1000);
#endif
    }
};
#define TIMER AutoTimer(__func__, __LINE__)


#endif