package com.daedalussystems.easySchedule.integration;

import com.daedalussystems.easySchedule.calendar.domain.CalendarProviderType;
import com.daedalussystems.easySchedule.common.enums.ConferencingProviderType;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ProviderCapabilityRegistry {
    private final Map<CalendarProviderType, ProviderCapabilities> calendarCapabilities;
    private final Map<ConferencingProviderType, ProviderCapabilities> conferencingCapabilities;

    public ProviderCapabilityRegistry() {
        EnumMap<CalendarProviderType, ProviderCapabilities> calendar = new EnumMap<>(CalendarProviderType.class);
        calendar.put(CalendarProviderType.GOOGLE, new ProviderCapabilities(true, true, true, true, true));
        calendar.put(CalendarProviderType.MICROSOFT, new ProviderCapabilities(true, false, true, false, true));
        this.calendarCapabilities = Map.copyOf(calendar);

        EnumMap<ConferencingProviderType, ProviderCapabilities> conferencing = new EnumMap<>(ConferencingProviderType.class);
        conferencing.put(ConferencingProviderType.NONE, new ProviderCapabilities(false, false, false, false, false));
        conferencing.put(ConferencingProviderType.GOOGLE_MEET, new ProviderCapabilities(false, true, false, false, false));
        conferencing.put(ConferencingProviderType.ZOOM, new ProviderCapabilities(false, true, false, false, false));
        conferencing.put(ConferencingProviderType.CUSTOM_URL, new ProviderCapabilities(false, true, false, false, false));
        this.conferencingCapabilities = Map.copyOf(conferencing);
    }

    public ProviderCapabilities forCalendar(CalendarProviderType provider) {
        return calendarCapabilities.get(provider);
    }

    public ProviderCapabilities forConferencing(ConferencingProviderType provider) {
        return conferencingCapabilities.get(provider);
    }

    public Map<CalendarProviderType, ProviderCapabilities> allCalendar() {
        return calendarCapabilities;
    }

    public Map<ConferencingProviderType, ProviderCapabilities> allConferencing() {
        return conferencingCapabilities;
    }
}
