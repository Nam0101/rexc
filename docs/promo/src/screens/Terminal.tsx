import React from 'react';
import {C, MONO, UI} from '../theme';
import {Glyph, LiveDot, State} from '../ui/Badge';
import {Screen} from '../ui/Device';
import {Add, ArrowBack, Close, MoreVert, OpenWith} from '../ui/Icons';
import type {Line} from '../data';

// The grid as the app draws it at the default 12.5 sp JetBrains Mono.
const X0 = 7;
const ROW0 = 147;
const CW = 7.5;
const LH = 17.55;

export type Box = {readonly fromRow: number; readonly toRow: number; readonly fromCol: number; readonly toCol: number};

const Grid: React.FC<{readonly lines: readonly Line[]; readonly box?: Box; readonly opacity?: number}> = ({lines, box, opacity = 1}) => (
	<div style={{position: 'absolute', left: X0, top: ROW0, right: 0, fontFamily: MONO, fontSize: 12.5, lineHeight: `${LH}px`, opacity}}>
		{lines.map((line, r) => (
			<div key={r} style={{height: LH, whiteSpace: 'pre'}}>
				{line.map(([text, color, bold, bg], i) => (
					<span key={i} style={{color: color ?? C.tInk, fontWeight: bold ? 700 : 400, background: bg, display: bg ? 'inline-block' : undefined, height: bg ? LH : undefined, verticalAlign: 'top'}}>
						{text}
					</span>
				))}
			</div>
		))}
		{box ? (
			<div
				style={{
					position: 'absolute',
					left: box.fromCol * CW + CW / 2,
					top: box.fromRow * LH + LH / 2,
					width: (box.toCol - box.fromCol) * CW,
					height: (box.toRow - box.fromRow) * LH,
					border: `1.2px solid ${C.clay}`,
				}}
			/>
		) : null}
	</div>
);

const Chip: React.FC<{readonly on: boolean; readonly left: number; readonly label: string; readonly agent?: boolean; readonly state?: State}> = ({
	on,
	left,
	label,
	agent,
	state,
}) => (
	<div
		style={{
			position: 'absolute',
			left,
			top: 103,
			width: 164,
			height: 34,
			borderRadius: 12,
			background: on ? C.chipOn : C.chipOff,
			display: 'flex',
			alignItems: 'center',
			padding: '0 10px 0 12px',
			fontSize: 14,
			color: on ? C.ink : C.muted,
			fontWeight: on ? 700 : 400,
		}}
	>
		{agent ? (
			<>
				<Glyph agent="claude" size={15} />
				<div style={{position: 'relative', width: 10, height: 10, marginLeft: 6}}>
					{state && state !== 'idle' ? <LiveDot state={state} size={10} ring={on ? C.chipOn : C.chipOff} /> : null}
				</div>
			</>
		) : null}
		<div style={{flex: 1, textAlign: 'center', paddingRight: agent ? 14 : 0}}>{label}</div>
		{on ? <Close color={C.ink} size={18} /> : null}
	</div>
);

const DOCK = [
	{label: 'esc', w: 51},
	{label: 'tab', w: 51},
	{label: 'ctrl', w: 60},
	{label: 'alt', w: 51},
	{label: '⇧', w: 44},
	{label: 'arrows', w: 44},
	{label: '-', w: 44},
	{label: '/', w: 44},
];

const Dock: React.FC<{readonly pressed?: string}> = ({pressed}) => {
	let x = 8;
	return (
		<>
			<div style={{position: 'absolute', left: 0, right: 0, top: 832, height: 1, background: '#121214'}} />
			{DOCK.map((k) => {
				const left = x;
				x += k.w + 6;
				const down = pressed === k.label;
				return (
					<div
						key={k.label}
						style={{
							position: 'absolute',
							left,
							top: 840,
							width: k.w,
							height: 42,
							borderRadius: 12,
							background: down ? '#12345C' : C.dockKey,
							display: 'flex',
							alignItems: 'center',
							justifyContent: 'center',
							fontFamily: MONO,
							fontSize: 15,
							color: down ? '#FFFFFF' : C.ink,
							scale: down ? '0.94' : '1',
						}}
					>
						{k.label === 'arrows' ? <OpenWith color={C.ink} size={20} /> : k.label}
					</div>
				);
			})}
		</>
	);
};

export type TerminalProps = {
	readonly lines: readonly Line[];
	readonly box?: Box;
	readonly tab?: 'claude' | 'git';
	readonly claudeState?: State;
	readonly pressed?: string;
	readonly gridOpacity?: number;
};

/** One session's terminal: title bar, a chip per tab, the grid, and the key dock. */
export const Terminal: React.FC<TerminalProps> = ({lines, box, tab = 'claude', claudeState = 'blocked', pressed, gridOpacity}) => (
	<Screen bg={C.termBg}>
		<div style={{position: 'absolute', left: 0, right: 0, top: 48, height: 54, display: 'flex', alignItems: 'center', fontFamily: UI}}>
			<div style={{width: 48, display: 'flex', justifyContent: 'center'}}>
				<ArrowBack color={C.ink} />
			</div>
			<div style={{width: 7, height: 7, borderRadius: 4, background: C.ok, marginLeft: 0}} />
			<div style={{marginLeft: 9, fontSize: 17, fontWeight: 700, color: C.ink}}>rexc</div>
			<div style={{marginLeft: 9, fontSize: 15, color: C.muted}}>studio</div>
			<div style={{flex: 1}} />
			<div style={{width: 48, textAlign: 'center', fontSize: 22, color: C.ink, fontWeight: 700}}>⌘</div>
			<div style={{width: 48, display: 'flex', justifyContent: 'center'}}>
				<MoreVert color={C.ink} />
			</div>
		</div>
		<Chip on={tab === 'claude'} left={12} label="claude" agent state={claudeState} />
		<Chip on={tab === 'git'} left={184} label="git" />
		<div
			style={{
				position: 'absolute',
				left: 356,
				top: 107,
				width: 42,
				height: 27,
				borderRadius: 13,
				background: C.chipOff,
				display: 'flex',
				alignItems: 'center',
				justifyContent: 'center',
			}}
		>
			<Add color={C.ink} size={20} />
		</div>
		<Grid lines={lines} box={box} opacity={gridOpacity} />
		<Dock pressed={pressed} />
	</Screen>
);
