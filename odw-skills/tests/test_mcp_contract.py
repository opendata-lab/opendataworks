"""Guard executable skill recipes when the built-in MCP input contract changes."""
import json
import re
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]


def test_skill_recipes_use_current_publish_and_argument_contract():
    for name in ('opendataworks-data-dev', 'opendataworks-platform-tools'):
        for path in (ROOT / 'odw-skills' / name).rglob('*.md'):
            text = path.read_text()
            assert 'preview_token' not in text, path
            assert 'preview token' not in text, path
            assert '不要添加 description' not in text, path
            for key in ('workflow_id', 'task_id', 'input_table_ids', 'output_table_ids', 'timeout_seconds'):
                assert not re.search(r'\b' + key + r'\b', text), (path, key)
            assert not re.search(r'\b(?:title|summary)\?:', text), path
    policy = json.loads((ROOT / 'odw-skills/opendataworks-data-dev/assets/dev-policies.json').read_text())
    assert policy['publish']['confirmDiff_default'] is False
    assert policy['publish']['revalidate_at_execution'] is True


def test_tool_schemas_register_all_tools_without_legacy_wrapper():
    tools = json.loads((ROOT / 'backend/mcp-server/src/main/resources/mcp/tools.json').read_text())
    assert len({tool['name'] for tool in tools}) == 23
    for tool in tools:
        schema = tool['inputSchema']
        assert schema['additionalProperties'] is False
        assert 'params' not in schema['properties']
        assert 'preview_token' not in json.dumps(schema)
        assert not re.search(r'\b(?:table_id|field_id|field_name|input_tableIds|output_tableIds)\b', json.dumps(tool))
        assert '$ref' not in json.dumps(schema)
        assert all('_' not in key for key in schema['properties'])
