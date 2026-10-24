package com.example.configmgr.task.controller;

import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.entity.TaskFile;
import com.example.configmgr.task.entity.TaskItem;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R1-13 / I-4：任务删除后的子表写入必须映射 404，并且写盘失败要清理已写文件。
 *
 * <p>FileStorageService / TaskFileRepository 用 Mockito 替身，以便精确断言“写盘前未调用”和
 * “FK 冲突后 delete(path) 被调用”；任务存在性与条目匹配仍走真实 H2。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:r113-file-task-gone;DB_CLOSE_DELAY=-1",
        "app.file.storage-path=./target/r113-file-task-gone"
})
class FileControllerTaskGoneTest {

    private static final String DEF_CODE = "R113_UPLOAD";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskItemRepository taskItemRepository;

    @MockitoBean
    private FileStorageService fileStorage;
    @MockitoBean
    private TaskFileRepository taskFileRepository;

    @Test
    void uploadOnMissingTaskReturns404BeforeWriting() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", DEF_CODE + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "x".getBytes());

        mockMvc.perform(multipart("/api/tasks/{id}/files/upload", 999_999L).file(file))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        verifyNoInteractions(fileStorage);
    }

    @Test
    void putOnMissingTaskReturns404BeforeWriting() throws Exception {
        mockMvc.perform(put("/api/tasks/{id}/files/{defCode}", 999_999L, DEF_CODE)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("x".getBytes()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        verifyNoInteractions(fileStorage);
    }

    @Test
    void uploadFkConflictCleansWrittenFileAndReturns404() throws Exception {
        long taskId = taskWithItem();
        when(fileStorage.store(any(InputStream.class), anyString())).thenReturn("/tmp/r113-upload.xlsx");
        when(taskFileRepository.findByTaskIdAndDefCodeAndFileType(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(taskFileRepository.save(any(TaskFile.class)))
                .thenThrow(new DataIntegrityViolationException("fk: task deleted"));

        MockMultipartFile file = new MockMultipartFile("file", DEF_CODE + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "x".getBytes());

        mockMvc.perform(multipart("/api/tasks/{id}/files/upload", taskId).file(file))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        verify(fileStorage).delete("/tmp/r113-upload.xlsx");
    }

    @Test
    void putFkConflictCleansNewFileAndReturns404() throws Exception {
        long taskId = taskWithItem();
        when(fileStorage.newPath(".xlsx")).thenReturn("/tmp/r113-put.xlsx");
        when(taskFileRepository.findByTaskIdAndDefCodeAndFileType(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(taskFileRepository.save(any(TaskFile.class)))
                .thenThrow(new DataIntegrityViolationException("fk: task deleted"));

        mockMvc.perform(put("/api/tasks/{id}/files/{defCode}", taskId, DEF_CODE)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("x".getBytes()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        verify(fileStorage).delete("/tmp/r113-put.xlsx");
    }

    private long taskWithItem() {
        Task task = taskService.create(Task.TaskType.EXPORT, "R113-FILE-TASK");
        TaskItem item = new TaskItem();
        item.setTaskId(task.getId());
        item.setDefCode(DEF_CODE);
        taskItemRepository.save(item);
        return task.getId();
    }
}
