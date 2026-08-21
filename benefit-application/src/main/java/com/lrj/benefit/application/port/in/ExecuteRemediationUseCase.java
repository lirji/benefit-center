package com.lrj.benefit.application.port.in;

import com.lrj.benefit.contract.RemediationCommand;
import com.lrj.benefit.contract.RemediationResult;

public interface ExecuteRemediationUseCase {
    RemediationResult accept(String tenantId, RemediationCommand command);
    RemediationResult execute(String tenantId, String remediationNo, String workerId);
    RemediationResult get(String tenantId, String remediationNo);
}
