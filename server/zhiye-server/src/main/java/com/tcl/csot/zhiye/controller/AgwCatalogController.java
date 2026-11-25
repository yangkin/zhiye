package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.source.AgwCatalogService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Profile("api")
@RequestMapping("/api/agw")
public class AgwCatalogController {
    private final AgwCatalogService catalog;
    private final CurrentPrincipal principal;

    public AgwCatalogController(AgwCatalogService catalog, CurrentPrincipal principal) {
        this.catalog = catalog;
        this.principal = principal;
    }

    @GetMapping("/callable-apis")
    public List<Map<String, Object>> callableApis() {
        return catalog.callableApis(principal.workspaceId());
    }

    @PostMapping("/apis/{apiId}/add")
    public Map<String, Object> add(@PathVariable String apiId, @RequestBody JsonNode body) {
        return catalog.addApi(principal.workspaceId(), body.path("projectCode").asText(""), apiId);
    }
}
