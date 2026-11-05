package com.onedata.portal.dto.workflow.runtime;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 声明关系与 SQL 推断关系比对详情
 */
@Data
public class RuntimeRelationCompareDetail {

    private List<RuntimeRelationChange> declaredRelations = new ArrayList<>();

    private List<RuntimeRelationChange> inferredRelations = new ArrayList<>();

    private List<RuntimeRelationChange> onlyInDeclared = new ArrayList<>();

    private List<RuntimeRelationChange> onlyInInferred = new ArrayList<>();
}
