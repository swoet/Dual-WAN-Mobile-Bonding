package planner

import "testing"

func TestBudgetPlanPrefersHealthyLowLatencyBudget(t *testing.T) {
	plan, err := BudgetPlan(1_000, []PathTelemetry{
		{Name: "wifi", BudgetBytes: 10_000, UsedBytes: 1_000, LatencyMillis: 20, Healthy: true},
		{Name: "mobile", BudgetBytes: 2_000, UsedBytes: 500, LatencyMillis: 80, Healthy: true},
		{Name: "offline", BudgetBytes: 10_000, UsedBytes: 0, LatencyMillis: 5, Healthy: false},
	})
	if err != nil {
		t.Fatalf("BudgetPlan returned error: %v", err)
	}
	if len(plan) != 2 {
		t.Fatalf("expected 2 eligible paths, got %d", len(plan))
	}
	if plan[0].Name != "wifi" || plan[0].Bytes <= plan[1].Bytes {
		t.Fatalf("expected wifi to receive the larger allocation: %#v", plan)
	}
}

func TestBudgetPlanCapsToRemainingBudget(t *testing.T) {
	plan, err := BudgetPlan(5_000, []PathTelemetry{
		{Name: "wifi", BudgetBytes: 500, UsedBytes: 100, LatencyMillis: 10, Healthy: true},
	})
	if err != nil {
		t.Fatalf("BudgetPlan returned error: %v", err)
	}
	if plan[0].Bytes != 400 {
		t.Fatalf("expected allocation to cap at remaining budget, got %d", plan[0].Bytes)
	}
}
