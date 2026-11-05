import React from 'react';
import {useVideoConfig} from 'remotion';
import {TransitionSeries, linearTiming} from '@remotion/transitions';
import {fade} from '@remotion/transitions/fade';
import {Alert, Core, End, Finish, Glance, Intro, Pair, Term} from './Scenes';

/** The intro film: 8 scenes, crossfaded. */
export const Promo: React.FC = () => {
	const {fps} = useVideoConfig();
	const cut = <TransitionSeries.Transition presentation={fade()} timing={linearTiming({durationInFrames: Math.round(0.5 * fps)})} />;
	return (
		<TransitionSeries>
			<TransitionSeries.Sequence name="Intro" durationInFrames={3 * fps}>
				<Intro />
			</TransitionSeries.Sequence>
			{cut}
			<TransitionSeries.Sequence name="Glance" durationInFrames={5.5 * fps}>
				<Glance />
			</TransitionSeries.Sequence>
			{cut}
			<TransitionSeries.Sequence name="Alert" durationInFrames={6.5 * fps}>
				<Alert />
			</TransitionSeries.Sequence>
			{cut}
			<TransitionSeries.Sequence name="Finish" durationInFrames={5.5 * fps}>
				<Finish />
			</TransitionSeries.Sequence>
			{cut}
			<TransitionSeries.Sequence name="Terminal" durationInFrames={6.5 * fps}>
				<Term />
			</TransitionSeries.Sequence>
			{cut}
			<TransitionSeries.Sequence name="Core" durationInFrames={5 * fps}>
				<Core />
			</TransitionSeries.Sequence>
			{cut}
			<TransitionSeries.Sequence name="Pair" durationInFrames={4.5 * fps}>
				<Pair />
			</TransitionSeries.Sequence>
			{cut}
			<TransitionSeries.Sequence name="End" durationInFrames={3.5 * fps}>
				<End />
			</TransitionSeries.Sequence>
		</TransitionSeries>
	);
};

/** Scene lengths minus the seven half-second crossfades. */
export const PROMO_SECONDS = 3 + 5.5 + 6.5 + 5.5 + 6.5 + 5 + 4.5 + 3.5 - 7 * 0.5;
