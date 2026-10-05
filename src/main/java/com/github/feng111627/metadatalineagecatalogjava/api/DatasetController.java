package com.github.feng111627.metadatalineagecatalogjava.api;

import com.github.feng111627.metadatalineagecatalogjava.model.CompatibilityReport;
import com.github.feng111627.metadatalineagecatalogjava.model.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldMeta;
import com.github.feng111627.metadatalineagecatalogjava.service.DatasetCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 数据集版本登记与查询接口。 */
@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    private final DatasetCatalogService catalogService;

    public DatasetController(DatasetCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public record RegisterDatasetRequest(String name, List<FieldMeta> fields) {
    }

    public record RegisterVersionRequest(int expectedBaseVersion, List<FieldMeta> fields) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DatasetVersion registerDataset(@RequestBody RegisterDatasetRequest request) {
        return catalogService.registerDataset(request.name(), request.fields());
    }

    @PostMapping("/{name}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public DatasetVersion registerNewVersion(@PathVariable String name,
                                             @RequestBody RegisterVersionRequest request) {
        return catalogService.registerNewVersion(name, request.expectedBaseVersion(), request.fields());
    }

    @GetMapping("/{name}/versions")
    public List<DatasetVersion> listVersions(@PathVariable String name) {
        return catalogService.listVersions(name);
    }

    @GetMapping("/{name}/versions/{version}")
    public DatasetVersion getVersion(@PathVariable String name, @PathVariable int version) {
        return catalogService.getVersion(name, version);
    }

    @GetMapping("/{name}/compatibility/{fromVersion}/{toVersion}")
    public CompatibilityReport checkCompatibility(@PathVariable String name,
                                                  @PathVariable int fromVersion,
                                                  @PathVariable int toVersion) {
        return catalogService.checkCompatibility(name, fromVersion, toVersion);
    }
}
