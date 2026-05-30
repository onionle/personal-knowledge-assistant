"""
========================================================================
鉴权：密码哈希 + JWT
------------------------------------------------------------------------
- 注册时用 bcrypt 把密码哈希后存库（绝不存明文）
- 登录成功签发一个 JWT（里头放 user_id + username），前端存起来，
  之后每个请求带 Authorization: Bearer <token>
- 三个后端共用同一个 JWT_SECRET（.env），所以同一个 token 三个实现都认

提供两个 FastAPI 依赖：
  get_current_user        必须登录，否则 401
  get_current_user_optional 可选登录（匿名也放行，登录了就拿到用户）—— 给 /chat 用，
                          这样没登录也能聊，登录了才落库存历史
========================================================================
"""

import os
import time
from typing import Optional

import bcrypt
import jwt
from fastapi import Depends, HTTPException, Request
from sqlalchemy.orm import Session

from db import User, get_db


JWT_SECRET = os.environ.get("JWT_SECRET", "dev-secret-change-me")
JWT_ALGORITHM = "HS256"
JWT_TTL_SECONDS = 7 * 24 * 3600  # token 有效期 7 天


# ============== 密码哈希 ==============
def hash_password(plain: str) -> str:
    return bcrypt.hashpw(plain.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def verify_password(plain: str, hashed: str) -> bool:
    try:
        return bcrypt.checkpw(plain.encode("utf-8"), hashed.encode("utf-8"))
    except ValueError:
        return False


# ============== JWT 签发 / 解析 ==============
def create_access_token(user: User) -> str:
    now = int(time.time())
    payload = {
        "sub": str(user.id),
        "username": user.username,
        "iat": now,
        "exp": now + JWT_TTL_SECONDS,
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def _decode_token(token: str) -> dict:
    try:
        return jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
    except jwt.PyJWTError:
        raise HTTPException(status_code=401, detail="token 无效或已过期")


def _extract_bearer(request: Request) -> Optional[str]:
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        return auth[7:].strip()
    return None


# ============== FastAPI 依赖 ==============
def get_current_user(request: Request, db: Session = Depends(get_db)) -> User:
    """必须登录。没带 / 带错 token → 401。"""
    token = _extract_bearer(request)
    if not token:
        raise HTTPException(status_code=401, detail="未登录")
    payload = _decode_token(token)
    user = db.get(User, int(payload["sub"]))
    if user is None:
        raise HTTPException(status_code=401, detail="用户不存在")
    return user


def get_current_user_optional(request: Request, db: Session = Depends(get_db)) -> Optional[User]:
    """可选登录：没带 token 返回 None（匿名放行），带了就校验并返回用户。"""
    token = _extract_bearer(request)
    if not token:
        return None
    payload = _decode_token(token)
    return db.get(User, int(payload["sub"]))
