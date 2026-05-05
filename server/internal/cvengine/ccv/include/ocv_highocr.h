/*************************************************************************
 * @file ocv_highocr.h
 * Copyright (C) 2021 The Force AI
*************************************************************************/

#ifndef _OCV_HIGHOCR_H_
#define _OCV_HIGHOCR_H_

#include "ocv_basetypes.h"

#ifdef __cplusplus
extern "C" {
#endif

// 错误代码
typedef enum ERR_TYPE {
    ERR_NONE = 0, // 成功
    ERR_UNKNOW = -1, // 未知错误
    ERR_LICENSE_CHECK_FAILED = 1001, // 授权校验失败
    ERR_DLL_OPEN_FAILED = 1002, // 动态库打开失败
    ERR_IMAGE_OPEN_FAILED = 4001, // 图片打开失败
    ERR_DETECT_FAILED = 4002, // 目标检测失败
    ERR_UNSUPPORTED_CONTENT = 4003 // 不支持的文档种类
} ERR_TYPE;

// 文档种类
typedef enum CONTENT_TYPE {
    CONTENT_JMSFZ = 1, // 居民身份证
    CONTENT_JSZ = 2, // 驾驶证
    CONTENT_XSZ = 3, // 行驶证
    CONTENT_GAXZCFJDS = 4, // 公安行政处罚决定书
    CONTENT_WFCLTZS = 5, // 强制措施凭证（违法处理通知书）
    CONTENT_XWBL = 6, // 询问笔录
    CONTENT_GZBL = 7, // 告知笔录
    CONTENT_SQB = 8, // 牌证申请表
    CONTENT_JDCCYJLB = 9, // 机动车查验记录表
    CONTENT_HWJKZMS = 10, // 货物进口证明书
    CONTENT_JDCXSFP = 11, // 机动车销售发票
    CONTENT_JQXPZ = 12, // 交强险凭证
    CONTENT_HGZ = 13, // 新车合格证
    CONTENT_ZZJGDMZ = 14, // 组织机构代码证
    CONTENT_YYZZ = 15, // 营业执照
    CONTENT_WTS = 16, // 委托书
    CONTENT_CCSMSZM = 17, // 车船税
    CONTENT_BIAOPAI = 18, // 标牌
    CONTENT_ESCXSFP = 19, // 二手车销售发票
    CONTENT_JMSFZBM = 21, // 居民身份证背面
    CONTENT_JSZFY = 22, // 驾驶证副页
    CONTENT_XSZFY = 23, // 行驶证副页
    CONTENT_CLGZSWSPZ = 24, // 车辆购置税完税凭证
    CONTENT_MAX
} CONTENT_TYPE;

typedef void* HighOCR;

/*************************************************************************
* 函数名称：HighOCR_Create
* 功能描述：创建HighOCR实例
* 输入参数：devId 实例运行位置，-1表示cpu，0(1,2...)表示显卡序号
* 输入参数：dataPath 算法引擎数据目录(默认空)
* 输出参数：highocr 实例指针
* 返 回 值：参考ERR_TYPE
*************************************************************************/
DLL_EXPORT int HighOCR_Create(HighOCR *highocr, int devId, const char *dataPath);

/*************************************************************************
* 函数名称：HighOCR_Release
* 功能描述：销毁HighOCR实例
* 输入参数：highocr 实例
* 输出参数：无
* 返 回 值：参考ERR_TYPE
*************************************************************************/
DLL_EXPORT int HighOCR_Release(HighOCR highocr);

/*************************************************************************
* 函数名称：HighOCR_DetectContentType
* 功能描述：识别图片中的文档种类
* 输入参数：highocr 实例
* 输入参数：imagePath 图片路径
* 输出参数：ids 识别文档种类集(调用方负责释放内存)
* 输出参数：scores 识别文档种类得分(调用方负责释放内存)
* 返 回 值：参考ERR_TYPE
*************************************************************************/
DLL_EXPORT int HighOCR_DetectContentType(HighOCR highocr, const char *imagePath, 
    IntVector *ids, FloatVector *scores);

/*************************************************************************
* 函数名称：HighOCR_DetectContentGeneral
* 功能描述：识别图片中的文本内容
* 输入参数：highocr 实例
* 输入参数：imagePath 图片路径
* 输出参数：outs 识别文本内容(调用方负责释放内存)
* 返 回 值：参考ERR_TYPE
*************************************************************************/
DLL_EXPORT int HighOCR_DetectContentGeneral(HighOCR highocr, const char *imagePath, CStrings *outs);

/*************************************************************************
* 函数名称：HighOCR_DetectContentSpecial
* 功能描述：识别图片中的文本结构化内容
* 输入参数：highocr 实例
* 输入参数：imagePath 图片路径
* 输入参数：contentType 文档种类，参考CONTENT_TYPE
* 输出参数：outs 识别文本内容(调用方负责释放内存)
* 返 回 值：参考ERR_TYPE
*************************************************************************/
DLL_EXPORT int HighOCR_DetectContentSpecial(HighOCR highocr, const char *imagePath, 
    int contentType, CItemVector *outs);

/*************************************************************************
* 函数名称：HighOCR_RecognizeText
* 功能描述：识别图片中指定区域内的一行文字
* 输入参数：highocr 实例
* 输入参数：imagePath 图片路径
* 输入参数：roi 图片中指定区域数组，对应x,y,width,height，NULL则不指定
* 输出参数：outs 识别文本内容(调用方负责释放内存)
* 返 回 值：参考ERR_TYPE
*************************************************************************/
DLL_EXPORT int HighOCR_RecognizeText(HighOCR highocr, const char *imagePath, int roi[4], CStrings *outs);

#ifdef __cplusplus
}
#endif

#endif //_OCV_HIGHOCR_H_