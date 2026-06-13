from __future__ import annotations

from typing import Any

from .backend_client import BackendApiClient, BackendApiError


class PortalToolService:
    def __init__(self, backend_client: BackendApiClient):
        self.backend_client = backend_client

    async def search_tables(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.inspect(**payload))

    async def get_lineage(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.lineage(**payload))

    async def resolve_datasource(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.resolve_datasource(**payload))

    async def export_metadata(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        return await self._wrap(self.backend_client.export_metadata(**payload))

    async def get_table_ddl(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.get_table_ddl(**payload))

    async def query_readonly(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.query_readonly(payload))

    # --- write surface (data development assistant) ---------------------------

    async def create_task(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.create_task(payload))

    async def update_task(self, task_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.update_task(task_id, payload))

    async def get_task(self, task_id: int) -> dict[str, Any]:
        return await self._wrap(self.backend_client.get_task(task_id))

    async def list_tasks(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.list_tasks(**payload))

    async def create_workflow(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.create_workflow(payload))

    async def update_workflow(self, workflow_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.update_workflow(workflow_id, payload))

    async def get_workflow(self, workflow_id: int) -> dict[str, Any]:
        return await self._wrap(self.backend_client.get_workflow(workflow_id))

    async def list_workflows(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.list_workflows(**payload))

    async def preview_publish(self, workflow_id: int) -> dict[str, Any]:
        return await self._wrap(self.backend_client.preview_publish(workflow_id))

    async def publish_workflow(self, workflow_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.publish_workflow(workflow_id, payload))

    async def upsert_schedule(self, workflow_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.upsert_schedule(workflow_id, payload))

    async def schedule_online(self, workflow_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.schedule_online(workflow_id, payload))

    async def schedule_offline(self, workflow_id: int) -> dict[str, Any]:
        return await self._wrap(self.backend_client.schedule_offline(workflow_id))

    async def analyze_sql(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._wrap(self.backend_client.analyze_sql(payload))

    async def _wrap(self, awaitable):
        try:
            return await awaitable
        except BackendApiError as exc:
            raise RuntimeError(str(exc)) from exc
