package com.lrj.benefit.application.port.out;

import java.util.Optional;

public interface CodeAssetRepository {
    Optional<IssuedCode> issueOne(String tenantId, String skuId, String itemNo, String operationNo);
    Optional<IssuedCode> findIssued(String tenantId, String itemNo);
    boolean reverse(String tenantId, String itemNo, String operationNo);

    record IssuedCode(String codeAssetId, String deliveryReference) {}
}
