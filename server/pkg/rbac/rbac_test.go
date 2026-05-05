package rbac

import "testing"

func TestBaselineInspector(t *testing.T) {
	e := Baseline()
	if !e.Allow(RoleInspector, "inspection", "create") {
		t.Fatal("inspector 应能创建查验")
	}
	if e.Allow(RoleInspector, "user", "read") {
		t.Fatal("inspector 不应能读其它用户")
	}
}

func TestBaselineSupervisor(t *testing.T) {
	e := Baseline()
	if !e.Allow(RoleSupervisor, "user", "read") {
		t.Fatal("supervisor 应能读用户")
	}
	if e.Allow(RoleSupervisor, "review", "decide") {
		t.Fatal("supervisor 不能直接做复核决定（要 reviewer）")
	}
}

func TestBaselineReviewer(t *testing.T) {
	e := Baseline()
	if !e.Allow(RoleReviewer, "review", "decide") {
		t.Fatal("reviewer 应能做复核决定")
	}
	if e.Allow(RoleReviewer, "asset", "upload_self") {
		t.Fatal("reviewer 不应能上传查验资产")
	}
}

func TestAdminAlways(t *testing.T) {
	e := NewInMemory() // 空策略
	if !e.Allow(RoleAdmin, "anything", "anywhere") {
		t.Fatal("admin 必须全通行（即便策略集是空）")
	}
}

func TestUnknownRole(t *testing.T) {
	e := Baseline()
	if e.Allow("guest", "inspection", "create") {
		t.Fatal("未知角色应拒绝")
	}
}
