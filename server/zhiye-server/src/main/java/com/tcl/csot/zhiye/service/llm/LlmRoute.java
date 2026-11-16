package com.tcl.csot.zhiye.service.llm;

import java.util.Map;

/**
 * 一次模型调用的去向：上游地址、密钥、模型名和附加请求头。
 * 本地模式是 zhiye.llm 的统一上游；五星模式是 llm-gateway 上该员工自己的路由（虚拟密钥 + X-User-Id）。
 */
public record LlmRoute(String baseUrl, String apiKey, String model, String provider, Map<String, String> headers,
                       String account, String label, boolean local) {}
