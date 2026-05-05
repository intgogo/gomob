#ifndef _OPENCV3_IMGCODECS_H_
#define _OPENCV3_IMGCODECS_H_

#include "ocv_core.h"

#ifdef __cplusplus
extern "C" {
#endif

Mat Image_IMRead(const char* filename, int flags);
bool Image_IMWrite(const char* filename, Mat img);
bool Image_IMWrite_WithParams(const char* filename, Mat img, IntVector params);
struct ByteVector Image_IMEncode(const char* fileExt, Mat img);
struct ByteVector Image_IMEncode_WithParams(const char* fileExt, Mat img, IntVector params);
Mat Image_IMDecode(ByteVector buf, int flags);

#ifdef __cplusplus
}
#endif

#endif //_OPENCV3_IMGCODECS_H_
