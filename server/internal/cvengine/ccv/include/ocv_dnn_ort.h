#ifndef _OPENCV3_DNN_ORT_H_
#define _OPENCV3_DNN_ORT_H_

#include "ocv_core.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef void* ORTSession;

ORTSession ORTSession_Create(struct ByteVector model, int devId, int modelId, CStrings optShapes);
void ORTSession_Destroy(ORTSession session);
void ORTSession_GetInputNames(ORTSession session, CStrings* names);
void ORTSession_GetOutputNames(ORTSession session, CStrings* names);
void ORTSession_GetInputShapes(ORTSession session, IntVector* shapes);
void ORTSession_RunMatting(ORTSession session, Mat inputBlob, CStrings inputNames, CStrings outputNames, Mat output);
void ORTSession_RunMetric(ORTSession session, Mat inputBlob, CStrings inputNames, CStrings outputNames, int clusters, FloatVector *embeddings);
void ORTSession_RunClassify(ORTSession session, Mat inputBlob, CStrings inputNames, CStrings outputNames, IntVector *outIds, FloatVector *outScores);
void ORTSession_RunInference(ORTSession session, Mat inputBlob, CStrings inputNames, CStrings outputNames, FloatVector *output);
void ORTSession_RunYolo(ORTSession session, Mat inputBlob, double scaleX, double scaleY, CStrings inputNames, CStrings outputNames, 
        IntVector strides, IntVector anchors, float confThreshold, float nmsThreshold, Rects *outBoxes, IntVector *outIds, FloatVector *outScores);
void ORTSession_RunMask(ORTSession session, Mat inputBlob, double scaleX, double scaleY, CStrings inputNames, CStrings outputNames, 
        float confThreshold, float maskThreshold, float nmsThreshold, Contours *outContours, RotatedRects *outRR, IntVector *outIds, FloatVector *outScores);
void ORTSession_RunDB(ORTSession session, Mat inputBlob, double scaleX, double scaleY, CStrings inputNames, CStrings outputNames,
        float binThreshold, float boxThresh, RotatedRects *outRR, FloatVector *outScores);
void ORTSession_RunYolact(ORTSession ortSession, Mat inputBlob, double scaleX, double scaleY, CStrings inputNames, CStrings outputNames,
        float confThreshold, float maskThreshold, float nmsThresh, Contours *outContours, RotatedRects *outRR, IntVector *outIds, FloatVector *outScores);
void ORTSession_RunCenterFace(ORTSession ortSession, Mat inputBlob, double scaleX, double scaleY, CStrings inputNames, CStrings outputNames, 
        float confThreshold , float nmsThreshold, Rects *outBoxes, FloatVector *outLandmarks, FloatVector *outScores);
void ORTSession_RunCenterPose(ORTSession ortSession, Mat inputBlob, double scaleX, double scaleY, CStrings inputNames, CStrings outputNames, 
        float confThreshold , float nmsThreshold, Rects *outBoxes, FloatVector *outLandmarks, FloatVector *outScores);

#ifdef __cplusplus
}
#endif

#endif //_OPENCV3_DNN_H_
