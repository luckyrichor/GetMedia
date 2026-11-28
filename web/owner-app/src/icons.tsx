/** 导航与概览「栏目」卡片共用的一套线性图标。
 *
 * 全部内联 SVG（24×24 视框、1.7 描边、`currentColor`），不引第三方图标库：
 * 管理台的 CSP 是 `default-src 'self'`，而且多一个依赖就多一份要维护的体积。
 * 图标永远和文字标签挨在一起，所以 `aria-hidden` 恒为真——读屏只念标签，不念图形。
 */
import type { ComponentChildren, JSX } from 'preact';

interface SvgProps {
  size?: number;
  children: ComponentChildren;
}

/** 所有图标共用的外壳：只换 `children`，线宽、圆角、颜色继承都锁在一处。 */
function Svg({ size = 18, children }: SvgProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  );
}

type IconProps = { size?: number };

/** 概览：四格仪表盘 */
function IconOverview(p: IconProps) {
  return (
    <Svg {...p}>
      <rect x="3.5" y="3.5" width="7" height="7" rx="1.6" />
      <rect x="13.5" y="3.5" width="7" height="7" rx="1.6" />
      <rect x="3.5" y="13.5" width="7" height="7" rx="1.6" />
      <rect x="13.5" y="13.5" width="7" height="7" rx="1.6" />
    </Svg>
  );
}

/** 权限管理：两个人 */
function IconAccounts(p: IconProps) {
  return (
    <Svg {...p}>
      <circle cx="9" cy="8" r="3.2" />
      <path d="M3.5 19.6c0-3 2.5-5.1 5.5-5.1s5.5 2.1 5.5 5.1" />
      <path d="M16.4 6.1a3 3 0 0 1 0 5.7" />
      <path d="M17.6 14.9c2 .6 3.2 2.4 3.2 4.7" />
    </Svg>
  );
}

/** 邀请码：票券 */
function IconInvites(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M3.5 9.4V7.2c0-.9.7-1.6 1.6-1.6h13.8c.9 0 1.6.7 1.6 1.6v2.2a2.6 2.6 0 0 0 0 5.2v2.2c0 .9-.7 1.6-1.6 1.6H5.1c-.9 0-1.6-.7-1.6-1.6v-2.2a2.6 2.6 0 0 0 0-5.2Z" />
      <path d="M12 8.4v7.2" strokeDasharray="2 2.4" />
    </Svg>
  );
}

/** 反馈收件箱：收件托盘 */
function IconFeedback(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M4 13.6h3.7l1.5 2.5h5.6l1.5-2.5H20" />
      <path d="M4 13.6 6.2 5.9c.2-.7.9-1.1 1.6-1.1h8.4c.7 0 1.4.4 1.6 1.1L20 13.6v3.9c0 .9-.7 1.6-1.6 1.6H5.6c-.9 0-1.6-.7-1.6-1.6Z" />
    </Svg>
  );
}

/** 媒体库：胶片 */
function IconLibrary(p: IconProps) {
  return (
    <Svg {...p}>
      <rect x="3.5" y="4.5" width="17" height="15" rx="2.2" />
      <path d="M7.6 4.5v15M16.4 4.5v15" />
      <path d="M7.6 9.2h8.8M7.6 14.8h8.8" strokeDasharray="1 2.6" />
    </Svg>
  );
}

/** 单个提取：向下落进托盘 */
function IconExtract(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M12 3.8v9.4" />
      <path d="m8.2 9.6 3.8 3.8 3.8-3.8" />
      <path d="M4.6 16.2v2.2c0 .9.7 1.6 1.6 1.6h11.6c.9 0 1.6-.7 1.6-1.6v-2.2" />
    </Svg>
  );
}

/** 批量提取：叠层 */
function IconCreators(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="m12 3.6 8.2 4.3-8.2 4.3-8.2-4.3z" />
      <path d="m3.8 12.3 8.2 4.3 8.2-4.3" />
      <path d="m3.8 16.4 8.2 4.3 8.2-4.3" />
    </Svg>
  );
}

/** 定时任务：时钟 */
function IconSchedules(p: IconProps) {
  return (
    <Svg {...p}>
      <circle cx="12" cy="12" r="8.2" />
      <path d="M12 7.2V12l3.3 2" />
    </Svg>
  );
}

/** Agent：对话气泡 */
function IconAgent(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M20.5 12.2c0 3.8-3.8 6.9-8.5 6.9-1 0-2-.1-2.9-.4L4 20.4l1.4-3.7a6.4 6.4 0 0 1-1.9-4.5c0-3.8 3.8-6.9 8.5-6.9s8.5 3.1 8.5 6.9Z" />
      <path d="M8.6 12.1h.01M12 12.1h.01M15.4 12.1h.01" />
    </Svg>
  );
}

/** 平台授权：钥匙 */
function IconPlatforms(p: IconProps) {
  return (
    <Svg {...p}>
      <circle cx="8.2" cy="15.8" r="3.6" />
      <path d="m10.8 13.2 8.4-8.4" />
      <path d="m16.4 7.6 2.4 2.4" />
    </Svg>
  );
}

/** 库维护：数据库 */
function IconMaintenance(p: IconProps) {
  return (
    <Svg {...p}>
      <ellipse cx="12" cy="6" rx="7.4" ry="2.9" />
      <path d="M4.6 6v6c0 1.6 3.3 2.9 7.4 2.9s7.4-1.3 7.4-2.9V6" />
      <path d="M4.6 12v6c0 1.6 3.3 2.9 7.4 2.9s7.4-1.3 7.4-2.9v-6" />
    </Svg>
  );
}

/** AI 视频：播放键 + 星芒 */
function IconAi(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M20.5 10v7.6c0 1-.8 1.9-1.9 1.9H5.4c-1 0-1.9-.8-1.9-1.9V7.4c0-1 .8-1.9 1.9-1.9h7.8" />
      <path d="M10 9.5 14.4 12 10 14.5z" />
      <path d="m18 2.6.6 1.6 1.6.6-1.6.6-.6 1.6-.6-1.6-1.6-.6 1.6-.6z" />
    </Svg>
  );
}

/** 路径 → 图标。没登记的路径（将来新加的栏目）退回「概览」那格，不空着。 */
const ICONS: Record<string, (p: IconProps) => JSX.Element> = {
  '/': IconOverview,
  '/accounts': IconAccounts,
  '/invites': IconInvites,
  '/feedback': IconFeedback,
  '/library': IconLibrary,
  '/extract': IconExtract,
  '/creators': IconCreators,
  '/schedules': IconSchedules,
  '/agent': IconAgent,
  '/platforms': IconPlatforms,
  '/maintenance': IconMaintenance,
  '/ai': IconAi,
};

/** 按栏目路径取图标：导航项与「栏目」卡片用同一个，位置不同但形状对得上。 */
export function SectionIcon({ path, size }: { path: string; size?: number }) {
  const Icon = ICONS[path] ?? IconOverview;
  return <Icon size={size} />;
}
