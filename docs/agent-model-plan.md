# 提取 Agent：旧实现对照与接回模型服务方案（2026-11-16）

结论先说：**旧版确实调用模型服务**（字节火山方舟 Ark，OpenAI 兼容的 `/chat/completions`），
新实现**一点模型代码都没有**，只有确定性规则。差的不是"一个配置项"，而是"模型应答 + 提取动作闭环"两块。
下面给出证据、差距清单和接回方案。

> **已定（2026-11-16，用户决定）**：接 **方案 B（模型 + 动作闭环）**，供应商沿用**火山方舟 Ark**，
> Agent 每账号每日上限 **200 次**（超限回落规则应答）。密钥由用户在服务器上自行拷贝进
> `/etc/getmedia/llm.env`；**实施时不读取旧服务的 `.env`、`credentials.env` 或任何旧配置**。
> 代码尚未实施，落地顺序见 §3，密钥变量名见 §5。

---

## 1. 旧版调的是哪个模型服务（代码证据）

`GetAudio/getaudio/agent.py`（GetVideo 的同名文件与它逐字节相同，只有 JSON schema 名字从 `getaudio_agent` 换成 `getvideo_agent`）：

```python
# getaudio/config.py
ark_api_key: str = Field(default="", validation_alias="ARK_API_KEY")
ark_url: str = "https://ark.cn-beijing.volces.com/api/v3"
ark_models: str = "doubao-seed-2-0-mini-260428,doubao-seed-2-1-pro-260915"
classify_timeout: float = 60.0

# getaudio/agent.py::answer
for model in dict.fromkeys(x.strip() for x in settings.ark_models.split(",") if x.strip()):
    response = httpx.post(f"{settings.ark_url.rstrip('/')}/chat/completions",
        headers={"Authorization": f"Bearer {settings.ark_api_key}"},
        timeout=settings.classify_timeout,
        json={"model": model, "messages": messages, "max_tokens": 500,
              "thinking": {"type": "disabled"},
              "response_format": {"type": "json_schema", "json_schema": {
                  "name": "getaudio_agent", "schema": AgentAnswer.model_json_schema(),
                  "strict": False}}})
    parsed = AgentAnswer.model_validate_json(response.json()["choices"][0]["message"]["content"])
```

要点：

| 项 | 旧实现 |
|---|---|
| 供应商 | 火山方舟（`ark.cn-beijing.volces.com`），不是 OpenAI 直连 |
| 模型 | 两个豆包模型按顺序 fallback：`doubao-seed-2-0-mini-260428` → `doubao-seed-2-1-pro-260915` |
| 调用形态 | 非流式、`thinking` 关闭、**强制 JSON Schema 输出**（`AgentAnswer` / `Plan`），`max_tokens` 500/700 |
| 无密钥时 | `if not settings.ark_api_key: return _fallback(tracks), None` —— 走规则兜底，**不报错、不发外网请求** |
| 失败时 | 捕获 `HTTPError/ValueError/KeyError/...`，记录模型名与异常类型名（不记密钥），换下一个模型 |
| 密钥来源 | 只从进程环境 `ARK_API_KEY` 读，配置文件里只有变量名 |
| 还有哪些地方用模型 | `extraction_agent.py`（对话规划）、`classification.py`（曲目分类）、GetVideo 的 `recreation_provider.py`（AI 分析/生成，图片+音频模型） |

`extraction_agent.py` 是"有边界的 Agent"：模型只输出一个 `Plan`（`answer` / `track_ids` / `extract` / `memory_proposal`），
然后由**用户点按钮**才真正执行提取；执行结果通过 `POST /agent/v2/actions/{aid}/claim`、`/result` 回传。
也就是说旧 Agent 的能力 = **模型规划 + 动作（actions）+ 证据回传**，不只是聊天。

## 2. 新实现差在哪

| 维度 | 旧（GetAudio / GetVideo） | 新（GetMedia P2d） | 差距性质 |
|---|---|---|---|
| 模型调用 | Ark `/chat/completions`，双模型 fallback，JSON Schema | **完全没有**；`brain` 默认 `_rules_brain` | 功能缺失 |
| 应答质量 | 能理解"帮我把这个 UP 最近 20 条里带吉他的找出来"并给结构化计划 | 5 类关键词命中（批量/定时/库统计/授权/失败）+ 兜底文案 | 能力差距大 |
| 会话 | `agent_conversations` + `agent_events`（role/payload） | `agent_conversations` + `agent_messages`（role/content/meta） | 等价，字段命名不同 |
| 动作闭环 | `agent_actions` + `claim` / `result`（手机领取、执行、回传证据） | **没有 actions** | 功能缺失（与模型无关，是流程） |
| 偏好记忆 | `agent_memories(content, revision)` + 版本表 `agent_memory_versions` | `agent_memories(user_id, key, value)`，无版本 | 简化，够用（网页/手机都只做增删改） |
| 凭据防护 | `safe_text()` 拒绝消息里出现 `ark-…`、`Bearer …`、`api_key/password/cookie=…` | 无（因为没有模型，也没外发） | 接回模型时必须补 |
| 越权面 | `/agent/memories/{user_id}` 路径带任意 user_id（有越权风险） | `user_id` 从会话取，URL 不带 | 新实现更好，保持 |
| 降级 | 无密钥→规则；模型全挂→规则 | 只有规则 | 接回后要保持"模型挂了也不 500" |

**一句话**：新实现把"壳"（会话、记忆、鉴权）做得比旧版干净，但把"脑"（模型）和"手"（actions）都砍了。
P2 当时是刻意的（无密钥不假装能用），现在要不要装回去由你定。

## 3. 接回模型服务方案（三档，建议 B）

### 方案 A：最小接入（只接"脑"，不做 actions）

新增 `server/getmedia/llm.py`，实现一个 `ArkBrain`，在 `create_app` 里注入 `AgentService(brain=...)`：

```python
# 伪代码，形状与旧版一致
class LlmConfig:                       # 只从环境读
    base_url: str = "https://ark.cn-beijing.volces.com/api/v3"
    api_key: str = ""                  # GETMEDIA_LLM_API_KEY
    models: tuple[str, ...] = ("doubao-seed-2-0-mini-260428", "doubao-seed-2-1-pro-260915")
    timeout: float = 35.0
    max_tokens: int = 700

def make_brain(cfg, stats_provider):
    if not cfg.api_key:
        return _rules_brain            # 与现在一致
    def brain(message: str, context: dict) -> str:
        try:
            return _call(cfg, message, context)   # JSON schema: {answer, track_ids?}
        except Exception as exc:                  # 只记异常类型
            log.warning("llm 调用失败：%s", type(exc).__name__)
            return _rules_brain(message, context)  # 不 500、不空回复
    return brain
```

- 回复写库时 `meta.engine` 记 `model:<名>` 或 `rules`，网页/手机能看出这条是不是模型答的。
- **保留现有全部规则**：模型不可用/超时/JSON 不合法 → 立刻回落规则，用户永远有回复。
- 请求里带最小上下文：库里最近 N 条标题 + 统计 + 用户消息；**不带 Cookie、不带会话令牌、不带手机号**。
- 入口加 `safe_text()`（照抄旧版正则）：命中 `Bearer …`/`api_key=`/`cookie=`/`SESSDATA=` 直接 400 并提示"请移除凭据"。
- 超时 35 s、单次 1 次重试、失败换下一个模型，最多 2 个模型（与旧版一致）。

### 方案 B（**已选**）：A + 恢复动作闭环

在 A 之上补 `agent_actions`（表 + 3 条接口），把"模型建议提取"变成可执行、可验证的流程：

| 新增 | 说明 |
|---|---|
| `POST /v2/agent/chat` 返回体加 `actions: [{id, kind:"extract", input, requires_confirm:true}]` | 模型只能"提议"，不能自己执行 |
| `POST /v2/agent/actions/{id}/claim` | 手机端领取（与旧版同名同语义） |
| `POST /v2/agent/actions/{id}/result` | 回传 `source: device_reported` + 成功/失败与错误码 |
| 表 `agent_actions(id, conversation_id, kind, payload_json, status, created_at, claimed_at, finished_at)` | v11 迁移 |

理由：旧 App 的 Agent 页依赖这套交互（`GetAudioApi.kt` 里确实在调 `/agent/v2/actions/...`），
只接模型不接动作，full 版 Agent 页仍然只能聊天、不能"让 Agent 帮你提取"，与旧版差距还在。

### 方案 C（不采用）：B + 分类也接回（`classification.py`）

旧版用模型给曲目打分类标签。新版 tracks 有 `categories` 字段但没有自动分类。
建议**先不做**：这一项可以用"标题关键词 + 平台分区"覆盖 80%，且失败时不影响主流程。等你用一段时间再评估。

### 密钥与配置（三档共用）

| 项 | 规定 |
|---|---|
| 变量 | `GETMEDIA_LLM_API_KEY`、`GETMEDIA_LLM_BASE_URL`、`GETMEDIA_LLM_MODELS`、`GETMEDIA_LLM_TIMEOUT`、`GETMEDIA_LLM_MAX_TOKENS`、`GETMEDIA_LLM_DAILY_CAP`（完整清单见 §5） |
| 存放 | `/etc/getmedia/llm.env`，`root:root 600`；systemd 用 `EnvironmentFile=-/etc/getmedia/llm.env` 单独加载 |
| 不进 | Git（`.gitignore` 已含 `*.env`）、客户端、日志、错误响应、迁移报告 |
| 客户端 | 永远不持有模型密钥；手机端只调 `/v2/agent/chat` |
| 日志 | 只记 `type(exc).__name__` 与模型名；**不记 prompt、不记响应原文**（旧版也没记） |
| 轮换 | 改 `/etc/getmedia/llm.env` → `systemctl restart getmedia-api`；无需改代码 |
| 关闭 | 删除该文件的 `GETMEDIA_LLM_API_KEY` 或注释掉 → 重启即回到纯规则 |

### 费用与滥用控制（新增，旧版没有）

1. 每账号每日调用上限（默认 200 次，`GETMEDIA_LLM_DAILY_CAP`），超限直接回规则并在回复里说明。
2. 单条消息 ≤ 4000 字（已有 `MAX_MESSAGE`）；上下文最多 20 条曲目 + 最近 10 条会话。
3. 只对 owner 开放（`/v2/agent/*` 已是 `require_tier("owner")`，无需改）。
4. `GET /v2/agent/status` 返回 `{engine, models, calls_today, cap}`，网页 owner 可看，不含密钥。

### 落地顺序与验收

| 步 | 内容 | 验收 |
|---|---|---|
| 1 | `llm.py` + 注入 + 单测（假 HTTP：成功/超时/非 JSON/401） | 无密钥→`engine=rules`；有密钥→`engine=model:…`；异常→回落且不 500 |
| 2 | `safe_text` 拒收凭据 + 单测 | 含 `SESSDATA=`/`Bearer` 的消息 400 |
| 3 | `/v2/agent/status` + 每日上限 | 超限后仍能收到规则回复，响应里有 `degraded_reason` |
| 4 | 迁移 v11 + actions 三接口（方案 B） | claim/result 状态机与旧版一致；重复 claim 409 |
| 5 | 服务器实测 | 用真实密钥跑一次，确认耗时 < 8 s、回复可解析、日志无密钥（`scan_leaks.py` 通过） |

## 4. 已确认的决定（2026-11-16）

| 项 | 决定 | 对实施的影响 |
|---|---|---|
| 档位 | **B**：模型应答 + `actions` 动作闭环 | 要加 `llm.py`、`agent_actions` 表（schema v11）、`claim/result` 两条接口 |
| 供应商 | **火山方舟 Ark**（沿用旧版，OpenAI 兼容） | 只走 `/chat/completions`，不引入厂商 SDK，用 httpx 直连 |
| 每日上限 | **200 次/账号** | `GETMEDIA_LLM_DAILY_CAP=200`；超限回落规则并在回复里说明，不报错 |
| 密钥来源 | 用户在服务器上自行拷贝，**不由我读取旧 `.env`** | 实施与测试只用假密钥；验收前不产生任何真实调用 |
| 分类 | 不做（原方案 C 里的自动分类） | `classification.py` 不移植 |

**不做**：读取旧服务 `.env` / `credentials.env` / 任何旧配置文件；把密钥写进仓库、聊天、日志或响应。

## 5. `/etc/getmedia/llm.env` 需要的变量（只有键名与示例行，不含值）

文件位置 `/etc/getmedia/llm.env`，属主 `root:root`，权限 `600`；由
`deploy/systemd/getmedia-api.service` 里的 `EnvironmentFile=-/etc/getmedia/llm.env` 加载（`-` 表示缺文件也能启动）。
**等号右边由你自己在服务器上粘贴，本仓库与本对话里不出现真实值。**

| 变量 | 必填 | 说明 |
|---|---|---|
| `GETMEDIA_LLM_API_KEY` | 是 | Ark 的 API Key。留空/删掉 → 纯规则应答，不发外网请求 |
| `GETMEDIA_LLM_BASE_URL` | 否 | 接口地址，默认火山方舟北京区。这是**地址不是密钥**；换供应商改这里，须保持 `/chat/completions` 形状 |
| `GETMEDIA_LLM_MODELS` | 否 | 模型名/推理接入点 ID，逗号分隔，按顺序 fallback；留空用内置默认 |
| `GETMEDIA_LLM_TIMEOUT` | 否 | 单次调用超时（秒），默认 35 |
| `GETMEDIA_LLM_MAX_TOKENS` | 否 | 最大输出 token，默认 700 |
| `GETMEDIA_LLM_DAILY_CAP` | 否 | 每账号每日调用上限，默认 200 |

示例行（**等号右边由你在服务器上填**，这里只给形状）：

```sh
GETMEDIA_LLM_API_KEY=<在服务器上粘贴>
GETMEDIA_LLM_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
GETMEDIA_LLM_MODELS=<模型1>,<模型2>
GETMEDIA_LLM_TIMEOUT=35
GETMEDIA_LLM_MAX_TOKENS=700
GETMEDIA_LLM_DAILY_CAP=200
```

服务器上的填写与校验（不打印值）：

```sh
sudo install -d -m 0750 -o root -g getmedia /etc/getmedia
sudo install -m 600 -o root -g root /dev/null /etc/getmedia/llm.env
sudoedit /etc/getmedia/llm.env          # 只填 GETMEDIA_LLM_API_KEY（以及你要覆盖的可选项）
sudo systemctl restart getmedia-api
# 只验证"有没有加载到"，不显示内容。
# 注意：systemctl show -p Environment 只反映单元里的 Environment=，看不到 EnvironmentFile 的内容，
# 必须读进程环境（-0 分隔，tr 成换行后只匹配键名，值不会出现在输出里）：
sudo tr '\0' '\n' < /proc/$(systemctl show -p MainPID --value getmedia-api)/environ \
  | grep -c '^GETMEDIA_LLM_API_KEY='
```

`grep -c` 输出 `1` 即已加载；输出 `0` 说明文件没被读到（检查路径、`EnvironmentFile` 行、是否 `daemon-reload`、
以及 `MainPID` 是不是重启后的新进程）。

代码实施后 `GET /v2/agent/status` 会返回 `{engine, models, calls_today, cap}`，可确认"模型生效、今天用了几次"，同样不含密钥。
