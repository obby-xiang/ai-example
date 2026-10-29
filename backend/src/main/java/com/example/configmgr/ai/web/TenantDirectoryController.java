package com.example.configmgr.ai.web;

import com.example.configmgr.ai.tenant.TenantUserDirectory;
import com.example.configmgr.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 租户/用户选项（前端身份切换用）。
 *
 * <p>
 * 本接口<b>不</b>要求身份头（用户还没选身份，必须能先读到选项），因此不在
 * {@code TenantIdentityFilter} 的边界内。它只返回预置目录，不携带任何对话数据。
 */
@RestController
@RequestMapping("/api/ai")
public class TenantDirectoryController {

	@GetMapping("/tenants")
	public ApiResponse<List<TenantUserDirectory.TenantView>> tenants() {
		return ApiResponse.ok(TenantUserDirectory.tenants());
	}

}
