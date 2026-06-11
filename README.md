# HelloRokid v2 - 名片扫描系统（Monorepo）

基于 Rokid 智能眼镜的名片扫描和管理系统 v2 版本，采用 Gradle Monorepo 架构。

> v1 已冻结在独立仓库/目录 [`HelloRokid`](../HelloRokid)，本仓库为 v2 全新开发。

## 文档

- [项目分析与优化方案](docs/project-analysis.md) — 结构说明、完成度评估、功能优化与竞赛方向

## 项目架构

```
HelloRokid-v2/
├── docs/            # 项目文档
├── shared/          # 共享模块（数据模型、BLE 协议、图片编解码）
├── glass-app/       # Rokid 眼镜端 App
├── mobile-app/      # Android 手机端 App
└── backend/         # 后端服务（Python FastAPI + Gemini）
```

## 核心功能

### 端到端链路

```
眼镜拍照 → BLE 分片传输 → 手机接收 → Gemini 分析 → Room 存储 → 眼镜 HUD 展示
```

### 眼镜端（`glass-app`）

- **Camera2 拍照**：`RokidCameraManager` 接入主流程
- **BLE Peripheral**：`GlassBleServer` 广播并等待手机连接
- **图片分片发送**：通过 `shared` 模块的 `BleImageTransfer` 协议传输 JPEG
- **HUD 展示**：扫描结果完整展示姓名、职位、公司、行业、营收、商机等 AI 推断字段
- **按键交互**：OK 键扫描，上下键滚动结果

### 手机端（`mobile-app`）

- **BLE Central**：`PhoneBleClient` 扫描并连接眼镜，接收图片
- **AI 分析**：`BackendApiService` 调用后端 `/api/analyze`，API Key 仅保存在服务端
- **本地存储**：Room 数据库（`BusinessCardEntity`）+ `CardRepository`
- **名片管理**：Material Design 列表、详情页、连接状态指示
- **图片导入**：支持从相册选取名片图片，走同一分析流程
- **导出分享**：vCard / CSV 导出，通过系统分享发送

### 共享模块（`shared`）

| 文件 | 作用 |
|------|------|
| `BusinessCard.kt` | 名片数据模型（基础信息 + AI 商务推断） |
| `BusinessCardJson.kt` | JSON 序列化 / 反序列化 |
| `BleProtocol.kt` | BLE Service UUID、特征值等常量 |
| `BleImageTransfer.kt` | 图片分片传输编解码（START / CHUNK / END） |
| `ImageBleProcessor.kt` | 图片 BLE 发送 / 接收处理器 |

### 后端（`backend`）

- **FastAPI** 代理 Gemini API，隐藏 API Key
- 默认模型 `gemini-2.5-flash`（可通过 `GEMINI_MODEL` 环境变量覆盖）
- 结构化 JSON Schema 输出，含容错解析
- 接收 Base64 图片，返回 19 个名片字段（含部门、手机、传真等）

## 快速开始

### 后端服务

macOS 默认没有 `python` 命令，请使用 `python3` 并建议创建虚拟环境：

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
# 编辑 .env，填入 GEMINI_API_KEY
python src/main.py
```

服务默认在 `http://localhost:8000` 启动。可用浏览器访问根路径验证：

```json
{"status": "ok", "message": "Rokid Card Backend API"}
```

若端口被占用（`address already in use`），查找并结束旧进程：

```bash
lsof -i :8000
kill <PID>
```

### Android App

使用 Android Studio 打开 **本目录根路径** `HelloRokid-v2`：

| 模块 | 安装目标 | Application ID |
|------|----------|----------------|
| `glass-app` | Rokid 眼镜 | `com.example.hellorokid.glass` |
| `mobile-app` | Android 手机 | `com.example.hellorokid.mobile` |

在 Run Configuration 中选择对应模块运行。两端需授予相机和蓝牙权限。

### 配置

在项目根目录 `local.properties` 中可配置：

```properties
backend.url=http://10.0.2.2:8000
```

- 模拟器访问本机后端：`http://10.0.2.2:8000`
- 真机访问电脑后端：改为电脑的局域网 IP，如 `http://192.168.1.100:8000`

后端 `.env` 可选配置：

```properties
GEMINI_API_KEY=your_gemini_api_key_here
GEMINI_MODEL=gemini-2.5-flash
```

## 使用流程

1. 启动后端服务，确保 `.env` 中已配置 `GEMINI_API_KEY`
2. 手机端打开 **Rokid Card Manager**，点击「扫描连接眼镜」
3. 眼镜端打开 **Rokid Glass Scanner**，等待手机连接后按 OK 键扫描名片
4. 手机端自动接收图片、调用后端分析并保存到本地
5. 眼镜端 HUD 展示 AI 分析结果；手机端可查看列表、详情
6. 导出 vCard / CSV 并通过系统分享发送

也可在手机端点击「从相册导入」，无需眼镜直接分析本地名片图片。

## 与 v1 的关系

| 版本 | 位置 | 说明 |
|------|------|------|
| v1 | `HelloRokid` + `RokidCard` repo | 单 App 眼镜端，已冻结 |
| v2 | `HelloRokid-v2` + 新 repo | Monorepo，眼镜 + 手机 + 后端 |

## 开发状态

- [x] Monorepo 多模块结构
- [x] 共享数据模型与 BLE 协议定义
- [x] 眼镜端 / 手机端独立 App
- [x] 后端 Gemini 代理服务（结构化输出 + 模型可配置）
- [x] BLE 实际通信（眼镜 Peripheral + 手机 Central + 图片分片）
- [x] 眼镜端 Camera2 拍照接入
- [x] 眼镜端完整 HUD 结果展示
- [x] Room 数据库与名片列表 / 详情
- [x] vCard / CSV 导出
- [x] 手机端相册图片导入
- [ ] 眼镜端接收手机回传的 AI 结果（BLE 下行）
- [ ] 离线降级（无网时先存图，有网再分析）
- [ ] Event Mode / 智能去重
