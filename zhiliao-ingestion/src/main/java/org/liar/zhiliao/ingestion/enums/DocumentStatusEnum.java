package org.liar.zhiliao.ingestion.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author Pei
 * @since 2026-07-07
 */
@Getter
@AllArgsConstructor
public enum DocumentStatusEnum {
    UPLOADED("UPLOADED"),
    PROCESSING("PROCESSING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED");
    private final String status;
}
