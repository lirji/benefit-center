package com.lrj.benefit.application.result;

import com.lrj.benefit.domain.model.AwardOrderStatus;

public record AcceptResult(String awardOrderNo, AwardOrderStatus status, boolean replay) {
}
