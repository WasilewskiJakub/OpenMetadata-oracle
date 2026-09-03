import type { SVGProps } from 'react';

export type IconName =
  | 'arrow-left'
  | 'arrow-right'
  | 'check'
  | 'chevron-down'
  | 'database'
  | 'eye'
  | 'eye-off'
  | 'flow'
  | 'folder'
  | 'layers'
  | 'lock'
  | 'logout'
  | 'minus'
  | 'plus'
  | 'search'
  | 'server'
  | 'shield'
  | 'warning';

interface IconProps extends SVGProps<SVGSVGElement> {
  name: IconName;
  size?: number;
}

const paths: Record<IconName, React.ReactNode> = {
  'arrow-left': <><path d="m15 18-6-6 6-6" /><path d="M21 12H9" /></>,
  'arrow-right': <><path d="m9 18 6-6-6-6" /><path d="M3 12h12" /></>,
  check: <path d="m5 12 4 4L19 6" />,
  'chevron-down': <path d="m6 9 6 6 6-6" />,
  database: <><ellipse cx="12" cy="5" rx="8" ry="3" /><path d="M4 5v7c0 1.7 3.6 3 8 3s8-1.3 8-3V5" /><path d="M4 12v7c0 1.7 3.6 3 8 3s8-1.3 8-3v-7" /></>,
  eye: <><path d="M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6Z" /><circle cx="12" cy="12" r="2.5" /></>,
  'eye-off': <><path d="m3 3 18 18" /><path d="M10.6 6.2c.5-.1.9-.2 1.4-.2 6 0 9.5 6 9.5 6a16 16 0 0 1-2.2 2.9M6.1 6.1C3.8 8 2.5 12 2.5 12s3.5 6 9.5 6c1.3 0 2.5-.3 3.5-.7" /></>,
  flow: <><rect x="3" y="4" width="6" height="5" rx="1" /><rect x="15" y="15" width="6" height="5" rx="1" /><path d="M9 6.5h3a6 6 0 0 1 6 6V15" /><path d="m15 12 3 3 3-3" /></>,
  folder: <path d="M3 6.5A1.5 1.5 0 0 1 4.5 5H9l2 2h8.5A1.5 1.5 0 0 1 21 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5Z" />,
  layers: <><path d="m12 3 9 5-9 5-9-5 9-5Z" /><path d="m3 12 9 5 9-5" /><path d="m3 16 9 5 9-5" /></>,
  lock: <><rect x="4" y="10" width="16" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3" /></>,
  logout: <><path d="M10 5H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h5" /><path d="m15 8 4 4-4 4" /><path d="M19 12H9" /></>,
  minus: <path d="M5 12h14" />,
  plus: <><path d="M12 5v14" /><path d="M5 12h14" /></>,
  search: <><circle cx="11" cy="11" r="7" /><path d="m20 20-4-4" /></>,
  server: <><rect x="3" y="3" width="18" height="7" rx="2" /><rect x="3" y="14" width="18" height="7" rx="2" /><path d="M7 6.5h.01M7 17.5h.01" /></>,
  shield: <path d="M12 22s8-3 8-10V5l-8-3-8 3v7c0 7 8 10 8 10Z" />,
  warning: <><path d="M10.3 3.5 2.4 18a2 2 0 0 0 1.8 3h15.6a2 2 0 0 0 1.8-3L13.7 3.5a2 2 0 0 0-3.4 0Z" /><path d="M12 9v4M12 17h.01" /></>,
};

export function Icon({ name, size = 18, ...props }: IconProps) {
  return (
    <svg
      aria-hidden="true"
      fill="none"
      height={size}
      viewBox="0 0 24 24"
      width={size}
      {...props}
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth="1.7">
      {paths[name]}
    </svg>
  );
}
