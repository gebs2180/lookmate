package com.lookmate.app.matching;

import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.StorageProperties;
import org.springframework.stereotype.Component;

/** Reads photos from the private bucket. */
@Component
class BlobImageSource implements ImageSource {

    private final BlobStore blobs;
    private final StorageProperties storage;

    BlobImageSource(BlobStore blobs, StorageProperties storage) {
        this.blobs = blobs;
        this.storage = storage;
    }

    @Override
    public byte[] read(String objectKey) {
        return blobs.read(storage.photoBucket(), objectKey);
    }
}
