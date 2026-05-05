#ifndef _OPENCV3_SVD_H_
#define _OPENCV3_SVD_H_

#include "ocv_core.h"

#ifdef __cplusplus
extern "C" {
#endif

void SVD_Compute(Mat src, Mat w, Mat u, Mat vt);

#ifdef __cplusplus
}
#endif

#endif //_OPENCV3_SVD_H