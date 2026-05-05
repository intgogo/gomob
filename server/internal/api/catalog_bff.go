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

// MountCatalogBFF 在 api 的 mux 上挂 /v1/catalog/* 反代到三个后端：
//
//   - vinref：/v1/catalog/vehicles/{vmid}/vin-refs/...
//   - shaperef：/v1/catalog/vehicles/{vmid}/shape{,/url} + /v1/catalog/vehicles/{vmid}/shapes/...
//   - catalog：其余 /v1/catalog/...（更通用的兜底）
//
// Go 1.22 ServeMux 多模式匹配时优先选最具体的，所以三组 Handle 调用顺序无关。
func MountCatalogBFF(mux *http.ServeMux, catalogTarget, vinrefTarget, shaperefTarget string) error {
	if vinrefTarget != "" {
		vu, err := url.Parse(vinrefTarget)
		if err != nil {
			return err
		}
		vp := httputil.NewSingleHostReverseProxy(vu)
		mux.Handle("/v1/catalog/vehicles/{vmid}/vin-refs/", vp)
		mux.Handle("/v1/catalog/vehicles/{vmid}/vin-refs", vp)
	}
	if shaperefTarget != "" {
		su, err := url.Parse(shaperefTarget)
		if err != nil {
			return err
		}
		sp := httputil.NewSingleHostReverseProxy(su)
		// 单数 shape：active 摘要 + 子路径 /url
		mux.Handle("/v1/catalog/vehicles/{vmid}/shape", sp)
		mux.Handle("/v1/catalog/vehicles/{vmid}/shape/", sp)
		// 复数 shapes：历史版本详情
		mux.Handle("/v1/catalog/vehicles/{vmid}/shapes/", sp)
		mux.Handle("/v1/catalog/vehicles/{vmid}/shapes", sp)
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
