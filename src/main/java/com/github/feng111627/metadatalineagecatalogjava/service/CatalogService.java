package com.github.feng111627.metadatalineagecatalogjava.service;

import com.github.feng111627.metadatalineagecatalogjava.domain.CompatibilityResult;
import com.github.feng111627.metadatalineagecatalogjava.domain.DatasetVersion;
import com.github.feng111627.metadatalineagecatalogjava.domain.FieldSchema;
import com.github.feng111627.metadatalineagecatalogjava.domain.SensitivityLevel;
import com.github.feng111627.metadatalineagecatalogjava.engine.CompatibilityEngine;
import com.github.feng111627.metadatalineagecatalogjava.exception.CatalogErrorCode;
import com.github.feng111627.metadatalineagecatalogjava.exception.CatalogException;
import com.github.feng111627.metadatalineagecatalogjava.store.CatalogStore;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.DatasetRegisterRequest;
import com.github.feng111627.metadatalineagecatalogjava.web.dto.RegistrationOutcome;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据集版本登记与历史查询服务。
 *
 * <p>登记采用乐观并发控制：请求必须携带 expectedVersion（首次登记为 0），
 * 与服务端当前最新版本不一致时以 {@link CatalogErrorCode#VERSION_CONFLICT} 拒绝，
 * 不会静默覆盖并发提交。登记总是产生一个新版本，同时返回相邻版本兼容性结论。</p>
 */
@Service
public class CatalogService {

    private final CatalogStore store;
    private final CompatibilityEngine compatibilityEngine;

    public CatalogService(CatalogStore store, CompatibilityEngine compatibilityEngine) {
        this.store = store;
        this.compatibilityEngine = compatibilityEngine;
    }

    /**
     * 登记新版本。
     */
    public RegistrationOutcome register(String datasetName, DatasetRegisterRequest request) {
        validateDatasetName(datasetName);
        int expectedBase = validateRequest(request);
        List<FieldSchema> fields = mapFields(request);

        DatasetVersion previous = store.latestVersion(datasetName).orElse(null);
        // store 在版本号不匹配时抛 VERSION_CONFLICT；此处先行判空给出更明确的错误
        DatasetVersion committed =
                store.commitVersion(datasetName, expectedBase, fields,
                        request.createdBy(), request.changeNote());
        CompatibilityResult compatibility = compatibilityEngine.compare(previous, committed);
        return new RegistrationOutcome(committed, compatibility);
    }

    /**
     * 预演兼容性判定，不写入任何版本。
     * 以请求中的 expectedVersion 作为比较基线（0 表示与“空数据集”比较）。
     */
    public CompatibilityResult previewCompatibility(String datasetName, DatasetRegisterRequest request) {
        validateDatasetName(datasetName);
        int expectedBase = validateRequest(request);
        List<FieldSchema> fields = mapFields(request);

        DatasetVersion baseline;
        if (expectedBase == 0) {
            baseline = null;
        } else {
            baseline = store.getVersion(datasetName, expectedBase)
                    .orElseThrow(() -> new CatalogException(CatalogErrorCode.VERSION_NOT_FOUND,
                            "dataset '" + datasetName + "' has no version " + expectedBase));
        }
        DatasetVersion candidate = new DatasetVersion(
                datasetName, expectedBase + 1, fields, java.time.Instant.now(),
                request.createdBy() == null ? "" : request.createdBy(),
                request.changeNote() == null ? "" : request.changeNote(), expectedBase);
        return compatibilityEngine.compare(baseline, candidate);
    }

    public DatasetVersion latest(String datasetName) {
        validateDatasetName(datasetName);
        return store.latestVersion(datasetName)
                .orElseThrow(() -> notFound(datasetName));
    }

    public DatasetVersion versionAt(String datasetName, int version) {
        validateDatasetName(datasetName);
        if (version <= 0) {
            throw new CatalogException(CatalogErrorCode.VERSION_NOT_FOUND,
                    "version number must be positive, got " + version);
        }
        return store.getVersion(datasetName, version)
                .orElseThrow(() -> new CatalogException(CatalogErrorCode.VERSION_NOT_FOUND,
                        "dataset '" + datasetName + "' has no version " + version));
    }

    public List<DatasetVersion> history(String datasetName) {
        validateDatasetName(datasetName);
        if (!store.latestVersion(datasetName).isPresent()) {
            throw notFound(datasetName);
        }
        return store.history(datasetName);
    }

    public List<String> datasets() {
        return store.datasets();
    }

    private static CatalogException notFound(String datasetName) {
        return new CatalogException(CatalogErrorCode.DATASET_NOT_FOUND,
                "dataset '" + datasetName + "' is not registered");
    }

    private static void validateDatasetName(String datasetName) {
        if (datasetName == null || datasetName.isBlank()) {
            throw new CatalogException(CatalogErrorCode.INVALID_REQUEST,
                    "dataset name must not be blank");
        }
    }

    private static int validateRequest(DatasetRegisterRequest request) {
        if (request == null) {
            throw new CatalogException(CatalogErrorCode.INVALID_REQUEST, "request body is required");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new CatalogException(CatalogErrorCode.INVALID_REQUEST,
                    "expectedVersion is required and must be >= 0");
        }
        if (request.fields() == null) {
            throw new CatalogException(CatalogErrorCode.INVALID_REQUEST,
                    "fields list is required (may be empty)");
        }
        return request.expectedVersion();
    }

    private static List<FieldSchema> mapFields(DatasetRegisterRequest request) {
        List<FieldSchema> fields = new java.util.ArrayList<>(request.fields().size());
        Set<String> seen = new HashSet<>();
        for (DatasetRegisterRequest.FieldInput input : request.fields()) {
            if (input == null || input.name() == null || input.name().isBlank()) {
                throw new CatalogException(CatalogErrorCode.INVALID_REQUEST,
                        "each field requires a non-blank name");
            }
            if (input.type() == null) {
                throw new CatalogException(CatalogErrorCode.INVALID_REQUEST,
                        "field '" + input.name() + "' requires a type");
            }
            if (!seen.add(input.name())) {
                throw new CatalogException(CatalogErrorCode.DUPLICATE_FIELD,
                        "duplicate field name '" + input.name() + "' in the same version");
            }
            fields.add(new FieldSchema(
                    input.name(),
                    input.type(),
                    input.nullable() == null || input.nullable(),
                    input.semantic(),
                    input.sensitivity() == null ? SensitivityLevel.NONE : input.sensitivity(),
                    input.description()));
        }
        return List.copyOf(fields);
    }
}
