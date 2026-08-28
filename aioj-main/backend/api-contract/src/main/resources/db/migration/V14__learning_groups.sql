CREATE TABLE IF NOT EXISTS learning_groups (
    id BIGINT PRIMARY KEY,
    parent_group_id BIGINT,
    type VARCHAR(32) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    owner_user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    archived_at DATETIME(3),
    CONSTRAINT fk_learning_groups_parent FOREIGN KEY (parent_group_id) REFERENCES learning_groups(id),
    CONSTRAINT fk_learning_groups_owner FOREIGN KEY (owner_user_id) REFERENCES users(id),
    INDEX idx_learning_groups_owner (owner_user_id, type, status),
    INDEX idx_learning_groups_parent (parent_group_id, type, status),
    INDEX idx_learning_groups_type_status (type, status, created_at)
);

CREATE TABLE IF NOT EXISTS learning_group_members (
    id BIGINT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_learning_group_members_group FOREIGN KEY (group_id) REFERENCES learning_groups(id),
    CONSTRAINT fk_learning_group_members_user FOREIGN KEY (user_id) REFERENCES users(id),
    UNIQUE KEY uk_learning_group_members_group_user (group_id, user_id),
    INDEX idx_learning_group_members_user (user_id, group_id),
    INDEX idx_learning_group_members_group_role (group_id, role)
);
