# DeepSeek 4.1 和 Kimi 快速配置指南

## 快速开始（5 分钟）

### 前提条件
- 已有管理员账号登录权限
- 已获取 API Key（DeepSeek 或 Moonshot）

### 配置步骤

#### 1. 登录管理后台
访问：http://j3160.aicodex.work

#### 2. 进入模型注册表
导航：**设置** -> **系统配置** -> **模型注册表** 标签页

#### 3. 添加模型

**选项 A：添加 DeepSeek Flash (V4.1)**
```
模型标识: deepseek-flash
显示名称: DeepSeek Flash (V4.1)
提供商类型: openai-compatible
协议: responses
模型名称: deepseek-flash
Base URL: https://api.deepseek.com
API Key: sk-你的密钥

上下文窗口: 1000000
最大输出: 384000
支持流式: ✓
推理档位: low,high,deep,max
启用: ✓
```

**选项 B：添加 Moonshot (Kimi)**
```
模型标识: moonshot-v1-128k
显示名称: Moonshot 128K (Kimi)
提供商类型: openai-compatible
协议: chat_completions
模型名称: moonshot-v1-128k
Base URL: https://api.moonshot.cn/v1
API Key: sk-你的密钥

上下文窗口: 131072
最大输出: 65536
支持流式: ✓
推理档位: low,high,max
启用: ✓
```

#### 4. 探测和保存
1. 点击模型右侧的 **探测** 按钮，验证连通性
2. 确认探测成功（绿色提示）
3. 点击 **保存模型注册表** 按钮

#### 5. 验证
1. 进入 **AI 问答** 页面
2. 点击模型选择器
3. 确认能看到新添加的模型
4. 发送测试消息

### 常见问题

**Q: 保存后看不到新模型？**
- 刷新浏览器（Ctrl+Shift+R）
- 检查模型的 "启用" 状态是否勾选

**Q: 探测失败？**
- 确认 API Key 格式正确（以 `sk-` 开头）
- 检查 Key 是否已在官网激活
- 确认有网络访问权限

**Q: API Key 会被泄露吗？**
- 不会，数据库中自动加密存储
- 前端显示为脱敏状态（`sk-***...***`）

---

## 详细文档
完整配置说明请查看：`docs/deepseek-4.1-kimi-config.md`

## 技术支持
- DeepSeek: https://api-docs.deepseek.com/zh-cn/quick_start/pricing
- Moonshot: https://platform.moonshot.cn/docs
