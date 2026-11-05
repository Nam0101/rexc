import React from 'react';
import {Easing} from 'remotion';
import {C, MONO} from '../theme';
import {useClock} from './clock';

export type Agent = 'claude' | 'codex' | 'gemini' | 'copilot' | 'cargo' | 'shell';
export type State = 'idle' | 'working' | 'blocked' | 'done' | 'error';

const brand: Record<Agent, string> = {
	claude: '#D97757',
	codex: '#10A37F',
	gemini: '#4E8DF5',
	copilot: '#8957E5',
	cargo: C.accent,
	shell: C.muted,
};
const mark: Partial<Record<Agent, string>> = {codex: 'cx', copilot: 'cp', cargo: 'C', shell: '>_'};

export const stateColor = (s: State): string =>
	({blocked: C.amber, error: C.danger, working: C.accent, done: C.ok, idle: C.muted})[s];

const hexA = (hex: string, a: number) => {
	const n = parseInt(hex.slice(1), 16);
	return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
};

/** The agent's mark: Claude's eight spokes, Gemini's spark, or a short monospace mark. */
export const Glyph: React.FC<{readonly agent: Agent; readonly size: number}> = ({agent, size}) => {
	const tint = brand[agent];
	const r = size / 2;
	if (agent === 'claude') {
		return (
			<svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
				{Array.from({length: 8}, (_, i) => {
					const a = (i * Math.PI) / 4;
					return (
						<line
							key={i}
							x1={r + Math.cos(a) * r * 0.22}
							y1={r + Math.sin(a) * r * 0.22}
							x2={r + Math.cos(a) * r * 0.87}
							y2={r + Math.sin(a) * r * 0.87}
							stroke={tint}
							strokeWidth={r * 0.26}
							strokeLinecap="round"
						/>
					);
				})}
			</svg>
		);
	}
	if (agent === 'gemini') {
		return (
			<svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
				<path
					d={`M${r} 0 Q${r} ${r} ${size} ${r} Q${r} ${r} ${r} ${size} Q${r} ${r} 0 ${r} Q${r} ${r} ${r} 0Z`}
					fill={tint}
				/>
			</svg>
		);
	}
	return (
		<span style={{fontFamily: MONO, fontWeight: 700, fontSize: size * 0.75, color: tint, lineHeight: 1}}>
			{mark[agent]}
		</span>
	);
};

/** A state dot that lives: working breathes, blocked pings outward, the rest hold still. */
export const LiveDot: React.FC<{readonly state: State; readonly size: number; readonly ring?: string}> = ({
	state,
	size,
	ring,
}) => {
	const t = useClock();
	const color = stateColor(state);
	const r = size / 2;
	const inner = ring ? r * 0.72 : r;
	let scale = 1;
	let alpha = 1;
	let ping: React.ReactNode = null;
	if (state === 'working') {
		const phase = (t % 2.2) / 2.2;
		const breath = Easing.inOut(Easing.cubic)(phase < 0.5 ? phase * 2 : 2 - phase * 2);
		scale = 0.85 + 0.15 * breath;
		alpha = 0.55 + 0.45 * breath;
	}
	if (state === 'blocked') {
		const p = Easing.out(Easing.cubic)((t % 1.6) / 1.6);
		ping = (
			<circle
				cx={r * 3}
				cy={r * 3}
				r={r * (1 + 1.6 * p)}
				fill="none"
				stroke={hexA(color, (1 - p) * 0.7)}
				strokeWidth={r * 0.35}
			/>
		);
	}
	return (
		<svg width={size * 3} height={size * 3} style={{position: 'absolute', left: -size, top: -size, overflow: 'visible'}}>
			{ping}
			{ring ? <circle cx={r * 3} cy={r * 3} r={r} fill={ring} /> : null}
			<circle cx={r * 3} cy={r * 3} r={inner * scale} fill={hexA(color, alpha)} />
		</svg>
	);
};

/** Who runs in a pane: a tinted tile with the agent's mark and its state on the corner. */
export const Badge: React.FC<{readonly agent: Agent; readonly state?: State; readonly size?: number; readonly ring?: string}> = ({
	agent,
	state,
	size = 28,
	ring = C.surface,
}) => (
	<div style={{position: 'relative', width: size, height: size, flexShrink: 0}}>
		<div
			style={{
				width: size,
				height: size,
				borderRadius: size * 0.32,
				background: hexA(brand[agent], 0.16),
				display: 'flex',
				alignItems: 'center',
				justifyContent: 'center',
			}}
		>
			<Glyph agent={agent} size={size * 0.56} />
		</div>
		{state && state !== 'idle' ? (
			<div style={{position: 'absolute', width: size * 0.4, height: size * 0.4, right: -size * 0.12, bottom: -size * 0.12}}>
				<LiveDot state={state} size={size * 0.4} ring={ring} />
			</div>
		) : null}
	</div>
);
