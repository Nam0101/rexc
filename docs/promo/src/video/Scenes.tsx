import React from 'react';
import {AbsoluteFill} from 'remotion';
import {cargoInbox, claudeDone, claudeInbox, claudeWorking, gitLog, hosts} from '../data';
import {AddServer} from '../screens/AddServer';
import {Home} from '../screens/Home';
import {Shade} from '../screens/Shade';
import {Terminal} from '../screens/Terminal';
import {C, MONO} from '../theme';
import {Backdrop, Caption, Stage, Tap, iv, typed, useSeconds} from './kit';

const Wordmark: React.FC<{readonly size: number; readonly letters?: number; readonly blink?: boolean}> = ({size, letters = 4, blink = true}) => {
	const t = useSeconds();
	return (
		<div style={{display: 'flex', alignItems: 'center'}}>
			<div style={{fontSize: size, fontWeight: 700, letterSpacing: -size * 0.02, lineHeight: 1}}>{'rexc'.slice(0, letters)}</div>
			<div
				style={{
					width: size * 0.36,
					height: size * 0.8,
					marginLeft: size * 0.07,
					background: C.accent,
					opacity: !blink || t % 1.1 < 0.62 ? 1 : 0,
				}}
			/>
		</div>
	);
};

export const Intro: React.FC = () => {
	const t = useSeconds();
	const letters = Math.min(4, Math.max(0, Math.floor((t - 0.25) / 0.11)));
	return (
		<Backdrop>
			<AbsoluteFill style={{alignItems: 'center', justifyContent: 'center'}}>
				<Wordmark size={210} letters={letters} blink={t > 0.8} />
				<div style={{fontSize: 52, fontWeight: 500, marginTop: 56, opacity: iv(t, [0.9, 1.4], [0, 1]), translate: `0px ${iv(t, [0.9, 1.5], [24, 0])}px`}}>
					Your Rex terminals, on Android.
				</div>
				<div style={{fontFamily: MONO, fontSize: 28, color: '#7fa7ff', marginTop: 26, opacity: iv(t, [1.4, 1.9], [0, 1])}}>
					sessions · agents · alerts
				</div>
			</AbsoluteFill>
		</Backdrop>
	);
};

export const Glance: React.FC = () => {
	const t = useSeconds();
	return (
		<Backdrop>
			<Caption kicker="At a glance" title={"Every session,\nevery agent."} body="Claude, Codex, Gemini and Copilot show up on their rows, with a live dot for what each one is doing." />
			<Stage enter>
				<Home inbox={[claudeInbox(), cargoInbox]} hosts={hosts()} reveal={t - 0.5} scroll={iv(t, [3.2, 4.4], [0, 210])} />
			</Stage>
		</Backdrop>
	);
};

const REPLY = 'yes, and add a test for it';

export const Alert: React.FC = () => {
	const t = useSeconds();
	const replying = t > 2.0;
	return (
		<Backdrop>
			<Caption kicker="Alerts" title={"Know the moment\nan agent needs you."} body="A blocked agent pings your phone. Approve it, or type a reply, right from the notification." />
			<Stage zoom={iv(t, [0.9, 1.8], [1, 1.28])}>
				<Shade
					arrive={iv(t, [0.4, 1.0], [0, 1]) * iv(t, [4.9, 5.4], [1, 0])}
					reply={replying ? typed(REPLY, t, 2.4, 14) : undefined}
					typing={replying && t < 4.6}
				/>
				<Tap x={213} y={320} at={1.8} />
				<Tap x={366} y={330} at={4.6} />
			</Stage>
		</Backdrop>
	);
};

export const Finish: React.FC = () => {
	const t = useSeconds();
	const stage = t < 3.2 ? 'working' : 'done';
	return (
		<Backdrop>
			<Caption kicker="Inbox" title="Then watch it finish." body="The answer goes straight to the pane. The row turns blue while it works and green when it's done." />
			<Stage zoom={iv(t, [0.1, 1.0], [1, 1.22])}>
				<Home inbox={[claudeInbox({exit: iv(t, [0.5, 1.2], [0, 1])}), cargoInbox]} hosts={hosts(stage)} />
			</Stage>
		</Backdrop>
	);
};

export const Term: React.FC = () => {
	const t = useSeconds();
	const done = t > 3.0;
	const git = t > 4.6;
	const lines = git ? gitLog : done ? claudeDone : claudeWorking(Math.min(4, 1 + Math.floor(t / 0.65)), 9 + Math.floor(t), '✢✳✶✻✽'[Math.floor(t * 6) % 5]);
	return (
		<Backdrop>
			<Caption kicker="Terminal" title={"A real terminal,\nmade for thumbs."} body="libghostty-vt emulation in Rust, tabs and panes from the desktop, and a key dock for esc, ctrl and arrows." />
			<Stage>
				<Terminal lines={lines} tab={git ? 'git' : 'claude'} claudeState={done ? 'done' : 'working'} pressed={t > 1.6 && t < 2.0 ? 'ctrl' : undefined} />
				<Tap x={152} y={861} at={1.6} />
				<Tap x={266} y={120} at={4.45} />
			</Stage>
		</Backdrop>
	);
};

const CRATES = [
	['rexc-proto', 'Rex protocol and snapshots'],
	['rexc-client', 'tokio + rustls, MagicDNS fallback'],
	['rexc-term', 'libghostty-vt terminal emulation'],
	['rexc-core', 'engine: reconnects, ownership, alerts'],
] as const;

const Box: React.FC<{readonly at: number; readonly style: React.CSSProperties; readonly children: React.ReactNode}> = ({at, style, children}) => {
	const t = useSeconds();
	return (
		<div
			style={{
				position: 'absolute',
				borderRadius: 18,
				border: '1.5px solid #2a3a5c',
				background: 'rgba(14,26,50,0.72)',
				opacity: iv(t, [at, at + 0.4], [0, 1]),
				translate: `0px ${iv(t, [at, at + 0.6], [22, 0])}px`,
				...style,
			}}
		>
			{children}
		</div>
	);
};

const Arrow: React.FC<{readonly at: number; readonly x: number; readonly y: number; readonly w: number}> = ({at, x, y, w}) => {
	const t = useSeconds();
	const p = iv(t, [at, at + 0.45], [0, 1]);
	return (
		<div style={{position: 'absolute', left: x, top: y}}>
			<div style={{width: w * p, height: 3, background: C.accent, borderRadius: 2}} />
			<div
				style={{
					position: 'absolute',
					left: w * p - 12,
					top: -7.5,
					width: 0,
					height: 0,
					borderTop: '9px solid transparent',
					borderBottom: '9px solid transparent',
					borderLeft: `14px solid ${C.accent}`,
					opacity: p > 0.05 ? 1 : 0,
				}}
			/>
		</div>
	);
};

export const Core: React.FC = () => {
	const t = useSeconds();
	return (
		<Backdrop>
			<div style={{position: 'absolute', left: 150, top: 96, opacity: iv(t, [0.1, 0.6], [0, 1]), translate: `0px ${iv(t, [0.1, 0.8], [24, 0])}px`}}>
				<div style={{fontSize: 26, fontWeight: 500, letterSpacing: 3, textTransform: 'uppercase', color: '#7fa7ff'}}>Under the hood</div>
				<div style={{fontSize: 84, fontWeight: 700, letterSpacing: -1.5, marginTop: 14}}>Rust does the work. Compose draws it.</div>
			</div>
			{CRATES.map(([name, what], i) => (
				<Box key={name} at={0.5 + i * 0.18} style={{left: 150, top: 360 + i * 140, width: 640, height: 112, padding: '0 30px', display: 'flex', flexDirection: 'column', justifyContent: 'center'}}>
					<div style={{fontFamily: MONO, fontSize: 34, fontWeight: 700, color: '#d6e2ff'}}>{name}</div>
					<div style={{fontSize: 25, color: C.muted, marginTop: 6}}>{what}</div>
				</Box>
			))}
			<Arrow at={1.5} x={810} y={636} w={150} />
			<Box at={1.8} style={{left: 980, top: 556, width: 260, height: 166, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center'}}>
				<div style={{fontFamily: MONO, fontSize: 38, fontWeight: 700, color: '#d6e2ff'}}>UniFFI</div>
				<div style={{fontSize: 24, color: C.muted, marginTop: 8}}>Kotlin bindings</div>
			</Box>
			<Arrow at={2.2} x={1260} y={636} w={150} />
			<Box at={2.5} style={{left: 1430, top: 440, width: 360, height: 400, padding: '34px 34px', border: `1.5px solid ${C.accent}`}}>
				<div style={{fontSize: 38, fontWeight: 700}}>Android app</div>
				{['Jetpack Compose', 'Hilt · Navigation 3', 'Canvas grid renderer', 'Foreground alerts', 'Inline replies'].map((s) => (
					<div key={s} style={{fontSize: 27, color: C.muted, marginTop: 16, display: 'flex', alignItems: 'center', gap: 14}}>
						<div style={{width: 8, height: 8, borderRadius: 4, background: C.accent}} />
						{s}
					</div>
				))}
			</Box>
		</Backdrop>
	);
};

/** A decorative QR: finder squares and a seeded module pattern. */
const Qr: React.FC<{readonly size: number}> = ({size}) => {
	const n = 25;
	const m = size / n;
	const cells: React.ReactNode[] = [];
	const finder = (x: number, y: number) => x < 7 && y < 7;
	for (let y = 0; y < n; y++) {
		for (let x = 0; x < n; x++) {
			const inFinder = finder(x, y) || finder(n - 1 - x, y) || finder(x, n - 1 - y);
			let on: boolean;
			if (inFinder) {
				const fx = x < 7 ? x : n - 1 - x;
				const fy = y < 7 ? y : n - 1 - y;
				const r = Math.max(Math.abs(fx - 3), Math.abs(fy - 3));
				on = r !== 2;
			} else {
				on = ((x * 73856093) ^ (y * 19349663) ^ 0x5bd1e995) % 7 < 3;
				if ((x < 8 && y < 8) || (x > n - 9 && y < 8) || (x < 8 && y > n - 9)) on = false;
			}
			if (on) cells.push(<rect key={`${x}-${y}`} x={x * m} y={y * m} width={m + 0.3} height={m + 0.3} fill="#0A0A0B" />);
		}
	}
	return (
		<svg width={size} height={size} style={{background: '#fff', borderRadius: 14, padding: 14, boxSizing: 'content-box', display: 'block'}}>
			{cells}
		</svg>
	);
};

export const Pair: React.FC = () => {
	const t = useSeconds();
	const scanned = t > 2.3;
	return (
		<Backdrop>
			<div style={{position: 'absolute', left: 150, top: 0, bottom: 0, width: 860, display: 'flex', flexDirection: 'column', justifyContent: 'center'}}>
				<div style={{fontSize: 26, fontWeight: 500, letterSpacing: 3, textTransform: 'uppercase', color: '#7fa7ff', opacity: iv(t, [0.05, 0.5], [0, 1])}}>Setup</div>
				<div style={{fontSize: 92, fontWeight: 700, lineHeight: 1.04, letterSpacing: -1.5, marginTop: 22, opacity: iv(t, [0.15, 0.6], [0, 1])}}>Add a server<br />with one scan.</div>
				<div style={{display: 'flex', alignItems: 'center', gap: 34, marginTop: 44, opacity: iv(t, [0.6, 1.1], [0, 1]), translate: `0px ${iv(t, [0.6, 1.3], [24, 0])}px`}}>
					<Qr size={170} />
					<div style={{fontFamily: MONO, fontSize: 27, lineHeight: 1.6, background: '#0b0f18', border: '1.5px solid #222c40', borderRadius: 16, padding: '22px 28px'}}>
						<span style={{color: '#7a8399'}}>$ </span>
						<span style={{color: '#d6e2ff'}}>rexc qr</span>
						<br />
						<span style={{color: C.ok}}>  my-mac.tail1234.ts.net</span>
						<br />
						<span style={{color: '#e6be5a'}}>  &quot;my mac&quot;</span>
					</div>
				</div>
			</div>
			<Stage>
				<AddServer sheet={iv(t, [0.5, 1.2], [0, 1])} address={scanned ? 'my-mac.tail1234.ts.net' : ''} name={scanned ? 'my mac' : ''} />
				<Tap x={364} y={582} at={1.9} />
			</Stage>
		</Backdrop>
	);
};

export const End: React.FC = () => {
	const t = useSeconds();
	return (
		<Backdrop>
			<AbsoluteFill style={{alignItems: 'center', justifyContent: 'center'}}>
				<div style={{scale: String(iv(t, [0, 0.8], [0.92, 1])), opacity: iv(t, [0, 0.5], [0, 1])}}>
					<Wordmark size={180} />
				</div>
				<div style={{fontFamily: MONO, fontSize: 44, color: '#d6e2ff', marginTop: 56, opacity: iv(t, [0.5, 1.0], [0, 1])}}>github.com/Nam0101/rexc</div>
				<div style={{fontSize: 30, color: C.muted, marginTop: 22, opacity: iv(t, [0.8, 1.3], [0, 1])}}>Rust core · UniFFI · Jetpack Compose</div>
			</AbsoluteFill>
		</Backdrop>
	);
};
