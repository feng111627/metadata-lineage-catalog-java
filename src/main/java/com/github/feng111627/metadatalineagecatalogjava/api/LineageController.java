package com.github.feng111627.metadatalineagecatalogjava.api;

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

/** 血缘登记与影响分析接口。 */
@RestController
@RequestMapping("/api/lineage")
public class LineageController {

    private final LineageService lineageService;

    public LineageController(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    public record AddEdgeRequest(String upstream, String downstream) {
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

    @GetMapping("/impact/{dataset}")
    public Set<String> downstreamImpact(@PathVariable String dataset) {
        return lineageService.downstreamImpact(dataset);
    }

    @GetMapping("/upstream/{dataset}")
    public Set<String> upstreamLineage(@PathVariable String dataset) {
        return lineageService.upstreamLineage(dataset);
    }
}
