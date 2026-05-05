#ifndef _OPENCV3_ASYNNCARRAY_H_
#define _OPENCV3_ASYNNCARRAY_H_

#include "ocv_core.h"
#include "ocv_dnn.h"

#ifdef __cplusplus
extern "C" {
#endif

#ifdef __cplusplus
typedef cv::AsyncArray* AsyncArray;
#else
typedef void* AsyncArray;
#endif

AsyncArray AsyncArray_New();
const char* AsyncArray_GetAsync(AsyncArray async_out,Mat out);
void AsyncArray_Close(AsyncArray a);
AsyncArray Net_forwardAsync(Net net, const char* outputName);


#ifdef __cplusplus
}
#endif

#endif //_OPENCV3_ASYNNCARRAY_H_
