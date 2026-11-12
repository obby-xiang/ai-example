package com.example.configmgr.task.controller;

import com.example.configmgr.common.ApiResponse;
import com.example.configmgr.definition.service.DefinitionService;
import com.example.configmgr.excel.ExcelTemplateBuilder;
import com.example.configmgr.file.FileStorageService;
import com.example.configmgr.file.StoredFile;
import com.example.configmgr.task.entity.Task;
import com.example.configmgr.task.repo.TaskFileRepository;
import com.example.configmgr.task.repo.TaskItemRepository;
import com.example.configmgr.task.repo.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileControllerZipTest {

    private final FileStorageService fileStorage = mock(FileStorageService.class);
    private final TaskFileRepository taskFileRepository = mock(TaskFileRepository.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final DefinitionService definitionService = mock(DefinitionService.class);
    private final FileController controller = new FileController(fileStorage, taskFileRepository,
            mock(TaskItemRepository.class), taskRepository, definitionService, mock(ExcelTemplateBuilder.class));

    @Test
    void zipDirectoryEntriesAreSkippedWithoutBecomingUnmatchedFiles() throws Exception {
        when(taskRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(new Task()));
        when(definitionService.findByCode("ROLE_DICT")).thenReturn(new com.example.configmgr.definition.entity.ConfigDefinition());
        when(fileStorage.store(any(), anyString()))
                .thenReturn(new StoredFile("stored-role.xlsx", Path.of("target/stored-role.xlsx")));
        when(taskFileRepository.findByTaskIdAndDefCodeAndFileType(1L, "ROLE_DICT", "UPLOAD"))
                .thenReturn(Optional.empty());
        MockMultipartFile upload = new MockMultipartFile("file", "roles.zip", "application/zip", zipWithDirectory());

        ApiResponse<Map<String, Object>> response = controller.upload(1L, upload, List.of("ROLE_DICT"));

        assertThat(response.getData().get("count")).isEqualTo(1);
        assertThat((List<?>) response.getData().get("unmatchedFiles")).isEmpty();
        verify(fileStorage).store(any(), anyString());
    }

    private static byte[] zipWithDirectory() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("nested/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ROLE_DICT.xlsx"));
            zip.write(new byte[] { 1 });
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
