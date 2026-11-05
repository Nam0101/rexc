import React from 'react';
import {Easing, interpolate} from 'remotion';
import {C, MONO, UI} from '../theme';
import {Agent, Badge, State} from '../ui/Badge';
import {useClock} from '../ui/clock';
import {Screen} from '../ui/Device';
import {Add, ChevronRight, ExpandMore, MoreVert, Send, Settings} from '../ui/Icons';

export type SessionRow = {
	readonly label: string;
	readonly agent: Agent;
	readonly state?: State;
	readonly activity: string;
};
export type HostCard = {readonly name: string; readonly sessions: readonly SessionRow[]};
export type InboxItem = {
	readonly agent: Agent;
	readonly state: State;
	readonly title: string;
	readonly meta: string;
	readonly preview?: readonly string[];
	readonly reply?: string;
	/** 0 shown, 1 gone: the item folds away after it was answered. */
	readonly exit?: number;
};

export type HomeProps = {
	readonly inbox: readonly InboxItem[];
	readonly hosts: readonly HostCard[];
	/** Seconds since the list started appearing; rows rise in one after another. */
	readonly reveal?: number;
	/** How far the list is scrolled, in dp. */
	readonly scroll?: number;
	/** Shows a caret in the first reply box (the user is typing there). */
	readonly typing?: boolean;
};

const ease = Easing.bezier(0.2, 0.9, 0.25, 1);
const rise = (reveal: number, index: number): React.CSSProperties => {
	const t = reveal - index * 0.07;
	return {
		opacity: interpolate(t, [0, 0.35], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'}),
		translate: `0px ${interpolate(t, [0, 0.5], [18, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: ease})}px`,
	};
};

const ITEM_PLAIN = 74;
const ITEM_BLOCKED = 191;

const InboxRow: React.FC<{readonly item: InboxItem; readonly first: boolean; readonly typing: boolean}> = ({item, first, typing}) => {
	const blocked = item.state === 'blocked';
	const exit = item.exit ?? 0;
	const full = (blocked ? ITEM_BLOCKED : ITEM_PLAIN) + (first ? 0 : 1);
	const t = useClock();
	const caretOn = typing && t % 1 < 0.55;
	return (
		<div
			style={{
				height: full * (1 - Easing.inOut(Easing.cubic)(exit)),
				opacity: 1 - exit,
				overflow: 'hidden',
			}}
		>
			{first ? null : <div style={{height: 1, background: C.line, margin: '0 16px'}} />}
			<div style={{padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: 8}}>
				<div style={{display: 'flex', alignItems: 'flex-start', gap: 12}}>
					<Badge agent={item.agent} state={item.state} />
					<div style={{display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0}}>
						<div style={{fontSize: 15, lineHeight: '24px', color: C.ink, whiteSpace: 'nowrap'}}>{item.title}</div>
						<div style={{fontSize: 12, lineHeight: '24px', color: C.muted, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'}}>
							{item.meta}
						</div>
					</div>
				</div>
				{blocked && item.preview ? (
					<div
						style={{
							background: C.bg,
							borderRadius: 12,
							padding: '8px 12px',
							fontFamily: MONO,
							fontSize: 11,
							lineHeight: '15px',
							color: 'rgba(242,242,247,0.85)',
							whiteSpace: 'pre',
							overflow: 'hidden',
						}}
					>
						{item.preview.join('\n')}
					</div>
				) : null}
				{blocked ? (
					<div style={{height: 40, borderRadius: 12, background: C.key, display: 'flex', alignItems: 'center'}}>
						<div style={{flex: 1, paddingLeft: 12, fontFamily: MONO, fontSize: 14, color: item.reply ? C.ink : C.muted}}>
							{item.reply || 'Reply…'}
							{caretOn ? <span style={{display: 'inline-block', width: 2, height: 17, background: C.accent, verticalAlign: -3, marginLeft: 1}} /> : null}
						</div>
						<div style={{width: 48, display: 'flex', justifyContent: 'center'}}>
							<Send color={item.reply ? C.accent : C.muted} size={22} />
						</div>
					</div>
				) : null}
			</div>
		</div>
	);
};

const HostHeader: React.FC<{readonly name: string}> = ({name}) => (
	<div style={{height: 64, display: 'flex', alignItems: 'center', padding: '0 4px 0 16px'}}>
		<div style={{width: 8, height: 8, borderRadius: 4, background: C.ok}} />
		<div style={{marginLeft: 8, flex: 1}}>
			<div style={{fontSize: 16, fontWeight: 600, lineHeight: '24px', color: C.ink}}>{name}</div>
			<div style={{fontSize: 12, lineHeight: '24px', color: C.muted}}>Connected</div>
		</div>
		<div style={{width: 48, display: 'flex', justifyContent: 'center'}}>
			<ExpandMore color={C.muted} />
		</div>
		<div style={{width: 48, display: 'flex', justifyContent: 'center'}}>
			<MoreVert color={C.muted} />
		</div>
	</div>
);

const Row: React.FC<{readonly s: SessionRow}> = ({s}) => (
	<div style={{height: 74, display: 'flex', alignItems: 'center', padding: '0 16px'}}>
		<Badge agent={s.agent} state={s.state} />
		<div style={{marginLeft: 12, flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 2}}>
			<div style={{fontSize: 15, lineHeight: '24px', color: C.ink}}>{s.label}</div>
			<div style={{fontSize: 12, lineHeight: '24px', color: C.muted, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'}}>
				{s.activity}
			</div>
		</div>
		<ChevronRight color={C.line} size={18} />
	</div>
);

export const AppBar: React.FC = () => {
	const t = useClock();
	return (
		<div style={{height: 64, display: 'flex', alignItems: 'center', padding: '0 4px 0 16px'}}>
			<div style={{fontSize: 28, fontWeight: 700, color: C.ink, letterSpacing: -0.2}}>rexc</div>
			<div style={{width: 11, height: 24, background: C.accent, marginLeft: 2, opacity: t % 1.1 < 0.6 ? 1 : 0}} />
			<div style={{flex: 1}} />
			<div style={{width: 48, display: 'flex', justifyContent: 'center'}}>
				<Settings color={C.muted} />
			</div>
			<div style={{width: 48, display: 'flex', justifyContent: 'center'}}>
				<Add color={C.accent} size={28} />
			</div>
		</div>
	);
};

/** The home list: what needs you first, then every server with its sessions. */
export const Home: React.FC<HomeProps> = ({inbox, hosts, reveal = 99, scroll = 0, typing = false}) => {
	const shown = inbox.filter((i) => (i.exit ?? 0) < 1);
	let index = 0;
	return (
		<Screen bg={C.bg}>
			<div style={{position: 'absolute', left: 0, right: 0, top: 50 - scroll, fontFamily: UI}}>
				<div style={rise(reveal, index++)}>
					<AppBar />
				</div>
				{shown.length ? (
					<>
						<div style={{...rise(reveal, index++), height: 45, display: 'flex', alignItems: 'center', padding: '0 16px', fontSize: 14, fontWeight: 500, color: C.muted}}>
							Needs you
						</div>
						<div style={{...rise(reveal, index++), margin: '0 16px', borderRadius: 16, background: C.surface, overflow: 'hidden'}}>
							{shown.map((item, i) => (
								<InboxRow key={item.title} item={item} first={i === 0} typing={typing && i === 0} />
							))}
						</div>
					</>
				) : (
					<div style={{height: 12}} />
				)}
				{hosts.map((h) => (
					<div key={h.name} style={{...rise(reveal, index++), margin: '12px 16px 0', borderRadius: 16, background: C.surface, overflow: 'hidden'}}>
						<HostHeader name={h.name} />
						{h.sessions.map((s) => (
							<div key={s.label} style={rise(reveal, index++)}>
								<Row s={s} />
							</div>
						))}
						<div style={{height: 48, display: 'flex', alignItems: 'center', padding: '0 16px 0 22px', color: C.accent, fontSize: 15, gap: 14}}>
							<Add color={C.accent} size={22} />
							New session
						</div>
					</div>
				))}
			</div>
		</Screen>
	);
};

