package com.onedata.portal.service;

import com.onedata.portal.entity.DataTask;
import com.onedata.portal.mapper.DataTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 归档器自身的单元测试，重点覆盖批量（级联删除）入口。
 *
 * <p>单任务删除与创建时释放历史编码的行为，由 {@code DataTaskServiceWorkflowMetadataTest}
 * 通过真实归档器覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class DataTaskIdentityArchiverTest {

    @Mock
    private DataTaskMapper dataTaskMapper;

    @InjectMocks
    private DataTaskIdentityArchiver archiver;

    @Test
    void archiveByIdsShouldArchiveEveryCascadeDeletedTask() {
        DataTask first = new DataTask();
        first.setId(601L);
        first.setTaskName("cascade-a");
        first.setTaskCode("cascade-a");

        DataTask second = new DataTask();
        second.setId(602L);
        second.setTaskName("cascade-b");
        second.setTaskCode("cascade-b");

        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Arrays.asList(first, second));
        when(dataTaskMapper.archiveUniqueIdentity(anyLong(), anyString(), anyString(), anyInt()))
                .thenReturn(1);

        archiver.archiveByIds(Arrays.asList(601L, 602L));

        verify(dataTaskMapper).archiveUniqueIdentity(
                601L, "cascade-a__deleted_601", "cascade-a__deleted_601", 0);
        verify(dataTaskMapper).archiveUniqueIdentity(
                602L, "cascade-b__deleted_602", "cascade-b__deleted_602", 0);
    }

    @Test
    void archiveByIdsShouldContinueWhenOneTaskWasConcurrentlyArchived() {
        DataTask concurrentlyDeleted = new DataTask();
        concurrentlyDeleted.setId(701L);
        concurrentlyDeleted.setTaskName("gone");
        concurrentlyDeleted.setTaskCode("gone");

        DataTask survivor = new DataTask();
        survivor.setId(702L);
        survivor.setTaskName("kept");
        survivor.setTaskCode("kept");

        when(dataTaskMapper.selectBatchIds(any()))
                .thenReturn(Arrays.asList(concurrentlyDeleted, survivor));
        when(dataTaskMapper.archiveUniqueIdentity(
                eq(701L), anyString(), anyString(), anyInt())).thenReturn(0);
        when(dataTaskMapper.archiveUniqueIdentity(
                eq(702L), anyString(), anyString(), anyInt())).thenReturn(1);

        archiver.archiveByIds(Arrays.asList(701L, 702L));

        verify(dataTaskMapper).archiveUniqueIdentity(
                702L, "kept__deleted_702", "kept__deleted_702", 0);
    }

    @Test
    void archiveByIdsShouldSkipWhenNoTaskIds() {
        archiver.archiveByIds(Collections.emptyList());
        archiver.archiveByIds(null);

        verifyNoInteractions(dataTaskMapper);
    }

    @Test
    void archiveByIdsShouldSkipWhenTasksAlreadyGone() {
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Collections.emptyList());

        archiver.archiveByIds(Collections.singletonList(801L));

        verify(dataTaskMapper, never()).archiveUniqueIdentity(
                anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void archiveShouldKeepDeletedFlagWhenArchivingHistoricalRecord() {
        DataTask deletedTask = new DataTask();
        deletedTask.setId(77L);
        deletedTask.setTaskName("recreated-task");
        deletedTask.setTaskCode("reusable-task-code");
        deletedTask.setDeleted(1);

        when(dataTaskMapper.archiveUniqueIdentity(
                77L, "reusable-task-code__deleted_77", "recreated-task__deleted_77", 1))
                .thenReturn(1);

        assertTrue(archiver.archive(deletedTask));
    }

    @Test
    void archiveShouldSkipTaskWithoutId() {
        DataTask task = new DataTask();
        task.setTaskName("no-id");

        assertFalse(archiver.archive(task));
        assertFalse(archiver.archive(null));

        verifyNoInteractions(dataTaskMapper);
    }
}
