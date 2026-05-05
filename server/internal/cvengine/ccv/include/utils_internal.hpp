#ifndef __PKCODE_UTILS_H__
#define __PKCODE_UTILS_H__
#include "opencv2/opencv.hpp"
#include "ocv_core.h"
#include <numeric>

template <typename T>
static std::vector<size_t> sortIds(const std::vector<T> &v) {
    std::vector<size_t> idx(v.size());
    std::iota(idx.begin(), idx.end(), 0);
    std::sort(idx.begin(), idx.end(), [&v](size_t i1, size_t i2) { return v[i1] > v[i2]; });
    return idx;
}

static Points transFromCVPoints(std::vector<cv::Point> pp) {
    Points res;
    res.length = pp.size();
    res.points = new Point[res.length];
    for (int i = 0; i < pp.size(); i++) {
        res.points[i].x = pp[i].x;
        res.points[i].y = pp[i].y;
    }
    return res;
}

static std::vector<cv::Point> trans2CVPoints(Points pp) {
    std::vector<cv::Point> outs;
    for (int i = 0; i < pp.length; i++) {
        outs.push_back(cv::Point(pp.points[i].x, pp.points[i].y));
    }
    return outs;
}

static ByteVector trans2bytes(std::string &str) {
    ByteVector bb = ByteVector_New(str.size()+1);
    strcpy(bb.data, str.c_str());
    return bb;
}

static FloatVector trans2floats(std::vector<float> &src) {
   FloatVector dst = FloatVector_New(src.size());
   memcpy(dst.val, src.data(), sizeof(float) * src.size());
   return dst;
}

static IntVector trans2ints(std::vector<int> &src) {
   IntVector dst = IntVector_New(src.size());
   memcpy(dst.val, src.data(), sizeof(int) * src.size());
   return dst;
}

static bool isImageFile(std::string filename) {
    if (filename.find(".jpg") != std::string::npos || 
        filename.find(".JPG") != std::string::npos || 
        filename.find(".png") != std::string::npos || 
        filename.find(".PNG") != std::string::npos) {
        return true;
    }
    return false;
}

static void showImage(std::string name, cv::Mat &img, bool wait = true, std::vector<cv::Point> *outPts = NULL) {
    cv::Mat imgr = img;
    int xcnt = 4, ycnt = 4;
    int xi = 0, yi = 0;
    int xs = img.cols/xcnt, ys = img.rows/ycnt;
    
    struct MouseParam {
        std::vector<cv::Point> *pts;
        cv::Mat &imgr;
        MouseParam(cv::Mat &imgr_, std::vector<cv::Point> *pts_) :
            imgr(imgr_), pts(pts) {}
    };
    MouseParam param(imgr, outPts);

    while (yi < ycnt) {
        cv::namedWindow(name, cv::WINDOW_NORMAL);
        // cv::resizeWindow(name, cv::Size(300, 100));
        // cv::moveWindow(name, 1700, 850);
        // cv::setMouseCallback(name, [](int event, int x, int y, int flags, void* param) {
        //     MouseParam *mp = static_cast<MouseParam *>(param);
        //     if (mp->pts) {
        //         mp->pts->push_back(cv::Point(x, y));
        //     }
        //     // cv::putText(imgr, std::to_string(x)+","+std::to_string(y), cv::Point(20, 20), 
        //     //     cv::FONT_HERSHEY_COMPLEX, 1, cv::Scalar(0, 0, 255));
            
        // }, &param);

        cv::imshow(name, imgr);
        if (wait) {
            int code = cv::waitKey();
            if (code == ' ') {
                break;
            }
            // else if (code == 'x') {
            //     if (param.pts) {
            //         param.pts->clear();
            //     }
            // }
            
            int xmin = xi * xs;
            int xmax = std::min(img.cols-1, (xi + 1) *xs);
            int ymin = yi * ys;
            int ymax = std::min(img.rows-1, (yi + 1) *ys); 
            imgr = img.rowRange(ymin, ymax).colRange(xmin, xmax);
            if (++xi == xcnt) {
                xi = 0;
                yi += 1;
            }
        }
    }
}

//去除二值图像边缘的突出部
//uthreshold、vthreshold分别表示突出部的宽度阈值和高度阈值
//type代表突出部的颜色，0表示黑色，1代表白色
static void deleteJut(cv::Mat &src, cv::Mat &dst, int uthreshold, int vthreshold, int type) {
    int threshold;
    src.copyTo(dst);
    int height = dst.rows;
    int width = dst.cols;
    int k; //用于循环计数传递到外部
    for (int i = 0; i < height - 1; i++) {
        uchar *p = dst.ptr<uchar>(i);
        for (int j = 0; j < width - 1; j++) {
            if (type == 0) {
                //行消除
                if (p[j] == 255 && p[j + 1] == 0) {
                    if (j + uthreshold >= width) {
                        for (int k = j + 1; k < width; k++)
                            p[k] = 255;
                    } else {
                        for (k = j + 2; k <= j + uthreshold; k++) {
                            if (p[k] == 255)
                                break;
                        }
                        if (p[k] == 255) {
                            for (int h = j + 1; h < k; h++)
                                p[h] = 255;
                        }
                    }
                }
                //列消除
                if (p[j] == 255 && p[j + width] == 0) {
                    if (i + vthreshold >= height) {
                        for (k = j + width; k < j + (height - i) * width; k += width)
                            p[k] = 255;
                    } else {
                        for (k = j + 2 * width; k <= j + vthreshold * width; k += width) {
                            if (p[k] == 255)
                                break;
                        }
                        if (p[k] == 255) {
                            for (int h = j + width; h < k; h += width)
                                p[h] = 255;
                        }
                    }
                }
            } else //type = 1
            {
                //行消除
                if (p[j] == 0 && p[j + 1] == 255) {
                    if (j + uthreshold >= width) {
                        for (int k = j + 1; k < width; k++)
                            p[k] = 0;
                    } else {
                        for (k = j + 2; k <= j + uthreshold; k++) {
                            if (p[k] == 0)
                                break;
                        }
                        if (p[k] == 0) {
                            for (int h = j + 1; h < k; h++)
                                p[h] = 0;
                        }
                    }
                }
                //列消除
                if (p[j] == 0 && p[j + width] == 255) {
                    if (i + vthreshold >= height) {
                        for (k = j + width; k < j + (height - i) * width; k += width)
                            p[k] = 0;
                    } else {
                        for (k = j + 2 * width; k <= j + vthreshold * width; k += width) {
                            if (p[k] == 0)
                                break;
                        }
                        if (p[k] == 0) {
                            for (int h = j + width; h < k; h += width)
                                p[h] = 0;
                        }
                    }
                }
            }
        }
    }
}

static cv::Mat fitLine(std::vector<cv::Point>& pts) {
    cv::Mat m;
    cv::fitLine(pts, m, cv::DIST_L2, 0, 0, 0);
    return m;
}

static cv::Mat fitCurve(std::vector<cv::Point>& pts, int n) {
    int N = pts.size();
    cv::Mat X = cv::Mat::zeros(n + 1, n + 1, CV_64FC1);
    for (int i = 0; i < n + 1; i++) {
        for (int j = 0; j < n + 1; j++) {
            for (int k = 0; k < N; k++) {
                X.at<double>(i, j) = 
                    X.at<double>(i, j) + std::pow(pts[k].x, i + j);
            }
        }
    }

    cv::Mat Y = cv::Mat::zeros(n + 1, 1, CV_64FC1);
    for (int i = 0; i < n + 1; i++) {
        for (int k = 0; k < N; k++) {
            Y.at<double>(i, 0) = Y.at<double>(i, 0) +
                                    std::pow(pts[k].x, i) * pts[k].y;
        }
    }

    cv::Mat A = cv::Mat::zeros(n + 1, 1, CV_64FC1);
    cv::solve(X, Y, A, cv::DECOMP_LU);
    return A;
}

static cv::Mat fitCurve2(std::vector<cv::Point>& pts) {
	double sx=0, sx2=0, sx3=0, sx4=0, sy=0, sxy=0, sx2y=0;
    int iLength = pts.size();
	for (int ii= 0; ii<iLength; ii++) {
		double y = double(pts[ii].y);
		double x = double(pts[ii].x);
		double x2 = x*x;
		sx += x;
		sy += y;
		sx2 += x2;
		sx3 += x * x2;
		sx4 += 1.0 * x2 * x2;
		sxy += x * y;
		sx2y += x2 * y;
	}

	double dValMat[9] = {sx/iLength, sx2/iLength, sy/iLength,
		sx2/sx, sx3/sx, sxy/sx,	sx3/sx2, sx4/sx2, sx2y/sx2};
	double subm = dValMat[0] - dValMat[3] + 1e-10;
	double subn = dValMat[0] - dValMat[6] + 1e-10;
	double dVal2Mat[4] = { (dValMat[1]-dValMat[4])/subm, (dValMat[2]-dValMat[5])/subm,
		(dValMat[1]-dValMat[7])/subn, (dValMat[2]-dValMat[8])/subn };

	double fa = (dVal2Mat[1] - dVal2Mat[3]) / (dVal2Mat[0] - dVal2Mat[2] + 1e-10);
	double fb = dVal2Mat[1] - dVal2Mat[0] * fa;
	double fc = dValMat[2] - dValMat[1] * fa - dValMat[0] * fb;
    cv::Mat m(3, 1, CV_64FC1);
    m.at<double>(2, 0) = fa;
    m.at<double>(1, 0) = fb;
    m.at<double>(0, 0) = fc;
    return m;
}

static cv::RotatedRect scaleRotatedRect(cv::RotatedRect rr, float scaleX, float scaleY) {
	int dx = int(float(rr.size.width) * scaleX);
	int dy = int(float(rr.size.height) * scaleY);
	rr.size.width += dx * 2;
	rr.size.height += dy * 2;
	return cv::RotatedRect(rr.center, rr.size, rr.angle);
}

static void extractRotatedRect(cv::RotatedRect rr, std::vector<cv::Point> &pts, cv::Size &size) {
    cv::Rect r = rr.boundingRect();
    int w = r.width, h = r.height;
    cv::Point2f p[4];
    rr.points(p);

    double deltaA = abs(double(w-rr.size.width)) + abs(double(h-rr.size.height));
    double deltaB = abs(double(w-rr.size.height)) + abs(double(h-rr.size.width));
    if (deltaA > deltaB) {
        size = cv::Size(rr.size.height, rr.size.width);
        pts.push_back(p[2]);
        pts.push_back(p[3]);
        pts.push_back(p[0]);
        pts.push_back(p[1]);
    } else {
        size = cv::Size(rr.size.width, rr.size.height);
        pts.push_back(p[1]);
        pts.push_back(p[2]);
        pts.push_back(p[3]);
        pts.push_back(p[0]);
    }
}

static cv::Mat cropRotatedRect(cv::Mat &img, std::vector<cv::Point> pts, cv::Size size) {
    std::vector<cv::Point2f> src {
        pts[0],
        pts[1],
        pts[2],
        pts[3]
    };
    std::vector<cv::Point2f> dst {
        cv::Point(0, 0),
        cv::Point(size.width, 0),
        cv::Point(size.width, size.height),
        cv::Point(0, size.height)
    };

    cv::Mat m = cv::getPerspectiveTransform(src, dst);
    cv::Mat imgt;
    cv::warpPerspective(img, imgt, m, size, cv::INTER_AREA);
    
    return imgt;
}

static cv::Mat rotateImage(cv::Mat &src, cv::Point2f center, float angle) {
    double radian = CV_PI*angle/180;
    double ow = src.cols, oh = src.rows;
    double sinv = std::abs(std::sin(radian));
    double cosv = std::abs(std::cos(radian));
    double nh = ow*sinv + oh*cosv;
    double nw = oh*sinv + ow*cosv;
    cv::Mat am = cv::getRotationMatrix2D(center, angle, 1.0);
    double v02 = am.at<double>(0, 2) + (nw-ow)/2.0;
    double v12 = am.at<double>(1, 2) + (nh-oh)/2.0;
    am.at<double>(0, 2) = v02;
    am.at<double>(1, 2) = v12;

    cv::Mat dst;
    cv::warpAffine(src, dst, am, cv::Size(nw, nh));
    return dst;
}

static void drawRotatedRect(cv::Mat &img, cv::RotatedRect rr, cv::Scalar color, int width) {
    cv::Point2f pts[4];
    rr.points(pts);
    for (int j = 0; j < 4; j++) {
        line(img, pts[j], pts[(j + 1) % 4], color, width, cv::LINE_AA);
    }
}

template<typename T>
T revertPoint(T p, cv::Mat rmat) {
    if (rmat.empty()) {
        return p;
    }

    T q;
    q.x = round(double(p.x)*rmat.at<double>(0, 0) + double(p.y)*rmat.at<double>(0, 1) + rmat.at<double>(0, 2));
    q.y = round(double(p.x)*rmat.at<double>(1, 0) + double(p.y)*rmat.at<double>(1, 1) + rmat.at<double>(1, 2));
    return q;
}

template <class T>
T clipVal(T val, T minVal, T maxVal) {
    // fprintf(stderr, "val=%d, minVal=%d, maxVal=%d\n", val, minVal, maxVal);
    val = std::max(val, minVal);
    val = std::min(val, maxVal);
    return val;
}

static int stoi2(std::string s) {
    int v = 0;
    for (int i = 0; i < s.size(); i++) {
        if (s[s.size()-1-i] == '1') {
            v += 1 << i;
        }
    }
    return v;
}

template<class T>
void copyVec(std::vector<T> &src, int startIdx, int endIdx, std::vector<T> &dst) {
    for (int i = startIdx; i <= endIdx; i++) {
        dst.push_back(src[i]);
    }
}

template<class T>
T accumulateVec(std::vector<T> &src, int startIdx, int endIdx) {
    T sum = 0;
    endIdx = std::min(int(src.size()-1), endIdx);
    for (int i = startIdx; i <= endIdx; i++) {
        sum += src[i];
    }
    return sum;
}

static int roundN(int v, int n) {
    return (v/n)*n;
}

static std::string codeStr(int code) {
    static const std::string PKC[] = {"", "A", "2", "3", "4", "5", 
    "6", "7", "8", "9", "10", "J", "Q", "K"};
    if (code == 0) {
        return "";
    }

    std::string str;
    if (code == 0x5e) {
        str = "XW";
    } else if (code == 0x5f) {
        str = "DW";
    } else if (code == 0x6e) {
        str = "GG";
    } else {
        std::string a, b;
        switch (code >> 4) {
        case 1:
            a = "H";
            break;
        case 2:
            a = "T";
            break;
        case 3:
            a = "M";
            break;
        case 4:
            a = "F";
            break;
        default:
            break;
        }
        b = PKC[code & 0xf];
        str = a+b;
    }
    return str;
}

#endif