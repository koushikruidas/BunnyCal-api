package io.bunnycal.calendar.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum ProviderEventProjectionStatus {
    ACTIVE,
    TOMBSTONED_SOFT,
    TOMBSTONED_HARD;

    public static final Set<String> ALLOWED_NAMES = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
}
