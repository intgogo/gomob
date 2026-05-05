#ifndef _TPS_H_
#define _TPS_H_

#include "ocv_core.h"

#ifdef __cplusplus
#include <opencv2/opencv.hpp>
#include <opencv2/dnn.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/shape.hpp>
extern "C" {
#endif

#ifdef __cplusplus
typedef cv::Ptr<cv::ThinPlateSplineShapeTransformer>* TPS;
#else
typedef void* TPS;
#endif

TPS TPS_Create(double regularizationParameter);
void TPS_RunImage(TPS tps, Mat srcImg, Mat dstImg, Size dstSize, FloatVector pts);

#ifdef __cplusplus
}
#endif

#endif //_TPS_H_
