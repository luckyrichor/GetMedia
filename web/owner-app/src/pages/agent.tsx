/** 5d：提取 Agent。
 *
 * 这里能做的：看模型是否生效、今天用了几次、开会话、发消息、看本轮回复、看偏好记忆（可增删改）。
 * 这里故意**不做**的：领取动作。动作（`/v2/agent/actions/{id}/claim`）是"谁来干活"的约定——
 * 由手机端领取并回报结果，网页领取只会把状态推到 running 然后没人执行，所以只读展示。
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import {
  ownerApi, type AgentStatus, type Conversation, type ConversationDetail, type Memory,
} from '../api';
import { ACTION } from '../labels';
import { Empty, ErrorBox, fmtTime, Loading, relTime } from '../ui';

export function AgentPage() {
  const [status, setStatus] = useState<AgentStatus | null>(null);
  const [statusErr, setStatusErr] = useState('');
  const [convs, setConvs] = useState<Conversation[]>([]);
  const [curId, setCurId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ConversationDetail | null>(null);
  const [detailErr, setDetailErr] = useState('');
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [memories, setMemories] = useState<Memory[]>([]);
  const [memKey, setMemKey] = useState('');
  const [memVal, setMemVal] = useState('');
  const bottom = useRef<HTMLDivElement | null>(null);

  async function loadStatus() {
    try {
      setStatus(await ownerApi.agentStatus());
      setStatusErr('');
    } catch (e) {
      setStatusErr(messageFor(e));
    }
  }

  async function loadConvs() {
    try {
      setConvs(await ownerApi.conversations());
    } catch (e) {
      setErr(messageFor(e));
    }
  }

  async function loadMemories() {
    try {
      setMemories(await ownerApi.memories());
    } catch (e) {
      setErr(messageFor(e));
    }
  }

  async function open(id: string) {
    setCurId(id);
    setDetail(null);
    setDetailErr('');
    try {
      setDetail(await ownerApi.conversation(id));
    } catch (e) {
      setDetailErr(messageFor(e));
    }
  }

  useEffect(() => {
    void loadStatus();
    void loadConvs();
    void loadMemories();
  }, []);

  useEffect(() => {
    bottom.current?.scrollIntoView({ block: 'nearest' });
  }, [detail?.messages.length]);

  async function send() {
    const message = draft.trim();
    if (!message) return;
    setBusy(true);
    setErr('');
    try {
      const out = await ownerApi.chat(message, curId);
      setDraft('');
      await loadConvs();
      await open(out.conversation_id);
      await loadStatus();
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function newConv() {
    setBusy(true);
    setErr('');
    try {
      const c = await ownerApi.newConversation('');
      await loadConvs();
      await open(c.id);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function dropConv(c: Conversation) {
    if (!confirm(`删除会话「${c.title || '未命名'}」？消息会一起删掉，记忆不受影响。`)) return;
    setBusy(true);
    setErr('');
    try {
      await ownerApi.dropConversation(c.id);
      if (curId === c.id) {
        setCurId(null);
        setDetail(null);
      }
      await loadConvs();
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveMemory() {
    const key = memKey.trim();
    if (!key) {
      setErr('记忆的键不能为空');
      return;
    }
    setBusy(true);
    setErr('');
    try {
      await ownerApi.putMemory(key, memVal);
      setMemKey('');
      setMemVal('');
      await loadMemories();
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function dropMemory(m: Memory) {
    setBusy(true);
    setErr('');
    try {
      await ownerApi.dropMemory(m.key);
      await loadMemories();
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1>提取 Agent</h1>
      <p class="lead">
        对话式找作品、按条件筛选、给出下一步建议。模型没配好时会退回"规则应答"，页面上会如实显示当前是哪种。
      </p>

      {statusErr && <div class="alert warn small">{statusErr}</div>}
      {status && (
        <div class="cards">
          <div class="metric">
            <div class="metric-k">应答引擎</div>
            <div class="metric-v">{status.engine === 'model' ? '模型' : '规则'}</div>
          </div>
          <div class="metric">
            <div class="metric-k">今日调用</div>
            <div class="metric-v">{status.calls_today} / {status.daily_cap}</div>
          </div>
          <div class="metric">
            <div class="metric-k">模型</div>
            <div class="metric-v small">{status.models.length > 0 ? status.models.join(' → ') : '未配置'}</div>
          </div>
          <div class="metric">
            <div class="metric-k">凭据保险箱</div>
            <div class="metric-v">{status.vault_ready ? '可用' : '不可用'}</div>
          </div>
        </div>
      )}
      {status?.engine === 'rules' && (
        <div class="alert info">
          当前是<strong>规则应答</strong>：没配 `GETMEDIA_LLM_API_KEY` 与 `GETMEDIA_LLM_MODELS` 时不发任何外网请求，
          回复只按关键词给固定动作。这不是故障，是省流量的默认态。
        </div>
      )}

      <div class="split">
        <div class="col">
          <div class="col-head">
            <strong>会话</strong>
            <button type="button" class="btn btn-ghost small" disabled={busy} onClick={() => void newConv()}>
              新建
            </button>
          </div>
          {convs.length === 0 ? (
            <p class="small muted">还没有会话。直接发消息会自动建一个。</p>
          ) : (
            <ul class="convs">
              {convs.map((c) => (
                <li class={curId === c.id ? 'conv on' : 'conv'}>
                  <button type="button" class="conv-main" onClick={() => void open(c.id)}>
                    {/* 侧栏只有 320px，长标题按一行省略；完整标题进 tooltip
                        （这里不放横向滚动条：会把右边的相对时间挤成一条缝） */}
                    <span class="ellipsis" title={c.title || undefined}>{c.title || '未命名'}</span>
                    <span class="small muted nowrap">{relTime(c.updated_at)}</span>
                  </button>
                  <button type="button" class="btn btn-ghost small" disabled={busy} onClick={() => void dropConv(c)}>
                    删
                  </button>
                </li>
              ))}
            </ul>
          )}

          <h2>偏好记忆</h2>
          <p class="small muted">键值对，会作为上下文交给 Agent。键最多 64 字，值最多 2000 字。</p>
          {memories.length === 0 ? (
            <p class="small muted">还没有记忆。</p>
          ) : (
            <ul class="mems">
              {memories.map((m) => (
                <li class="mem">
                  <code>{m.key}</code>
                  <span class="grow ellipsis" title={m.value}>{m.value}</span>
                  <button type="button" class="btn btn-ghost small" disabled={busy} onClick={() => void dropMemory(m)}>
                    删
                  </button>
                </li>
              ))}
            </ul>
          )}
          <div class="row-actions">
            <input class="search" placeholder="键" maxLength={64} value={memKey}
                   onInput={(e) => setMemKey((e.target as HTMLInputElement).value)} />
            <input class="search" placeholder="值" maxLength={2000} value={memVal}
                   onInput={(e) => setMemVal((e.target as HTMLInputElement).value)} />
            <button type="button" class="btn small" disabled={busy} onClick={() => void saveMemory()}>保存记忆</button>
          </div>
        </div>

        <div class="col chat">
          <div class="col-head">
            <strong>{detail?.title || (curId ? '会话' : '新对话')}</strong>
            {detail && <span class="small muted">最后更新 {fmtTime(detail.updated_at)}</span>}
          </div>

          {detailErr && <ErrorBox message={detailErr} retry={() => curId && void open(curId)} />}
          {!detail && !detailErr && curId && <Loading what="会话消息" />}
          {!detail && !curId && (
            <Empty>
              <strong>发一句话开始</strong>
              <p class="small muted">例如："把最近 20 条里时长超过 10 分钟的发我"。带作品链接时，服务端会直接建一条待领取动作。</p>
            </Empty>
          )}

          {detail && (
            <>
              <div class="msgs">
                {detail.messages.length === 0 && <p class="small muted">这个会话还没有消息。</p>}
                {detail.messages.map((m) => (
                  <div class={`msg ${m.role}`}>
                    <div class="msg-body">{m.content}</div>
                    <div class="small muted">
                      {m.role === 'user' ? '你' : 'Agent'} · {fmtTime(m.created_at)}
                      {m.role === 'assistant' && m.meta?.engine ? ` · ${m.meta.engine === 'model' ? '模型' : '规则'}` : ''}
                      {m.meta?.model ? ` · ${m.meta.model}` : ''}
                      {m.meta?.reason ? ` · ${m.meta.reason}` : ''}
                    </div>
                  </div>
                ))}
                <div ref={bottom} />
              </div>

              {detail.actions.length > 0 && (
                <>
                  <h3>这一轮的动作</h3>
                  <ul class="plain tight">
                    {detail.actions.map((a) => (
                      <li class="item-row">
                        <span class={`tag ${a.status === 'done' ? 'ok' : a.status === 'failed' ? 'bad' : ''}`}>
                          {ACTION[a.status] ?? a.status}
                        </span>
                        <span class="grow ellipsis" title={a.input_url}>{a.input_url}</span>
                        <span class="small muted nowrap">{a.mode ? `由${a.mode === 'phone' ? '手机' : '服务器'}执行` : '未领取'}</span>
                      </li>
                    ))}
                  </ul>
                  <p class="small muted">
                    动作由手机端领取并回报结果；网页只读，不代领——代领会把状态推到"进行中"却没人执行。
                  </p>
                </>
              )}
            </>
          )}

          <div class="composer">
            <textarea
              rows={3}
              maxLength={4000}
              placeholder="说一句你要什么…（不要粘贴 Cookie 或令牌）"
              value={draft}
              onInput={(e) => setDraft((e.target as HTMLTextAreaElement).value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) void send();
              }}
            />
            <div class="row-actions">
              <button type="button" class="btn" disabled={busy || !draft.trim()} onClick={() => void send()}>
                {busy ? '等待回复…' : '发送'}
              </button>
              <span class="small muted">Ctrl/⌘ + Enter 也能发</span>
            </div>
          </div>
          {err && <div class="alert warn small">{err}</div>}
        </div>
      </div>
    </>
  );
}
