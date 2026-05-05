#ifndef _OCV_SAFETY_H_
#define _OCV_SAFETY_H_

// 在 extern "C" 入口包一层 try/catch，避免 ORT::Exception / cv::Exception 跨
// CGO 边界传播（属于 UB，实际表现为进程 abort）。
//
// 用法：
//   void Foo(...) {
//       OCV_GUARD_BEGIN
//       // body
//       OCV_GUARD_END_VOID
//   }
//   int Bar(...) {
//       OCV_GUARD_BEGIN
//       // body
//       return 0;
//       OCV_GUARD_END_RET(-1)
//   }

#ifdef __cplusplus

#include <exception>
#include "ocv_basetypes.h"

#define OCV_GUARD_BEGIN try {

#define OCV_GUARD_END_VOID                                                 \
    } catch (const std::exception &__ocv_e) {                              \
        ccv_log("[ocv] %s: %s\n", __func__, __ocv_e.what());               \
    } catch (...) {                                                        \
        ccv_log("[ocv] %s: unknown exception\n", __func__);                \
    }

#define OCV_GUARD_END_RET(errval)                                          \
    } catch (const std::exception &__ocv_e) {                              \
        ccv_log("[ocv] %s: %s\n", __func__, __ocv_e.what());               \
        return errval;                                                     \
    } catch (...) {                                                        \
        ccv_log("[ocv] %s: unknown exception\n", __func__);                \
        return errval;                                                     \
    }

#endif // __cplusplus
#endif // _OCV_SAFETY_H_
