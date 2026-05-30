-- ============================================================
-- 个人知识库助手 · 用户与会话历史 表结构（MySQL 8.0+）
-- ------------------------------------------------------------
-- 三张表：
--   users          用户（登录用，密码存 bcrypt 哈希）
--   conversations  会话（一个用户多段会话；id 即 conversation_id）
--   messages       消息（一段会话多条消息，用户/助手交替）
--
-- 字符集统一 utf8mb4（支持中文与 emoji）。
-- 外键级联删除：删用户 → 删其所有会话 → 删会话里所有消息。
--
-- 用法：
--   mysql -u root -p < db/schema.sql
-- 后端连接信息放各自的 .env（DB_URL / DB_USER / DB_PASSWORD），JWT 密钥放 JWT_SECRET。
-- ============================================================

CREATE DATABASE IF NOT EXISTS knowledge_assistant
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE knowledge_assistant;

-- ---------- 1) 用户表 ----------
CREATE TABLE IF NOT EXISTS users (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username      VARCHAR(50)     NOT NULL,
  password_hash VARCHAR(255)    NOT NULL,                 -- bcrypt 哈希，绝不存明文
  created_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------- 2) 会话表 ----------
CREATE TABLE IF NOT EXISTS conversations (
  id          CHAR(36)        NOT NULL,                   -- UUID，即前端/接口里的 conversation_id
  user_id     BIGINT UNSIGNED NOT NULL,
  title       VARCHAR(255)    NOT NULL DEFAULT '新会话',  -- 首条消息截取，或用户重命名
  backend     VARCHAR(20)     DEFAULT NULL,               -- python / langchain4j / springai（可选，记录这段会话用的实现）
  status      TINYINT         NOT NULL DEFAULT 0,         -- 逻辑删除：0=正常，1=已删除（删除不真删行，只置 1）
  created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_conv_user (user_id, status, updated_at),        -- 按用户列出未删除的最近会话
  CONSTRAINT fk_conv_user FOREIGN KEY (user_id)
    REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------- 3) 消息表 ----------
CREATE TABLE IF NOT EXISTS messages (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  conversation_id CHAR(36)        NOT NULL,
  role            VARCHAR(16)     NOT NULL,               -- 'user' / 'assistant'
  content         MEDIUMTEXT      NOT NULL,
  created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_msg_conv (conversation_id, id),                 -- 按会话顺序取消息
  CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id)
    REFERENCES conversations (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
