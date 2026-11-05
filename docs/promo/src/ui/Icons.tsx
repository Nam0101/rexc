import React from 'react';

type P = {readonly size?: number; readonly color: string; readonly style?: React.CSSProperties};

const Svg: React.FC<P & {readonly children: React.ReactNode}> = ({size = 24, color, style, children}) => (
	<svg width={size} height={size} viewBox="0 0 24 24" fill={color} style={{display: 'block', flexShrink: 0, ...style}}>
		{children}
	</svg>
);

// Material Symbols paths (Apache 2.0), the same icons the app draws.
export const Add: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" />
	</Svg>
);
export const ExpandMore: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z" />
	</Svg>
);
export const ChevronRight: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z" />
	</Svg>
);
export const MoreVert: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z" />
	</Svg>
);
export const Send: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
	</Svg>
);
export const ArrowBack: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
	</Svg>
);
export const Close: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
	</Svg>
);
export const OpenWith: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M10 9h4V6h3l-5-5-5 5h3v3zm-1 1H6V7l-5 5 5 5v-3h3v-4zm14 2l-5-5v3h-3v4h3v3l5-5zm-9 3h-4v3H7l5 5 5-5h-3v-3z" />
	</Svg>
);
export const Settings: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z" />
	</Svg>
);
export const QrScan: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M3 3h7v7H3V3zm2 2v3h3V5H5zm9-2h7v7h-7V3zm2 2v3h3V5h-3zM3 14h7v7H3v-7zm2 2v3h3v-3H5zm9-2h2v2h-2v-2zm2 2h2v2h-2v-2zm2-2h3v2h-3v-2zm-4 4h2v3h-2v-3zm4 0h3v3h-3v-3zm-2 2h2v1h-2v-1z" />
	</Svg>
);
export const Wifi: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M12 21L23.6 7.4C23.15 7.06 18.67 3.4 12 3.4S.85 7.06.4 7.4L12 21z" />
	</Svg>
);
export const Bluetooth: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M17.71 7.71L12 2h-1v7.59L6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 11 14.41V22h1l5.71-5.71-4.3-4.29 4.3-4.29zM13 5.83l1.88 1.88L13 9.59V5.83zm1.88 10.46L13 18.17v-3.76l1.88 1.88z" />
	</Svg>
);
export const Flashlight: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M6 2h12v5l-3 4v11H9V11L6 7V2zm2 2v2h8V4H8zm4 9a1 1 0 100 2 1 1 0 000-2z" />
	</Svg>
);
export const Dnd: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zM7 11h10v2H7z" />
	</Svg>
);
export const Signal: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M2 22h20V2L2 22z" />
	</Svg>
);
export const Bell: React.FC<P> = (p) => (
	<Svg {...p}>
		<path d="M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z" />
	</Svg>
);

export const Battery: React.FC<{readonly color: string; readonly height?: number}> = ({color, height = 17}) => (
	<svg width={height * 0.55} height={height} viewBox="0 0 11 20" style={{display: 'block'}}>
		<rect x="3.5" y="0" width="4" height="2" rx="0.6" fill={color} />
		<rect x="0" y="1.6" width="11" height="18.4" rx="2" fill={color} />
	</svg>
);
