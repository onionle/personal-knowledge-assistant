"""
========================================================================
数据库访问层（SQLAlchemy + MySQL）
------------------------------------------------------------------------
用户系统（登录 / 历史）用到三张表：users / conversations / messages，
表结构见项目根 db/schema.sql。这里用 SQLAlchemy 2.x 的 ORM 映射它们，
并提供一个 get_db() 依赖给 FastAPI 用。

连接信息从项目根 .env 读：DB_HOST / DB_PORT / DB_USER / DB_PASSWORD / DB_NAME。
========================================================================
"""

import os
from datetime import datetime

from sqlalchemy import (
    create_engine, String, Text, BigInteger, ForeignKey, DateTime, func,
)
from sqlalchemy.orm import (
    DeclarativeBase, Mapped, mapped_column, relationship, sessionmaker,
)


# ============== 连接 ==============
DB_HOST = os.environ.get("DB_HOST", "127.0.0.1")
DB_PORT = os.environ.get("DB_PORT", "3306")
DB_USER = os.environ.get("DB_USER", "root")
DB_PASSWORD = os.environ.get("DB_PASSWORD", "")
DB_NAME = os.environ.get("DB_NAME", "knowledge_assistant")

# mysql+pymysql 用纯 Python 驱动；charset=utf8mb4 支持中文
DATABASE_URL = (
    f"mysql+pymysql://{DB_USER}:{DB_PASSWORD}@{DB_HOST}:{DB_PORT}/{DB_NAME}?charset=utf8mb4"
)

# pool_pre_ping：连接长时间空闲被 MySQL 掐断后，自动重连而不是报错
engine = create_engine(DATABASE_URL, pool_pre_ping=True, future=True)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


# ============== ORM 模型（对应 db/schema.sql 三张表）==============
class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    username: Mapped[str] = mapped_column(String(50), unique=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class Conversation(Base):
    __tablename__ = "conversations"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)  # UUID = conversation_id
    user_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("users.id", ondelete="CASCADE"))
    title: Mapped[str] = mapped_column(String(255), default="新会话")
    backend: Mapped[str | None] = mapped_column(String(20), nullable=True)
    # 逻辑删除：0=正常，1=已删除（删除不真删行，只置 1，列表里过滤掉）
    status: Mapped[int] = mapped_column(default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )
    messages: Mapped[list["Message"]] = relationship(
        back_populates="conversation", cascade="all, delete-orphan"
    )


class Message(Base):
    __tablename__ = "messages"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    conversation_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("conversations.id", ondelete="CASCADE")
    )
    role: Mapped[str] = mapped_column(String(16))  # user / assistant
    content: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    conversation: Mapped["Conversation"] = relationship(back_populates="messages")


# ============== FastAPI 依赖：每个请求一个 Session ==============
def get_db():
    """FastAPI 依赖：进请求开 Session，出请求关掉。"""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
