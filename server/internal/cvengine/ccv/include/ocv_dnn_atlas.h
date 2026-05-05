#ifndef _OPENCV3_DNN_ORT_H_
#define _OPENCV3_DNN_ORT_H_

#include "ocv_core.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef void* AtlasSession;

AtlasSession AtlasSession_Create(struct ByteVector model, int deviceId);
// void AtlasSession_GetInputNames(AtlasSession sess, CStrings* names);
// void AtlasSession_GetOutputNames(AtlasSession sess, CStrings* names);
void AtlasSession_GetInputShapes(AtlasSession sess, IntVector* shapes);
int AtlasSession_RunYolo(AtlasSession sess, Mat inputBlob, double scaleX, double scaleY, 
        IntVector strides, IntVector anchors, float confThreshold, float nmsThreshold, 
        Rects *outBoxes, IntVector *outIds, FloatVector *outScores);
int AtlasSession_RunMask(AtlasSession sess, Mat inputBlob, double scaleX, double scaleY, 
        float confThreshold, float maskThreshold, float nmsThreshold, 
        Contours *outContours, RotatedRects *outRR, IntVector *outIds, FloatVector *outScores);
int AtlasSession_RunYolact(AtlasSession sess, Mat inputBlob, double scaleX, double scaleY, 
        float confThreshold, float maskThreshold, float nmsThreshold, 
        Contours *outContours, RotatedRects *outRR, IntVector *outIds, FloatVector *outScores);
int AtlasSession_RunInference(AtlasSession sess, Mat img, FloatVector *out);
int AtlasSession_RunClassify(AtlasSession sess, Mat img, IntVector *outIds, FloatVector *outScores);
int AtlasSession_RunMetric(AtlasSession sess, Mat img, int clusters, FloatVector *embeddings);
int AtlasSession_RunDB(AtlasSession session, Mat inputBlob, double scaleX, double scaleY,
                       float binThreshold, float boxThresh, RotatedRects *outRR, FloatVector *outScores);
int AtlasSession_RunCenterFace(AtlasSession sess, Mat inputBlob, double scaleX, double scaleY, 
        float confThreshold , float nmsThreshold, Rects *outBoxes, FloatVector *outLandmarks, FloatVector *outScores);
int AtlasSession_RunCenterPose(AtlasSession sess, Mat inputBlob, double scaleX, double scaleY, 
        float confThreshold , float nmsThreshold, Rects *outBoxes, FloatVector *outLandmarks, FloatVector *outScores);
int AtlasSession_RunMatting(AtlasSession sess, Mat inputBlob, Mat output);

#ifdef __cplusplus
}
#endif

#endif //_OPENCV3_DNN_H_
