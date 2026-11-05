package com.onedata.portal.dto.workflow.runtime;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 任务差异
 */
@Data
public class RuntimeTaskChange {

    private Long taskCode;

    private String taskName;

    private List<RuntimeDiffFieldChange> fieldChanges = new ArrayList<>();

    /**
     * Backward-compatible helper for tests that used string-based diff entries.
     */
    public boolean contains(String text) {
        if (text == null) {
            return false;
        }
        String codeText = taskCode == null ? "" : String.valueOf(taskCode);
        if (codeText.contains(text) || ("taskCode=" + codeText).contains(text)) {
            return true;
        }
        if (taskName != null && taskName.contains(text)) {
            return true;
        }
        for (RuntimeDiffFieldChange change : fieldChanges) {
            if (change != null && change.contains(text)) {
                return true;
            }
        }
        return false;
    }
}
