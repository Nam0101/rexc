import React from 'react';
import {claudeBlocked, claudeBox, claudeInbox, cargoInbox, hosts} from './data';
import {AddServer} from './screens/AddServer';
import {Home} from './screens/Home';
import {Shade} from './screens/Shade';
import {Terminal} from './screens/Terminal';
import {C, MONO, UI} from './theme';
import {ClockAt} from './ui/clock';
import {Phone} from './ui/Device';

const Rounded: React.FC<{readonly children: React.ReactNode}> = ({children}) => (
	<div style={{width: '100%', height: '100%', borderRadius: 34, overflow: 'hidden'}}>{children}</div>
);

// Each still freezes the live dots mid-ping so the state reads at a glance.

export const HomeShot: React.FC = () => (
	<ClockAt seconds={0.45}>
		<Rounded>
			<Home inbox={[claudeInbox(), cargoInbox]} hosts={hosts()} />
		</Rounded>
	</ClockAt>
);

export const TerminalShot: React.FC = () => (
	<ClockAt seconds={0.45}>
		<Rounded>
			<Terminal lines={claudeBlocked} box={claudeBox} />
		</Rounded>
	</ClockAt>
);

export const AlertShot: React.FC = () => (
	<ClockAt seconds={0.2}>
		<Rounded>
			<Shade reply="yes, and add a test for it" typing />
		</Rounded>
	</ClockAt>
);

export const AddServerShot: React.FC = () => (
	<ClockAt seconds={0.2}>
		<Rounded>
			<AddServer />
		</Rounded>
	</ClockAt>
);

/** README banner and social preview: the pitch on the left, two phones on the right. */
export const Hero: React.FC = () => (
	<ClockAt seconds={0.45}>
		<div
			style={{
				width: '100%',
				height: '100%',
				background: 'radial-gradient(120% 140% at 85% 20%, #10213f 0%, #070a12 45%, #040405 100%)',
				position: 'relative',
				overflow: 'hidden',
				fontFamily: UI,
			}}
		>
			<div style={{position: 'absolute', left: 80, top: 150, width: 560}}>
				<div style={{display: 'flex', alignItems: 'center'}}>
					<div style={{fontSize: 112, fontWeight: 700, color: C.ink, letterSpacing: -2, lineHeight: 1}}>rexc</div>
					<div style={{width: 44, height: 92, background: C.accent, marginLeft: 8}} />
				</div>
				<div style={{fontSize: 38, fontWeight: 500, color: C.ink, marginTop: 30, lineHeight: 1.2}}>
					Your Rex terminals, on Android.
				</div>
				<div style={{fontSize: 23, color: C.muted, marginTop: 18, lineHeight: 1.45}}>
					Every session and coding agent at a glance. Answer them from the inbox or a notification.
				</div>
				<div style={{display: 'flex', gap: 10, marginTop: 34, fontFamily: MONO, fontSize: 16}}>
					{['Rust core', 'UniFFI', 'Jetpack Compose'].map((t) => (
						<div key={t} style={{padding: '8px 14px', borderRadius: 10, border: '1px solid #2a3550', color: '#b8c7e6', background: 'rgba(16,33,63,0.5)'}}>
							{t}
						</div>
					))}
				</div>
			</div>
			<Phone style={{position: 'absolute', left: 900, top: 70, scale: '0.62', transformOrigin: 'top left'}}>
				<Terminal lines={claudeBlocked} box={claudeBox} />
			</Phone>
			<Phone style={{position: 'absolute', left: 680, top: 120, scale: '0.62', transformOrigin: 'top left'}}>
				<Home inbox={[claudeInbox(), cargoInbox]} hosts={hosts()} />
			</Phone>
		</div>
	</ClockAt>
);
