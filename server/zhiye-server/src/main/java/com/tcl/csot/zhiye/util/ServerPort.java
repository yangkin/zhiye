package com.tcl.csot.zhiye.util;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 记录实际监听端口（测试用随机端口时网关地址需要它）。 */
@Component
public class ServerPort {
    private volatile int port = 8080;

    @EventListener
    public void on(WebServerInitializedEvent e) { port = e.getWebServer().getPort(); }

    public int port() { return port; }

    public String selfBase() { return "http://127.0.0.1:" + port; }
}
