# HelloRokid v2 - 名片扫描系统（Monorepo）

基于 Rokid 智能眼镜的名片扫描和管理系统 v2 版本，采用 Gradle Monorepo 架构。

> v1 已冻结在独立仓库/目录 [`HelloRokid`](../HelloRokid)，本仓库为 v2 全新开发。

## 项目架构

```
HelloRokid-v2/
├── shared/          # 共享模块（数据模型、BLE 协议）
├── glass-app/       # Rokid 眼镜端 App
├── mobile-app/      # Android 手机端 App
└── backend/         # 后端服务（Python FastAPI）
```

## 核心功能

- **隐藏大模型 API**：手机端通过 `BackendApiService` 调用后端，API Key 仅保存在服务端
- **名片数据管理**：手机端本地存储（Room，待完善）
- **蓝牙通信**：眼镜端 Peripheral + 手机端 Central（待完善）
- **共享协议**：`shared` 模块统一 BLE 协议与数据模型

## 快速开始

### 后端服务

```bash
cd backend
pip install -r requirements.txt
cp .env.example .env
# 编辑 .env，填入 GEMINI_API_KEY
python src/main.py
```

服务默认在 `http://localhost:8000` 启动。

### Android App

使用 Android Studio 打开 **本目录根路径** `HelloRokid-v2`：

| 模块 | 安装目标 | Application ID |
|------|----------|----------------|
| `glass-app` | Rokid 眼镜 | `com.example.hellorokid.glass` |
| `mobile-app` | Android 手机 | `com.example.hellorokid.mobile` |

在 Run Configuration 中选择对应模块运行。

### 配置

在项目根目录 `local.properties` 中可配置：

```properties
backend.url=http://10.0.2.2:8000
```

- 模拟器访问本机后端：`http://10.0.2.2:8000`
- 真机访问电脑后端：改为电脑的局域网 IP，如 `http://192.168.1.100:8000`

## 使用流程

1. 启动后端服务
2. 手机端打开 **Rokid Card Manager**，点击扫描连接眼镜
3. 眼镜端打开 **Rokid Glass Scanner**，按 OK 键扫描名片
4. 手机端接收图片、调用后端分析并保存
5. 导出 vCard / CSV（待完善）

## 与 v1 的关系

| 版本 | 位置 | 说明 |
|------|------|------|
| v1 | `HelloRokid` + `RokidCard` repo | 单 App 眼镜端，已冻结 |
| v2 | `HelloRokid-v2` + 新 repo | Monorepo，眼镜 + 手机 + 后端 |

## 开发状态

- [x] Monorepo 多模块结构
- [x] 共享数据模型与 BLE 协议定义
- [x] 眼镜端 / 手机端独立 App
- [x] 后端 Gemini 代理服务
- [ ] BLE 实际通信
- [ ] Room 数据库与名片列表
- [ ] vCard / CSV 导出
