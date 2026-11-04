package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;


/**
 * 字段级差异
 */
@Data
public class RuntimeDiffFieldChange {

    private String field;

    private String before;

    private String after;

    /**
     * Backward-compatible helper for tests that used string-based diff entries.
     */
    public boolean contains(String text) {
        if (text == null) {
            return false;
        }
        return joinText().contains(text);
    }

    private String joinText() {
        return String.format("%s | before=%s | after=%s",
                field == null ? "" : field,
                before == null ? "" : before,
                after == null ? "" : after);
    }
}
