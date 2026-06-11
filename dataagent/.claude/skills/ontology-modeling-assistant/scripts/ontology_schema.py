#!/usr/bin/env python3
"""Pydantic data model and JSON Schema for ontology files."""

from __future__ import annotations

import json
from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field


SnakeCaseId = str

OBJECT_KIND_OPTIONS: Dict[str, Dict[str, str]] = {
    "domain": {"label_zh": "业务域", "description": "本体覆盖的业务边界"},
    "entity": {"label_zh": "业务实体", "description": "可解释、可查询的核心业务对象"},
    "attribute": {"label_zh": "业务属性", "description": "实体属性、状态、分类、时间字段、标识字段"},
    "metric": {"label_zh": "业务指标", "description": "指标、口径、公式、聚合规则"},
    "physical_source": {"label_zh": "物理来源", "description": "数据库表、字段集合、DDL、血缘信息"},
    "document": {"label_zh": "文档来源", "description": "上传文档或外部说明"},
    "skill": {"label_zh": "领域 skill", "description": "目标本体 skill 本身"},
    "query_function": {"label_zh": "查询函数", "description": "下游问数链路的语义交接函数"},
    "relation": {"label_zh": "关系对象", "description": "当关系本身需要被解释、归类或复用时使用"},
}

RELATION_KIND_OPTIONS: Dict[str, Dict[str, str]] = {
    "covers": {"label_zh": "覆盖", "example": "domain_ontology_skill -> business_domain"},
    "supports_scope": {"label_zh": "支撑范围", "example": "source_document -> business_domain"},
    "supports": {"label_zh": "支撑对象", "example": "physical_table -> domain_entity"},
    "has_attribute": {"label_zh": "拥有属性", "example": "domain_entity -> domain_attribute"},
    "measures": {"label_zh": "度量实体", "example": "domain_metric -> domain_entity"},
    "connects": {"label_zh": "连接实体", "example": "semantic_relation -> domain_entity"},
    "semantic_mapping": {"label_zh": "语义映射", "example": "domain_attribute -> physical_table"},
    "caliber_rule": {
        "label_zh": "口径规则",
        "example": "domain_metric -> physical_table、domain_query_function -> domain_ontology_skill",
    },
}

CARDINALITY_OPTIONS: Dict[str, Dict[str, str]] = {
    "one_to_one": {"label_zh": "一对一"},
    "one_to_many": {"label_zh": "一对多"},
    "many_to_one": {"label_zh": "多对一"},
    "many_to_many": {"label_zh": "多对多"},
}

FIELD_DICTIONARY: Dict[str, Dict[str, Any]] = {
    "top_level_fields": {
        "title": "顶层字段",
        "values": {
            "metadata": {"type": "object", "required": "是", "description": "本体基本信息"},
            "object_types": {"type": "object[]", "required": "是", "description": "本体对象清单"},
            "object_relations": {
                "type": "object[]",
                "required": "是",
                "description": "统一关系清单，所有关系类型由 relation_kind 表达",
            },
        },
    },
    "object_types.kind": {
        "title": "object_types.kind",
        "values": OBJECT_KIND_OPTIONS,
    },
    "object_relations.relation_kind": {
        "title": "object_relations.relation_kind",
        "values": RELATION_KIND_OPTIONS,
    },
    "object_relations.cardinality": {
        "title": "object_relations.cardinality",
        "values": CARDINALITY_OPTIONS,
    },
    "query_functions": {
        "title": "query_functions 字段",
        "values": {
            "function_name": {"type": "string", "required": "是", "description": "snake_case 函数名"},
            "intent": {"type": "string", "required": "是", "description": "函数服务的用户意图"},
            "grain": {"type": "string", "required": "是", "description": "查询语义粒度"},
            "params": {"type": "string[]", "required": "是", "description": "参数槽位"},
            "output_fields": {"type": "string[]", "required": "是", "description": "输出字段"},
            "notes": {"type": "string", "required": "是", "description": "口径、边界和注意事项"},
        },
    },
}

ObjectKind = Literal[*tuple(OBJECT_KIND_OPTIONS)]

RelationKind = Literal[*tuple(RELATION_KIND_OPTIONS)]

Cardinality = Literal[*tuple(CARDINALITY_OPTIONS)]

class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Metadata(StrictModel):
    id: SnakeCaseId = Field(pattern=r"^[a-z][a-z0-9_]*$")
    name_zh: str
    name_en: str
    owner: str
    version: str
    description: Optional[str] = None


class OntologyProperty(StrictModel):
    name_zh: str
    name_en: str
    description: str


class PhysicalSource(StrictModel):
    table: str
    purpose: Optional[str] = None
    columns: List[str] = Field(default_factory=list)


class QueryFunction(StrictModel):
    function_name: SnakeCaseId = Field(pattern=r"^[a-z][a-z0-9_]*$")
    intent: str
    grain: str
    params: List[str]
    output_fields: List[str]
    notes: str


class ObjectType(StrictModel):
    id: SnakeCaseId = Field(pattern=r"^[a-z][a-z0-9_]*$")
    name_zh: str
    name_en: str
    kind: ObjectKind
    description: str
    synonyms: List[str]
    properties: List[OntologyProperty] = Field(default_factory=list)
    physical_sources: List[PhysicalSource] = Field(default_factory=list)
    default_filters: Dict[str, Any] = Field(default_factory=dict)
    query_functions: List[QueryFunction] = Field(default_factory=list)
    needs_confirmation: Optional[Any] = None


class ObjectRelation(StrictModel):
    id: SnakeCaseId = Field(pattern=r"^[a-z][a-z0-9_]*$")
    from_object: SnakeCaseId = Field(pattern=r"^[a-z][a-z0-9_]*$")
    to_object: SnakeCaseId = Field(pattern=r"^[a-z][a-z0-9_]*$")
    relation_kind: RelationKind
    cardinality: Cardinality
    description: str
    modeling_guidance: List[str] = Field(default_factory=list)
    needs_confirmation: Optional[Any] = None


class OntologyFile(StrictModel):
    metadata: Metadata
    object_types: List[ObjectType]
    object_relations: List[ObjectRelation]


def ontology_json_schema() -> Dict[str, Any]:
    schema = OntologyFile.model_json_schema()
    schema["x-field-dictionary"] = FIELD_DICTIONARY
    return schema


def ontology_json_schema_text() -> str:
    return json.dumps(ontology_json_schema(), ensure_ascii=False, indent=2, sort_keys=True) + "\n"


if __name__ == "__main__":
    print(ontology_json_schema_text(), end="")
