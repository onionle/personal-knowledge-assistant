import { useState, useRef, useEffect } from 'react'

// ============================================================
// 前端通过下拉框选择连接哪个后端实现（三者接口一致：/chat/stream + 统一 SSE 协议）。
// 登录后会话历史存数据库；未登录时匿名可用，历史存浏览器本地。
// ============================================================
const BACKENDS = [
  { label: 'LangChain (Python)', base: 'http://localhost:8001' },
  { label: 'LangChain4j', base: 'http://localhost:8080' },
  { label: 'Spring AI', base: 'http://localhost:8082' },
]
const STORAGE_SESSIONS = 'kb_sessions_v2'
const STORAGE_AUTH = 'kb_auth_v1'
const STORAGE_BACKEND = 'kb_backend_v1'

function blankSession() {
  return { id: crypto.randomUUID(), title: '新会话', messages: [] }
}
function loadJSON(key, fallback) {
  try {
    const v = JSON.parse(localStorage.getItem(key))
    return v ?? fallback
  } catch {
    return fallback
  }
}

export default function App() {
  const [auth, setAuth] = useState(() => loadJSON(STORAGE_AUTH, null)) // {token, username} | null
  const [backendIdx, setBackendIdx] = useState(() => loadJSON(STORAGE_BACKEND, 0))
  const [sessions, setSessions] = useState(() => {
    const s = loadJSON(STORAGE_SESSIONS, null)
    return Array.isArray(s) && s.length ? s : [blankSession()]
  })
  const [currentId, setCurrentId] = useState(() => sessions[0]?.id)
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [editingId, setEditingId] = useState(null)   // 正在重命名的会话 id
  const [editTitle, setEditTitle] = useState('')

  // 登录弹窗
  const [authOpen, setAuthOpen] = useState(false)
  const [authMode, setAuthMode] = useState('login') // 'login' | 'register'
  const [form, setForm] = useState({ username: '', password: '' })
  const [authErr, setAuthErr] = useState('')
  const [authBusy, setAuthBusy] = useState(false)

  const sessionsRef = useRef(sessions)
  useEffect(() => { sessionsRef.current = sessions }, [sessions])
  const scrollRef = useRef(null)

  const apiBase = BACKENDS[backendIdx].base
  const current = sessions.find((s) => s.id === currentId) ?? sessions[0]
  const isEmpty = current && Array.isArray(current.messages) && current.messages.length === 0
  const isLoadingMsgs = current && current.messages == null

  const authHeader = () => (auth ? { Authorization: `Bearer ${auth.token}` } : {})

  useEffect(() => localStorage.setItem(STORAGE_BACKEND, JSON.stringify(backendIdx)), [backendIdx])
  useEffect(() => {
    if (auth) localStorage.setItem(STORAGE_AUTH, JSON.stringify(auth))
    else localStorage.removeItem(STORAGE_AUTH)
  }, [auth])
  // 只有匿名模式才把会话存本地；登录后以服务端为准
  useEffect(() => {
    if (!auth) localStorage.setItem(STORAGE_SESSIONS, JSON.stringify(sessions))
  }, [sessions, auth])
  useEffect(() => {
    scrollRef.current?.scrollTo(0, scrollRef.current.scrollHeight)
  }, [sessions, currentId])

  // 登录态或后端变化时，登录用户从服务端拉会话列表
  useEffect(() => {
    if (!auth) return
    let cancelled = false
    ;(async () => {
      try {
        const r = await fetch(`${apiBase}/conversations`, { headers: authHeader() })
        if (!r.ok) throw new Error()
        const list = await r.json() // [{id,title,backend,updated_at}]
        if (cancelled) return
        const stubs = list.map((c) => ({ id: c.id, title: c.title, messages: null })) // messages 懒加载
        const next = stubs.length ? stubs : [blankSession()]
        setSessions(next)
        setCurrentId(next[0].id)
      } catch {
        if (!cancelled) {
          // 该后端没实现登录/历史（如 Java 版尚未做）→ 给个空白会话，仍可聊
          const b = blankSession()
          setSessions([b])
          setCurrentId(b.id)
        }
      }
    })()
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [auth, backendIdx])

  // ---------- 会话管理 ----------
  function newSession() {
    if (current && Array.isArray(current.messages) && current.messages.length === 0) {
      setCurrentId(current.id)
      return
    }
    const existingEmpty = sessions.find((s) => Array.isArray(s.messages) && s.messages.length === 0)
    if (existingEmpty) {
      setCurrentId(existingEmpty.id)
      return
    }
    const s = blankSession()
    setSessions((prev) => [s, ...prev])
    setCurrentId(s.id)
  }

  async function selectSession(id) {
    setCurrentId(id)
    // 登录态下点开历史会话且消息还没加载 → 拉服务端消息
    const s = sessionsRef.current.find((x) => x.id === id)
    if (auth && s && s.messages == null) {
      try {
        const r = await fetch(`${apiBase}/conversations/${id}/messages`, { headers: authHeader() })
        const list = r.ok ? await r.json() : []
        setSessions((prev) => prev.map((x) => (x.id === id ? { ...x, messages: list } : x)))
      } catch {
        setSessions((prev) => prev.map((x) => (x.id === id ? { ...x, messages: [] } : x)))
      }
    }
  }

  async function deleteSession(id, e) {
    e.stopPropagation()
    // 登录用户：调服务端逻辑删除（置 status=1，不真删）；匿名只删本地显示
    if (auth) {
      try {
        await fetch(`${apiBase}/conversations/${id}`, { method: 'DELETE', headers: authHeader() })
      } catch {
        /* 忽略网络错误，前端照常移除 */
      }
    }
    setSessions((prev) => {
      const left = prev.filter((s) => s.id !== id)
      const next = left.length ? left : [blankSession()]
      if (id === currentId) setCurrentId(next[0].id)
      return next
    })
  }

  function changeBackend(idx) {
    setBackendIdx(idx)
    if (!auth) {
      const b = blankSession()
      setSessions([b])
      setCurrentId(b.id)
    }
    // 登录态：上面的 useEffect 会按新后端重新拉历史
  }

  // ---------- 发送 + 流式 ----------
  async function send() {
    const text = input.trim()
    if (!text || loading || !current) return
    const sid = current.id
    setInput('')
    setLoading(true)

    setSessions((prev) =>
      prev.map((s) => {
        if (s.id !== sid) return s
        const base = Array.isArray(s.messages) ? s.messages : []
        const title = base.length === 0 ? text.slice(0, 24) : s.title
        return { ...s, title, messages: [...base, { role: 'user', content: text }, { role: 'assistant', content: '' }] }
      }),
    )

    try {
      const resp = await fetch(`${apiBase}/chat/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader() },
        // 会话 id 直接当 conversation_id，前后端一致，登录态下服务端按这个 id 落库
        body: JSON.stringify({ message: text, conversation_id: sid, conversationId: sid }),
      })
      if (!resp.ok || !resp.body) throw new Error(`HTTP ${resp.status}`)
      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const events = buffer.split('\n\n')
        buffer = events.pop()
        for (const evt of events) {
          const dataLine = evt.split('\n').find((l) => l.startsWith('data:'))
          if (!dataLine) continue
          let p
          try { p = JSON.parse(dataLine.slice(5).trim()) } catch { continue }
          if (p.error) appendToLast(sid, `\n[出错] ${p.error}`)
          if (p.token) appendToLast(sid, p.token)
        }
      }
    } catch (e) {
      appendToLast(sid, `\n[请求失败] ${e.message}（对应后端是否已启动？）`)
    } finally {
      setLoading(false)
    }
  }

  function appendToLast(sid, chunk) {
    setSessions((prev) =>
      prev.map((s) => {
        if (s.id !== sid) return s
        const msgs = (s.messages || []).slice()
        const last = msgs[msgs.length - 1]
        msgs[msgs.length - 1] = { ...last, content: last.content + chunk }
        return { ...s, messages: msgs }
      }),
    )
  }

  function onKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      send()
    }
  }

  // ---------- 重命名会话 ----------
  function startRename(s, e) {
    e.stopPropagation()
    setEditingId(s.id)
    setEditTitle(s.title || '')
  }
  async function commitRename(id) {
    const title = editTitle.trim()
    setEditingId(null)
    if (!title) return
    setSessions((prev) => prev.map((s) => (s.id === id ? { ...s, title } : s)))
    if (auth) {
      try {
        await fetch(`${apiBase}/conversations/${id}`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json', ...authHeader() },
          body: JSON.stringify({ title }),
        })
      } catch {
        /* 忽略 */
      }
    }
  }

  // ---------- 登录 / 注册 / 退出 ----------
  function openAuth(mode) {
    setAuthMode(mode)
    setForm({ username: '', password: '' })
    setAuthErr('')
    setAuthOpen(true)
  }
  async function submitAuth() {
    if (!form.username || !form.password) { setAuthErr('请填写用户名和密码'); return }
    setAuthBusy(true)
    setAuthErr('')
    try {
      const r = await fetch(`${apiBase}/auth/${authMode}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(form),
      })
      const data = await r.json().catch(() => ({}))
      if (!r.ok) throw new Error(data.detail || `失败（HTTP ${r.status}）`)
      setAuth({ token: data.access_token, username: data.username })
      setAuthOpen(false)
    } catch (e) {
      setAuthErr(e.message)
    } finally {
      setAuthBusy(false)
    }
  }
  function logout() {
    setAuth(null)
    const b = blankSession()
    setSessions([b])
    setCurrentId(b.id)
  }

  const backendLabel = BACKENDS[backendIdx].label

  const composerEl = (
    <div className="composer">
      <textarea
        rows={1}
        value={input}
        placeholder="请输入你的需求，按 Enter 发送"
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={onKeyDown}
      />
      <button className="send" onClick={send} disabled={loading || !input.trim()}>
        {loading ? '生成中' : '发送'}
      </button>
    </div>
  )

  // ============================================================
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">知识库助手</div>

        <button className="new-btn" onClick={newSession}>
          <span className="plus">＋</span> 新会话
        </button>

        <div className="backend-row">
          <label>后端</label>
          <select value={backendIdx} onChange={(e) => changeBackend(Number(e.target.value))}>
            {BACKENDS.map((b, i) => (
              <option key={i} value={i}>{b.label}</option>
            ))}
          </select>
        </div>

        <div className="history-label">{auth ? '会话记录（云端）' : '会话记录（本地）'}</div>
        <nav className="history">
          {sessions.map((s) => (
            <div
              key={s.id}
              className={`history-item ${s.id === currentId ? 'active' : ''}`}
              onClick={() => selectSession(s.id)}
              title={s.title}
            >
              {editingId === s.id ? (
                <input
                  className="hi-edit"
                  autoFocus
                  value={editTitle}
                  onChange={(e) => setEditTitle(e.target.value)}
                  onClick={(e) => e.stopPropagation()}
                  onBlur={() => commitRename(s.id)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') { e.preventDefault(); commitRename(s.id) }
                    if (e.key === 'Escape') setEditingId(null)
                  }}
                />
              ) : (
                <>
                  <span className="hi-title" onDoubleClick={(e) => startRename(s, e)}>
                    {s.title || '新会话'}
                  </span>
                  <span className="hi-act" onClick={(e) => startRename(s, e)} title="重命名">✎</span>
                  <span className="hi-del" onClick={(e) => deleteSession(s.id, e)} title="删除">✕</span>
                </>
              )}
            </div>
          ))}
        </nav>

        <div className="user-area">
          {auth ? (
            <>
              <div className="avatar">{auth.username.slice(0, 1).toUpperCase()}</div>
              <div className="user-meta">
                <div className="uname">{auth.username}</div>
                <div className="utip">已登录 · 历史云端保存</div>
              </div>
              <button className="logout" onClick={logout}>退出</button>
            </>
          ) : (
            <>
              <div className="avatar">游</div>
              <div className="user-meta">
                <div className="uname">本地访客</div>
                <div className="utip">登录后会话云端保存</div>
              </div>
              <button className="logout" onClick={() => openAuth('login')}>登录</button>
            </>
          )}
        </div>
      </aside>

      <main className="main">
        {isLoadingMsgs ? (
          <div className="center-stage"><div className="stage-hint">加载会话中…</div></div>
        ) : isEmpty ? (
          <div className="center-stage">
            <h1 className="stage-title">知识库助手</h1>
            <div className="stage-composer">{composerEl}</div>
            <div className="stage-hint">回车发送，Shift+回车换行 · 当前后端：{backendLabel}</div>
          </div>
        ) : (
          <>
            <header className="topbar">
              <span className="conv-title">{current?.title || '新会话'}</span>
              <span className="conv-backend">{backendLabel}</span>
            </header>
            <div className="messages" ref={scrollRef}>
              <div className="msg-list">
                {(current?.messages || []).map((m, i) => (
                  <div key={i} className={`row ${m.role}`}>
                    {m.role === 'user' ? (
                      <div className="bubble">{m.content}</div>
                    ) : (
                      <div className="assistant-text">{m.content || (loading ? '正在思考…' : '')}</div>
                    )}
                  </div>
                ))}
              </div>
            </div>
            <div className="composer-wrap">
              {composerEl}
              <div className="footer-tip">内容由 AI 生成，重要信息请核实</div>
            </div>
          </>
        )}
      </main>

      {/* ---------- 登录/注册 弹窗 ---------- */}
      {authOpen && (
        <div className="modal-mask" onClick={() => setAuthOpen(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-title">{authMode === 'login' ? '登录' : '注册'}</div>
            <input
              className="modal-input"
              placeholder="用户名"
              value={form.username}
              onChange={(e) => setForm({ ...form, username: e.target.value })}
            />
            <input
              className="modal-input"
              type="password"
              placeholder="密码"
              value={form.password}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
              onKeyDown={(e) => e.key === 'Enter' && submitAuth()}
            />
            {authErr && <div className="modal-err">{authErr}</div>}
            <button className="modal-submit" onClick={submitAuth} disabled={authBusy}>
              {authBusy ? '请稍候…' : authMode === 'login' ? '登录' : '注册并登录'}
            </button>
            <div className="modal-switch">
              {authMode === 'login' ? (
                <>还没有账号？<a onClick={() => { setAuthMode('register'); setAuthErr('') }}>去注册</a></>
              ) : (
                <>已有账号？<a onClick={() => { setAuthMode('login'); setAuthErr('') }}>去登录</a></>
              )}
            </div>
            <div className="modal-tip">登录连接当前后端：{backendLabel}（三个后端共用同一账号体系，互通）</div>
          </div>
        </div>
      )}
    </div>
  )
}
