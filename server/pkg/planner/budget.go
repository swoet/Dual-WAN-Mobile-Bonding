package planner

import (
	"errors"
	"math"
)

// PathTelemetry describes the current state of one network path.
type PathTelemetry struct {
	Name          string
	BudgetBytes   int64
	UsedBytes     int64
	LatencyMillis float64
	Healthy       bool
}

// PathAllocation is the recommended byte allowance for a path.
type PathAllocation struct {
	Name              string
	Bytes             int64
	Percent           float64
	RemainingBudget   int64
	LatencyPenalty    float64
	ExhaustionWarning bool
}

// BudgetPlan allocates requested traffic across healthy paths while preserving
// remaining user data bundles and preferring lower latency links.
func BudgetPlan(requestedBytes int64, paths []PathTelemetry) ([]PathAllocation, error) {
	if requestedBytes <= 0 {
		return nil, errors.New("requestedBytes must be greater than zero")
	}

	type weightedPath struct {
		path      PathTelemetry
		remaining int64
		weight    float64
	}

	eligible := []weightedPath{}
	for _, path := range paths {
		if !path.Healthy {
			continue
		}

		remaining := path.BudgetBytes - path.UsedBytes
		if remaining <= 0 {
			continue
		}

		latency := math.Max(path.LatencyMillis, 1)
		weight := float64(remaining) / latency
		eligible = append(eligible, weightedPath{path: path, remaining: remaining, weight: weight})
	}

	if len(eligible) == 0 {
		return nil, errors.New("no healthy paths with remaining budget")
	}

	totalWeight := 0.0
	totalRemaining := int64(0)
	for _, item := range eligible {
		totalWeight += item.weight
		totalRemaining += item.remaining
	}

	if requestedBytes > totalRemaining {
		requestedBytes = totalRemaining
	}

	allocations := make([]PathAllocation, 0, len(eligible))
	allocated := int64(0)
	for index, item := range eligible {
		bytes := int64(math.Floor((item.weight / totalWeight) * float64(requestedBytes)))
		if bytes > item.remaining {
			bytes = item.remaining
		}
		if index == len(eligible)-1 {
			bytes = requestedBytes - allocated
			if bytes > item.remaining {
				bytes = item.remaining
			}
		}

		allocated += bytes
		remainingAfter := item.remaining - bytes
		allocations = append(allocations, PathAllocation{
			Name:              item.path.Name,
			Bytes:             bytes,
			Percent:           roundPercent(float64(bytes) / float64(requestedBytes) * 100),
			RemainingBudget:   remainingAfter,
			LatencyPenalty:    roundPercent(item.path.LatencyMillis / 100),
			ExhaustionWarning: remainingAfter < item.path.BudgetBytes/10,
		})
	}

	return allocations, nil
}

func roundPercent(value float64) float64 {
	return math.Round(value*100) / 100
}
