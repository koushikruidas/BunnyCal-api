package io.bunnycal.team.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateTeamRequest(String name, String slug) {}
