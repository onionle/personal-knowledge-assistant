# 前端（React + Vite）

知识库助手的 Web 界面：极简聊天 UI，支持流式输出、登录、会话历史。
通过顶部下拉框选择连接哪个后端实现（LangChain Python / LangChain4j / Spring AI）——
三者接口一致，所以同一套前端通用。

## 功能

- 流式对话（SSE 逐字输出）
- 下拉切换后端实现
- 登录 / 注册（JWT），登录后会话历史存数据库；未登录匿名可用、历史存浏览器本地
- 会话管理：新建、重命名（双击 / ✎）、删除（✕）

## 运行

```bash
npm install
npm run dev        # 开发服务器：http://localhost:5173
```

前提：至少启动一个后端（见各后端目录的 README），并在前端下拉框里选中它。
后端已配置 CORS 放行 `http://localhost:5173`。

```bash
npm run build      # 产出静态文件到 dist/
```

## 配置

后端地址在 `src/App.jsx` 顶部的 `BACKENDS` 常量里：

```js
const BACKENDS = [
  { label: 'LangChain (Python)', base: 'http://localhost:8001' },
  { label: 'LangChain4j',        base: 'http://localhost:8080' },
  { label: 'Spring AI',          base: 'http://localhost:8082' },
]
```

## 技术

- React 18 + Vite 5，零 UI 库（样式全在 `src/styles.css`）
- SSE 用 `fetch` + `ReadableStream` 手动解析（支持 POST body，比 `EventSource` 灵活）
