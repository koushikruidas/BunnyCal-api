package com.daedalussystems.easySchedule.auth.dto;

import com.daedalussystems.easySchedule.auth.domain.user.User;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {

    private UUID id;
    private String email;
    private String username;
    private String name;
    private String timezone;

    public static UserDto from(User user) {
        return UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .name(user.getName())
                .timezone(user.getTimezone())
                .build();
    }
}
