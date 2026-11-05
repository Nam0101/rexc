import React, {createContext, useContext} from 'react';
import {useCurrentFrame, useVideoConfig} from 'remotion';

const Offset = createContext(0);

/** Freezes looping UI motion (breathing dots, pings, cursors) at a chosen moment in a still. */
export const ClockAt: React.FC<{readonly seconds: number; readonly children: React.ReactNode}> = ({seconds, children}) => (
	<Offset.Provider value={seconds}>{children}</Offset.Provider>
);

/** Seconds since the composition started, plus any still offset. */
export const useClock = (): number => {
	const frame = useCurrentFrame();
	const {fps} = useVideoConfig();
	return frame / fps + useContext(Offset);
};
