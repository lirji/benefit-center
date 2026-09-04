package com.lrj.benefit.web;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 容器诊断探活。门户与浏览器入口已迁到独立 console，本服务不再提供 HTML 落地页。
 */
@RestController
public class PortalLandingController {
    static final String HEALTH_BODY = "ok";

    @GetMapping(value = "/healthz", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> healthz() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(HEALTH_BODY);
    }
}
