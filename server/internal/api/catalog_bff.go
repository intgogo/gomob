// catalog BFF：把 /v1/catalog/* 反代到 vehicle-catalog 服务。
//
// 通路决策见 docs/architecture/server/00-server-overview.md §6.app：参考库三件套
// HTTP 入口由 api 服务承担（App 只感知 /v1/catalog/*），api 内部反代到对应后端。
//
// 仅放行**只读**前缀。写路径走 admin BFF，不经 api。
package api

import (
	"net/http"
	"net/http/httputil"
	"net/url"
)

// MountCatalogBFF 在 api 的 mux 上挂 /v1/catalog/* 反代到 catalogTarget（如 http://127.0.0.1:18059）。
//
// 当 vinrefTarget 非空时，优先把 /v1/catalog/vehicles/{vmid}/vin-refs/...（更具体的路径）反代到 vinref。
// Go 1.22 ServeMux 多模式匹配时优先选最具体的，所以两个 Handle 调用顺序无关。
func MountCatalogBFF(mux *http.ServeMux, catalogTarget, vinrefTarget string) error {
	if vinrefTarget != "" {
		vu, err := url.Parse(vinrefTarget)
		if err != nil {
			return err
		}
		vp := httputil.NewSingleHostReverseProxy(vu)
		mux.Handle("/v1/catalog/vehicles/{vmid}/vin-refs/", vp)
		mux.Handle("/v1/catalog/vehicles/{vmid}/vin-refs", vp)
	}
	if catalogTarget == "" {
		return nil
	}
	u, err := url.Parse(catalogTarget)
	if err != nil {
		return err
	}
	proxy := httputil.NewSingleHostReverseProxy(u)
	// /v1/catalog/ 前缀匹配（Go 1.22 ServeMux 末尾斜杠 = 前缀）
	mux.Handle("/v1/catalog/", proxy)
	return nil
}
