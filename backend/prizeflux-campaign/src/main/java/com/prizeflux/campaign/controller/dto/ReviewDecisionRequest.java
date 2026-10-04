package com.prizeflux.campaign.controller.dto;

import jakarta.validation.constraints.Size;

public record ReviewDecisionRequest(@Size(max = 500) String comment) {
}
