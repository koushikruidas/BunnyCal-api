package io.bunnycal.team.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.bunnycal.team.domain.TeamRole;
import java.util.List;

/** One or more invitees sharing a role. Sent as a single request so the client shows one loader. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BulkInviteMembersRequest(List<String> emails, TeamRole role) {}
