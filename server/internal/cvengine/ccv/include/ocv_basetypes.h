/*******************************************************************
 * @file ocv_basetypes.h
 * Copyright (C) 2021 The Force AI
*******************************************************************/

#ifndef _OCV_BASETYPES_H_
#define _OCV_BASETYPES_H_

#ifdef __cplusplus
extern "C" {
#endif

#ifdef WIN32
#ifdef _MAKE_DLL_
#define DLL_EXPORT _declspec( dllexport )
#else
#define DLL_EXPORT _declspec(dllimport)
#endif
#else
#define DLL_EXPORT
#endif

// Wrapper for std::vector<int>
typedef struct IntVector {
    int* val;
    int length;
} IntVector;

// Wrapper for std::vector<long>
typedef struct LongVector {
    long* val;
    int length;
} LongVector;

// Wrapper for std::vector<float>
typedef struct FloatVector {
    float* val;
    int length;
} FloatVector;

// Wrapper for std::vector<string>
typedef struct CStrings {
    char** strs;
    int length;
} CStrings;

typedef struct ByteVector {
    char* data;
    int length;
} ByteVector;

typedef struct CItem {
    int ctype;
    int cid;
    float score;
    ByteVector key;
    ByteVector more;
    ByteVector value;
    FloatVector scores;
    IntVector xywh;
} CItem;

typedef struct CItemVector {
    CItem *its;
    int length;
} CItemVector;

/*************************************************************************
* 函数名称：IntVector_New
* 功能描述：创建IntVector
* 输入参数：len 创建Int数
* 输出参数：无
* 返 回 值：IntVector实例
*************************************************************************/
DLL_EXPORT IntVector IntVector_New(int len);

/*************************************************************************
* 函数名称：LongVector_New
* 功能描述：创建LongVector
* 输入参数：len 创建Long数
* 输出参数：无
* 返 回 值：LongVector实例
*************************************************************************/
DLL_EXPORT LongVector LongVector_New(int len);

/*************************************************************************
* 函数名称：FloatVector_New
* 功能描述：创建FloatVector
* 输入参数：len 创建Float数
* 输出参数：无
* 返 回 值：FloatVector实例
*************************************************************************/
DLL_EXPORT FloatVector FloatVector_New(int len);

/*************************************************************************
* 函数名称：ByteVector_New
* 功能描述：创建ByteVector
* 输入参数：len 创建Byte数
* 输出参数：无
* 返 回 值：ByteVector实例
*************************************************************************/
DLL_EXPORT ByteVector ByteVector_New(int len);

/*************************************************************************
* 函数名称：CItemVector_New
* 功能描述：创建CItemVector
* 输入参数：len 创建CItem数
* 输出参数：无
* 返 回 值：CItemVector实例
*************************************************************************/
DLL_EXPORT CItemVector CItemVector_New(int len);

/*************************************************************************
* 函数名称：IntVector_Release
* 功能描述：销毁IntVector
* 输入参数：buf IntVector实例
* 输出参数：无
* 返 回 值：无
*************************************************************************/
DLL_EXPORT void IntVector_Release(IntVector buf);

/*************************************************************************
* 函数名称：LongVector_Release
* 功能描述：销毁LongVector
* 输入参数：buf LongVector实例
* 输出参数：无
* 返 回 值：无
*************************************************************************/
DLL_EXPORT void LongVector_Release(LongVector buf);

/*************************************************************************
* 函数名称：FloatVector_Release
* 功能描述：销毁FloatVector
* 输入参数：buf FloatVector实例
* 输出参数：无
* 返 回 值：无
*************************************************************************/
DLL_EXPORT void FloatVector_Release(FloatVector buf);

/*************************************************************************
* 函数名称：CStrings_Release
* 功能描述：销毁CStrings
* 输入参数：buf CStrings实例
* 输出参数：无
* 返 回 值：无
*************************************************************************/
DLL_EXPORT void CStrings_Release(CStrings buf);

/*************************************************************************
* 函数名称：ByteVector_Release
* 功能描述：销毁ByteVector
* 输入参数：buf ByteVector实例
* 输出参数：无
* 返 回 值：无
*************************************************************************/
DLL_EXPORT void ByteVector_Release(ByteVector buf);

/*************************************************************************
* 函数名称：CItem_Release
* 功能描述：销毁CItem
* 输入参数：buf CItem实例
* 输出参数：无
* 返 回 值：无
*************************************************************************/
DLL_EXPORT void CItem_Release(CItem buf);

/*************************************************************************
* CItemVector_Release
* 功能描述：销毁CItemVector
* 输入参数：buf CItemVector实例
* 输出参数：无
* 返 回 值：无
*************************************************************************/
DLL_EXPORT void CItemVector_Release(CItemVector buf);

typedef void (*ccv_log_callback_t)(const char* msg);
DLL_EXPORT void ccv_set_log_callback(ccv_log_callback_t cb);
DLL_EXPORT void ccv_log(const char* fmt, ...);

#ifdef __cplusplus
}
#endif

#endif
