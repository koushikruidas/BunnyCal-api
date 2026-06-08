package io.bunnycal.team.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.bunnycal.team.domain.TeamRole;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InviteMemberRequest(String email, TeamRole role) {}
