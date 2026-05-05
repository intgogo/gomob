package gocv

/*
#include <stdlib.h>
#include <memory.h>
#include "ssc.h"
*/
import "C"
import (
	"runtime"
	"sync"
)

type SetOut struct {
	Err int
}

type SetIn struct {
	Dim        int
	Embeddings []float32
	OutChan    chan SetOut
}

type SearchOut struct {
	Ids []int
	Dis []float32
}

type SearchIn struct {
	TopK       int
	NProbs     int
	Num        int
	Embeddings []float32
	OutChan    chan SearchOut
}

type SSC struct {
	p      C.Index
	mutex  sync.Mutex
	inChan chan interface{}
}

func CreateSSC(gpuId, clusters, dim int, xb []float32) *SSC {
	c := make(chan *SSC)

	go func() {
		runtime.LockOSThread()

		ssc := SSC{}
		ssc.inChan = make(chan interface{})

		xx := toCFloatVector(xb)
		ssc.p = C.SSC_CreateIndex(C.int(gpuId), C.int(clusters), C.int(dim), C.int(len(xb)/dim), xx)
		c <- &ssc

		for {
			inData := <-ssc.inChan
			data, ok := inData.(SearchIn)
			if ok {
				var ids C.IntVector
				var dis C.FloatVector

				xq := toCFloatVector(data.Embeddings)
				C.SSC_SearchIndex(ssc.p, C.int(data.Num), xq, C.int(data.TopK), C.int(data.NProbs), &dis, &ids)

				out := SearchOut{
					Ids: toGoInts(ids),
					Dis: toGoFloats(dis),
				}
				C.IntVector_Release(ids)
				C.FloatVector_Release(dis)

				data.OutChan <- out
			} else {
				data, ok := inData.(SetIn)
				if !ok {
					panic("SSC data in struct error")
				}

				xq := toCFloatVector(data.Embeddings)
				C.SSC_Rebuild(ssc.p, C.int(data.Dim), C.int(len(xb)/data.Dim), xq)

				out := SetOut{
					Err: 0,
				}

				data.OutChan <- out
			}

		}
	}()

	ssc := <-c
	return ssc
}

func RunSSC(ssc *SSC, embeddings []float32, num, topk, nprobs int) ([]int, []float32) {
	data := SearchIn{
		TopK:       topk,
		NProbs:     nprobs,
		Num:        num,
		Embeddings: embeddings,
		OutChan:    make(chan SearchOut),
	}
	ssc.inChan <- data
	out := <-data.OutChan
	return out.Ids, out.Dis
}

func UpdateSSC(ssc *SSC, dim int, embeddings []float32) int {
	data := SetIn{
		Dim:        dim,
		Embeddings: embeddings,
		OutChan:    make(chan SetOut),
	}
	ssc.inChan <- data
	out := <-data.OutChan
	return out.Err
}

func SSCDist(aa, bb []float32) float32 {
	xa := toCFloatVector(aa)
	xb := toCFloatVector(bb)
	dist := float32(C.SSC_L2Dist(xa, xb))
	return dist
}
