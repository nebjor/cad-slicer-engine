package com.slicercore.model;

/**
 * A model kept by the backend after an upload, so that further quotes for other filaments or
 * qualities reference it by id instead of sending the file again.
 *
 * @param modelId   content hash of the file; the value to send as {@code modelId} to {@code POST /api/quotes}
 * @param fileName  original file name without any path, echoed in quotes
 * @param sizeBytes size of the stored file
 */
public record UploadedModel(String modelId, String fileName, long sizeBytes) {
}
