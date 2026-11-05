import React from 'react';
import {C, MONO, UI} from '../theme';
import {useClock} from '../ui/clock';
import {Screen} from '../ui/Device';
import {QrScan} from '../ui/Icons';
import {AppBar} from './Home';

const Field: React.FC<{readonly top: number; readonly label: string; readonly value: string; readonly focused?: boolean; readonly trailing?: React.ReactNode; readonly caret?: boolean}> = ({
	top,
	label,
	value,
	focused,
	trailing,
	caret,
}) => (
	<div
		style={{
			position: 'absolute',
			left: 20,
			right: 21,
			top,
			height: 56,
			borderRadius: 5,
			border: focused ? `2px solid ${C.accent}` : `1px solid ${C.line}`,
			display: 'flex',
			alignItems: 'center',
			padding: focused ? '0 14px 0 15px' : '0 15px 0 16px',
			fontSize: 16,
			color: C.ink,
		}}
	>
		<div
			style={{
				position: 'absolute',
				left: 12,
				top: -9,
				padding: '0 4px',
				background: C.surface,
				fontSize: 12,
				lineHeight: '16px',
				color: focused ? C.accent : C.muted,
			}}
		>
			{label}
		</div>
		<div style={{flex: 1}}>
			{value}
			{caret ? <span style={{display: 'inline-block', width: 2, height: 19, background: C.accent, verticalAlign: -4, marginLeft: 1}} /> : null}
		</div>
		{trailing}
	</div>
);

/** The empty home behind a scrim, and the sheet that adds a server (typed, or scanned from `rexc qr`). */
export const AddServer: React.FC<{readonly address?: string; readonly name?: string; readonly sheet?: number}> = ({
	address = 'my-mac.tail1234.ts.net',
	name = 'studio',
	sheet = 1,
}) => {
	const t = useClock();
	return (
		<Screen bg={C.bg}>
			<div style={{position: 'absolute', left: 0, right: 0, top: 50, fontFamily: UI}}>
				<AppBar />
				<div
					style={{
						margin: '6px 16px 0',
						borderRadius: 16,
						background: C.surface,
						height: 268,
						display: 'flex',
						flexDirection: 'column',
						alignItems: 'center',
						paddingTop: 20,
					}}
				>
					<div style={{width: 56, height: 56, borderRadius: 14, background: C.key, display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: MONO, fontWeight: 700, fontSize: 22, color: '#30D158'}}>
						{'>_'}
					</div>
					<div style={{fontSize: 20, fontWeight: 600, color: C.ink, marginTop: 14}}>No servers yet</div>
					<div style={{fontSize: 14, lineHeight: '20px', color: C.muted, textAlign: 'center', margin: '8px 22px 0'}}>
						Add your Rex server by its tailnet name (name.tailxxxx.ts.net), a 100.x address with a port, or a full https:// URL.
					</div>
					<div style={{marginTop: 18, height: 40, padding: '0 24px', borderRadius: 20, background: C.accent, color: '#fff', fontSize: 15, fontWeight: 500, display: 'flex', alignItems: 'center'}}>
						Add server
					</div>
				</div>
			</div>
			<div style={{position: 'absolute', inset: 0, background: `rgba(0,0,0,${0.6 * sheet})`}} />
			<div
				style={{
					position: 'absolute',
					left: 0,
					right: 0,
					top: 457 + (1 - sheet) * 460,
					bottom: -460 * (1 - sheet),
					background: C.surface,
					borderRadius: '28px 28px 0 0',
					fontFamily: UI,
				}}
			>
				<div style={{position: 'absolute', left: 190, top: 22, width: 32, height: 4, borderRadius: 2, background: C.muted}} />
				<div style={{position: 'absolute', left: 20, top: 46, fontSize: 22, fontWeight: 500, color: C.ink}}>Add server</div>
				<Field top={97} label="Address" value={address} trailing={<QrScan color={C.accent} size={24} />} />
				<div style={{position: 'absolute', left: 36, right: 30, top: 160, fontSize: 12, lineHeight: '16px', color: C.muted}}>
					Tailnet name, host:port, or an https:// URL. Or scan the code from `rexc qr`.
				</div>
				<Field top={205} label="Name (optional)" value={name} focused caret={t % 1 < 0.55} />
				<div style={{position: 'absolute', left: 20, top: 272, fontSize: 16, color: C.ink}}>Alerts in the background</div>
				<div style={{position: 'absolute', left: 20, width: 300, top: 298, fontSize: 14, lineHeight: '24px', color: C.muted}}>
					Keep this server connected while the app is away, behind a quiet notification, so a program that needs you can reach you.
				</div>
				<div style={{position: 'absolute', left: 339, top: 306, width: 52, height: 32, borderRadius: 16, background: C.accent}}>
					<div style={{position: 'absolute', right: 4, top: 4, width: 24, height: 24, borderRadius: 12, background: '#fff'}} />
				</div>
				<div style={{position: 'absolute', left: 240, top: 389, width: 60, height: 40, display: 'flex', alignItems: 'center', justifyContent: 'center', color: C.accent, fontSize: 15, fontWeight: 500}}>
					Cancel
				</div>
				<div style={{position: 'absolute', left: 313, top: 389, width: 78, height: 40, borderRadius: 20, background: C.accent, color: '#fff', fontSize: 15, fontWeight: 500, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
					Save
				</div>
			</div>
		</Screen>
	);
};
