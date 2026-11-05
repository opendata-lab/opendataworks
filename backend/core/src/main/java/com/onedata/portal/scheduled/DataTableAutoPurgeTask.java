package com.onedata.portal.scheduled;

import com.onedata.portal.entity.DataTable;
import com.onedata.portal.service.DataTableService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;



/**
 * 废弃表自动物理清理任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataTableAutoPurgeTask {

    private static final int BATCH_SIZE = 200;

    private final DataTableService dataTableService;

    /**
     * 每天凌晨 03:15 清理到期废弃表
     */
    @Scheduled(cron = "0 15 3 * * ?")
    public void purgeExpiredTables() {
        LocalDateTime now = LocalDateTime.now();
        List<DataTable> dueTables = dataTableService.listDueForPurge(now, BATCH_SIZE);
        if (dueTables.isEmpty()) {
            return;
        }

        for (DataTable table : dueTables) {
            try {
                dataTableService.dropPhysicalTableIfRequired(table);
                dataTableService.purgeTableMetadata(table.getId());
                log.info("Auto purged deprecated table, tableId={}, db={}, table={}",
                        table.getId(), table.getDbName(), table.getTableName());
            } catch (Exception e) {
                // 删除失败保留记录，交给下一轮重试
                log.error("Auto purge failed for tableId={}, db={}, table={}",
                        table.getId(), table.getDbName(), table.getTableName(), e);
            }
        }
    }
}
