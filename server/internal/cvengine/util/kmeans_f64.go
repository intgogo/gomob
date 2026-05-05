package util

import (
	"math"
	"math/rand"
)

func distance(p1, p2 float64) float64 {
	return math.Abs(p1 - p2)
}

func CalKMeansF64(data []float64, k int) []float64 {
	n := len(data)

	clusters := make([]float64, k)
	for i := range clusters {
		clusters[i] = rand.Float64() * 10
	}

	for {
		// Assign data points to clusters
		assignments := make([]int, n)
		for i, point := range data {
			minDist := math.MaxFloat64
			clusterIdx := 0
			for j, cluster := range clusters {
				dist := distance(point, cluster)
				if dist < minDist {
					minDist = dist
					clusterIdx = j
				}
			}
			assignments[i] = clusterIdx
		}

		// Update cluster centers
		newClusters := make([]float64, k)
		counts := make([]int, k)

		for i, point := range data {
			clusterIdx := assignments[i]
			counts[clusterIdx]++
			newClusters[clusterIdx] += point
		}

		changed := false
		for i := range newClusters {
			newClusters[i] /= float64(counts[i])
			if newClusters[i] != clusters[i] {
				changed = true
			}
		}

		if !changed {
			break
		}

		clusters = newClusters
	}

	return clusters
}

// func main() {
// 	data := []float64{1, 2, 3, 8, 9, 10}
// 	k := 2
// 	result := CalKMeansW1(data, k)

// 	fmt.Println("Cluster Centers:")
// 	fmt.Println(result)
// }
