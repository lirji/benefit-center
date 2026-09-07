package com.lrj.benefit.web;

import com.lrj.benefit.adapters.security.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 权益控制台使用的审批运行态聚合，只做跨台只读查询。 */
@RestController
@RequestMapping("/admin/v1/skus")
public final class ApprovalRuntimeController {
    private final WorkflowApprovalRuntimeClient workflow;

    public ApprovalRuntimeController(WorkflowApprovalRuntimeClient workflow) {
        this.workflow = workflow;
    }

    /** 返回同租户、同 SKU 业务键下是否已经存在流程实例。 */
    @GetMapping("/{skuId}/approval-runtime")
    public WorkflowApprovalRuntimeClient.ApprovalRuntimeView approvalRuntime(@PathVariable String skuId) {
        return workflow.inspect(TenantContext.required(), skuId);
    }
}
