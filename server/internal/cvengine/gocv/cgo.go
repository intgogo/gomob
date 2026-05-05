package gocv

/*
#cgo CXXFLAGS: --std=c++11
#cgo CPPFLAGS: -I../ccv/include -I/user/local/onnxruntime/include
// #cgo LDFLAGS: -lccv_atlas
// #cgo LDFLAGS: -lccv_lynxi -L/usr/local/lynxi/sdk/lib -lLYNCHIPSDKCLIENT -lLYNCHIPSDKCLIENTCOMM
#cgo LDFLAGS: -lccv -L/usr/local/onnxruntime/lib -lonnxruntime -lengine_crypt
#cgo !windows LDFLAGS: -L/usr/local/lib  -lopencv_world
#cgo windows CPPFLAGS: -I../include
#cgo windows LDFLAGS: -L../lib -lopencv_world430
*/
import "C"
