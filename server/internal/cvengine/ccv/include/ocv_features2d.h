#ifndef _OPENCV3_FEATURES2D_H_
#define _OPENCV3_FEATURES2D_H_

#include "ocv_core.h"

#ifdef __cplusplus
extern "C" {
#endif

#ifdef __cplusplus
typedef cv::Ptr<cv::AKAZE>* AKAZE;
typedef cv::Ptr<cv::AgastFeatureDetector>* AgastFeatureDetector;
typedef cv::Ptr<cv::BRISK>* BRISK;
typedef cv::Ptr<cv::FastFeatureDetector>* FastFeatureDetector;
typedef cv::Ptr<cv::GFTTDetector>* GFTTDetector;
typedef cv::Ptr<cv::KAZE>* KAZE;
typedef cv::Ptr<cv::MSER>* MSER;
typedef cv::Ptr<cv::ORB>* ORB;
typedef cv::Ptr<cv::SimpleBlobDetector>* SimpleBlobDetector;
typedef cv::Ptr<cv::BFMatcher>* BFMatcher;
#else
typedef void* AKAZE;
typedef void* AgastFeatureDetector;
typedef void* BRISK;
typedef void* FastFeatureDetector;
typedef void* GFTTDetector;
typedef void* KAZE;
typedef void* MSER;
typedef void* ORB;
typedef void* SimpleBlobDetector;
typedef void* BFMatcher;
#endif

AKAZE AKAZE_Create();
void AKAZE_Release(AKAZE a);
struct KeyPoints AKAZE_Detect(AKAZE a, Mat src);
struct KeyPoints AKAZE_DetectAndCompute(AKAZE a, Mat src, Mat mask, Mat desc);

AgastFeatureDetector AgastFeatureDetector_Create();
void AgastFeatureDetector_Release(AgastFeatureDetector a);
struct KeyPoints AgastFeatureDetector_Detect(AgastFeatureDetector a, Mat src);

BRISK BRISK_Create();
void BRISK_Release(BRISK b);
struct KeyPoints BRISK_Detect(BRISK b, Mat src);
struct KeyPoints BRISK_DetectAndCompute(BRISK b, Mat src, Mat mask, Mat desc);

FastFeatureDetector FastFeatureDetector_Create();
FastFeatureDetector FastFeatureDetector_CreateWithParams(int threshold, bool nonmaxSuppression, int type);
void FastFeatureDetector_Release(FastFeatureDetector f);
struct KeyPoints FastFeatureDetector_Detect(FastFeatureDetector f, Mat src);

GFTTDetector GFTTDetector_Create();
void GFTTDetector_Release(GFTTDetector a);
struct KeyPoints GFTTDetector_Detect(GFTTDetector a, Mat src);

KAZE KAZE_Create();
void KAZE_Release(KAZE a);
struct KeyPoints KAZE_Detect(KAZE a, Mat src);
struct KeyPoints KAZE_DetectAndCompute(KAZE a, Mat src, Mat mask, Mat desc);

MSER MSER_Create();
void MSER_Release(MSER a);
struct KeyPoints MSER_Detect(MSER a, Mat src);

ORB ORB_Create();
void ORB_Release(ORB o);
struct KeyPoints ORB_Detect(ORB o, Mat src);
struct KeyPoints ORB_DetectAndCompute(ORB o, Mat src, Mat mask, Mat desc);

SimpleBlobDetector SimpleBlobDetector_Create();
SimpleBlobDetector SimpleBlobDetector_Create_WithParams(SimpleBlobDetectorParams params);
void SimpleBlobDetector_Release(SimpleBlobDetector b);
struct KeyPoints SimpleBlobDetector_Detect(SimpleBlobDetector b, Mat src);
SimpleBlobDetectorParams SimpleBlobDetectorParams_Create();

BFMatcher BFMatcher_Create();
BFMatcher BFMatcher_CreateWithParams(int normType, bool crossCheck);
void BFMatcher_Release(BFMatcher b);
struct MultiDMatches BFMatcher_KnnMatch(BFMatcher b, Mat query, Mat train, int k);

void DrawKeyPoints(Mat src, struct KeyPoints kp, Mat dst, const Scalar s, int flags);

#ifdef __cplusplus
}
#endif

#endif //_OPENCV3_FEATURES2D_H_
