package com.secureleaf.admin.mapper;

import com.secureleaf.admin.dto.AdminActionDto;
import com.secureleaf.admin.dto.AdminActionPageDto;
import com.secureleaf.admin.dto.AdminActorDto;
import com.secureleaf.admin.entity.AdminAction;
import org.springframework.data.domain.Page;

import java.time.ZoneOffset;

public class AdminActionMapper {

    private AdminActionMapper() {}

    public static AdminActionDto toDto(AdminAction action) {
        if (action == null) return null;
        return new AdminActionDto(
                action.getId(),
                new AdminActorDto(action.getAdmin().getId(), action.getAdmin().getDisplayName()),
                action.getAction().name(),
                action.getTargetType().name(),
                action.getTargetId(),
                action.getReason(),
                action.getCreatedAt() != null ? action.getCreatedAt().atOffset(ZoneOffset.UTC) : null
        );
    }

    public static AdminActionPageDto toPageDto(Page<AdminAction> page) {
        return new AdminActionPageDto(
                page.getContent().stream().map(AdminActionMapper::toDto).toList(),
                Math.toIntExact(page.getTotalElements()),
                page.getTotalPages(),
                page.getNumber()
        );
    }
}
