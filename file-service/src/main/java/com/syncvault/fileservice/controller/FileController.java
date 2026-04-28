package com.syncvault.fileservice.controller;

import com.syncvault.fileservice.dto.response.*;
import com.syncvault.fileservice.service.FileSearchService;
import com.syncvault.fileservice.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
@Tag(name = "Files", description = "File management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class FileController {

    private final FileService fileService;
    private final FileSearchService fileSearchService;

    @GetMapping("/search")
    @Operation(summary = "Semantic search across user's files using natural language")
    public ResponseEntity<List<FileSearchResult>> search(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            Authentication auth) {

        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(fileSearchService.search(userId, query, limit));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a new file")
    public ResponseEntity<UploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "baseVersion", required = false) String baseVersion,
            Authentication auth) throws IOException {

        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(fileService.uploadFile(file, userId));
    }

    @GetMapping("/{fileId}")
    @Operation(summary = "Get file metadata and presigned download URL")
    public ResponseEntity<FileInfoResponse> getFile(
            @PathVariable UUID fileId,
            Authentication auth) {

        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(fileService.getFile(fileId, userId));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "List all files for a user")
    public ResponseEntity<List<FileListItemResponse>> getUserFiles(
            @PathVariable UUID userId,
            Authentication auth) {

        UUID requestingUserId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(fileService.getUserFiles(userId, requestingUserId));
    }

    @PutMapping(value = "/{fileId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Update a file (with conflict detection)")
    public ResponseEntity<Object> updateFile(
            @PathVariable UUID fileId,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "baseVersion", required = false) String baseVersion,
            Authentication auth) throws IOException {

        UUID userId = UUID.fromString(auth.getName());
        Integer base = baseVersion != null ? Integer.parseInt(baseVersion) : null;
        Object result = fileService.updateFile(fileId, file, base, userId);

        if (result instanceof ConflictResponse) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        }
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{fileId}")
    @Operation(summary = "Soft-delete a file (moved to trash, recoverable for 30 days)")
    public ResponseEntity<MessageResponse> deleteFile(
            @PathVariable UUID fileId,
            Authentication auth) {

        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(fileService.deleteFile(fileId, userId));
    }

    @GetMapping("/{fileId}/versions")
    @Operation(summary = "Get version history for a file")
    public ResponseEntity<List<VersionListItemResponse>> getVersions(
            @PathVariable UUID fileId,
            Authentication auth) {

        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(fileService.getVersions(fileId, userId));
    }

    @PostMapping("/{fileId}/summarize")
    @Operation(summary = "Re-trigger AI summarization for an existing file (synchronous, max 30s)")
    public ResponseEntity<AiSummaryResponse> retriggerSummarization(
            @PathVariable UUID fileId,
            Authentication auth) {

        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(fileService.retriggerSummarization(fileId, userId));
    }

    @GetMapping("/{fileId}/summary")
    @Operation(summary = "Get stored AI summary for a file")
    public ResponseEntity<AiSummaryResponse> getSummary(
            @PathVariable UUID fileId,
            Authentication auth) {

        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(fileService.getSummary(fileId, userId));
    }

    @GetMapping("/{fileId}/versions/{versionNumber}/restore")
    @Operation(summary = "Restore a specific version of a file")
    public ResponseEntity<RestoreResponse> restoreVersion(
            @PathVariable UUID fileId,
            @PathVariable int versionNumber,
            Authentication auth) {

        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(fileService.restoreVersion(fileId, versionNumber, userId));
    }
}
