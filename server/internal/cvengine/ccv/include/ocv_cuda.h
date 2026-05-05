#ifndef _OPENCV3_CUDA_H_
#define _OPENCV3_CUDA_H_

#include "ocv_core.h"

#ifdef __cplusplus
extern "C" {
#endif

int Cuda_GetDeviceCount();
int Cuda_GetDevice();
void Cuda_SetDevice(int deviceId);
int Cuda_GetDeviceMajorVersion(int deviceId);
int Cuda_GetDeviceMinorVersion(int deviceId);

#ifdef __cplusplus
}
#endif

#endif //_OPENCV3_CORE_H_
