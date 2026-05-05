#ifndef _OPENCV3_DNN_ORT_H_
#define _OPENCV3_DNN_ORT_H_

#include "ocv_core.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef void* LynxiSession;

LynxiSession LynxiSession_Create(struct ByteVector model, int deviceId);
void LynxiSession_PrintDesc(LynxiSession sess);
// void LynxiSession_GetInputNames(LynxiSession sess, CStrings* names);
// void LynxiSession_GetOutputNames(LynxiSession sess, CStrings* names);
void LynxiSession_GetInputShapes(LynxiSession sess, IntVector* shapes);
int LynxiSession_RunYolo(LynxiSession sess, Mat inputBlob, double scaleX, double scaleY, 
        IntVector strides, IntVector anchors, float confThreshold, float nmsThreshold, 
        Rects *outBoxes, IntVector *outIds, FloatVector *outScores);
int LynxiSession_RunMask(LynxiSession sess, Mat inputBlob, double scaleX, double scaleY, 
        float confThreshold, float maskThreshold, float nmsThreshold, 
        Contours *outContours, RotatedRects *outRR, IntVector *outIds, FloatVector *outScores);
int LynxiSession_RunYolact(LynxiSession sess, Mat inputBlob, double scaleX, double scaleY, 
        float confThreshold, float maskThreshold, float nmsThreshold, 
        Contours *outContours, RotatedRects *outRR, IntVector *outIds, FloatVector *outScores);
int LynxiSession_RunInference(LynxiSession sess, Mat img, FloatVector *out);
int LynxiSession_RunClassify(LynxiSession sess, Mat img, IntVector *outIds, FloatVector *outScores);
int LynxiSession_RunMetric(LynxiSession sess, Mat img, int clusters, FloatVector *embeddings);
int LynxiSession_RunCenterFace(LynxiSession sess, Mat inputBlob, double scaleX, double scaleY, 
        float confThreshold , float nmsThreshold, Rects *outBoxes, FloatVector *outLandmarks, FloatVector *outScores);
int LynxiSession_RunCenterPose(LynxiSession sess, Mat inputBlob, double scaleX, double scaleY, 
        float confThreshold , float nmsThreshold, Rects *outBoxes, FloatVector *outLandmarks, FloatVector *outScores);
int LynxiSession_RunMatting(LynxiSession sess, Mat inputBlob, Mat output);

#ifdef __cplusplus
}
#endif

#endif //_OPENCV3_DNN_H_
