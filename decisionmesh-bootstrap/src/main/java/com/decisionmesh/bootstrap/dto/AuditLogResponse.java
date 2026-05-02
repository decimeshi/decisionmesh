package com.decisionmesh.bootstrap.dto;

import com.decisionmesh.persistence.entity.AuditLogEntity;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for GET /api/audit.
 *
 * Critical mapping: AuditLogEntity.occurredAt → AuditLogDto.timestamp
 * The UI reads e.timestamp — the DB column is occurred_at, the Java field is
 * occurredAt, but the JSON field MUST be "timestamp" to match AuditLog.jsx.
 *
 * CSV export in AuditLog.jsx uses:
 *   [e.timestamp, e.userId, e.action, e.entityType, e.entityId, e.tenantId]
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AuditLogResponse {

    public UUID   id;
    public OffsetDateTime timestamp;   // ← from occurredAt — UI reads e.timestamp
    public String userId;
    public String action;
    public String entityType;
    public UUID   entityId;
    public UUID   tenantId;
    public String resourceType;
    public String resourceId;
    public String outcome;
    public String detail;

    public static AuditLogResponse from(AuditLogEntity e) {
        AuditLogResponse dto  = new AuditLogResponse();
        dto.id           = e.id;
        dto.timestamp    = e.occurredAt;   // occurred_at → timestamp
        dto.userId       = e.userId;
        dto.action       = e.action;
        dto.entityType   = e.entityType;
        dto.entityId     = e.entityId;
        dto.tenantId     = e.tenantId;
        dto.resourceType = e.resourceType;
        dto.resourceId   = e.resourceId;
        dto.outcome      = e.outcome;
        dto.detail       = e.detail;
        return dto;
    }

    // ── Paginated response ────────────────────────────────────────────────────

    /** AuditLog.jsx reads: data.content, data.totalElements, data.totalPages */
    public static class AuditPage {
        public List<AuditLogResponse> content;
        public long totalElements;
        public int  totalPages;
        public int  size;
        public int  number;

        public AuditPage(List<AuditLogResponse> content, long totalElements,
                             int page, int size) {
            this.content       = content;
            this.totalElements = totalElements;
            this.size          = size;
            this.number        = page;
            this.totalPages    = size > 0
                    ? (int) Math.ceil((double) totalElements / size) : 0;
        }
    }
}