/** 服务端状态值 → 页面文案。集中放一处，避免每个页面各写一套同义词。 */

export const PLATFORM: Record<string, string> = { bilibili: 'B 站', douyin: '抖音', unknown: '未知' };
export const platformName = (p: string | null | undefined) => (p ? PLATFORM[p] ?? p : '—');

export const STORAGE: Record<string, string> = {
  phone: '手机下载',
  metadata: '仅元数据',
  archive: '服务端归档',
  cache: '服务端缓存',
};

/** 四种存放方式各自实际会做什么（批量任务只扫列表，文件另走下载入口）。 */
export const STORAGE_DESC: Record<string, string> = {
  phone: '只记作品清单和播放地址，文件由手机逐条下载。',
  metadata: '只记标题、时长这些信息，不下载文件。',
  archive: '记清单；之后在网页点单条下载时由服务器转发，不长期占用服务器空间。',
  cache: '记清单；网页按需下载的文件进临时缓存，到期自动清理。',
};

export const JOB: Record<string, string> = {
  queued: '排队中',
  running: '进行中',
  pending: '待开始',
  paused: '已暂停',
  done: '已完成',
  failed: '失败',
  cancelled: '已取消',
};

/** 用于筛选片与行内标记的色调：ok / warn / bad / '' */
export const TONE: Record<string, string> = {
  queued: '',
  pending: '',
  running: 'warn',
  paused: 'warn',
  done: 'ok',
  failed: 'bad',
  cancelled: '',
};

export const ITEM: Record<string, string> = {
  pending: '待处理',
  downloading: '下载中',
  done: '已入库',
  failed: '失败',
  skipped: '已跳过',
};

export const EXTRACT: Record<string, string> = {
  queued: '排队中',
  running: '解析/下载中',
  done: '已入库',
  failed: '失败',
};

export const ACTION: Record<string, string> = {
  pending: '待领取',
  running: '进行中',
  done: '已完成',
  failed: '失败',
};

export const SELECTION: Record<string, string> = {
  latest_n: '最近 N 条',
  date_range: '指定日期',
  all: '全部作品',
};

export function intervalText(min: number): string {
  if (min % 1440 === 0) return `每 ${min / 1440} 天`;
  if (min % 60 === 0) return `每 ${min / 60} 小时`;
  return `每 ${min} 分钟`;
}
