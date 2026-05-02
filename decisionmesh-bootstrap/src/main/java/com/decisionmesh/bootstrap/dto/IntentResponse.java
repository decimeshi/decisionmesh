package com.decisionmesh.bootstrap.dto;

import com.decisionmesh.persistence.entity.IntentEntity;

import java.util.List;

/**
 * Paginated response for GET /api/intents.
 *
 * Field names MUST match what the React frontend reads:
 *
 *   Dashboard.jsx:     intents?.content        intents?.totalElements   intents?.totalPages
 *   IntentsTable.jsx:  data?.content           data?.totalElements      data?.totalPages
 *                      data?.totalPages > 1    (pagination controls)
 *
 * Previous field names (data, total, pageIndex, pageSize) did NOT match
 * any of the above — every consumer was reading undefined silently.
 */
public class IntentResponse {

    /** The page of intent records. Frontend reads: .content */
    public List<IntentEntity> content;

    /** Total matching records across all pages. Frontend reads: .totalElements */
    public long totalElements;

    /** Total number of pages. Frontend reads: .totalPages for pagination controls. */
    public int totalPages;

    /** Items per page. Frontend reads: .size */
    public int size;

    /** Zero-based current page index. Frontend reads: .number */
    public int number;

    public IntentResponse(List<IntentEntity> content,
                          long totalElements,
                          int pageIndex,
                          int pageSize) {
        this.content       = content;
        this.totalElements = totalElements;
        this.number        = pageIndex;
        this.size          = pageSize;
        // Avoid division by zero when pageSize is 0
        this.totalPages    = pageSize > 0
                ? (int) Math.ceil((double) totalElements / pageSize)
                : 0;
    }
}