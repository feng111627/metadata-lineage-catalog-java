package com.github.feng111627.metadatalineagecatalogjava.api;

import com.github.feng111627.metadatalineagecatalogjava.error.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.error.ErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldLineageEdge;
import com.github.feng111627.metadatalineagecatalogjava.model.FieldRef;
import com.github.feng111627.metadatalineagecatalogjava.model.ImpactResult;
import com.github.feng111627.metadatalineagecatalogjava.model.LineageEdge;
import com.github.feng111627.metadatalineagecatalogjava.service.LineageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** 血缘登记与影响分析接口（数据集级 + 字段级）。 */
@RestController
@RequestMapping("/api/lineage")
public class LineageController {

    private final LineageService lineageService;

    public LineageController(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    public record AddEdgeRequest(String upstream, String downstream) {
    }

    public record FieldRefDto(String dataset, String field) {
    }

    public record AddFieldEdgeRequest(FieldRefDto upstream, FieldRefDto downstream) {
    }

    @PostMapping("/edges")
    @ResponseStatus(HttpStatus.CREATED)
    public LineageEdge addEdge(@RequestBody AddEdgeRequest request) {
        requirePresent(request, "upstream", request == null ? null : request.upstream());
        requirePresent(request, "downstream", request == null ? null : request.downstream());
        return lineageService.addEdge(request.upstream(), request.downstream());
    }

    @GetMapping("/edges")
    public List<LineageEdge> listEdges() {
        return lineageService.listEdges();
    }

    @PostMapping("/field-edges")
    @ResponseStatus(HttpStatus.CREATED)
    public FieldLineageEdge addFieldEdge(@RequestBody AddFieldEdgeRequest request) {
        if (request == null || request.upstream() == null) {
            throw new CatalogException(ErrorCode.VALIDATION_ERROR, "必填项 upstream（含 dataset、field）缺失");
        }
        if (request.downstream() == null) {
            throw new CatalogException(ErrorCode.VALIDATION_ERROR, "必填项 downstream（含 dataset、field）缺失");
        }
        requirePresent(request.upstream().dataset(), "upstream.dataset");
        requirePresent(request.upstream().field(), "upstream.field");
        requirePresent(request.downstream().dataset(), "downstream.dataset");
        requirePresent(request.downstream().field(), "downstream.field");
        return lineageService.addFieldEdge(
                new FieldRef(request.upstream().dataset(), request.upstream().field()),
                new FieldRef(request.downstream().dataset(), request.downstream().field()));
    }

    @GetMapping("/field-edges")
    public List<FieldLineageEdge> listFieldEdges() {
        return lineageService.listFieldEdges();
    }

    /** 数据集级影响：被波及的下游数据集与具体下游字段。 */
    @GetMapping("/impact/{dataset}")
    public ImpactResult downstreamImpact(@PathVariable String dataset) {
        return lineageService.impactOfDataset(dataset);
    }

    /** 字段级影响：某个具体字段变更波及的下游数据集与下游字段。 */
    @GetMapping("/impact/field/{dataset}/{field}")
    public ImpactResult fieldImpact(@PathVariable String dataset, @PathVariable String field) {
        return lineageService.impactOfField(new FieldRef(dataset, field));
    }

    @GetMapping("/upstream/{dataset}")
    public Set<String> upstreamLineage(@PathVariable String dataset) {
        return lineageService.upstreamLineage(dataset);
    }

    private static void requirePresent(Object request, String key, String value) {
        if (request == null) {
            throw new CatalogException(ErrorCode.REQUEST_BODY_MISSING, "请求体缺失：该接口要求携带 JSON 请求体");
        }
        requirePresent(value, key);
    }

    private static void requirePresent(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new CatalogException(ErrorCode.VALIDATION_ERROR, "必填项 [" + key + "] 缺失或为空");
        }
    }
}
