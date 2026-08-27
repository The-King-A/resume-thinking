-- V2 used BLOB, which is limited to 64 KiB on MySQL. Resume uploads are
-- accepted up to 5 MiB, so encrypted content needs the 16 MiB MEDIUMBLOB.
ALTER TABLE resumes
    MODIFY COLUMN raw_content_ciphertext MEDIUMBLOB NOT NULL;
