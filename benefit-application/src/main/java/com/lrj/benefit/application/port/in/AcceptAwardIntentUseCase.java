package com.lrj.benefit.application.port.in;

import com.lrj.benefit.application.command.AwardIntentCommand;
import com.lrj.benefit.application.result.AcceptResult;

public interface AcceptAwardIntentUseCase {
    AcceptResult accept(AwardIntentCommand command);
}
