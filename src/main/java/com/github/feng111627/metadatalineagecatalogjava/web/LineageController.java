package com.github.feng111627.metadatalineagecatalogjava.web;

import com.github.feng111627.metadatalineagecatalogjava.domain.ImpactReport;
import com.github.feng111627.metadatalineagecatalogjava.domain.LineageEdge;
import com.github.feng111627.metadatalineagecatalogjava.service.LineageService;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.LineageRegisterRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 血缘与影响分析接口（骨架）。
 */
@RestController
@RequestMapping("/api/lineage")
public class LineageController {

    private final LineageService lineageService;

    public LineageController(LineageService lineageService) {
        this.lineageService = lineageService;
    }

    @GetMapping("/edges")
    public List<LineageEdge> edges() {
        return lineageService.edges();
    }

    @PostMapping("/edges")
    public ResponseEntity<Void> register(@RequestBody LineageRegisterRequest request) {
        boolean created = lineageService.registerEdge(request);
        return ResponseEntity.status(created ? 201 : 200).build();
    }

    @GetMapping("/impact/{dataset}")
    public ImpactReport impact(@PathVariable String dataset,
                               @RequestParam(required = false) String field) {
        return lineageService.analyzeImpact(dataset, field);
    }
}
