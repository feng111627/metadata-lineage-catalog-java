package com.github.feng111627.metadatalineagecatalogjava.api;

import com.github.feng111627.metadatalineagecatalogjava.model.FieldLineageEdge;
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

    public record AddFieldEdgeRequest(String upstreamDataset, String upstreamField,
                                      String downstreamDataset, String downstreamField) {
    }

    @PostMapping("/edges")
    @ResponseStatus(HttpStatus.CREATED)
    public LineageEdge addEdge(@RequestBody AddEdgeRequest request) {
        return lineageService.addEdge(request.upstream(), request.downstream());
    }

    @GetMapping("/edges")
    public List<LineageEdge> listEdges() {
        return lineageService.listEdges();
    }

    @PostMapping("/field-edges")
    @ResponseStatus(HttpStatus.CREATED)
    public FieldLineageEdge addFieldEdge(@RequestBody AddFieldEdgeRequest request) {
        return lineageService.addFieldEdge(request.upstreamDataset(), request.upstreamField(),
                request.downstreamDataset(), request.downstreamField());
    }

    @GetMapping("/field-edges")
    public List<FieldLineageEdge> listFieldEdges() {
        return lineageService.listFieldEdges();
    }

    @GetMapping("/impact/{dataset}")
    public ImpactResult downstreamImpact(@PathVariable String dataset) {
        return lineageService.impactOfDataset(dataset);
    }

    @GetMapping("/impact/{dataset}/fields/{field}")
    public ImpactResult fieldImpact(@PathVariable String dataset, @PathVariable String field) {
        return lineageService.impactOfField(dataset, field);
    }

    @GetMapping("/upstream/{dataset}")
    public Set<String> upstreamLineage(@PathVariable String dataset) {
        return lineageService.upstreamLineage(dataset);
    }
}
