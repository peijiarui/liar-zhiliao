# 知了知了 前端测试客户端设计

> 版本：v1.0 | 日期：2026-07-08 | 作者：Claude

## 1. 概述

纯静态前端页面，用于对接知了知了后端 REST API 进行功能验证和测试。独立部署，零构建依赖。

## 2. 技术选型

| 项 | 选择 | 理由 |
|----|------|------|
| 框架 | 无（Vanilla HTML/CSS/JS） | 零依赖、无需构建、轻量 |
| HTTP 请求 | `fetch` + 封装 | 浏览器原生支持 |
| SSE 流式渲染 | `fetch` + `ReadableStream` | 后端使用 `text/html` 非标准 SSE，需手动读管道 |
| JWT 存储 | `localStorage` | 页面关闭后失效，足够测试用途 |
| 部署 | 任意静态服务器 | Python HTTP、Nginx、Live Server 等 |

## 3. 项目结构

```
zhiliao-frontend/
├── login.html           # 登录页
├── index.html           # 主页（导航 + 快捷入口）
├── documents.html       # 文档管理（上传 + 状态查询）
├── chat.html            # 对话页（SSE 流式）
├── css/
│   └── style.css        # 全局样式
└── js/
    ├── config.js        # API 地址配置
    ├── api.js           # HTTP 请求封装（自动带 token）
    ├── login.js         # 登录逻辑
    ├── documents.js     # 文档上传/查询逻辑
    └── chat.js          # 对话流式渲染
```

## 4. 接口映射

| 后端接口 | 前端页面 | 功能 |
|----------|----------|------|
| `POST /api/auth/login` | login.html | 登录，返回 JWT |
| `POST /api/documents/upload` | documents.html | 上传文档文件 |
| `GET /api/documents/{id}` | documents.html | 查询文档处理状态 |
| `GET /chat?memoryId=X&message=Y` | chat.html | 流式对话 |

> 后端服务器地址默认为 `http://localhost:8080`，通过 `js/config.js` 集中配置。

## 5. 页面详细设计

### 5.1 登录页 (login.html)

- 用户名字段 + 密码字段 + 登录按钮
- 点击登录 → `POST /api/auth/login` → 成功则存储 token 到 `localStorage` → 跳转主页
- 失败在页面上显示错误信息
- 已登录状态自动跳转主页

### 5.2 主页 (index.html)

- 顶部显示当前登录用户名
- 导航卡片：
  - **文档管理** → 跳转 documents.html
  - **对话测试** → 跳转 chat.html
- 底部登出按钮（清除 localStorage token）

### 5.3 文档管理 (documents.html)

- **上传区域**：文件选择器 + kbId 输入（默认 1）+ 上传按钮
  - 上传后展示返回的文档信息卡片（id, 文件名, 类型, 大小, 状态）
- **查询区域**：文档 ID 输入 + 查询按钮
  - 显示文档当前状态、分块数量等
- 状态标签用不同颜色区分（UPLOADED=灰, PROCESSING=蓝, COMPLETED=绿, FAILED=红）

### 5.4 对话页 (chat.html)

- Memory ID 输入框（可选，留空自动生成）
- 问题输入框 + 发送按钮
- 对话记录列表（用户消息 + AI 回复）
- 流式渲染：发送后，使用 `fetch` 读取 `ReadableStream`，逐块追加到当前 AI 回复的 DOM 节点中
- 流式传输中的回复显示光标闪烁效果，完成后停止

## 6. 数据流

### 6.1 认证流程

```
login.html: 表单提交 → api.js.fetchWithAuth (配置 baseUrl)
  → POST {baseUrl}/api/auth/login → 返回 { token }
  → localStorage.setItem("zhiliao_token", token)
  → window.location.href = "index.html"

后续请求:
  api.js 自动从 localStorage 读取 token
  → 所有请求头携带 Authorization: Bearer {token}
```

### 6.2 文档上传流程

```
documents.html: 选择文件 → 点击上传
  → FormData(file, kbId) → POST {baseUrl}/api/documents/upload
  → 展示上传结果卡片
  → 可手动输入 ID 查询最新状态
```

### 6.3 对话流程

```
chat.html: 输入问题 → 点击发送
  → GET {baseUrl}/chat?memoryId=X&message=Y
  → headers: { Authorization: Bearer {token} }
  → fetch 响应 → response.body.getReader() → ReadableStream
  → 逐块读取 Uint8Array → TextDecoder → 追加到消息 DOM
  → 流结束后移除光标
```

## 7. 错误处理

| 场景 | 处理 |
|------|------|
| 401 未授权 | 跳转 login.html |
| 网络错误 | 页面内 toast 提示 |
| 上传失败 | 显示后端返回的错误信息 |
| 对话流中断 | 在对话中显示"连接中断"提示 |

## 8. 部署方式

### 方式一：本地开发（推荐）

```bash
cd zhiliao-frontend
python3 -m http.server 8081
# 浏览器访问 http://localhost:8081
```

### 方式二：VS Code Live Server

右键 `index.html` → Open with Live Server

### 方式三：Nginx 部署

```nginx
server {
    listen 80;
    server_name your-domain.com;
    root /path/to/zhiliao-frontend;
    index index.html;
}
```
