"""
========================================================================
LangChain Python 版 · Step 6：流式输出 + 前端
------------------------------------------------------------------------
在 Step 5 (Agent /agent) 基础上新增：
  7. /chat/stream 接口 —— SSE 流式输出：模型边生成边逐字推送，
     前端能像 ChatGPT 那样"一个字一个字蹦出来"，而不是等全部生成完才显示。
     底层用 llm.stream()（返回一个 chunk 迭代器），用 SSE(text/event-stream)
     把每个 token 推给浏览器。
  + 开启 CORS：前端(React)跑在另一个端口(5173)，浏览器跨域请求需要后端放行。
  + 配套 React 前端见 frontend/ 目录。

历史接口（/chat /ask /agent 等）保持不变。

------------------------------------------------------------------------
在 Step 4 (完整 RAG /ask) 基础上 Step 5 新增：
  /agent 接口 —— 让模型自己决定"要不要查知识库"：
       - 把"检索知识库"做成一个 tool（函数）交给模型
       - 模型看到用户问题后自行判断：闲聊/打招呼 → 直接答；
         需要专业知识 → 自动调用检索工具，拿到资料再回答
       - 这就是 Agent + Function Calling：模型不再被动接收 context，
         而是主动选择用什么工具

  对比 Step 4 的 /ask（"必定先检索再回答"，流程写死）：
  Step 5 的 /agent 把"是否检索"的决策权交给了模型本身。

Python 版刻意用"手动 agent 循环"展示函数调用的底层机制：
  调模型 → 模型返回 tool_calls → 我们执行工具 → 把结果回灌 → 再调模型 → ... → 最终回答。
其他实现：LangChain4j 用 AiServices.tools() 自动跑这个循环，Spring AI 用 ChatClient.tools()，
两个 Java 版都把循环"藏"在框架里。

⚠️ Agent 用的是 deepseek-chat (V3) 而不是主模型——DeepSeek 的思维链模型
   (v4-pro / reasoner) 不支持 tool_choice / function calling，跟 /extract 一个道理。

入库脚本是单独的 ingest.py（离线任务），不在 FastAPI 进程里跑。

启动方式跟 Step 1 一样：
    ./scripts/start-python.sh
========================================================================
"""

import os
import json
import uuid
from functools import lru_cache
from typing import Optional

import chromadb
from fastapi import FastAPI, HTTPException, Depends
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from dotenv import load_dotenv, find_dotenv

# 用户系统（登录 / 历史）
from sqlalchemy import select, desc, func
from sqlalchemy.orm import Session
from db import get_db, SessionLocal, User, Conversation, Message
import auth

# 本实现的标识（写进 conversations.backend，便于区分是哪套实现存的）
BACKEND_NAME = "python"

# 多轮对话带入 LLM 的历史消息条数上限（控制 token 消耗）。
# 知识库场景每次问题大多独立、上下文不需要太多，默认只带最近 10 条（约 5 轮）。
DB_CONTEXT_MESSAGES = int(os.environ.get("CHAT_HISTORY_MESSAGES", "10"))


def load_db_history(conv_id: str, user_id: int, limit: int = 20) -> list:
    """从 DB 取这段会话最近 limit 条消息，构建成 LLM 上下文（登录用户）。
    这样即使服务重启、换了机器，续聊也能带上完整历史——进程内记忆做不到。"""
    s = SessionLocal()
    try:
        conv = s.get(Conversation, conv_id)
        if conv is None or conv.user_id != user_id or conv.status != 0:
            return []
        rows = s.scalars(
            select(Message).where(Message.conversation_id == conv_id).order_by(Message.id)
        ).all()
        recent = rows[-limit:]
        out = []
        for m in recent:
            out.append(HumanMessage(content=m.content) if m.role == "user" else AIMessage(content=m.content))
        return out
    finally:
        s.close()


def persist_turn(user_id: int, conv_id: str, user_msg: str, assistant_msg: str) -> None:
    """把一轮问答写进 MySQL（仅登录用户）。新会话自动建，标题取首条消息。"""
    s = SessionLocal()
    try:
        conv = s.get(Conversation, conv_id)
        if conv is None:
            conv = Conversation(
                id=conv_id, user_id=user_id,
                title=(user_msg[:24] or "新会话"), backend=BACKEND_NAME,
            )
            s.add(conv)
        elif conv.user_id != user_id:
            return  # 不是本人的会话，拒绝写入
        else:
            conv.updated_at = func.now()  # 置顶到历史列表最前
        s.add(Message(conversation_id=conv_id, role="user", content=user_msg))
        s.add(Message(conversation_id=conv_id, role="assistant", content=assistant_msg))
        s.commit()
    except Exception:
        s.rollback()
    finally:
        s.close()

from langchain_openai import ChatOpenAI
# 新增：消息基类 + 提示词模板 + 历史消息占位符
from langchain_core.messages import HumanMessage, AIMessage, BaseMessage, SystemMessage, ToolMessage
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
# 新增（Step 5）：@tool 装饰器，把普通函数变成模型能调用的"工具"
from langchain_core.tools import tool
# 新增（Step 3）：嵌入模型 + Chroma 向量库
from langchain_chroma import Chroma
# 自己实现的 Embeddings 子类，包了 sentence-transformers。
# langchain-huggingface 锁死 langchain-core<1.0 跟我们冲突，所以自己写
from embeddings import BgeSmallZhEmbeddings


# 加载 .env（项目根目录），系统已有环境变量优先级更高
load_dotenv(find_dotenv(usecwd=False))


# ============== 全局：LLM 实例 ==============
# 主对话模型：可以是任何 OpenAI 兼容模型，包括 deepseek-v4-pro / deepseek-reasoner
# 这些"思维链推理模型"在多轮对话、复杂问题上效果更好
llm = ChatOpenAI(
    model=os.environ.get("DEEPSEEK_MODEL", "deepseek-chat"),
    base_url=os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
    api_key=os.environ["DEEPSEEK_API_KEY"],
    temperature=0.7,
    max_tokens=1024,
)

# 结构化输出专用模型：固定用 deepseek-chat (V3)
# 原因：DeepSeek 的思维链推理模型 (v4-pro / reasoner) 不支持 tool_choice，
# 而结构化输出底层走的就是 function calling。
# 真实项目里"按任务挑模型"很常见——抽取/分类用便宜快的，对话用强的。
# temperature=0 让抽取结果更稳定（同样输入 → 同样 JSON）
extraction_llm = ChatOpenAI(
    model="deepseek-chat",
    base_url=os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
    api_key=os.environ["DEEPSEEK_API_KEY"],
    temperature=0,
    max_tokens=1024,
)

# Agent 专用模型（Step 5）：同样固定 deepseek-chat (V3)。
# 原因跟 extraction_llm 一样——function calling 只有 V3 支持，思维链模型不支持。
# 单独建一个实例是为了语义清晰（Agent 归 Agent），也方便以后单独调参。
agent_llm = ChatOpenAI(
    model="deepseek-chat",
    base_url=os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
    api_key=os.environ["DEEPSEEK_API_KEY"],
    temperature=0.3,
    max_tokens=1024,
)


# ============== 全局：Prompt 模板 ==============
# ChatPromptTemplate.from_messages 接受一个"角色, 内容"的元组列表
#   - ("system", "...{var}...")        系统提示，含变量占位符
#   - MessagesPlaceholder("history")   把历史消息插进来（List[BaseMessage]）
#   - ("human", "{input}")             本轮用户输入
# 这种"模板 + 变量"的写法的好处：业务层关心的是"想让 AI 扮什么角色"
# 而不是要拼一堆字符串。Step 4 RAG 时会再加一段 "context" 把检索结果塞进来。
chat_prompt = ChatPromptTemplate.from_messages([
    (
        "system",
        "你是一个{role}。使用{language}回答用户问题，"
        "回答控制在 {max_words} 字以内。"
    ),
    MessagesPlaceholder(variable_name="history"),
    ("human", "{input}"),
])


# ============== 全局：RAG 问答 Prompt 模板（Step 4 新增） ==============
# 跟普通 chat_prompt 的区别就在 system 里多了一段 {context}——
# 这就是 RAG 的精髓："把检索到的资料塞进 prompt，让模型基于资料回答"。
#
# 两条关键约束写进系统提示，能显著降低"幻觉"：
#   1. "只能根据参考资料回答"——不让模型用自己训练时的记忆瞎编
#   2. "资料里没有就说不知道"——给模型一个诚实的出口，而不是硬编
# RAG 没有对话历史（每次问答独立），所以不需要 MessagesPlaceholder。
rag_prompt = ChatPromptTemplate.from_messages([
    (
        "system",
        "你是一个严谨的知识库问答助手。只能根据下面【参考资料】里的内容回答用户问题。\n"
        "如果参考资料里找不到答案，就直说\"根据已有资料无法回答\"，绝对不要编造。\n"
        "使用{language}回答，控制在 {max_words} 字以内。\n\n"
        "【参考资料】\n{context}"
    ),
    ("human", "{question}"),
])


# ============== 全局：对话记忆（最简单的版本） ==============
# 生产环境里要用 Redis / 数据库 / Vector Store 持久化；
# 这里用进程内 dict——重启即清空，但够演示多轮对话。
#
# 每个 conversation_id 对应一个消息列表（HumanMessage + AIMessage 交替）
CONVERSATIONS: dict[str, list[BaseMessage]] = {}

# 单次对话最多保留多少条消息（控制 prompt 体积，超出就丢最早的）
# 之所以是偶数：每轮 1 条 human + 1 条 ai，保持成对
MAX_HISTORY_MESSAGES = 20


# ============== 向量检索：懒加载（Step 3 新增） ==============
# 这些常量跟 ingest.py 保持一致——必须用同一个 collection 名 + 同一个嵌入模型，
# 否则查询向量和库里向量在同一个语义空间里都对不上。
COLLECTION_NAME = "knowledge_base_bge_small_zh"
# 跟 ingest.py 完全一致：默认 HF 模型 ID，设了 EMBED_MODEL_NAME 就用本地目录离线加载。
# 查询用的模型必须和入库时一模一样，否则向量对不上，所以两边都读同一个环境变量。
EMBED_MODEL_NAME = os.environ.get("EMBED_MODEL_NAME", "BAAI/bge-small-zh-v1.5")
CHROMA_HOST = os.environ.get("CHROMA_HOST", "localhost")
CHROMA_PORT = int(os.environ.get("CHROMA_PORT", "8000"))


@lru_cache(maxsize=1)
def get_vectorstore() -> Chroma:
    """
    懒加载向量库连接。

    用 @lru_cache 保证整个进程只初始化一次（首次调用 /search 时触发）。
    选懒加载而非启动时加载的原因：
      - 启动 FastAPI 时不强依赖 Chroma 服务存在，开发体验更宽容
      - BGE 模型加载要几秒，挪到首次请求里对启动友好
      - 加载失败时报错落在请求 /search，能直接看到完整 HTTP 错误
    代价：第一次调 /search 会等 3-5 秒（模型加载 + 连库），之后就秒回。

    重要：这里用的 BgeSmallZhEmbeddings 必须跟 ingest.py 完全一致——
    同样的模型、同样的归一化、同样的查询 prefix，否则查询向量和库里向量
    在同一个语义空间里都对不上号。
    """
    embeddings = BgeSmallZhEmbeddings(model_name=EMBED_MODEL_NAME, device="cpu")
    client = chromadb.HttpClient(host=CHROMA_HOST, port=CHROMA_PORT)
    return Chroma(
        client=client,
        collection_name=COLLECTION_NAME,
        embedding_function=embeddings,
    )


# ============== FastAPI 应用 ==============
app = FastAPI(
    title="LangChain Python 知识库助手",
    description="Step 6: 流式输出 + 前端",
    version="0.6.0",
)

# CORS：前端 React 开发服务器跑在 http://localhost:5173，跟后端(8001)不同源，
# 浏览器会拦跨域请求。这里放行本地前端端口（生产应收紧到真实域名）。
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_methods=["*"],
    allow_headers=["*"],
)


# ============== /chat 接口 ==============
class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=4000, description="用户输入")
    conversation_id: Optional[str] = Field(
        default=None,
        description="多轮对话标识。不传会生成新 ID 并在响应里返回，下一轮带上即可继续"
    )
    role: str = Field(default="简洁、友好的中文助手", description="角色设定")
    language: str = Field(default="中文", description="回答语言")
    max_words: int = Field(default=200, ge=20, le=2000, description="回答最大字数")


class ChatResponse(BaseModel):
    reply: str
    conversation_id: str = Field(description="带着这个 ID 再请求就是同一轮对话")
    model: str
    turns: int = Field(description="本会话进行到第几轮")


@app.get("/")
def root():
    return {"status": "ok", "service": "langchain-python", "step": 6}


@app.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest):
    """
    多轮对话：
      - 不传 conversation_id —— 新建会话
      - 传同一个 conversation_id —— 模型能看到这个会话之前所有问答
    """
    # 1) 取/建会话历史
    conv_id = req.conversation_id or str(uuid.uuid4())
    history = CONVERSATIONS.setdefault(conv_id, [])

    # 2) 用模板渲染出最终发给模型的消息列表
    #    format_messages 会做四件事：
    #      a. 替换 system 里的 {role}/{language}/{max_words}
    #      b. 把 history 这个列表直接拼到 system 后面
    #      c. 用本轮 input 构造一条 HumanMessage 放最末
    #      d. 返回 List[BaseMessage] 给模型
    messages = chat_prompt.format_messages(
        role=req.role,
        language=req.language,
        max_words=req.max_words,
        history=history,
        input=req.message,
    )

    # 3) 调用 LLM
    try:
        ai_message = llm.invoke(messages)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"调用 LLM 失败: {e}")

    # 4) 把本轮 human + ai 追加到历史，下一轮模型才看得到
    history.append(HumanMessage(content=req.message))
    history.append(ai_message)

    # 5) 历史长度封顶——超出就丢最早的，保护 prompt 不爆
    if len(history) > MAX_HISTORY_MESSAGES:
        del history[: len(history) - MAX_HISTORY_MESSAGES]

    return ChatResponse(
        reply=ai_message.content,
        conversation_id=conv_id,
        model=llm.model_name,
        turns=len(history) // 2,
    )


@app.post("/chat/stream")
def chat_stream(req: ChatRequest, user: Optional[User] = Depends(auth.get_current_user_optional)):
    """
    流式版的多轮对话（SSE）。

    跟 /chat 的唯一区别：不用 llm.invoke()（等全部生成完一次性返回），
    而是 llm.stream()（边生成边一段段 yield），用 SSE 把每个 token 实时推给前端。

    SSE 协议很简单：HTTP 响应头 Content-Type: text/event-stream，
    然后每条消息是一行 `data: <内容>\\n\\n`。我们把每个 token 包成 JSON
    （`{"token": "..."}`）再发，避免 token 里有换行把 SSE 格式冲掉；
    最后发一条 `{"done": true, "conversation_id": ...}` 告诉前端结束了。
    """
    conv_id = req.conversation_id or str(uuid.uuid4())
    # 提前取出 user_id（int）。不能把 ORM 的 user 对象带进 generator——
    # 请求的 DB Session 在流真正跑起来前就可能关了，对象会失效。
    user_id = user.id if user else None

    # 上下文来源：登录用户从 DB 取（重启/换机器也能续历史），匿名用进程内记忆窗口
    if user_id is not None:
        history = load_db_history(conv_id, user_id, DB_CONTEXT_MESSAGES)
    else:
        history = CONVERSATIONS.setdefault(conv_id, [])

    messages = chat_prompt.format_messages(
        role=req.role,
        language=req.language,
        max_words=req.max_words,
        history=history,
        input=req.message,
    )

    def event_generator():
        # 先把 conversation_id 发给前端（流式场景下前端要尽早拿到它以便续聊）
        yield f"data: {json.dumps({'conversation_id': conv_id})}\n\n"

        full_reply = []
        try:
            # llm.stream() 返回 AIMessageChunk 的迭代器，每个 chunk.content 是一小段文本
            for chunk in llm.stream(messages):
                token = chunk.content
                if not token:
                    continue
                full_reply.append(token)
                yield f"data: {json.dumps({'token': token})}\n\n"
        except Exception as e:
            yield f"data: {json.dumps({'error': str(e)})}\n\n"
            return

        # 流结束：把完整回答写回会话记忆（下一轮才看得到），并通知前端 done
        reply_text = "".join(full_reply)
        if user_id is not None:
            # 登录：落库（下一轮直接从 DB 读历史当上下文）
            persist_turn(user_id, conv_id, req.message, reply_text)
        else:
            # 匿名：写进程内记忆窗口
            history.append(HumanMessage(content=req.message))
            history.append(AIMessage(content=reply_text))
            if len(history) > MAX_HISTORY_MESSAGES:
                del history[: len(history) - MAX_HISTORY_MESSAGES]

        yield f"data: {json.dumps({'done': True, 'conversation_id': conv_id})}\n\n"

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        # 关掉 Nginx 等中间层的缓冲，确保 token 即时到达
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.delete("/conversations/{conv_id}")
def delete_conversation(
    conv_id: str,
    user: Optional[User] = Depends(auth.get_current_user_optional),
    db: Session = Depends(get_db),
):
    """
    删除会话：
      - 登录用户 → 逻辑删除 DB 里这段会话（校验归属，置 status=1，不真删）
      - 同时清掉进程内记忆（匿名场景就只做这个）
    """
    if user is not None:
        conv = db.get(Conversation, conv_id)
        if conv is not None and conv.user_id == user.id:
            conv.status = 1
            db.commit()
    CONVERSATIONS.pop(conv_id, None)
    return {"status": "deleted", "conversation_id": conv_id}


@app.get("/conversations/{conv_id}")
def get_conversation(conv_id: str):
    """查看某个会话的完整历史（调试用）"""
    if conv_id not in CONVERSATIONS:
        raise HTTPException(404, "conversation not found")
    return {
        "conversation_id": conv_id,
        "messages": [
            {"role": "user" if isinstance(m, HumanMessage) else "ai", "content": m.content}
            for m in CONVERSATIONS[conv_id]
        ],
    }


# ============== /extract 接口：结构化输出演示 ==============
# Pydantic 模型同时承担两个角色：
#   1) FastAPI 用它做响应序列化
#   2) LangChain 用它生成 JSON Schema 喂给模型，让模型"按这个 schema 输出"
# 注意 Field 里的 description—— LangChain 会把它写进 schema，模型看得到。
class Person(BaseModel):
    """从一段文字里抽取出来的人物信息"""
    name: str = Field(description="人物姓名")
    age: Optional[int] = Field(default=None, description="年龄，未提及就留空")
    occupation: Optional[str] = Field(default=None, description="职业，未提及就留空")
    summary: str = Field(description="一句话概括这个人")


class ExtractRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=4000, description="包含人物信息的文字")


@app.post("/extract", response_model=Person)
def extract(req: ExtractRequest):
    """
    结构化输出：保证返回值一定是合法的 Person JSON。

    实现原理：with_structured_output() 会自动把 Pydantic 模型转成
    JSON Schema，通过 Function Calling 协议告诉模型"请按这个 schema 输出"。

    ⚠️ 两个细节：
       1. 用 extraction_llm 而不是 llm —— 思维链模型不支持 tool_choice
       2. method="function_calling" —— langchain-openai 1.x 默认走的新 response_format
          json_schema 协议 DeepSeek 不支持，老的 function_calling 协议是稳的
    """
    structured_llm = extraction_llm.with_structured_output(Person, method="function_calling")
    try:
        return structured_llm.invoke(
            f"从下面这段话里提取人物信息，如果某字段没提到就留空：\n\n{req.text}"
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"结构化抽取失败: {e}")


# ============== /search 接口：纯检索演示（Step 3 新增） ==============
# 这个接口只做"检索"，不调 LLM——目的是单独验证 RAG 的"前半段"是否好用：
#   - 入库的文档能不能被找回来？
#   - 问"RAG 是什么"能命中 rag-basics.md 而不是 langchain.md 吗？
# 检索没问题再进 Step 4 把这些片段拼进 prompt 让 LLM 总结回答。
class SearchRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=2000, description="检索问题")
    k: int = Field(default=3, ge=1, le=20, description="返回 Top K 个相关文档块")


class SearchHit(BaseModel):
    content: str = Field(description="文档块原文")
    source: str = Field(description="来源文件名")
    score: Optional[float] = Field(default=None, description="距离分数（越小越相似）")


class SearchResponse(BaseModel):
    query: str
    hits: list[SearchHit]


@app.post("/search", response_model=SearchResponse)
def search(req: SearchRequest):
    """
    在 Chroma 知识库里检索 Top K 个语义相近的文档块。

    流程：
      1. BGE 把 query 编码成 512 维向量（自动加 query_instruction 前缀）
      2. Chroma 用 L2 距离找最近的 K 条
      3. 返回每条的原文 + 来源 + 距离

    前置条件：已经跑过 `python ingest.py` 把 docs/knowledge/ 写进库。
    """
    try:
        vs = get_vectorstore()
    except Exception as e:
        raise HTTPException(
            status_code=503,
            detail=f"向量库未就绪（请确认 Chroma 服务在 {CHROMA_HOST}:{CHROMA_PORT} 跑着，且已运行 ingest.py）: {e}",
        )

    try:
        # similarity_search_with_score 返回 [(Document, distance)] 列表
        # distance 越小越相似（BGE 归一化后大致在 0~2 之间，0 = 完全相同）
        results = vs.similarity_search_with_score(req.query, k=req.k)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"检索失败: {e}")

    return SearchResponse(
        query=req.query,
        hits=[
            SearchHit(
                content=doc.page_content,
                source=doc.metadata.get("source", "unknown"),
                score=float(dist),
            )
            for doc, dist in results
        ],
    )


# ============== /ask 接口：完整 RAG 问答（Step 4 新增） ==============
# 这是 Step 3 /search 的"下半段"——检索回来的资料不再直接返回，
# 而是拼进 prompt 让 LLM 基于资料生成自然语言回答。
class AskRequest(BaseModel):
    question: str = Field(..., min_length=1, max_length=2000, description="要问知识库的问题")
    k: int = Field(default=4, ge=1, le=20, description="检索 Top K 个文档块作为参考资料")
    language: str = Field(default="中文", description="回答语言")
    max_words: int = Field(default=300, ge=20, le=2000, description="回答最大字数")


class AskResponse(BaseModel):
    answer: str = Field(description="LLM 基于参考资料生成的回答")
    sources: list[SearchHit] = Field(description="本次回答引用的文档块（可溯源）")
    model: str


@app.post("/ask", response_model=AskResponse)
def ask(req: AskRequest):
    """
    完整 RAG 问答：检索 + 生成。

    流程（手动拼接，看得见每一步）：
      1. 检索：BGE 把问题嵌入，去 Chroma 取 Top K 个最相关的文档块
      2. 组装 context：把这些块编号 + 标注来源，拼成一段"参考资料"
      3. 渲染 prompt：用 rag_prompt 把 context + question 填进模板
      4. 生成：调 LLM 让它只根据参考资料回答
      5. 返回：回答 + 引用到的来源（可溯源，是 RAG 相比纯 LLM 的关键优势）

    前置条件：已跑过 `python ingest.py` 把 docs/knowledge/ 写进库。
    """
    # 1) 检索
    try:
        vs = get_vectorstore()
    except Exception as e:
        raise HTTPException(
            status_code=503,
            detail=f"向量库未就绪（确认 Chroma 在 {CHROMA_HOST}:{CHROMA_PORT} 跑着且已 ingest）: {e}",
        )
    try:
        results = vs.similarity_search_with_score(req.question, k=req.k)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"检索失败: {e}")

    # 没检索到任何资料：直接返回兜底答案，不浪费一次 LLM 调用
    if not results:
        return AskResponse(answer="根据已有资料无法回答。", sources=[], model=llm.model_name)

    # 2) 组装 context：给每块编号 + 标来源，方便模型引用、也方便人核对
    context = "\n\n".join(
        f"[资料{i + 1}｜来源：{doc.metadata.get('source', 'unknown')}]\n{doc.page_content}"
        for i, (doc, _dist) in enumerate(results)
    )

    # 3) + 4) 渲染 prompt 并调用 LLM
    messages = rag_prompt.format_messages(
        language=req.language,
        max_words=req.max_words,
        context=context,
        question=req.question,
    )
    try:
        ai_message = llm.invoke(messages)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"调用 LLM 失败: {e}")

    # 5) 回答 + 来源一起返回
    return AskResponse(
        answer=ai_message.content,
        sources=[
            SearchHit(
                content=doc.page_content,
                source=doc.metadata.get("source", "unknown"),
                score=float(dist),
            )
            for doc, dist in results
        ],
        model=llm.model_name,
    )


# ============== /agent 接口：Agent + 工具调用（Step 5 新增） ==============
# 跟 Step 4 /ask 的本质区别：
#   /ask  —— 流程写死："必定先检索，再把结果喂给 LLM"
#   /agent —— 把"检索"做成工具交给模型，由【模型自己决定】要不要调、调几次。
#            问"你好" → 模型直接答；问"RAG 是什么" → 模型自己去调检索工具。

# --- 1) 定义工具 ---
# @tool 装饰器把普通函数变成模型能"看见并调用"的工具。
# 函数的 docstring 至关重要！模型就是靠它判断"什么时候该用这个工具"，
# 所以要把"适用场景"写清楚（这等于是写给模型看的说明书）。
@tool
def search_knowledge_base(query: str) -> str:
    """检索本地知识库，获取关于 RAG、LangChain、LangChain4j、Spring AI、向量库等
    专业主题的资料。当用户的问题涉及这些专业知识、需要事实依据时调用本工具。
    普通闲聊、打招呼、常识性问题不要调用。

    参数 query: 用来检索的关键词或问题。
    返回: 命中的知识库片段（含来源），或"没找到"。
    """
    try:
        vs = get_vectorstore()
        results = vs.similarity_search_with_score(query, k=4)
    except Exception as e:
        return f"检索失败：{e}"
    if not results:
        return "知识库里没有找到相关资料。"
    return "\n\n".join(
        f"[来源：{doc.metadata.get('source', 'unknown')}]\n{doc.page_content}"
        for doc, _dist in results
    )


# --- 2) 把工具绑定到模型 + 准备工具查找表 ---
# bind_tools 会把工具的"名字 + 参数 schema + docstring"通过 function calling
# 协议告诉模型，模型于是知道"我有这么个工具可以用"。
AGENT_TOOLS = [search_knowledge_base]
agent_llm_with_tools = agent_llm.bind_tools(AGENT_TOOLS)
TOOLS_BY_NAME = {t.name: t for t in AGENT_TOOLS}

AGENT_SYSTEM_PROMPT = (
    "你是一个知识库智能助手。你有一个 search_knowledge_base 工具可以检索专业知识库。\n"
    "规则：\n"
    "1. 用户问到 RAG、LangChain、向量库等专业内容时，先调用工具检索，再基于检索结果回答；\n"
    "2. 普通闲聊、打招呼、你已知的常识问题，直接回答，不要调用工具；\n"
    "3. 基于工具返回的资料回答时，不要编造资料里没有的内容。\n"
    "用中文回答，简洁清楚。"
)

# 防止模型陷入"反复调工具"的死循环，限制最多调用轮数
MAX_AGENT_STEPS = 5


class AgentRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=2000, description="用户输入")


class AgentResponse(BaseModel):
    reply: str = Field(description="Agent 最终回答")
    tools_used: list[str] = Field(description="本次实际调用了哪些工具（空=没查知识库，直接答的）")
    steps: int = Field(description="经过了几轮模型调用")


@app.post("/agent", response_model=AgentResponse)
def agent(req: AgentRequest):
    """
    手动 Agent 循环——把 function calling 的每一步都摊开：
      1. 带着工具定义调模型
      2. 模型若返回 tool_calls：逐个执行工具，把结果作为 ToolMessage 回灌
      3. 再调模型；若模型这次不再要工具、直接给文本 → 就是最终答案
      4. 循环直到拿到最终答案，或达到 MAX_AGENT_STEPS 上限
    """
    messages: list[BaseMessage] = [
        SystemMessage(content=AGENT_SYSTEM_PROMPT),
        HumanMessage(content=req.message),
    ]
    tools_used: list[str] = []

    for step in range(1, MAX_AGENT_STEPS + 1):
        try:
            ai_message = agent_llm_with_tools.invoke(messages)
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"调用 Agent 模型失败: {e}")
        messages.append(ai_message)

        # 模型没要求调工具 → 这就是最终回答
        if not ai_message.tool_calls:
            return AgentResponse(
                reply=ai_message.content,
                tools_used=tools_used,
                steps=step,
            )

        # 模型要求调工具：逐个执行，把结果以 ToolMessage 回灌
        for call in ai_message.tool_calls:
            tool_fn = TOOLS_BY_NAME.get(call["name"])
            if tool_fn is None:
                result = f"未知工具：{call['name']}"
            else:
                result = tool_fn.invoke(call["args"])
            tools_used.append(call["name"])
            # tool_call_id 必须对上——模型靠它知道"这是哪次调用的结果"
            messages.append(ToolMessage(content=str(result), tool_call_id=call["id"]))

    # 兜底：调了 MAX_AGENT_STEPS 轮还没收敛
    return AgentResponse(
        reply="（达到最大工具调用轮数仍未得到最终答案，请简化问题重试）",
        tools_used=tools_used,
        steps=MAX_AGENT_STEPS,
    )


# ============== 用户系统：注册 / 登录 / 历史 ==============
class RegisterRequest(BaseModel):
    username: str = Field(..., min_length=3, max_length=50)
    password: str = Field(..., min_length=6, max_length=72)  # bcrypt 上限 72 字节


class LoginRequest(BaseModel):
    username: str
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    username: str


class ConversationDto(BaseModel):
    id: str
    title: str
    backend: Optional[str] = None
    updated_at: Optional[str] = None


class MessageDto(BaseModel):
    role: str
    content: str


@app.post("/auth/register", response_model=TokenResponse)
def register(req: RegisterRequest, db: Session = Depends(get_db)):
    """注册：用户名唯一，密码 bcrypt 入库，成功直接返回 token（顺便登录）。"""
    exists = db.scalar(select(User).where(User.username == req.username))
    if exists:
        raise HTTPException(status_code=409, detail="用户名已被占用")
    user = User(username=req.username, password_hash=auth.hash_password(req.password))
    db.add(user)
    db.commit()
    db.refresh(user)
    return TokenResponse(access_token=auth.create_access_token(user), username=user.username)


@app.post("/auth/login", response_model=TokenResponse)
def login(req: LoginRequest, db: Session = Depends(get_db)):
    """登录：校验密码，成功签发 JWT。"""
    user = db.scalar(select(User).where(User.username == req.username))
    if user is None or not auth.verify_password(req.password, user.password_hash):
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    return TokenResponse(access_token=auth.create_access_token(user), username=user.username)


@app.get("/auth/me")
def me(user: User = Depends(auth.get_current_user)):
    """返回当前登录用户（校验 token 是否有效用）。"""
    return {"id": user.id, "username": user.username}


@app.get("/conversations", response_model=list[ConversationDto])
def list_conversations(user: User = Depends(auth.get_current_user), db: Session = Depends(get_db)):
    """我的会话列表，按最近更新倒序。"""
    rows = db.scalars(
        select(Conversation)
        .where(Conversation.user_id == user.id, Conversation.status == 0)
        .order_by(desc(Conversation.updated_at))
    ).all()
    return [
        ConversationDto(
            id=c.id, title=c.title, backend=c.backend,
            updated_at=c.updated_at.isoformat() if c.updated_at else None,
        )
        for c in rows
    ]


@app.get("/conversations/{conv_id}/messages", response_model=list[MessageDto])
def conversation_messages(
    conv_id: str,
    user: User = Depends(auth.get_current_user),
    db: Session = Depends(get_db),
):
    """某段会话的全部消息（仅本人可看）。"""
    conv = db.get(Conversation, conv_id)
    if conv is None or conv.user_id != user.id or conv.status != 0:
        raise HTTPException(status_code=404, detail="会话不存在")
    msgs = db.scalars(
        select(Message).where(Message.conversation_id == conv_id).order_by(Message.id)
    ).all()
    return [MessageDto(role=m.role, content=m.content) for m in msgs]


class RenameRequest(BaseModel):
    title: str = Field(..., min_length=1, max_length=255)


@app.patch("/conversations/{conv_id}")
def rename_conversation(
    conv_id: str,
    req: RenameRequest,
    user: User = Depends(auth.get_current_user),
    db: Session = Depends(get_db),
):
    """重命名会话（仅本人）。"""
    conv = db.get(Conversation, conv_id)
    if conv is None or conv.user_id != user.id or conv.status != 0:
        raise HTTPException(status_code=404, detail="会话不存在")
    conv.title = req.title
    db.commit()
    return {"id": conv_id, "title": req.title}
