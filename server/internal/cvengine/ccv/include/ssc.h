#ifndef _SSC_H_
#define _SSC_H_

#include "ocv_core.h"

#ifdef __cplusplus

#ifdef USE_SSC_FAISS
#include <faiss/IndexFlat.h>
#include <faiss/IndexIVFFlat.h>
typedef faiss::Index* Index;
#else
#include <opencv2/flann.hpp>
typedef cv::flann::Index* Index;
#endif

extern "C" {
#else
typedef void* Index;
#endif

typedef long* LongPtr;
typedef float* FloatPtr;

Index SSC_CreateIndex(int gpuid, int clusters, int dim, int nb, FloatVector xb);
void SSC_SearchIndex(Index index, int nq, FloatVector xq, int k, int nprobe, FloatVector *dis, IntVector *ids);
void SSC_Rebuild(Index index, int dim, int nb, FloatVector xb);
float SSC_L1Dist(FloatVector xa, FloatVector xb);
float SSC_L2Dist(FloatVector xa, FloatVector xb);

#ifdef __cplusplus
}
#endif

#endif //_SSC_H_
