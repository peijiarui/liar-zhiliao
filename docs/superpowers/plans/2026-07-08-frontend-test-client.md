# 前端测试客户端 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现纯静态前端测试客户端，对接知了知了后端 API，支持登录、文档管理、流式对话

**Architecture:** 零构建纯静态页面，项目结构独立于后端 Spring Boot 项目。JWT 通过 localStorage 管理，流式对话通过 fetch ReadableStream 渲染

**Tech Stack:** HTML5, CSS3, Vanilla JS (ES6+), Fetch API

**Global Constraints:**
- 不支持 IE 等旧浏览器，仅兼容最新 Chrome/Firefox/Safari
- 不引入任何 npm 依赖或构建工具
- 所有 JS 使用 ES6 模块语法或直接在 HTML 中通过 `<script>` 标签引用
- 中文界面，使用 UTF-8 编码
- 后端 API 地址统一在 `js/config.js` 中管理

---

## 文件全景

```
zhiliao-frontend/
├── login.html               (新建)  # 登录页
├── index.html               (新建)  # 主页
├── documents.html           (新建)  # 文档管理
├── chat.html                (新建)  # 流式对话
├── css/
│   └── style.css            (新建)  # 全局样式
└── js/
    ├── config.js            (新建)  # API 地址配置
    ├── api.js               (新建)  # HTTP 请求封装
    ├── login.js             (新建)  # 登录逻辑
    ├── documents.js         (新建)  # 文档管理逻辑
    └── chat.js              (新建)  # 对话流式逻辑
```

---

### Task 1: 项目脚手架 + 共享层

**Files:**
- Create: `zhiliao-frontend/js/config.js`
- Create: `zhiliao-frontend/js/api.js`
- Create: `zhiliao-frontend/css/style.css`

**Interfaces:**
- Consumes: 无
- Produces: `BASE_URL` 全局常量；`api.js` 暴露 `api.get(url)`, `api.postJson(url, body)`, `api.postForm(url, formData)` 三个方法

- [ ] **Step 1: 创建目录结构**

```bash
mkdir -p zhiliao-frontend/css zhiliao-frontend/js
```

- [ ] **Step 2: 创建 config.js**

```javascript
// js/config.js
const CONFIG = {
    BASE_URL: 'http://localhost:8080',
};
```

- [ ] **Step 3: 创建 api.js**

```javascript
// js/api.js
const api = {
    _token() {
        return localStorage.getItem('zhiliao_token');
    },

    _buildUrl(path) {
        return CONFIG.BASE_URL + path;
    },

    _handleResponse(response) {
        if (response.status === 401) {
            localStorage.removeItem('zhiliao_token');
            window.location.href = 'login.html';
            throw new Error('未登录或登录已过期');
        }
        if (!response.ok) {
            return response.json().then(err => { throw new Error(err.error || err.message || '请求失败'); });
        }
        return response;
    },

    get(path) {
        return fetch(this._buildUrl(path), {
            headers: { 'Authorization': 'Bearer ' + this._token() }
        }).then(this._handleResponse.bind(this));
    },

    postJson(path, data) {
        return fetch(this._buildUrl(path), {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': 'Bearer ' + this._token()
            },
            body: JSON.stringify(data)
        }).then(this._handleResponse.bind(this));
    },

    postForm(path, formData) {
        return fetch(this._buildUrl(path), {
            method: 'POST',
            headers: { 'Authorization': 'Bearer ' + this._token() },
            body: formData
        }).then(this._handleResponse.bind(this));
    }
};
```

- [ ] **Step 4: 创建全局样式 style.css**

```css
/* css/style.css */
* { margin: 0; padding: 0; box-sizing: border-box; }

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans SC", sans-serif;
    background: #f5f6fa;
    color: #2c3e50;
    min-height: 100vh;
}

.container { max-width: 800px; margin: 0 auto; padding: 24px 16px; }

/* 顶部导航条 */
.topbar {
    background: #fff;
    border-bottom: 1px solid #e8e8e8;
    padding: 12px 24px;
    display: flex;
    justify-content: space-between;
    align-items: center;
}
.topbar .app-title { font-size: 18px; font-weight: 600; color: #1a73e8; }
.topbar .user-info { font-size: 14px; color: #666; }
.topbar .user-info .logout-btn {
    margin-left: 12px; padding: 4px 12px;
    border: 1px solid #ddd; border-radius: 4px;
    background: #fff; cursor: pointer; font-size: 13px;
    color: #e74c3c;
}
.topbar .user-info .logout-btn:hover { background: #fef0ef; }

/* 卡片 */
.card {
    background: #fff;
    border-radius: 8px;
    padding: 20px 24px;
    margin-bottom: 16px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}
.card h2 { font-size: 16px; margin-bottom: 16px; color: #333; }

/* 表单 */
.form-group { margin-bottom: 14px; }
.form-group label { display: block; font-size: 14px; margin-bottom: 4px; color: #555; }
.form-group input, .form-group select {
    width: 100%; padding: 8px 12px;
    border: 1px solid #ddd; border-radius: 4px;
    font-size: 14px; outline: none;
}
.form-group input:focus { border-color: #1a73e8; box-shadow: 0 0 0 2px rgba(26,115,232,0.1); }

.btn {
    padding: 8px 20px; border: none; border-radius: 4px;
    font-size: 14px; cursor: pointer; transition: background 0.15s;
}
.btn-primary { background: #1a73e8; color: #fff; }
.btn-primary:hover { background: #1557b0; }
.btn-primary:disabled { background: #93b8f0; cursor: not-allowed; }
.btn-danger { background: #e74c3c; color: #fff; }
.btn-danger:hover { background: #c0392b; }

/* 状态标签 */
.badge {
    display: inline-block; padding: 2px 10px; border-radius: 10px;
    font-size: 12px; font-weight: 500;
}
.badge-uploaded { background: #f0f0f0; color: #666; }
.badge-processing { background: #dbeafe; color: #1a56db; }
.badge-completed { background: #d1fae5; color: #059669; }
.badge-failed { background: #fee2e2; color: #dc2626; }

/* Toast 提示 */
.toast {
    position: fixed; top: 20px; right: 20px;
    padding: 12px 20px; border-radius: 6px;
    color: #fff; font-size: 14px; z-index: 999;
    animation: fadeIn 0.3s ease;
}
.toast-error { background: #e74c3c; }
.toast-success { background: #059669; }
@keyframes fadeIn { from { opacity: 0; transform: translateY(-10px); } to { opacity: 1; transform: translateY(0); } }

/* 导航卡片网格 */
.nav-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 24px; }
.nav-card {
    background: #fff; border-radius: 10px; padding: 28px 20px;
    text-align: center; cursor: pointer; box-shadow: 0 1px 3px rgba(0,0,0,0.08);
    transition: box-shadow 0.2s, transform 0.1s;
    text-decoration: none; color: inherit; display: block;
}
.nav-card:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.12); transform: translateY(-2px); }
.nav-card .icon { font-size: 36px; margin-bottom: 12px; }
.nav-card .title { font-size: 16px; font-weight: 600; margin-bottom: 6px; }
.nav-card .desc { font-size: 13px; color: #888; }

/* 对话消息 */
.msg { margin-bottom: 16px; display: flex; }
.msg.user { justify-content: flex-end; }
.msg .bubble {
    max-width: 75%; padding: 10px 16px; border-radius: 10px;
    font-size: 14px; line-height: 1.6; white-space: pre-wrap;
}
.msg.user .bubble { background: #1a73e8; color: #fff; border-bottom-right-radius: 2px; }
.msg.assistant .bubble { background: #fff; color: #333; border-bottom-left-radius: 2px; box-shadow: 0 1px 2px rgba(0,0,0,0.06); }

/* 闪烁光标 */
.cursor { display: inline-block; width: 2px; height: 16px; background: #333; margin-left: 2px; animation: blink 0.8s step-end infinite; vertical-align: text-bottom; }
@keyframes blink { 50% { opacity: 0; } }

/* 记录列表 */
.record-list { margin-top: 12px; }
.record-item {
    padding: 10px 0; border-bottom: 1px solid #f0f0f0;
    display: flex; justify-content: space-between; align-items: center;
    font-size: 14px;
}
.record-item:last-child { border-bottom: none; }
.record-item .label { color: #888; }

/* 信息展示行 */
.info-row { display: flex; padding: 6px 0; font-size: 14px; }
.info-row .label { width: 100px; color: #888; flex-shrink: 0; }
.info-row .value { color: #333; word-break: break-all; }
```

---

### Task 2: 登录页

**Files:**
- Create: `zhiliao-frontend/login.html`
- Create: `zhiliao-frontend/js/login.js`

**Interfaces:**
- Consumes: `CONFIG.BASE_URL` from config.js, `api.postJson()` from api.js
- Produces: 无（将 token 存入 localStorage 后跳转主页）

- [ ] **Step 1: 创建 login.html**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>知了知了 - 登录</title>
    <link rel="stylesheet" href="css/style.css">
</head>
<body>
    <div class="container" style="max-width: 400px; padding-top: 100px;">
        <div class="card" style="text-align: center;">
            <h1 style="font-size: 24px; color: #1a73e8; margin-bottom: 4px;">知了知了</h1>
            <p style="font-size: 14px; color: #888; margin-bottom: 24px;">企业知识库测试客户端</p>
            <div class="form-group">
                <label>用户名</label>
                <input type="text" id="username" placeholder="请输入用户名" value="admin">
            </div>
            <div class="form-group">
                <label>密码</label>
                <input type="password" id="password" placeholder="请输入密码" value="123456">
            </div>
            <div id="error-msg" style="color: #e74c3c; font-size: 13px; margin-bottom: 12px; display: none;"></div>
            <button class="btn btn-primary" id="login-btn" style="width: 100%; padding: 10px; font-size: 15px;">登录</button>
        </div>
    </div>
    <script src="js/config.js"></script>
    <script src="js/api.js"></script>
    <script src="js/login.js"></script>
</body>
</html>
```

- [ ] **Step 2: 创建 login.js**

```javascript
// js/login.js
(function() {
    // 已登录则直接跳转
    if (localStorage.getItem('zhiliao_token')) {
        window.location.href = 'index.html';
        return;
    }

    const usernameInput = document.getElementById('username');
    const passwordInput = document.getElementById('password');
    const loginBtn = document.getElementById('login-btn');
    const errorMsg = document.getElementById('error-msg');

    loginBtn.addEventListener('click', async () => {
        const username = usernameInput.value.trim();
        const password = passwordInput.value.trim();
        if (!username || !password) {
            showError('请输入用户名和密码');
            return;
        }

        loginBtn.disabled = true;
        loginBtn.textContent = '登录中...';
        hideError();

        try {
            const res = await fetch(CONFIG.BASE_URL + '/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                throw new Error(err.error || '登录失败');
            }
            const data = await res.json();
            localStorage.setItem('zhiliao_token', data.token);
            window.location.href = 'index.html';
        } catch (e) {
            showError(e.message);
        } finally {
            loginBtn.disabled = false;
            loginBtn.textContent = '登录';
        }
    });

    // 回车提交
    passwordInput.addEventListener('keydown', e => { if (e.key === 'Enter') loginBtn.click(); });

    function showError(msg) { errorMsg.textContent = msg; errorMsg.style.display = 'block'; }
    function hideError() { errorMsg.style.display = 'none'; }
})();
```

- [ ] **Step 3: 验证** — 在浏览器中打开 `login.html`，确认表单渲染正常且有默认值 `admin/123456`

---

### Task 3: 主页

**Files:**
- Create: `zhiliao-frontend/index.html`

**Interfaces:**
- Consumes: `localStorage('zhiliao_token')`, `localStorage('zhiliao_user')`

- [ ] **Step 1: 创建 index.html**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>知了知了 - 主页</title>
    <link rel="stylesheet" href="css/style.css">
</head>
<body>
    <div class="topbar">
        <span class="app-title">知了知了</span>
        <span class="user-info">
            <span id="user-display">用户</span>
            <button class="logout-btn" id="logout-btn">退出登录</button>
        </span>
    </div>
    <div class="container">
        <div style="margin: 40px 0 12px; text-align: center;">
            <h2 style="font-size: 20px; font-weight: 600;">功能导航</h2>
            <p style="color: #888; font-size: 14px; margin-top: 4px;">选择要测试的功能模块</p>
        </div>
        <div class="nav-grid">
            <a href="documents.html" class="nav-card">
                <div class="icon">📄</div>
                <div class="title">文档管理</div>
                <div class="desc">上传文档、查看处理状态</div>
            </a>
            <a href="chat.html" class="nav-card">
                <div class="icon">💬</div>
                <div class="title">对话测试</div>
                <div class="desc">流式对话、RAG 检索验证</div>
            </a>
        </div>
    </div>
    <script>
        (function() {
            const token = localStorage.getItem('zhiliao_token');
            if (!token) { window.location.href = 'login.html'; return; }
            // 尝试显示用户名（从 JWT 解析或直接从 localStorage 获取）
            try {
                const payload = JSON.parse(atob(token.split('.')[1]));
                document.getElementById('user-display').textContent = payload.username || '用户';
            } catch(e) { /* ignore */ }
            document.getElementById('logout-btn').addEventListener('click', function() {
                localStorage.removeItem('zhiliao_token');
                window.location.href = 'login.html';
            });
        })();
    </script>
</body>
</html>
```

- [ ] **Step 2: 验证** — 登录后跳转主页，确认导航卡片渲染正常，退出登录功能正常

---

### Task 4: 文档管理页

**Files:**
- Create: `zhiliao-frontend/documents.html`
- Create: `zhiliao-frontend/js/documents.js`

**Interfaces:**
- Consumes: `api.get()`, `api.postForm()` from api.js

- [ ] **Step 1: 创建 documents.html**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>知了知了 - 文档管理</title>
    <link rel="stylesheet" href="css/style.css">
</head>
<body>
    <div class="topbar">
        <span class="app-title"><a href="index.html" style="color: #1a73e8; text-decoration: none;">知了知了</a></span>
        <span class="user-info"><span id="user-display">用户</span></span>
    </div>
    <div class="container">
        <!-- 上传区域 -->
        <div class="card">
            <h2>📤 上传文档</h2>
            <div class="form-group">
                <label>知识库 ID</label>
                <input type="number" id="kb-id" value="1">
            </div>
            <div class="form-group">
                <label>选择文件</label>
                <input type="file" id="file-input">
            </div>
            <button class="btn btn-primary" id="upload-btn">上传</button>
        </div>

        <!-- 上传结果 -->
        <div class="card" id="upload-result" style="display: none;">
            <h2>📋 上传结果</h2>
            <div id="upload-info"></div>
        </div>

        <!-- 查询区域 -->
        <div class="card">
            <h2>🔍 查询文档状态</h2>
            <div style="display: flex; gap: 8px;">
                <input type="number" id="query-id" placeholder="输入文档 ID" style="flex: 1; padding: 8px 12px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px;">
                <button class="btn btn-primary" id="query-btn">查询</button>
            </div>
        </div>

        <!-- 查询结果 -->
        <div class="card" id="query-result" style="display: none;">
            <h2>📄 文档信息</h2>
            <div id="query-info"></div>
        </div>
    </div>
    <script src="js/config.js"></script>
    <script src="js/api.js"></script>
    <script src="js/documents.js"></script>
</body>
</html>
```

- [ ] **Step 2: 创建 documents.js**

```javascript
// js/documents.js
(function() {
    if (!localStorage.getItem('zhiliao_token')) { window.location.href = 'login.html'; return; }

    const fileInput = document.getElementById('file-input');
    const kbIdInput = document.getElementById('kb-id');
    const uploadBtn = document.getElementById('upload-btn');
    const uploadResult = document.getElementById('upload-result');
    const uploadInfo = document.getElementById('upload-info');
    const queryIdInput = document.getElementById('query-id');
    const queryBtn = document.getElementById('query-btn');
    const queryResult = document.getElementById('query-result');
    const queryInfo = document.getElementById('query-info');

    // 上传
    uploadBtn.addEventListener('click', async () => {
        const file = fileInput.files[0];
        if (!file) { showToast('请选择文件', 'error'); return; }

        uploadBtn.disabled = true;
        uploadBtn.textContent = '上传中...';

        try {
            const formData = new FormData();
            formData.append('file', file);
            formData.append('kbId', kbIdInput.value || '1');

            const res = await api.postForm('/api/documents/upload', formData);
            const doc = await res.json();
            renderUploadResult(doc);
            showToast('上传成功', 'success');
        } catch (e) {
            showToast(e.message, 'error');
        } finally {
            uploadBtn.disabled = false;
            uploadBtn.textContent = '上传';
        }
    });

    // 查询
    queryBtn.addEventListener('click', async () => {
        const id = queryIdInput.value.trim();
        if (!id) { showToast('请输入文档 ID', 'error'); return; }

        try {
            const res = await api.get('/api/documents/' + id);
            const doc = await res.json();
            renderQueryResult(doc);
        } catch (e) {
            showToast(e.message, 'error');
        }
    });

    // 回车查询
    queryIdInput.addEventListener('keydown', e => { if (e.key === 'Enter') queryBtn.click(); });

    function statusBadge(status) {
        const map = { 'UPLOADED': 'badge-uploaded', 'PROCESSING': 'badge-processing', 'COMPLETED': 'badge-completed', 'FAILED': 'badge-failed' };
        return '<span class="badge ' + (map[status] || 'badge-uploaded') + '">' + status + '</span>';
    }

    function formatSize(bytes) {
        if (!bytes) return '-';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024*1024) return (bytes/1024).toFixed(1) + ' KB';
        return (bytes/1024/1024).toFixed(1) + ' MB';
    }

    function renderUploadResult(doc) {
        uploadInfo.innerHTML =
            '<div class="info-row"><span class="label">文档 ID</span><span class="value">' + doc.id + '</span></div>' +
            '<div class="info-row"><span class="label">文件名</span><span class="value">' + (doc.fileName || '-') + '</span></div>' +
            '<div class="info-row"><span class="label">大小</span><span class="value">' + formatSize(doc.fileSize) + '</span></div>' +
            '<div class="info-row"><span class="label">状态</span><span class="value">' + statusBadge(doc.status) + '</span></div>';
        uploadResult.style.display = 'block';
    }

    function renderQueryResult(doc) {
        queryInfo.innerHTML =
            '<div class="info-row"><span class="label">文档 ID</span><span class="value">' + doc.id + '</span></div>' +
            '<div class="info-row"><span class="label">文件名</span><span class="value">' + (doc.fileName || '-') + '</span></div>' +
            '<div class="info-row"><span class="label">类型</span><span class="value">' + (doc.fileType || '-') + '</span></div>' +
            '<div class="info-row"><span class="label">大小</span><span class="value">' + formatSize(doc.fileSize) + '</span></div>' +
            '<div class="info-row"><span class="label">状态</span><span class="value">' + statusBadge(doc.status) + '</span></div>' +
            '<div class="info-row"><span class="label">分块数</span><span class="value">' + (doc.chunkCount != null ? doc.chunkCount : '-') + '</span></div>' +
            '<div class="info-row"><span class="label">创建时间</span><span class="value">' + (doc.createdAt || '-') + '</span></div>';
        queryResult.style.display = 'block';
    }

    function showToast(msg, type) {
        var t = document.createElement('div');
        t.className = 'toast toast-' + type;
        t.textContent = msg;
        document.body.appendChild(t);
        setTimeout(function() { t.remove(); }, 3000);
    }
})();
```

---

### Task 5: 对话页

**Files:**
- Create: `zhiliao-frontend/chat.html`
- Create: `zhiliao-frontend/js/chat.js`

**Interfaces:**
- Consumes: `CONFIG.BASE_URL` from config.js

- [ ] **Step 1: 创建 chat.html**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>知了知了 - 对话测试</title>
    <link rel="stylesheet" href="css/style.css">
</head>
<body>
    <div class="topbar">
        <span class="app-title"><a href="index.html" style="color: #1a73e8; text-decoration: none;">知了知了</a></span>
        <span class="user-info"><span id="user-display">用户</span></span>
    </div>
    <div class="container">
        <!-- Memory ID -->
        <div class="card" style="padding: 12px 16px;">
            <div style="display: flex; gap: 8px; align-items: center;">
                <label style="font-size: 13px; color: #888; white-space: nowrap;">Memory ID:</label>
                <input type="text" id="memory-id" style="flex: 1; padding: 6px 10px; border: 1px solid #ddd; border-radius: 4px; font-size: 13px;" placeholder="留空自动生成">
                <button class="btn" id="new-btn" style="padding: 6px 12px; font-size: 13px; border: 1px solid #ddd; background: #fff;">新建</button>
            </div>
        </div>

        <!-- 对话区域 -->
        <div id="chat-box" style="min-height: 400px; padding: 16px 0;"></div>

        <!-- 输入区 -->
        <div class="card" style="position: sticky; bottom: 0; margin-bottom: 0;">
            <div style="display: flex; gap: 8px;">
                <input type="text" id="message-input" placeholder="输入你的问题..." style="flex: 1; padding: 10px 14px; border: 1px solid #ddd; border-radius: 6px; font-size: 14px; outline: none;">
                <button class="btn btn-primary" id="send-btn" style="padding: 10px 24px;">发送</button>
            </div>
        </div>
    </div>
    <script src="js/config.js"></script>
    <script src="js/chat.js"></script>
</body>
</html>
```

- [ ] **Step 2: 创建 chat.js**

```javascript
// js/chat.js
(function() {
    if (!localStorage.getItem('zhiliao_token')) { window.location.href = 'login.html'; return; }

    var memoryId = localStorage.getItem('zhiliao_memory_id') || '';
    var chatBox = document.getElementById('chat-box');
    var memoryInput = document.getElementById('memory-id');
    var messageInput = document.getElementById('message-input');
    var sendBtn = document.getElementById('send-btn');
    var newBtn = document.getElementById('new-btn');

    if (memoryId) memoryInput.value = memoryId;

    // 新建对话
    newBtn.addEventListener('click', function() {
        memoryId = '';
        memoryInput.value = '';
        localStorage.removeItem('zhiliao_memory_id');
        chatBox.innerHTML = '';
    });

    // 发送消息
    sendBtn.addEventListener('click', sendMessage);
    messageInput.addEventListener('keydown', function(e) { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); } });

    function sendMessage() {
        var msg = messageInput.value.trim();
        if (!msg) return;

        // 如果 memoryId 为空，自动生成
        if (!memoryId) {
            memoryId = 'test-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6);
            memoryInput.value = memoryId;
            localStorage.setItem('zhiliao_memory_id', memoryId);
        }

        // 追加用户消息
        appendMessage('user', msg);
        messageInput.value = '';
        sendBtn.disabled = true;
        sendBtn.textContent = '思考中...';

        // 追加助理消息占位（含光标）
        var assistantBubble = document.createElement('div');
        assistantBubble.className = 'bubble';
        assistantBubble.innerHTML = '<span class="cursor"></span>';
        var assistantMsg = document.createElement('div');
        assistantMsg.className = 'msg assistant';
        assistantMsg.appendChild(assistantBubble);
        chatBox.appendChild(assistantMsg);
        chatBox.scrollTop = chatBox.scrollHeight;

        var fullText = '';

        fetch(CONFIG.BASE_URL + '/chat/chat?memoryId=' + encodeURIComponent(memoryId) + '&message=' + encodeURIComponent(msg), {
            headers: { 'Authorization': 'Bearer ' + localStorage.getItem('zhiliao_token') }
        }).then(function(response) {
            if (response.status === 401) {
                localStorage.removeItem('zhiliao_token');
                window.location.href = 'login.html';
                throw new Error('未授权');
            }
            if (!response.ok) throw new Error('请求失败: ' + response.status);
            var reader = response.body.getReader();
            var decoder = new TextDecoder();

            function read() {
                reader.read().then(function(result) {
                    if (result.done) {
                        // 流结束，移除光标
                        assistantBubble.innerHTML = fullText;
                        sendBtn.disabled = false;
                        sendBtn.textContent = '发送';
                        return;
                    }
                    var chunk = decoder.decode(result.value, { stream: true });
                    fullText += chunk;
                    // 保留光标在末尾
                    assistantBubble.innerHTML = fullText + '<span class="cursor"></span>';
                    chatBox.scrollTop = chatBox.scrollHeight;
                    read();
                }).catch(function(e) {
                    fullText += '\n\n[连接中断]';
                    assistantBubble.innerHTML = fullText;
                    sendBtn.disabled = false;
                    sendBtn.textContent = '发送';
                });
            }
            read();
        }).catch(function(e) {
            assistantBubble.innerHTML = (fullText || '') + '\n\n[错误: ' + e.message + ']';
            sendBtn.disabled = false;
            sendBtn.textContent = '发送';
        });
    }

    function appendMessage(role, text) {
        var bubble = document.createElement('div');
        bubble.className = 'bubble';
        bubble.textContent = text;
        var msg = document.createElement('div');
        msg.className = 'msg ' + role;
        msg.appendChild(bubble);
        chatBox.appendChild(msg);
        chatBox.scrollTop = chatBox.scrollHeight;
    }
})();
```

---

### Task 6: 部署说明 + 验证

**Files:**
- Create: `zhiliao-frontend/README.md`

- [ ] **Step 1: 创建 README.md**

```markdown
# 知了知了 - 前端测试客户端

纯静态前端页面，用于对接知了知了后端 API 进行功能验证和测试。

## 启动

### 方式一：Python HTTP 服务器

```bash
cd zhiliao-frontend
python3 -m http.server 3000
```

浏览器访问 http://localhost:3000

### 方式二：VS Code Live Server

右键 `index.html` → Open with Live Server

## 配置

修改 `js/config.js` 中的 `BASE_URL` 指向后端服务地址：

```javascript
const CONFIG = {
    BASE_URL: 'http://localhost:8080',
};
```

## 测试流程

1. 打开 http://localhost:3000 → 自动跳转登录页
2. 输入用户名密码（默认 admin/123456）→ 登录
3. **文档管理** → 上传文件 → 查询处理状态
4. **对话测试** → 输入问题 → 验证流式回答和来源标注
```

- [ ] **Step 2: 验证整体流程** — 启动静态服务器，打开浏览器，走通完整链路：登录 → 文档上传 → 查询 → 对话
