package com.example.configmgr.ai.tenant;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.common.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * AI 对话请求的<b>身份边界</b>：读取 {@code X-Tenant-Id}/{@code X-User-Id}，校验后建立
 * {@link RequestContextUtils} 上下文，请求结束（{@code finally}）清理。
 *
 * <h2>纳入边界的范围</h2>
 * 只覆盖<b>对话数据面</b>：{@code /api/ai} 与 {@code /api/ai/} 下除下列之外的全部入口
 * （chat/confirm/events/runs/history/session/context/pending/feedback/cancel/表单附件，
 * 以及 startup-resume 的读取与手工扫描）。<b>默认受保护</b>，排除面只有两类：
 * <ul>
 * <li><b>精确路径</b>：{@code health}、{@code tools}、{@code tenants}。其<b>子路径不排除</b> ——
 * 新增数据接口不会静默逃逸；{@code /api/ai} 本身仍在边界内（缺头 = 400）；</li>
 * <li><b>模型配置子树</b>：{@code providers}/{@code models}/{@code settings} 及其子资源
 * （{@code AiConfigController} 的全部映射）。</li>
 * </ul>
 * 健康面/工具清单/身份目录不纳入，是因为它们不是对话数据，且前端必须在选身份之前就能读到目录。
 * {@code startup-resume}（读取报告 / 手工扫描）<b>在边界内</b>：两者都只处理<b>当前身份</b>的轮次，
 * 缺头即 400；应用启动时的自动扫描不经过 HTTP，仍在无身份线程上按每轮记录身份推进。
 *
 * <h2>为什么拒绝而不是补默认身份</h2>
 * "取不到就算 tenant=0"会让一次漏带请求头的调用读写到另一个身份的数据；这类跨身份错误
 * 事后只能靠猜。因此缺头、非正数、系统账号（{@code userId=-1} 仅系统任务）与目录外组合
 * 一律 400，且不进入业务链。
 *
 * <h2>线程生命周期</h2>
 * 上下文只在处理本请求的线程上存在；{@code finally} 关闭作用域（无旧值即清空），
 * 池化线程复用时不会带着上一位调用者的身份。
 * 异步执行体的身份传播由 TTL 装饰器在提交时刻捕获（见 {@link RequestContextTaskDecorator}）。
 */
public class TenantIdentityFilter extends OncePerRequestFilter {

	public static final String HEADER_TENANT_ID = "X-Tenant-Id";

	public static final String HEADER_USER_ID = "X-User-Id";

	private static final String AI_ROOT = "/api/ai";

	private static final String AI_PREFIX = AI_ROOT + "/";

	/**
	 * 不纳入租户边界的<b>精确路径</b>（不做子路径匹配）：健康面、静态工具清单、身份目录。
	 *
	 * <p>
	 * 只认精确路径是有意的：若按前缀放行，任何新增的 {@code /api/ai/tools/...} 之类
	 * <b>数据</b>接口都会静默逃出身份边界。子路径默认仍受保护，新增路由必须显式分类。
	 *
	 * <p>
	 * {@code startup-resume}（读取报告 / 手工扫描）<b>不在这里</b>：它的语义是"当前身份的轮次"，
	 * 不是"全租户运维面"；身份来自请求头。
	 */
	private static final Set<String> EXCLUDED_EXACT = Set.of("/api/ai/health", "/api/ai/tools",
			"/api/ai/tenants");

	/**
	 * 模型配置命名空间（{@code AiConfigController}）：整个子树不做租户隔离
	 * （provider/model/setting 的读写与其子资源）。
	 */
	private static final List<String> CONFIG_SUBTREES = List.of("/api/ai/providers", "/api/ai/models",
			"/api/ai/settings");

	private final ObjectMapper objectMapper;

	public TenantIdentityFilter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// CORS 预检不携带真实头值（只有 Access-Control-Request-Headers），
		// 必须放行给 CorsFilter 处理，否则所有跨域 AI 调用在预检阶段就 400。
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		String path = request.getRequestURI();
		if (path == null) {
			return true;
		}
		String contextPath = request.getContextPath();
		if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) {
			path = path.substring(contextPath.length());
		}
		// /api/ai 本身也明确纳入边界（缺头 = 400），不静默跳过。
		if (!path.equals(AI_ROOT) && !path.startsWith(AI_PREFIX)) {
			return true;
		}
		String normalized = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
		if (EXCLUDED_EXACT.contains(normalized)) {
			return true;
		}
		for (String configSubtree : CONFIG_SUBTREES) {
			if (normalized.equals(configSubtree) || normalized.startsWith(configSubtree + "/")) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String rawTenant = request.getHeader(HEADER_TENANT_ID);
		String rawUser = request.getHeader(HEADER_USER_ID);
		if (!StringUtils.hasText(rawTenant) || !StringUtils.hasText(rawUser)) {
			reject(response, ErrorCodes.TENANT_IDENTITY_REQUIRED, "缺少身份请求头 X-Tenant-Id / X-User-Id");
			return;
		}
		long tenantId;
		long userId;
		try {
			tenantId = Long.parseLong(rawTenant.trim());
			userId = Long.parseLong(rawUser.trim());
		}
		catch (NumberFormatException ex) {
			reject(response, ErrorCodes.TENANT_IDENTITY_REQUIRED, "身份请求头必须是 long 整数");
			return;
		}
		if (tenantId <= 0L) {
			reject(response, ErrorCodes.TENANT_IDENTITY_REQUIRED, "tenantId 必须是正数");
			return;
		}
		if (userId <= 0L) {
			reject(response, ErrorCodes.TENANT_IDENTITY_REQUIRED,
					userId == RequestContextUtils.SYSTEM_USER_ID ? "系统账号（userId=-1）仅限系统任务，HTTP 请求不得使用"
							: "userId 必须是正数");
			return;
		}
		if (!TenantUserDirectory.exists(tenantId, userId)) {
			reject(response, ErrorCodes.TENANT_IDENTITY_UNKNOWN, "未登记的租户/用户组合");
			return;
		}
		try (RequestContextUtils.SafeCloseable ignored = RequestContextUtils.useContext(tenantId, userId)) {
			chain.doFilter(request, response);
		}
	}

	private void reject(HttpServletResponse response, String code, String message) throws IOException {
		response.setStatus(HttpStatus.BAD_REQUEST.value());
		response.setCharacterEncoding("UTF-8");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write(this.objectMapper.writeValueAsString(ApiResponse.error(code, message)));
	}

}
