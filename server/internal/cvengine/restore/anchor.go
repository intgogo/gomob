package restore

import (
	"errors"
	"image"
	"math"
	"sort"
	"strings"

	"io.gomob/server/internal/cvengine/gocv"
)

const (
	vinCharacterCount           = 17
	canonicalProbeTargetPitchPx = 64.0
	charConfidenceMin           = 0.10
	charNMSIoU                  = 0.40
	charCandidateMax            = 24
	anchorScaleDeltaMax         = 0.15
	anchorNormalizedRMSMax      = 0.13
	anchorMeanScoreMin          = 0.80
	anchorAngleAbsMaxDeg        = 3.0
)

// ErrTextAnchorUnreliable 表示逐字符观测不足以建立唯一的 17 字符格架。
var ErrTextAnchorUnreliable = errors.New("VIN 文字格架不可靠")

// VinCharacterClasses 返回 vins0.onnx 的 33 个合法 VIN 字符类。
func VinCharacterClasses() []string {
	return []string{
		"0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
		"A", "B", "C", "D", "E", "F", "G", "H",
		"J", "K", "L", "M", "N", "P", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
	}
}

type yoloRunner interface {
	RunYolo(
		tag string,
		img gocv.Mat,
		confThreshold, nmsThreshold, rudeScale float32,
	) ([]image.Rectangle, []int, []float32, error)
}

type characterObservation struct {
	X, Y   float64
	Width  float64
	Height float64
	Score  float64
	Class  int
}

type textAnchor struct {
	CenterX, CenterY       float64
	DirectionX, DirectionY float64
	PitchPx                float64
	RMSPx                  float64
	MeanScore              float64
	MedianHeightPx         float64
	CandidateCount         int
	Count                  int
	Text                   string
	Selected               []characterObservation
}

func (a textAnchor) AngleDeg() float64 {
	return math.Atan2(a.DirectionY, a.DirectionX) * 180.0 / math.Pi
}

func detectTextAnchor(runner yoloRunner, tag string, probeBGR gocv.Mat) (textAnchor, error) {
	rgb := gocv.NewMat()
	defer func() { _ = rgb.Release() }()
	gocv.CvtColor(probeBGR, &rgb, gocv.ColorBGRToRGB)
	boxes, classes, scores, err := runner.RunYolo(
		tag, rgb, charConfidenceMin, charNMSIoU, 0,
	)
	if err != nil {
		return textAnchor{}, err
	}

	observations := make([]characterObservation, 0, len(boxes))
	for i, box := range boxes {
		if i >= len(scores) || box.Dx() <= 5 || box.Dy() <= 15 || box.Dx() >= 120 || box.Dy() >= 180 {
			continue
		}
		x := float64(box.Min.X+box.Max.X) * 0.5
		y := float64(box.Min.Y+box.Max.Y) * 0.5
		if x <= -20 || x >= float64(probeBGR.Cols()+20) || y <= 25 || y >= float64(probeBGR.Rows()-25) {
			continue
		}
		classID := -1
		if i < len(classes) {
			classID = classes[i]
		}
		observations = append(observations, characterObservation{
			X: x, Y: y, Width: float64(box.Dx()), Height: float64(box.Dy()),
			Score: float64(scores[i]), Class: classID,
		})
	}
	return fitTextAnchor(observations)
}

func fitTextAnchor(observations []characterObservation) (textAnchor, error) {
	if len(observations) < vinCharacterCount {
		return textAnchor{CandidateCount: len(observations)}, ErrTextAnchorUnreliable
	}
	sort.SliceStable(observations, func(i, j int) bool {
		if observations[i].X == observations[j].X {
			return observations[i].Score > observations[j].Score
		}
		return observations[i].X < observations[j].X
	})
	if len(observations) > charCandidateMax {
		sort.SliceStable(observations, func(i, j int) bool {
			return observations[i].Score > observations[j].Score
		})
		observations = append([]characterObservation(nil), observations[:charCandidateMax]...)
		sort.SliceStable(observations, func(i, j int) bool { return observations[i].X < observations[j].X })
	}

	best := anchorSubsetFit{Objective: math.Inf(1)}
	chosen := make([]int, vinCharacterCount)
	visitAnchorSubsets(observations, chosen, 0, 0, &best)
	if len(best.Indices) != vinCharacterCount {
		return textAnchor{}, ErrTextAnchorUnreliable
	}

	selected := make([]characterObservation, vinCharacterCount)
	for i, index := range best.Indices {
		selected[i] = observations[index]
	}
	anchor := robustGridFit(selected)
	anchor.CandidateCount = len(observations)
	anchor.Count = len(selected)
	if !anchorReliable(anchor) {
		return anchor, ErrTextAnchorUnreliable
	}
	return anchor, nil
}

type anchorSubsetFit struct {
	Objective float64
	Indices   []int
}

func visitAnchorSubsets(
	observations []characterObservation,
	chosen []int,
	depth, start int,
	best *anchorSubsetFit,
) {
	remaining := vinCharacterCount - depth
	for i := start; i <= len(observations)-remaining; i++ {
		chosen[depth] = i
		if depth+1 < vinCharacterCount {
			visitAnchorSubsets(observations, chosen, depth+1, i+1, best)
			continue
		}
		objective, ok := quickGridObjective(observations, chosen)
		if ok && objective < best.Objective {
			best.Objective = objective
			best.Indices = append(best.Indices[:0], chosen...)
		}
	}
}

func quickGridObjective(observations []characterObservation, indices []int) (float64, bool) {
	const sumK2 = 408.0 // Σ(-8..8)^2
	var cx, cy, dx, dy, scoreSum float64
	for slot, index := range indices {
		observation := observations[index]
		k := float64(slot - vinCharacterCount/2)
		cx += observation.X
		cy += observation.Y
		dx += k * observation.X
		dy += k * observation.Y
		scoreSum += observation.Score
	}
	cx /= vinCharacterCount
	cy /= vinCharacterCount
	dx /= sumK2
	dy /= sumK2
	pitch := math.Hypot(dx, dy)
	minPitch := canonicalProbeTargetPitchPx / (1.0 + anchorScaleDeltaMax)
	maxPitch := canonicalProbeTargetPitchPx / (1.0 - anchorScaleDeltaMax)
	if pitch < minPitch || pitch > maxPitch {
		return 0, false
	}

	var residual2, gapSum, gap2 float64
	for slot, index := range indices {
		observation := observations[index]
		k := float64(slot - vinCharacterCount/2)
		residual2 += square(observation.X-(cx+k*dx)) + square(observation.Y-(cy+k*dy))
		if slot > 0 {
			prev := observations[indices[slot-1]]
			gap := math.Hypot(observation.X-prev.X, observation.Y-prev.Y)
			gapSum += gap
			gap2 += gap * gap
		}
	}
	rms := math.Sqrt(residual2 / vinCharacterCount)
	gapMean := gapSum / float64(vinCharacterCount-1)
	gapVariance := gap2/float64(vinCharacterCount-1) - gapMean*gapMean
	if gapVariance < 0 {
		gapVariance = 0
	}
	gapCV := math.Sqrt(gapVariance) / math.Max(gapMean, 1e-9)
	meanScore := scoreSum / vinCharacterCount
	return rms/pitch + 0.08*gapCV + 0.04*(1.0-meanScore), true
}

func robustGridFit(observations []characterObservation) textAnchor {
	weights := make([]float64, len(observations))
	for i, observation := range observations {
		weights[i] = math.Max(observation.Score, charConfidenceMin)
	}

	var cx, cy, dx, dy float64
	for iteration := 0; iteration < 5; iteration++ {
		cx, dx = weightedLine(observations, weights, func(o characterObservation) float64 { return o.X })
		cy, dy = weightedLine(observations, weights, func(o characterObservation) float64 { return o.Y })
		pitch := math.Hypot(dx, dy)
		delta := math.Max(2.0, pitch*0.12)
		for i, observation := range observations {
			k := float64(i - vinCharacterCount/2)
			residual := math.Hypot(observation.X-(cx+k*dx), observation.Y-(cy+k*dy))
			robust := 1.0
			if residual > delta {
				robust = delta / residual
			}
			weights[i] = math.Max(observation.Score, charConfidenceMin) * robust
		}
	}

	pitch := math.Hypot(dx, dy)
	var residual2, scoreSum float64
	heights := make([]float64, len(observations))
	for i, observation := range observations {
		k := float64(i - vinCharacterCount/2)
		residual2 += square(observation.X-(cx+k*dx)) + square(observation.Y-(cy+k*dy))
		scoreSum += observation.Score
		heights[i] = observation.Height
	}
	sort.Float64s(heights)
	classes := VinCharacterClasses()
	var text strings.Builder
	text.Grow(len(observations))
	for _, observation := range observations {
		if observation.Class < 0 || observation.Class >= len(classes) {
			text.WriteByte('?')
			continue
		}
		text.WriteString(classes[observation.Class])
	}
	return textAnchor{
		CenterX: cx, CenterY: cy,
		DirectionX: dx / pitch, DirectionY: dy / pitch,
		PitchPx:        pitch,
		RMSPx:          math.Sqrt(residual2 / float64(len(observations))),
		MeanScore:      scoreSum / float64(len(observations)),
		MedianHeightPx: heights[len(heights)/2],
		Text:           text.String(),
		Selected:       append([]characterObservation(nil), observations...),
	}
}

func weightedLine(
	observations []characterObservation,
	weights []float64,
	value func(characterObservation) float64,
) (intercept, slope float64) {
	var sw, sk, sk2, sv, skv float64
	for i, observation := range observations {
		weight := weights[i]
		k := float64(i - vinCharacterCount/2)
		v := value(observation)
		sw += weight
		sk += weight * k
		sk2 += weight * k * k
		sv += weight * v
		skv += weight * k * v
	}
	denominator := sw*sk2 - sk*sk
	if math.Abs(denominator) < 1e-9 {
		return 0, 0
	}
	intercept = (sv*sk2 - sk*skv) / denominator
	slope = (sw*skv - sk*sv) / denominator
	return intercept, slope
}

func anchorReliable(anchor textAnchor) bool {
	if anchor.Count != vinCharacterCount || !isFinite(anchor.PitchPx) || anchor.PitchPx <= 0 {
		return false
	}
	scale := canonicalProbeTargetPitchPx / anchor.PitchPx
	return math.Abs(scale-1.0) <= anchorScaleDeltaMax &&
		anchor.RMSPx/anchor.PitchPx <= anchorNormalizedRMSMax &&
		anchor.MeanScore >= anchorMeanScoreMin &&
		math.Abs(anchor.AngleDeg()) <= anchorAngleAbsMaxDeg
}

func square(value float64) float64 { return value * value }
