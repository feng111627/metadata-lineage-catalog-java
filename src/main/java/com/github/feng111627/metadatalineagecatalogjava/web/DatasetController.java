package com.github.feng111627.metadatalineagecatalogjava.web;

import com.github.feng111627.metadatalineagecatalogjava.domain.CompatibilityResult;
import com.github.feng111627.metadatalineagecatalogjava.domain.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.service.CatalogService;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.DatasetRegisterRequest;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.RegistrationOutcome;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据集版本接口（骨架）。
 */
@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    private final CatalogService catalogService;

    public DatasetController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public List<String> list() {
        return catalogService.datasets();
    }

    @PostMapping("/{name}/versions")
    public RegistrationOutcome register(@PathVariable String name,
                                   @RequestBody DatasetRegisterRequest request) {
        return catalogService.register(name, request);
    }

    @PostMapping("/{name}/compatibility-checks")
    public CompatibilityResult preview(@PathVariable String name,
                                       @RequestBody DatasetRegisterRequest request) {
        return catalogService.previewCompatibility(name, request);
    }

    @GetMapping("/{name}/versions/latest")
    public DatasetVersion latest(@PathVariable String name) {
        return catalogService.latest(name);
    }

    @GetMapping("/{name}/versions/{version}")
    public DatasetVersion version(@PathVariable String name, @PathVariable int version) {
        return catalogService.versionAt(name, version);
    }

    @GetMapping("/{name}/versions")
    public List<DatasetVersion> history(@PathVariable String name) {
        return catalogService.history(name);
    }
}
