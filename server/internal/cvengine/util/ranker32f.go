package util

import (
	"sort"
)

// Ranker32f is a helper type for the rank function.
type Ranker32f struct {
	DD []float32 // Data to be ranked.
	II []int     // A list of indexes into f that reflects rank order after sorting.
}

// Ranker32f satisfies the sort.Interface without mutating the reference slice, f.
func (r Ranker32f) Len() int           { return len(r.DD) }
func (r Ranker32f) Less(i, j int) bool { return r.DD[r.II[i]] < r.DD[r.II[j]] }
func (r Ranker32f) Swap(i, j int)      { r.II[i], r.II[j] = r.II[j], r.II[i] }

// rank returns the sample ranks of the values in a vector. Ties (i.e.,
// equal values) are handled by ranking them as the mean rank of coequals.
func (r *Ranker32f) Rank(f []float32, reverse bool) []float32 {
	if len(f) == 0 {
		return nil
	}

	r.DD = f
	if len(r.II) < len(f) {
		r.II = make([]int, len(f))
	} else {
		r.II = r.II[:len(f)]
	}

	for i := range r.II {
		r.II[i] = i
	}

	if reverse {
		sort.Sort(sort.Reverse(r))
	} else {
		sort.Sort(r)
	}

	rl := make([]float32, len(f))
	for i, j := range r.II {
		rl[j] = float32(i)
	}

	var (
		prev = r.DD[r.II[0]]

		first int
		same  bool
	)
	for i, j := range r.II[1:] {
		if r.DD[j] == prev {
			if !same {
				first = i
			}
			same = true
		} else if same {
			v := (rl[r.II[i]] + rl[r.II[first]]) / 2
			for k := first; k <= i; k++ {
				rl[r.II[k]] = v
			}
			same = false
		}
		prev = r.DD[j]
	}

	return rl
}
