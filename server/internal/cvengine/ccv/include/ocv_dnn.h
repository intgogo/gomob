#ifndef _OPENCV3_DNN_H_
#define _OPENCV3_DNN_H_

#include <math.h>
#include "ocv_core.h"

#ifdef __cplusplus
#include <opencv2/opencv.hpp>
#include <opencv2/dnn.hpp>
#include <opencv2/imgproc.hpp>
extern "C" {
#endif

#ifdef __cplusplus
typedef cv::dnn::Net* Net;
typedef cv::Ptr<cv::dnn::Layer>* Layer;
#else
typedef void* Net;
typedef void* Layer;
#endif

Net Net_ReadNet(const char* model, const char* config);
Net Net_ReadNetBytes(const char* framework, struct ByteVector model, struct ByteVector config);
Net Net_ReadNetFromCaffe(const char* prototxt, const char* caffeModel);
Net Net_ReadNetFromCaffeBytes(struct ByteVector prototxt, struct ByteVector caffeModel);
Net Net_ReadNetFromTensorflow(const char* model);
Net Net_ReadNetFromTensorflowBytes(struct ByteVector model);
Mat Net_BlobFromImage(Mat image, double scalefactor, Size size, Scalar mean, bool swapRB,
                      bool crop);
void Net_BlobFromImages(struct Mats images, Mat blob,  double scalefactor, Size size, 
                        Scalar mean, bool swapRB, bool crop, int ddepth);
void Net_ImagesFromBlob(Mat blob_, struct Mats* images_);
void Net_Release(Net net);
bool Net_Empty(Net net);
void Net_SetInput(Net net, Mat blob, const char* name);
Mat Net_Forward(Net net, const char* outputName);
void Net_ForwardLayers(Net net, struct Mats* outputBlobs, struct CStrings outBlobNames);
void Net_SetPreferableBackend(Net net, int backend);
void Net_SetPreferableTarget(Net net, int target);
int64_t Net_GetPerfProfile(Net net);
void Net_GetUnconnectedOutLayers(Net net, IntVector* res);
void Net_GetLayerNames(Net net, CStrings* names);
void Net_GetOutputLayerNames(Net net, CStrings* names);

Mat Net_GetBlobChannel(Mat blob, int imgidx, int chnidx);
Scalar Net_GetBlobSize(Mat blob);

Layer Net_GetLayer(Net net, int layerid);
void Layer_Release(Layer layer);
int Layer_InputNameToIndex(Layer layer, const char* name);
int Layer_OutputNameToIndex(Layer layer, const char* name);
const char* Layer_GetName(Layer layer);
const char* Layer_GetType(Layer layer);

void RunNMSRect(Rects inBoxes, FloatVector inScores, float confThreshold , float nmsThreshold, IntVector *outIndices);
void RunNMSRotatedRect(Points inPts4, FloatVector inScores, float confThreshold , float nmsThreshold, IntVector *outIndices);

void Net_RunYolo(Net net, Mat inputBlob, Size inputSize, double inputScale, 
        struct CStrings outBlobNames, float confThreshold , float nmsThreshold, 
        Rects *outBoxes, IntVector *outIds, FloatVector *outScores);
void Net_RunClassify(Net net, Mat inputBlob, struct CStrings outBlobNames, IntVector *outIds, FloatVector *outScores);
void Net_RunMetric(Net net, Mat inputBlob, struct CStrings outBlobNames, int clusters, FloatVector *embeddings);
void Net_RunInference(Net net, Mat inputBlob, struct CStrings outBlobNames, FloatVector *output);

int WarpFace(Mat src, Mat dst, FloatVector landmarks);
void WarpRect(Mat src, Mat dst, FloatVector landmarks);

static inline float logistic_activate(float x) { return 1.F / (1.F + exp(-x)); }

static inline void softmax(FloatVector *xx)
{
    float max = -INT32_MAX, sum = 0;
    int i = 0;
    for (i = 0; i < xx->length; i++) {
        xx->val[i] = exp(xx->val[i]);
        sum += xx->val[i];
    }
    for (i = 0; i < xx->length; i++) {
        xx->val[i] /= sum;
    }
}
#ifdef __cplusplus
}
#endif

#endif //_OPENCV3_DNN_H_
