/** 服务端状态值 → 页面文案。集中放一处，避免每个页面各写一套同义词。 */

export const PLATFORM: Record<string, string> = { bilibili: 'B 站', douyin: '抖音', unknown: '未知' };
export const platformName = (p: string | null | undefined) => (p ? PLATFORM[p] ?? p : '—');

/** 存放方式：给用户看的说法，不是存储术语。 */
export const STORAGE: Record<string, string> = {
  metadata: '只记不下载',
  cache: '服务器缓存',
  archive: '服务器存储',
  phone: '只记不下载',   // 旧数据里的「手机下载」，行为本来就等于只记不下载
};

/** 三种存放方式各自会做什么（下拉里悬停某一行时弹出这段说明）。 */
export const STORAGE_DESC: Record<string, string> = {
  metadata: '只记录标题、时长和作品地址，不下载文件。',
  cache: '文件下载到服务器缓存，一天后失效，7 天后自动清理。',
  archive: '文件长期存储在服务器，但空间不足时同样会清理。',
};

/** 输出格式：视频四种、音频四种。展示用后缀名，说明放下拉提示里。
 *  source 是「平台给什么文件就存什么、不转码」，没有固定后缀，显示成「原始格式」不加点。 */
export const FORMAT: Record<string, string> = {
  source: '原始格式',
  mp4: '.mp4',
  mkv: '.mkv',
  webm: '.webm',
  m4a: '.m4a',
  mp3: '.mp3',
  flac: '.flac',
  wav: '.wav',
};

export const FORMAT_TIP: Record<string, string> = {
  source: '平台给什么就存什么，不转换，最快；不同作品的容器可能不一样。',
  mp4: '通用 MP4，手机、电脑、剪辑软件都认；源文件是别的容器时只换封装，很快。',
  mkv: 'MKV 封装，能装多条音轨与字幕；换封装为主，也比较快。',
  webm: 'WebM（VP9 + Opus），体积小，但需要整条重新编码，很慢。',
  m4a: '只留音轨，AAC 编码；源文件是 MP4 时几乎瞬间完成。',
  mp3: '只留音轨转 MP3，什么设备都能放；需要重新编码。',
  flac: '只留音轨转无损 FLAC，音质不损失，文件比较大。',
  wav: '只留音轨转 WAV，完全不压缩，文件最大。',
};

/** 媒体类型决定能选哪些格式：视频任务不出现音频格式，反之亦然。 */
export const FORMATS_OF: Record<string, string[]> = {
  video: ['source', 'mp4', 'mkv', 'webm'],
  audio: ['m4a', 'mp3', 'flac', 'wav'],
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
