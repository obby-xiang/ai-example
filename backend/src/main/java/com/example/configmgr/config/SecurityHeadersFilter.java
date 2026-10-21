package com.example.configmgr.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安全响应头（防护批③ B2-9）：<b>只做 Report-Only</b>，强制模式本批不做（用户裁决 2026-10-21 第 4 条）。
 *
 * <h2>为什么只 Report-Only</h2>
 * 页面由 Vite dev server 承载（{@code frontend/vite.config.ts} 只代理 {@code /api}），
 * 本过滤器挂在后端 ⇒ <b>覆盖不到页面本身</b>（实际生效面 = 后端 API 响应）。
 * 而 Vue SFC 运行时注入 {@code <style>}、Vite HMR 走 {@code ws:} —— 强制 CSP 会当场破版式与热更新；
 * 本仓也没有 E2E 覆盖"浏览器里页面是否还能跑"。因此首版只发
 * {@code Content-Security-Policy-Report-Only}：让违规在实际部署里被浏览器报出来，
 * 等前端承载方式（生产静态托管）确定后再谈强制。挂账见施工报告。
 *
 * <h2>其余头</h2>
 * {@code X-Content-Type-Options: nosniff} 与 {@code Referrer-Policy: no-referrer} 是<b>安全</b>且
 * 与 CSP 无关的两条：前者禁 MIME 嗅探（API 只回 JSON / SSE），后者避免把 URL 里的 sessionId
 * 经由 Referer 带到外部站点（与"外链加 rel=noreferrer"同一目的，这里是响应头兜底）。
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

	/** Report-Only 策略：Vite 页面侧的 {@code 'unsafe-inline'} 与 {@code ws:} 是现状必需（见类注）。 */
	public static final String CSP_REPORT_ONLY = "default-src 'self'; img-src 'self' data:; "
			+ "connect-src 'self' ws: wss:; style-src 'self' 'unsafe-inline'; script-src 'self'; "
			+ "object-src 'none'; base-uri 'self'; frame-ancestors 'none'";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		response.setHeader("Content-Security-Policy-Report-Only", CSP_REPORT_ONLY);
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Referrer-Policy", "no-referrer");
		chain.doFilter(request, response);
	}

}
