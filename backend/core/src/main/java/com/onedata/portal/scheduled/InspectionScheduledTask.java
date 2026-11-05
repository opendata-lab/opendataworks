package com.onedata.portal.scheduled;

import com.onedata.portal.service.InspectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * 巡检定时任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InspectionScheduledTask {

    private final InspectionService inspectionService;

    /**
     * 每天凌晨 2 点执行全量巡检
     * cron 表达式: 秒 分 时 日 月 周
     * 0 0 2 * * ? 表示每天凌晨 2 点
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledFullInspection() {
        log.info("=== Scheduled inspection task started ===");
        try {
            inspectionService.runFullInspection("schedule", "system");
            log.info("=== Scheduled inspection task completed successfully ===");
        } catch (Exception e) {
            log.error("=== Scheduled inspection task failed ===", e);
        }
    }

    /**
     * 每 6 小时执行一次快速巡检(仅检查关键问题)
     * cron 表达式: 0 0 星号斜杠6 星号 星号 问号 表示每 6 小时的整点执行
     *
     * 注意: 这个任务暂时注释掉,因为当前没有实现快速巡检功能
     * 如需启用,请先在 InspectionService 中实现 runQuickInspection 方法
     */
    // @Scheduled(cron = "0 0 */6 * * ?")
    // public void scheduledQuickInspection() {
    //     log.info("=== Scheduled quick inspection started ===");
    //     try {
    //         // TODO: 实现快速巡检,只检查关键问题(如任务失败、数据新鲜度等)
    //         // inspectionService.runQuickInspection("schedule", "system");
    //         log.info("=== Scheduled quick inspection completed ===");
    //     } catch (Exception e) {
    //         log.error("=== Scheduled quick inspection failed ===", e);
    //     }
    // }

    /**
     * 测试用的定时任务 - 每 5 分钟执行一次
     * 生产环境请删除或注释掉此任务
     */
    // @Scheduled(fixedRate = 300000) // 300000 毫秒 = 5 分钟
    // public void testScheduledInspection() {
    //     log.info("=== Test inspection task started (every 5 minutes) ===");
    //     try {
    //         inspectionService.runFullInspection("schedule_test", "system");
    //         log.info("=== Test inspection task completed ===");
    //     } catch (Exception e) {
    //         log.error("=== Test inspection task failed ===", e);
    //     }
    // }
}
