package com.slicercore.upload;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Model upload.
 *
 * POST /api/models   multipart upload of an STL/3MF; the response carries the id to quote with
 *
 * A quote by id skips the upload, which for a large model is a good share of the wait. The
 * model stays available for the configured time (see {@code quote.model-store}); afterwards a
 * quote by its id gets 404 and the client uploads again.
 */
@RestController
@RequestMapping("/api/models")
@Tag(name = "Models", description = "Upload a model once and quote it by id")
public class ModelController {

    private final UploadService uploadService;

    public ModelController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UploadedModel upload(@RequestPart("file") MultipartFile file) {
        return uploadService.storeUpload(file);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleTooLarge(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "The uploaded model exceeds the size limit.");
    }
}
