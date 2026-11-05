CREATE TABLE image_upload_metadata (
    object_key VARBINARY(1024) NOT NULL,
    user_id BIGINT NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (object_key),
    KEY idx_image_upload_metadata_expires_at (expires_at, object_key)
) ENGINE=InnoDB;
