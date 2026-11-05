import React from 'react';
import {AbsoluteFill, Easing, interpolate, useCurrentFrame, useVideoConfig} from 'remotion';
import {C, UI} from '../theme';
import {Phone} from '../ui/Device';

export const easeOut = Easing.bezier(0.16, 1, 0.3, 1);

/** Seconds since this scene started. */
export const useSeconds = (): number => {
	const frame = useCurrentFrame();
	const {fps} = useVideoConfig();
	return frame / fps;
};

/** interpolate() over seconds, clamped, eased out by default. */
export const iv = (t: number, from: [number, number], to: [number, number], easing = easeOut): number =>
	interpolate(t, from, to, {extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing});

/** The first [n] characters typed at [cps] from [start]. */
export const typed = (text: string, t: number, start: number, cps = 16): string =>
	text.slice(0, Math.max(0, Math.floor((t - start) * cps)));

export const Backdrop: React.FC<{readonly children: React.ReactNode}> = ({children}) => (
	<AbsoluteFill
		style={{
			background: 'radial-gradient(110% 130% at 78% 30%, #0f2141 0%, #070a12 48%, #040405 100%)',
			fontFamily: UI,
			color: C.ink,
		}}
	>
		{children}
	</AbsoluteFill>
);

/** A headline and one supporting line, rising in one after the other. */
export const Caption: React.FC<{readonly title: string; readonly body: string; readonly kicker?: string}> = ({title, body, kicker}) => {
	const t = useSeconds();
	const rise = (start: number): React.CSSProperties => ({
		opacity: iv(t, [start, start + 0.5], [0, 1]),
		translate: `0px ${iv(t, [start, start + 0.7], [28, 0])}px`,
	});
	return (
		<div style={{position: 'absolute', left: 150, top: 0, bottom: 0, width: 820, display: 'flex', flexDirection: 'column', justifyContent: 'center'}}>
			{kicker ? (
				<div style={{...rise(0.05), fontSize: 26, fontWeight: 500, letterSpacing: 3, textTransform: 'uppercase', color: '#7fa7ff', marginBottom: 22}}>
					{kicker}
				</div>
			) : null}
			<div style={{...rise(0.15), fontSize: 92, fontWeight: 700, lineHeight: 1.04, letterSpacing: -1.5, whiteSpace: 'pre-line'}}>{title}</div>
			<div style={{...rise(0.45), fontSize: 38, lineHeight: 1.35, color: C.muted, marginTop: 34, maxWidth: 760}}>{body}</div>
		</div>
	);
};

/** The phone on the right of a feature scene; [children] is one screen plus any overlay. */
export const Stage: React.FC<{readonly children: React.ReactNode; readonly enter?: boolean; readonly zoom?: number}> = ({children, enter, zoom = 1}) => {
	const t = useSeconds();
	const y = enter ? iv(t, [0, 0.9], [420, 0]) : 0;
	return (
		<div style={{position: 'absolute', left: 1250, top: 90, scale: String(0.94 * zoom), transformOrigin: '50% 0%', translate: `0px ${y}px`}}>
			<Phone>{children}</Phone>
		</div>
	);
};

/** A finger tap at screen point (x, y) in dp: a soft dot, then a ring that spreads and fades. */
export const Tap: React.FC<{readonly x: number; readonly y: number; readonly at: number}> = ({x, y, at}) => {
	const t = useSeconds() - at;
	if (t < -0.25 || t > 0.6) return null;
	const press = iv(t, [-0.25, 0], [0, 1]) * iv(t, [0.15, 0.45], [1, 0]);
	const ring = iv(t, [0, 0.5], [0, 1]);
	return (
		<div style={{position: 'absolute', left: x, top: y, zIndex: 80, pointerEvents: 'none'}}>
			<div
				style={{
					position: 'absolute',
					left: -22,
					top: -22,
					width: 44,
					height: 44,
					borderRadius: 22,
					background: `rgba(255,255,255,${0.32 * press})`,
					scale: String(0.8 + 0.2 * press),
				}}
			/>
			<div
				style={{
					position: 'absolute',
					left: -34,
					top: -34,
					width: 68,
					height: 68,
					borderRadius: 34,
					border: `2px solid rgba(255,255,255,${0.5 * (1 - ring)})`,
					scale: String(0.4 + 0.8 * ring),
					opacity: t >= 0 ? 1 : 0,
				}}
			/>
		</div>
	);
};
