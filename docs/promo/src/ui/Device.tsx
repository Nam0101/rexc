import React from 'react';
import {SCREEN, UI} from '../theme';
import {Battery, Signal, Wifi} from './Icons';

export const StatusBar: React.FC<{readonly ink?: string; readonly extra?: React.ReactNode; readonly date?: boolean}> = ({
	ink = '#FFFFFF',
	extra,
	date,
}) => (
	<div
		style={{
			position: 'absolute',
			left: 0,
			right: 0,
			top: 0,
			height: 48,
			display: 'flex',
			alignItems: 'center',
			padding: date ? '0 26px 0 16px' : '0 26px 0 28px',
			fontFamily: UI,
			fontSize: date ? 17 : 15.5,
			fontWeight: 500,
			color: ink,
			zIndex: 50,
		}}
	>
		<span>9:41</span>
		{date ? <span style={{marginLeft: 12, fontWeight: 500}}>Thu, Nov 5</span> : null}
		{extra}
		<div style={{flex: 1}} />
		<Wifi color={ink} size={17} />
		{date ? null : <Signal color={ink} size={14} style={{marginLeft: 6, opacity: 0}} />}
		<div style={{width: 6}} />
		<Battery color={ink} height={16} />
		{date ? <span style={{marginLeft: 6, fontSize: 16}}>100%</span> : null}
	</div>
);

const NavHandle: React.FC<{readonly color: string}> = ({color}) => (
	<div
		style={{
			position: 'absolute',
			left: (SCREEN.width - 108) / 2,
			bottom: 11,
			width: 108,
			height: 4,
			borderRadius: 2,
			background: color,
			zIndex: 50,
		}}
	/>
);

/** One phone screen at 412×915 dp: content, then the status bar and gesture handle on top. */
export const Screen: React.FC<{
	readonly bg: string;
	readonly children: React.ReactNode;
	readonly radius?: number;
	readonly statusInk?: string;
	readonly status?: React.ReactNode;
}> = ({bg, children, radius = 0, statusInk, status}) => (
	<div
		style={{
			position: 'relative',
			width: SCREEN.width,
			height: SCREEN.height,
			background: bg,
			overflow: 'hidden',
			borderRadius: radius,
			fontFamily: UI,
			flexShrink: 0,
		}}
	>
		{children}
		{status ?? <StatusBar ink={statusInk} />}
		<NavHandle color="rgba(255,255,255,0.92)" />
	</div>
);

/** A Pixel-like body around a [Screen]: bezel, frame highlight and the punch-hole camera. */
export const Phone: React.FC<{readonly children: React.ReactNode; readonly style?: React.CSSProperties}> = ({
	children,
	style,
}) => (
	<div
		style={{
			position: 'relative',
			padding: 11,
			borderRadius: 56,
			background: 'linear-gradient(145deg, #3a3a3e 0%, #1c1c1f 35%, #121214 70%, #2a2a2e 100%)',
			boxShadow: '0 0 0 1.5px #4a4a50 inset, 0 40px 120px rgba(0,0,0,0.65), 0 0 0 1px rgba(255,255,255,0.04)',
			...style,
		}}
	>
		<div style={{position: 'relative', borderRadius: 46, overflow: 'hidden', background: '#000'}}>
			{children}
			<div
				style={{
					position: 'absolute',
					top: 14,
					left: SCREEN.width / 2 - 7,
					width: 14,
					height: 14,
					borderRadius: 7,
					background: '#050505',
					boxShadow: '0 0 0 1.5px #151517',
					zIndex: 60,
				}}
			/>
		</div>
	</div>
);
