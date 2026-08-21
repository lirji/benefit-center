package com.lrj.benefit.web;

import com.lrj.benefit.adapters.security.TenantContext;
import com.lrj.benefit.application.port.in.ExecuteRemediationUseCase;
import com.lrj.benefit.contract.RemediationCommand;
import com.lrj.benefit.contract.RemediationResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/remediations")
public class RemediationController {
    private final ExecuteRemediationUseCase useCase;
    public RemediationController(ExecuteRemediationUseCase useCase) { this.useCase = useCase; }

    @PostMapping
    public RemediationResult accept(@Valid @RequestBody RemediationCommand command) {
        return useCase.accept(TenantContext.required(), command);
    }

    @PostMapping("/{remediationNo}/execute")
    public RemediationResult execute(@PathVariable String remediationNo,
                                     @RequestHeader(value = "X-Worker-Id", defaultValue = "api") String workerId) {
        return useCase.execute(TenantContext.required(), remediationNo, workerId);
    }

    @GetMapping("/{remediationNo}")
    public RemediationResult get(@PathVariable String remediationNo) {
        return useCase.get(TenantContext.required(), remediationNo);
    }
}
