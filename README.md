# recite.coach — 背诵小助手 Android

纯离线 Android ASR 背诵评分 App（MVP）。儿童对着手机背课文，实时识别 + 评分 + 错误定位。

## 架构

```
┌─────────────────────────────────────────────────────────┐
│ GitHub Actions CI                                        │
│  push main → version bump → build APK → GitHub Release   │
│  版本：1.0.GITHUB_RUN_NUMBER（自动递增）                    │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ syncthing downloads → 手机自动同步安装                      │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ Android App（纯离线 ASR，无云端依赖）                        │
│  Logcat → 远程日志上报                                     │
│  http://<tailnet>:8080/log                               │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│ Oracle VPS 后端（nginx :8080）                             │
│  /       → LogCat FastAPI :9100（日志收集+浏览器）           │
│  (未来)  → recite.coach 主站 / API                        │
└─────────────────────────────────────────────────────────┘
```

## 后端服务

| 服务 | 本地端口 | 外部入口 | 说明 |
|------|---------|---------|------|
| LogCat FastAPI | :9100 | `http://100.68.80.91:8080/` | 远程日志收集+浏览器 |
| nginx | :8080 | 同上 | 网关（反代 + 默认兜底） |

### 日志浏览器

打开 `http://100.68.80.91:8080/` 实时查看 App 上报的日志，支持按设备/级别/关键词筛选。

### 部署

```bash
# LogCat FastAPI（PM2 管理）
cd ~/projects/logcat
pm2 start "uvicorn server:app --host 0.0.0.0 --port 9100" --name logcat

# nginx 反代（sites-enabled/recite-coach）
sudo nginx -t && sudo systemctl reload nginx
```

配置文件：`nginx/recite-coach.conf`（仓库内）。

## 功能规划

### v1（当前）
- [x] 离线 ASR 背诵评分（sherpa-onnx-paraformer）
- [x] 停顿/重复/错漏字检测
- [x] 远程日志上报
- [x] CI 自动构建 + 版本管理

### v2（待开发）
- [ ] **域名注册 + HTTPS**（Let's Encrypt，Oracle 公网入口开放）
- [ ] **完善的后端服务**（nginx 管理的完整后端：API 网关 + 用户管理 + 多课文支持）
- [ ] 多课文/教材管理
- [ ] 历史记录同步
- [ ] 多用户/家长端

## 构建

```bash
git push origin main   # 自动触发 CI，版本号自动递增
```

产物在 GitHub Releases，APK 文件名：`recite-coach-v1.0.N.apk`。

## 技术栈

- Android: Kotlin + Jetpack Compose
- ASR: sherpa-onnx-paraformer-zh-2023-09-14 (int8, 字级时间戳)
- 后端: FastAPI + nginx + SQLite
- CI: GitHub Actions
