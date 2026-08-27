CREATE TABLE users (
    id BINARY(16) NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE llm_profiles (
    id BINARY(16) NOT NULL PRIMARY KEY,
    owner_id BINARY(16) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    endpoint_url VARCHAR(2048) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    api_key_ciphertext BLOB NOT NULL,
    api_key_nonce VARBINARY(12) NOT NULL,
    key_version INT NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    last_test_status VARCHAR(16) NULL,
    last_tested_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_llm_profiles_owner FOREIGN KEY (owner_id) REFERENCES users(id)
);
CREATE INDEX ix_llm_profiles_owner ON llm_profiles(owner_id);
