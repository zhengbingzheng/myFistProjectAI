# 附件接口前端调用示例（框架中立）

基于浏览器原生 `fetch` 与 `FormData`，不依赖任何前端框架。可按需替换为 axios、jQuery.ajax 或请求拦截器。

统一约定：

| 项 | 说明 |
|---|---|
| 基础地址 | `BASE = http://localhost:8080` |
| 上传入参 | `multipart/form-data`，单文件字段名 **file**，多文件字段名 **files** |
| 统一响应 | `{ code, message, data, timestamp }`，`code = 200` 表示成功 |
| 失败响应 | HTTP 状态码与 `code` 一致（400 / 404 / 500），文案在 `message` |

---

## 0. 公共工具

```js
const BASE = 'http://localhost:8080';

/** 统一解析错误：把 { code, message } 转成 Error */
async function parseApiError(response) {
    let message = `HTTP ${response.status}`;
    try {
        // 下载接口失败时返回的也是 JSON 错误体，但带 application/json 类型，需要区分处理
        const body = await response.clone().json();
        message = body?.message || message;
    } catch {
        // 非 JSON（一般不会），保留默认文案
    }
    const error = new Error(message);
    error.code = response.status;
    return error;
}

/** 触发浏览器下载 */
function saveBlob(blob, filename) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
}

/** 从 Content-Disposition 提取文件名（兼容 RFC 5987 的 filename*=UTF-8''） */
function filenameFrom(response, fallback) {
    const raw = response.headers.get('Content-Disposition') || '';
    const utf8 = raw.match(/filename\*=UTF-8''([^;]+)/i);
    if (utf8) {
        return decodeURIComponent(utf8[1]);
    }
    const plain = raw.match(/filename="?([^";]+)"?/i);
    return plain ? decodeURIComponent(plain[1]) : fallback;
}
```

---

## 1. 上传单个附件

```js
/**
 * @param file 来自 <input type="file"> 的 File 对象
 * @returns 附件 id，后续下载/删除都要用它
 */
async function uploadSingle(file) {
    const form = new FormData();
    form.append('file', file); // 字段名必须是 file

    const response = await fetch(`${BASE}/api/attachments/upload`, {
        method: 'POST',
        body: form
        // 注意：不要手动设置 Content-Type，浏览器会自动补上 multipart 的 boundary
    });

    const body = await response.json();
    if (!response.ok || body.code !== 200) {
        throw new Error(body.message || '上传失败');
    }
    return body.data;  // { id, originalName, fileType, fileSize, ... }
}

// 调用示例
const input = document.querySelector('#file');
input.addEventListener('change', async () => {
    const file = input.files[0];
    if (!file) return;
    try {
        const attachment = await uploadSingle(file);
        console.log('上传成功，id =', attachment.id);
    } catch (error) {
        alert(`上传失败：${error.message}`);
    }
});
```

---

## 2. 上传多个附件

```js
/**
 * @param files FileList 或 File[]，同一个字段名重复追加
 */
async function uploadMultiple(files) {
    const form = new FormData();
    for (const file of files) {
        form.append('files', file); // 字段名必须是 files，可多次 append
    }

    const response = await fetch(`${BASE}/api/attachments/upload/batch`, {
        method: 'POST',
        body: form
    });

    const body = await response.json();
    if (!response.ok || body.code !== 200) {
        throw new Error(body.message || '上传失败');
    }
    return body.data; // AttachmentVO 数组
}

// 调用示例：<input type="file" id="files" multiple>
document.querySelector('#files').addEventListener('change', async (event) => {
    try {
        const uploaded = await uploadMultiple(event.target.files);
        alert(`成功上传 ${uploaded.length} 个文件`);
    } catch (error) {
        alert(`上传失败：${error.message}`);
    }
});
```

> 批量上传语义：逐个落盘。**中途失败会中止后续处理，但已成功的部分不会回滚**，前端应提示用户检查已生成的文件。

---

## 3. 下载单个附件

```js
async function downloadSingle(id, fallbackName = 'download') {
    const response = await fetch(`${BASE}/api/attachments/${id}/download`);

    if (!response.ok) {
        throw await parseApiError(response); // 例如：附件不存在
    }

    const blob = await response.blob();
    saveBlob(blob, filenameFrom(response, fallbackName));
}

// 调用示例
async function handleDownload(id) {
    try {
        await downloadSingle(id);
    } catch (error) {
        alert(`下载失败：${error.message}`);
    }
}
```

---

## 4. 在线预览

预览走独立的 `preview` 地址，它返回 `Content-Disposition: inline`，因此 `<iframe>` / `<img>` / `<video>` 能直接渲染，不会被浏览器转成下载。

| 文件类型 | 服务端裁决 | 实际效果 |
|---|---|---|
| 图片 `image/*`（SVG 除外） | inline + 原类型 | 直接渲染 |
| 音视频 `video/*` `audio/*` | inline + 原类型 | 直接播放 |
| PDF `application/pdf` | inline + `application/pdf` | 内置阅读器渲染 |
| 文本类（含 `text/html`） | inline + `text/plain` | 显示**源码**，不执行脚本 |
| SVG、zip、docx 等 | `attachment` | 浏览器转为下载 |

**HTML 为什么显示的是源码而不是排版后的页面？**
附件内容是用户上传的。若按 `text/html` 内联，其中的 `<script>` 会在本站域名下执行，可以读取 Cookie 并调用本站接口。所以服务端把它降级为纯文本输出，并用 `nosniff` 与 `CSP sandbox` 兜底。这是有意的安全取舍。

**能否用请求参数切换 inline？**
不提供这个开关。内联与否由服务端按媒体类型裁决——一旦允许前端传 `inline=true`，上传者就能让任意文件获得同源执行环境，前面的防护会被绕过。

```js
/**
 * 预览地址，可直接赋给 <iframe src> / <img src> / <video src>
 * 注意：不在白名单里的类型（SVG、zip 等）服务端会自动回退成 attachment，浏览器转为下载，
 * 前端无需为了区分类型而分支处理。
 */
function previewUrl(id) {
    return `${BASE}/api/attachments/${encodeURIComponent(id)}/preview`;
}

// 用法一：iframe 预览（PDF、文本、图片都适用）
function openPreview(id) {
    document.querySelector('#viewer').src = previewUrl(id);
}

// 用法二：先判断能否内联渲染（例如决定是否显示"下载"兜底按钮）
async function canPreviewInline(id) {
    const response = await fetch(previewUrl(id), { method: 'HEAD' });
    if (!response.ok) {
        return false; // 404：附件不存在、已进回收站、或磁盘文件已丢失
    }
    return (response.headers.get('Content-Disposition') || '').startsWith('inline');
}
```

> **预览不计入下载次数**：`download_count` 只统计真正的下载动作，反复打开预览不会让它增长。
>
> **视频进度条拖动**：接口当前未实现 HTTP Range（206 Partial Content），部分浏览器拖动大视频进度条时会重新拉取整个文件。需要的话我可以补上。

---

## 5. 下载多个附件（打包 ZIP）

```js
/**
 * @param ids 附件 id 数组，来源于上传接口的返回值
 */
async function downloadZip(ids) {
    const response = await fetch(`${BASE}/api/attachments/download/zip`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids })
    });

    // 关键：超过服务端阈值时返回 400 + JSON，而不是 ZIP 流，必须在这里拦下来
    if (!response.ok) {
        throw await parseApiError(response);
    }

    const blob = await response.blob();
    saveBlob(blob, filenameFrom(response, 'attachments.zip'));
}

// 调用示例
async function handleBatchDownload(selectedIds) {
    if (!selectedIds || selectedIds.length === 0) {
        alert('请先选择要下载的附件');
        return;
    }
    try {
        await downloadZip(selectedIds);
    } catch (error) {
        // 命中阈值时会收到：多个附件下载超大，不允许打包下载
        alert(`下载失败：${error.message}`);
    }
}
```

### 为什么打包下载必须做这层判断

服务端返回的数据有两种形态，前端不能一律当 blob 处理：

| 情况 | 响应 | 前端应做什么 |
|---|---|---|
| 正常 | `200` + `application/octet-stream`（ZIP 流） | 生成 blob 并触发下载 |
| 总大小超阈值 | `400` + `application/json` | 读取 `message` 展示提示，**不能**当 ZIP 保存 |
| 含不存在的 id | `404` + `application/json` | 展示提示，不产生文件 |

若不加 `response.ok` 判断，超限时用户会下载到一个扩展名为 `.zip`、实际内容是 JSON 的坏文件。

---

## 6. 附件分页查询（正常 / 回收站共用）

两个列表接口的查询参数完全一致，只有路径不同。

| 参数 | 说明 |
|---|---|
| `originalName` | 文件名模糊匹配，可选。含 `%` `_` 时服务端已做转义，直接传原文即可 |
| `fileType` | 分类精确匹配：`image` / `document` / `video` / `audio` / `archive` / `other`，可选 |
| `startTime` `endTime` | 上传时间区间，**格式必须是 `yyyy-MM-dd HH:mm:ss`**，含边界，可选 |
| `page` | 页码，从 1 开始，默认 1 |
| `pageSize` | 每页条数，默认 10，上限 100 |

`data` 结构：`{ items, total, page, pageSize }`。回收站列表的 `items` 元素还会多出 `deleteTime` 与 `recycleExpireTime`。

```js
/**
 * @param params  { originalName, fileType, startTime, endTime, page, pageSize }
 * @param recycled true 查回收站，false 查正常列表
 */
async function pageAttachments(params = {}, recycled = false) {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
        if (value !== undefined && value !== null && value !== '') {
            query.set(key, value);
        }
    }
    const path = recycled ? '/api/attachments/recycle' : '/api/attachments';

    const response = await fetch(`${BASE}${path}?${query}`);
    const body = await response.json();
    if (!response.ok || body.code !== 200) {
        // 例如：开始时间不能晚于结束时间 / 非法的文件分类：xxx
        throw new Error(body.message || '查询失败');
    }
    return body.data; // { items, total, page, pageSize }
}

/**
 * 页码越界的兜底处理：服务端不报错，而是返回空 items + 真实 total，
 * 前端据此跳回最后一页，避免用户停在一片空白上看不出原因。
 */
async function loadPageWithGuard(page, pageSize = 10, keyword = '') {
    const result = await pageAttachments({ page, pageSize, originalName: keyword });
    if (result.items.length === 0 && result.total > 0) {
        const lastPage = Math.max(1, Math.ceil(result.total / result.pageSize));
        return loadPageWithGuard(lastPage, pageSize, keyword);
    }
    return result;
}

// 调用示例
async function renderList(page = 1, keyword = '') {
    try {
        const { items, total } = await loadPageWithGuard(page, 10, keyword);
        console.log(`第 ${page} 页，共 ${total} 条：`, items);
    } catch (error) {
        alert(`查询失败：${error.message}`);
    }
}
```

> 注意：`startTime` / `endTime` 若传错格式（如 `2026/10/01`）会返回 **400 参数 startTime 类型不正确**，建议前端统一用同一套格式化函数。

---

## 7. 删除附件（两级删除）

删除被刻意拆成两级，中间隔着一个回收站：

| 步骤 | 接口 | 是否可逆 | 说明 |
|---|---|---|---|
| ① 移入回收站 | `DELETE /api/attachments/{id}`（批量：`/batch`） | **可逆** | 文件仍在磁盘上，可在回收站恢复 |
| ② 彻底删除 | `DELETE /api/attachments/recycle/{id}`（批量：`/recycle/batch`） | **不可逆** | 记录与磁盘文件一起清除 |

**后端保证第 ② 步不对正常状态的附件生效**，直接调用会返回 409 `附件不在回收站中，不能彻底删除：xxx（请先移入回收站）`。这是防止误删的唯一防线，前端不要试图绕过它——顺序必须是先 ① 再 ②。

```js
/** 删除单个附件（移入回收站，可在回收站恢复） */
async function removeOne(id) {
    const response = await fetch(`${BASE}/api/attachments/${encodeURIComponent(id)}`, {
        method: 'DELETE'
    });
    const body = await response.json();
    if (!response.ok || body.code !== 200) {
        throw new Error(body.message || '删除失败');
    }
    return body.message; // 已删除，可在回收站恢复
}

/**
 * 批量删除（移入回收站）。
 * 整批通过才执行：任一条不存在或已在回收站，一条都不会被删除。
 */
async function removeBatch(ids) {
    const response = await fetch(`${BASE}/api/attachments/batch`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids })
    });
    const body = await response.json();
    if (!response.ok || body.code !== 200) {
        throw new Error(body.message || '批量删除失败');
    }
    return body.message; // 已删除 N 个附件，可在回收站恢复
}

/** 彻底删除：只回收收站里的附件，正常状态的会返回 409 */
async function purgeOne(id) {
    const response = await fetch(`${BASE}/api/attachments/recycle/${encodeURIComponent(id)}`, {
        method: 'DELETE'
    });
    const body = await response.json();
    if (!response.ok || body.code !== 200) {
        throw new Error(body.message || '彻底删除失败');
    }
    return body.message;
}

/** 批量彻底删除 */
async function purgeBatch(ids) {
    const response = await fetch(`${BASE}/api/attachments/recycle/batch`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids })
    });
    const body = await response.json();
    if (!response.ok || body.code !== 200) {
        throw new Error(body.message || '批量彻底删除失败');
    }
    return body.message;
}

/** 清空回收站：回收站为空时也返回成功 */
async function emptyRecycle() {
    const response = await fetch(`${BASE}/api/attachments/recycle`, { method: 'DELETE' });
    const body = await response.json();
    if (!response.ok || body.code !== 200) {
        throw new Error(body.message || '清空回收站失败');
    }
    return body.message;
}

// 调用示例：先确认，再删除，最后刷新
async function handleDelete(selectedIds) {
    if (!selectedIds?.length) return;
    try {
        const message = selectedIds.length === 1
            ? await removeOne(selectedIds[0])
            : await removeBatch(selectedIds);
        alert(message);
        await reloadList();
    } catch (error) {
        // 常见：附件已在回收站中，无需重复删除：xxx（409）
        alert(`删除失败：${error.message}`);
    }
}
```

> 单次批量上限 **500** 条，超限返回 400。彻底删除会等到数据库事务提交后再清理磁盘文件，因此极端情况下可能残留孤儿文件，但不会出现「记录还在、文件已没了」这种更难处理的不一致。

---

## 8. 回收站恢复

单个恢复与批量恢复是**两个独立接口**，失败语义不同：

| 接口 | 成功 | 失败 |
|---|---|---|
| `POST /api/attachments/{id}/restore` | 200 `恢复成功` | 404 附件不存在 / 409 附件不处于回收站 |
| `POST /api/attachments/restore/batch` | 200 `已恢复 N 个附件` | 404 含不存在的 id / 409 含非回收站 id，**整批不执行** |

```js
/** 恢复单个附件 */
async function restoreOne(id) {
    const response = await fetch(`${BASE}/api/attachments/${encodeURIComponent(id)}/restore`, {
        method: 'POST'
    });
    const body = await response.json();
    if (!response.ok || body.code !== 200) {
        throw new Error(body.message || '恢复失败');
    }
    return body.message; // 恢复成功
}

/** 批量恢复：ids 中任一条不合法则全部不执行 */
async function restoreBatch(ids) {
    const response = await fetch(`${BASE}/api/attachments/restore/batch`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids })
    });
    const body = await response.json();
    if (!response.ok || body.code !== 200) {
        throw new Error(body.message || '批量恢复失败');
    }
    return body.message; // 已恢复 N 个附件
}

// 调用示例
async function handleRestore(selectedIds) {
    if (!selectedIds?.length) {
        alert('请先选择要恢复的附件');
        return;
    }
    try {
        const message = selectedIds.length === 1
            ? await restoreOne(selectedIds[0])
            : await restoreBatch(selectedIds);
        alert(message);
        await renderList(); // 刷新列表
    } catch (error) {
        // 常见：附件不在回收站中，无需恢复：季度报告.txt（409）
        alert(`恢复失败：${error.message}`);
    }
}
```

> 服务端会先对 ids 去重，单次上限 **500** 条，超限返回 400。

---

## 9. 错误码对照

| code | HTTP | 典型文案 | 处理建议 |
|---|---|---|---|
| 200 | 200 | 操作成功 | 正常流程 |
| 400 | 400 | 上传的文件不能为空 / 文件大小超过上限，单个文件最大 N KB | 提示用户压缩或更换文件 |
| 400 | 400 | **多个附件下载超大，不允许打包下载** | 提示用户减少数量或分批下载 |
| 400 | 400 | 缺少上传文件字段：file | 检查 FormData 字段名 |
| 404 | 404 | 附件不存在：xxx / 附件文件已丢失：xxx | 提示刷新列表，记录可能已被清理 |
| 404 | 404 | 待恢复的附件中包含不存在的数据，未执行恢复 | 刷新列表后重新选择 |
| 409 | 409 | 附件不在回收站中，无需恢复：xxx | 刷新列表，该附件已是正常状态 |
| 409 | 409 | 待恢复的附件中存在不处于回收站状态的数据，未执行恢复 | 同上；批量时被整批拒绝 |
| 400 | 400 | 开始时间不能晚于结束时间 / 非法的文件分类：xxx / 分页偏移量过大 | 修正查询条件后重试 |
| 409 | 409 | 附件已在回收站中，无需重复删除：xxx | 刷新列表，该附件已在回收站 |
| 409 | 409 | 附件不在回收站中，不能彻底删除：xxx（请先移入回收站） | 先调用删除接口移入回收站，再彻底删除 |
| 404 | 404 | 待删除的附件中包含不存在的数据，未执行删除 | 刷新列表后重新选择 |
| 500 | 500 | 恢复附件失败：预期影响 1 条，实际 0 条，操作已回滚 | 数据可能已被并发修改，刷新列表后重试 |
| 500 | 500 | 服务内部异常 | 记录日志，提示稍后重试 |

---

## 10. 常见问题

**中文文件名乱码？**
响应头已同时给出 `filename=` 与 `filename*=UTF-8''`，用上面 `filenameFrom()` 取值即可，不要用响应头的原始字符串。

**下载到的 ZIP 打不开？**
先确认不是把错误 JSON 存成了 ZIP（见第 4 节）。若确实是流损坏，检查是否有代理对响应做了缓冲改写。

**想直连下载（不用 blob）？**
单个下载可以直接拼 `<a href>` 或 `location.href`，服务端已返回 `Content-Disposition: attachment`：

```html
<a href="http://localhost:8080/api/attachments/{id}/download">下载（或右键另存）</a>
```

但**批量下载不能这样做**——它需要 POST 携带 JSON body，必须由脚本发起并通过 blob 保存。
