import React from 'react';
import {C, MONO, UI} from '../theme';
import {useClock} from '../ui/clock';
import {Screen, StatusBar} from '../ui/Device';
import {Bell, Bluetooth, ChevronRight, Dnd, ExpandMore, Flashlight, Send, Signal} from '../ui/Icons';

const Tile: React.FC<{readonly on: boolean; readonly left: number; readonly top: number; readonly label: string; readonly icon: React.ReactNode; readonly arrow?: boolean; readonly dim?: boolean}> = ({
	on,
	left,
	top,
	label,
	icon,
	arrow,
	dim,
}) => (
	<div
		style={{
			position: 'absolute',
			left,
			top,
			width: 186,
			height: 60,
			borderRadius: 30,
			background: on ? C.sysTile : dim ? C.sysTileDim : C.sysTileOff,
			display: 'flex',
			alignItems: 'center',
			padding: '0 16px 0 18px',
			gap: 12,
			fontSize: 16,
			color: on ? C.sysTileInk : dim ? '#8E9099' : C.sysInk,
		}}
	>
		{icon}
		<div style={{flex: 1}}>{label}</div>
		{arrow ? <ChevronRight color={on ? C.sysTileInk : C.sysInk} size={20} /> : null}
	</div>
);

const AppIcon: React.FC = () => (
	<div
		style={{
			width: 24,
			height: 24,
			borderRadius: 12,
			background: C.sysIcon,
			display: 'flex',
			alignItems: 'center',
			justifyContent: 'center',
			fontFamily: MONO,
			fontWeight: 700,
			fontSize: 10.5,
			color: '#0B1D4A',
			flexShrink: 0,
		}}
	>
		{'>_'}
	</div>
);

export type ShadeProps = {
	/** Undefined shows the Yes / No / Reply actions; a string shows the inline reply field. */
	readonly reply?: string;
	readonly typing?: boolean;
	/** 0..1: the alert sliding in at the top of the list. */
	readonly arrive?: number;
};

/** Android's notification shade with rexc's alert: answer the agent without opening the app. */
export const Shade: React.FC<ShadeProps> = ({reply, typing, arrive = 1}) => {
	const t = useClock();
	const caretOn = typing && t % 1 < 0.55;
	return (
		<Screen bg={C.sysBg} status={<StatusBar date ink={C.sysInk} />}>
			<div style={{fontFamily: UI}}>
				<Tile on left={16} top={50} label="Internet" arrow icon={<Signal color={C.sysTileInk} size={20} />} />
				<Tile on left={209} top={50} label="Bluetooth" arrow icon={<Bluetooth color={C.sysTileInk} size={22} />} />
				<Tile on={false} dim left={16} top={119} label="Flashlight" icon={<Flashlight color="#8E9099" size={22} />} />
				<Tile on={false} left={209} top={119} label="Do Not Disturb" icon={<Dnd color={C.sysInk} size={22} />} />
				<div style={{position: 'absolute', left: 0, right: 0, top: 202, bottom: 0, background: C.sysPanel, borderRadius: '28px 28px 0 0'}} />
				<div
					style={{
						position: 'absolute',
						left: 16,
						right: 17,
						top: 219,
						borderRadius: 28,
						background: C.sysCard,
						padding: '22px 16px 18px 16px',
						opacity: arrive,
						translate: `0px ${(1 - arrive) * -40}px`,
					}}
				>
					<div style={{display: 'flex', alignItems: 'flex-start', gap: 12}}>
						<div style={{paddingTop: 4}}>
							<AppIcon />
						</div>
						<div style={{flex: 1, minWidth: 0}}>
							<div style={{display: 'flex', alignItems: 'center', gap: 5, fontSize: 15.5, color: C.sysInk, whiteSpace: 'nowrap'}}>
								claude needs you
								<span style={{fontSize: 13, color: C.sysMuted}}>· studio › rexc › claude</span>
								<Bell color={C.sysMuted} size={14} />
							</div>
							<div style={{fontSize: 15, color: C.sysMuted, marginTop: 4}}>Make this edit to Dock.kt?</div>
						</div>
						<div style={{width: 26, height: 26, borderRadius: 13, background: C.sysBtn, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
							<ExpandMore color={C.sysInk} size={20} />
						</div>
					</div>
					{reply === undefined ? (
						<div style={{display: 'flex', gap: 30, marginTop: 22, paddingLeft: 52, fontSize: 15.5, fontWeight: 500, color: C.sysAction}}>
							<span>Yes</span>
							<span>No</span>
							<span>Reply</span>
						</div>
					) : (
						<div style={{display: 'flex', alignItems: 'center', marginTop: 18, gap: 12}}>
							<div
								style={{
									flex: 1,
									height: 51,
									borderRadius: 26,
									background: C.sysPanel,
									display: 'flex',
									alignItems: 'center',
									padding: '0 18px',
									fontSize: 16,
									color: C.sysInk,
								}}
							>
								{reply || <span style={{color: '#8E9099'}}>Reply</span>}
								{caretOn ? <span style={{display: 'inline-block', width: 2, height: 20, background: C.sysAction, marginLeft: 1}} /> : null}
							</div>
							<Send color={C.sysAction} size={26} />
						</div>
					)}
				</div>
				<div style={{position: 'absolute', left: 32, top: 374 + (reply === undefined ? 0 : 32), fontSize: 15, color: C.sysInk}}>Silent</div>
				<div
					style={{
						position: 'absolute',
						left: 16,
						right: 17,
						top: 410 + (reply === undefined ? 0 : 32),
						height: 83,
						borderRadius: 28,
						background: C.sysCard,
						display: 'flex',
						alignItems: 'center',
						padding: '0 16px',
						gap: 12,
					}}
				>
					<AppIcon />
					<div style={{flex: 1}}>
						<div style={{fontSize: 15.5, color: C.sysInk}}>Watching for alerts</div>
						<div style={{fontSize: 14.5, color: C.sysMuted, marginTop: 3}}>studio, build-box</div>
					</div>
					<div style={{width: 26, height: 26, borderRadius: 13, background: C.sysBtn, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
						<ExpandMore color={C.sysInk} size={20} />
					</div>
				</div>
			</div>
		</Screen>
	);
};
